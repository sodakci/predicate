package verifier;

import graph.Edge;
import graph.EdgeType;
import graph.KnownGraph;
import graph.MatrixGraph;
import history.Event;
import history.History;
import history.HistoryLoader;
import history.Transaction;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
import java.util.stream.Collectors;

import com.google.common.graph.GraphBuilder;
import com.google.common.graph.MutableGraph;
import com.google.common.graph.ValueGraph;
import org.apache.commons.lang3.tuple.Pair;

import util.Profiler;
import util.TriConsumer;

@SuppressWarnings("UnstableApiUsage")
public class SIVerifier<KeyType, ValueType> {
    public enum AuditResult {
        ACCEPT(0, "[[[[ ACCEPT ]]]]"),
        REJECT(-1, "[[[[ REJECT ]]]]");

        public final int exitCode;
        public final String marker;

        AuditResult(int exitCode, String marker) {
            this.exitCode = exitCode;
            this.marker = marker;
        }
    }

    @FunctionalInterface
    public interface SatSolveBackend {
        /** 返回本次约束求解是否可满足。 */
        boolean solve(monosat.Solver solver,
                Collection<monosat.Lit> assumptions);
    }

    public enum AuditStage { WW, GMWR, PREDICATE, SAT }

    public static final class SolverSettings {
        public PredicateMode predicateMode = PredicateMode.GMWR;
        public boolean predicateWitnessCoalescing = true;
        public boolean graphEdgeInterning = true;
        /** Package-private test/ablation switch; production keeps this enabled. */
        boolean wwReachabilityPruning = true;
        /** SI visibility/repair propagation before SAT encoding. */
        public boolean gmwrPrepropagation = true;
        public boolean detailedPredicateMetrics;
        public SatSolveBackend satSolveBackend;
        public Consumer<AuditStage> auditProgressListener = ignored -> { };

        public static SolverSettings defaults() {
            return new SolverSettings();
        }
    }

    public enum PredicateMode {
        EAGER,
        GMWR
    }

    private final History<KeyType, ValueType> history;
    private final SolverSettings solverSettings;

    public SIVerifier(HistoryLoader<KeyType, ValueType> loader) {
        this(loader, SolverSettings.defaults(), false);
    }

    public SIVerifier(HistoryLoader<KeyType, ValueType> loader,
            boolean detailedPredicateMetrics) {
        this(loader, SolverSettings.defaults(), detailedPredicateMetrics);
    }

    public SIVerifier(HistoryLoader<KeyType, ValueType> loader,
            SolverSettings solverSettings,
            boolean detailedPredicateMetrics) {
        history = loader.loadHistory();
        this.solverSettings = Objects.requireNonNull(solverSettings, "solverSettings");
        this.solverSettings.detailedPredicateMetrics = detailedPredicateMetrics;
        Objects.requireNonNull(solverSettings.predicateMode, "predicateMode");

    }

    public boolean audit() {
        return auditResult() == AuditResult.ACCEPT;
    }

    public int getTransactionCount() {
        return history.getClientTransactions().size();
    }

    public int getEventCount() {
        return history.getClientTransactions().stream()
                .mapToInt(transaction -> transaction.getEvents().size()).sum();
    }

    public long getPredicateObservationCount() {
        return history.getEvents().stream()
                .filter(event -> event.getType() == Event.EventType.PREDICATE_READ).count();
    }

    public AuditResult auditResult() {
        PredicateAnalysis.validateSupportedPredicates(history);
        var profiler = Profiler.getInstance();
        profiler.startTick("ONESHOT_CONS");
        profiler.startTick("SI_VERIFY_INT");
        boolean consistent = Utils.verifyInternalConsistency(history);
        profiler.endTick("SI_VERIFY_INT");
        if (!consistent) {
            profiler.endTick("ONESHOT_CONS");
            return AuditResult.REJECT;
        }
        profiler.startTick("SI_GEN_PREC_GRAPH");
        var graph = new KnownGraph<>(history, false);
        var vis = new SIReachabilityOracle<KeyType, ValueType>(graph);
        profiler.endTick("SI_GEN_PREC_GRAPH");
        profiler.startTick("SI_GEN_CONSTRAINTS");
        var constraints = generateConstraintsSI(history, graph);
        profiler.endTick("SI_GEN_CONSTRAINTS");
        int initialChoices = constraints.size();
        profiler.addCount("WW_INITIAL_CHOICES", initialChoices);
        profiler.startTick("WW_REACHABILITY_PRUNE_MS");
        SIConstraint<KeyType, ValueType> pruningConflict = null;
        boolean pruningRejected = false;
        try {
            if (solverSettings.wwReachabilityPruning) {
                SIReachabilityPruner.Result<KeyType, ValueType> reduction;
                do {
                    reduction = SIReachabilityPruner.reduceOnce(graph, constraints, vis);
                    if (reduction.rejected) {
                        pruningRejected = true;
                        pruningConflict = reduction.conflict;
                        break;
                    }
                } while (!constraints.isEmpty()
                        && reduction.forced > 0.01 * Math.max(1, constraints.size()));
            }
        } finally {
            profiler.endTick("WW_REACHABILITY_PRUNE_MS");
        }
        profiler.addCount("WW_AFTER_REACHABILITY", constraints.size());
        profiler.addCount("WW_REACHABILITY_FORCED", initialChoices - constraints.size());
        solverSettings.auditProgressListener.accept(AuditStage.WW);
        profiler.endTick("ONESHOT_CONS");
        if (pruningRejected) {
            emitRejectDiagnostics(graph, constraints,
                    Pair.of(Collections.emptyList(), pruningConflict == null
                            ? Collections.emptyList() : List.of(pruningConflict)), List.of());
            return AuditResult.REJECT;
        }

        var analysis = new PredicateAnalysis<>(graph, vis);
        var prepared = new PredicatePruning<>(history, graph, vis, solverSettings, analysis).prune();
        int beforeFeedback = constraints.size();
        long updatesBeforeFeedback = vis.updateCount();
        int feedbackRounds = 0;
        profiler.startTick("WW_GMWR_FEEDBACK_MS");
        try {
            if (!prepared.hasConflict() && !prepared.observations().isEmpty()
                    && solverSettings.predicateMode == PredicateMode.GMWR
                    && solverSettings.gmwrPrepropagation && solverSettings.wwReachabilityPruning) {
                // 只反馈确定事实；沿用完整 WW/RW 分支提交，不重建谓词阶段。
                while (!constraints.isEmpty()) {
                    var reduction = SIReachabilityPruner.reduceOnce(graph, constraints, vis);
                    feedbackRounds++;
                    if (reduction.rejected) {
                        pruningRejected = true;
                        pruningConflict = reduction.conflict;
                        break;
                    }
                    if (reduction.forced == 0) {
                        break;
                    }
                }
            }
        } finally {
            profiler.endTick("WW_GMWR_FEEDBACK_MS");
        }
        profiler.addCount("WW_GMWR_FEEDBACK_ROUNDS", feedbackRounds);
        profiler.addCount("WW_GMWR_FEEDBACK_FORCED", beforeFeedback - constraints.size());
        profiler.addCount("WW_AFTER_GMWR_FEEDBACK", constraints.size());
        profiler.addCount("SI_ORACLE_UPDATES", vis.updateCount() - updatesBeforeFeedback);
        if (solverSettings.predicateMode == PredicateMode.GMWR) {
            solverSettings.auditProgressListener.accept(AuditStage.GMWR);
        }
        if (prepared.hasConflict()) {
            analysis.publishMetrics(profiler, solverSettings.detailedPredicateMetrics);
            solverSettings.auditProgressListener.accept(AuditStage.PREDICATE);
            System.err.println("[SI] 谓词剪枝发现确定性冲突：");
            for (var reason : prepared.conflictReasons()) {
                System.err.println("[SI]   " + reason);
            }
            return AuditResult.REJECT;
        }

        if (pruningRejected) {
            System.err.println("[SI] GMWR → WW feedback 发现 WW 分支不可行或提交后 induced 成环。");
            emitRejectDiagnostics(graph, constraints,
                    Pair.of(Collections.emptyList(), pruningConflict == null
                            ? Collections.emptyList() : List.of(pruningConflict)), List.of());
            return AuditResult.REJECT;
        }

        profiler.startTick("ONESHOT_SOLVE");
        SISolverInduced<KeyType, ValueType> solver;
        profiler.startTick("SI_GRAPH_ENCODE");
        try {
            solver = new SISolverInduced<>(history, graph, constraints, true,
                    solverSettings.detailedPredicateMetrics, solverSettings, vis, prepared);
        } finally {
            profiler.endTick("SI_GRAPH_ENCODE");
        }
        solverSettings.auditProgressListener.accept(AuditStage.PREDICATE);
        solverSettings.auditProgressListener.accept(AuditStage.SAT);
        profiler.startTick("SI_GRAPH_SOLVE");
        SolveStatus status;
        try {
            status = solver.solveStatus();
        } finally {
            profiler.endTick("SI_GRAPH_SOLVE");
            profiler.endTick("ONESHOT_SOLVE");
        }
        if (status == SolveStatus.UNSAT) {
            emitRejectDiagnostics(graph, constraints, solver.getConflicts(), solver.getConflictReasons());
            return AuditResult.REJECT;
        }
        return AuditResult.ACCEPT;
    }

    private void emitRejectDiagnostics(
            KnownGraph<KeyType, ValueType> graph,
            Collection<SIConstraint<KeyType, ValueType>> constraints,
            Pair<Collection<Pair<com.google.common.graph.EndpointPair<Transaction<KeyType, ValueType>>, Collection<Edge<KeyType>>>>,
                    Collection<SIConstraint<KeyType, ValueType>>> conflicts,
            Collection<SISolverInduced.AssumptionReason<KeyType, ValueType>> reasons) {
        System.err.println("[SI] Reject reason: Adya typed dependency constraints are UNSAT.");
        System.err.printf("[SI] Diagnostic counts: knownEdges=%d, unresolvedWWChoices=%d, predicateReads=%d%n",
                graph.getKnownGraphA().edges().size() + graph.getKnownGraphB().edges().size(),
                constraints.size(), graph.getPredicateObservations().size());
        if (!reasons.isEmpty()) {
            System.err.printf("[SI] Conflict clause: %s%n", reasons.stream()
                    .map(reason -> "!" + reason.assumptionId()).collect(Collectors.joining(" | ")));
            for (var reason : reasons) {
                System.err.printf("[SI]   +-- %s [%s] %s%n",
                        reason.assumptionId(), reason.getKind(), reason.getReason());
            }
        } else if (conflicts.getLeft().isEmpty() && conflicts.getRight().isEmpty()) {
            System.err.println("[SI] No assumption conflict clause was produced; "
                    + "the contradiction is in deterministic graph or pruning facts.");
        } else {
            System.err.printf("[SI] Conflict core: knownEdges=%d, wwChoices=%d%n",
                    conflicts.getLeft().size(), conflicts.getRight().size());
        }
    }

    static final class InducedGraph {
        private InducedGraph() {
        }

        static <KeyType, ValueType> Collection<Pair<com.google.common.graph.EndpointPair<Transaction<KeyType, ValueType>>, Collection<Edge<KeyType>>>>
        extractCycleEdges(KnownGraph<KeyType, ValueType> graph) {
            var cycleNodes = findCycle(inducedGraph(graph));
            if (cycleNodes.isEmpty()) {
                return Collections.emptyList();
            }

            var result = new ArrayList<Pair<com.google.common.graph.EndpointPair<Transaction<KeyType, ValueType>>, Collection<Edge<KeyType>>>>();
            for (int i = 0; i + 1 < cycleNodes.size(); i++) {
                var from = cycleNodes.get(i);
                var to = cycleNodes.get(i + 1);
                appendInducedWitness(graph, from, to, result);
            }
            return result;
        }

        private static <KeyType, ValueType> void appendInducedWitness(
                KnownGraph<KeyType, ValueType> graph,
                Transaction<KeyType, ValueType> from,
                Transaction<KeyType, ValueType> to,
                List<Pair<com.google.common.graph.EndpointPair<Transaction<KeyType, ValueType>>, Collection<Edge<KeyType>>>> result) {
            var directA = graph.getKnownGraphA().edgeValue(from, to)
                    .orElse(List.of());
            if (!directA.isEmpty()) {
                result.add(Pair.of(
                        com.google.common.graph.EndpointPair.ordered(from, to),
                        new ArrayList<>(directA)));
                return;
            }

            for (var middle : graph.getKnownGraphA().successors(from)) {
                var first = graph.getKnownGraphA().edgeValue(from, middle)
                        .orElse(List.of());
                var second = graph.getKnownGraphB().edgeValue(middle, to)
                        .orElse(List.of());
                if (first.isEmpty() || second.isEmpty()) {
                    continue;
                }
                result.add(Pair.of(
                        com.google.common.graph.EndpointPair.ordered(from, middle),
                        new ArrayList<>(first)));
                result.add(Pair.of(
                        com.google.common.graph.EndpointPair.ordered(middle, to),
                        new ArrayList<>(second)));
                return;
            }
        }

        private static <KeyType, ValueType> MatrixGraph<Transaction<KeyType, ValueType>> inducedGraph(
                KnownGraph<KeyType, ValueType> graph) {
            var dep = depGraph(graph);
            var anti = antiDepGraph(graph, dep);
            return dep.union(dep.composition(anti));
        }

        private static <KeyType, ValueType> MatrixGraph<Transaction<KeyType, ValueType>> depGraph(
                KnownGraph<KeyType, ValueType> graph) {
            return new MatrixGraph<>(toSimpleGraph(graph.getKnownGraphA()));
        }

        private static <KeyType, ValueType> MatrixGraph<Transaction<KeyType, ValueType>> antiDepGraph(
                KnownGraph<KeyType, ValueType> graph,
                MatrixGraph<Transaction<KeyType, ValueType>> depGraph) {
            return new MatrixGraph<>(toSimpleGraph(graph.getKnownGraphB()), depGraph.getNodeMap());
        }

        private static <KeyType, ValueType> MutableGraph<Transaction<KeyType, ValueType>> toSimpleGraph(
                ValueGraph<Transaction<KeyType, ValueType>, Collection<Edge<KeyType>>> source) {
            var graph = GraphBuilder.directed()
                    .allowsSelfLoops(true)
                    .<Transaction<KeyType, ValueType>>build();
            source.nodes().forEach(graph::addNode);
            for (var ep : source.edges()) {
                graph.putEdge(ep.source(), ep.target());
            }
            return graph;
        }

        private static <KeyType, ValueType> List<Transaction<KeyType, ValueType>> findCycle(
                MatrixGraph<Transaction<KeyType, ValueType>> graph) {
            var color = new HashMap<Transaction<KeyType, ValueType>, Integer>();
            var stack = new ArrayList<Transaction<KeyType, ValueType>>();
            var stackIndex = new HashMap<Transaction<KeyType, ValueType>, Integer>();

            for (var node : graph.nodes()) {
                if (color.getOrDefault(node, 0) != 0) {
                    continue;
                }
                var cycle = dfsCycle(node, graph, color, stack, stackIndex);
                if (!cycle.isEmpty()) {
                    return cycle;
                }
            }
            return Collections.emptyList();
        }

        private static <KeyType, ValueType> List<Transaction<KeyType, ValueType>> dfsCycle(
                Transaction<KeyType, ValueType> node,
                MatrixGraph<Transaction<KeyType, ValueType>> graph,
                Map<Transaction<KeyType, ValueType>, Integer> color,
                List<Transaction<KeyType, ValueType>> stack,
                Map<Transaction<KeyType, ValueType>, Integer> stackIndex) {
            color.put(node, 1);
            stackIndex.put(node, stack.size());
            stack.add(node);

            for (var succ : graph.successors(node)) {
                int succColor = color.getOrDefault(succ, 0);
                if (succColor == 0) {
                    var cycle = dfsCycle(succ, graph, color, stack, stackIndex);
                    if (!cycle.isEmpty()) {
                        return cycle;
                    }
                } else if (succColor == 1) {
                    var cycle = new ArrayList<>(stack.subList(stackIndex.get(succ), stack.size()));
                    cycle.add(succ);
                    return cycle;
                }
            }

            stack.remove(stack.size() - 1);
            stackIndex.remove(node);
            color.put(node, 2);
            return Collections.emptyList();
        }
    }

    private static <KeyType, ValueType> Collection<SIConstraint<KeyType, ValueType>> generateConstraintsCoalesce(
            History<KeyType, ValueType> history, KnownGraph<KeyType, ValueType> graph) {
        var readFrom = graph.getReadFrom();
        var writes = new HashMap<KeyType, Set<Transaction<KeyType, ValueType>>>();

        history.getEvents().stream().filter(e -> e.getType() == Event.EventType.WRITE).forEach(ev -> {
            writes.computeIfAbsent(ev.getKey(), k -> new HashSet<>()).add(ev.getTransaction());
        });

        var forEachWriteSameKey = ((Consumer<TriConsumer<Transaction<KeyType, ValueType>, Transaction<KeyType, ValueType>, KeyType>>) f -> {
            for (var p : writes.entrySet()) {
                var key = p.getKey();
                var list = new ArrayList<>(p.getValue());
                for (int i = 0; i < list.size(); i++) {
                    for (int j = i + 1; j < list.size(); j++) {
                        f.accept(list.get(i), list.get(j), key);
                    }
                }
            }
        });

        var constraintEdges = new HashMap<Pair<Transaction<KeyType, ValueType>, Transaction<KeyType, ValueType>>, Collection<SIEdge<KeyType, ValueType>>>();
        forEachWriteSameKey.accept((a, c, key) -> {
            var addEdge = ((BiConsumer<Transaction<KeyType, ValueType>, Transaction<KeyType, ValueType>>) (m, n) -> {
                constraintEdges.computeIfAbsent(Pair.of(m, n), p -> new ArrayList<>())
                        .add(new SIEdge<>(m, n, EdgeType.WW, key));
            });
            addEdge.accept(a, c);
            addEdge.accept(c, a);
        });

        for (var a : history.getTransactions()) {
            for (var b : readFrom.successors(a)) {
                for (var edge : readFrom.edgeValue(a, b).get()) {
                    for (var c : writes.get(edge.getKey())) {
                        if (a == c || b == c) {
                            continue;
                        }

                        constraintEdges.get(Pair.of(a, c)).add(new SIEdge<>(b, c, EdgeType.RW, edge.getKey()));
                    }
                }
            }
        }

        var constraints = new HashSet<SIConstraint<KeyType, ValueType>>();
        var addedPairs = new HashSet<Pair<Transaction<KeyType, ValueType>, Transaction<KeyType, ValueType>>>();
        AtomicInteger constraintId = new AtomicInteger();
        forEachWriteSameKey.accept((a, c, key) -> {
            if (addedPairs.contains(Pair.of(a, c)) || addedPairs.contains(Pair.of(c, a))) {
                return;
            }
            addedPairs.add(Pair.of(a, c));
            constraints.add(new SIConstraint<>(constraintEdges.get(Pair.of(a, c)), constraintEdges.get(Pair.of(c, a)),
                    a, c, constraintId.getAndIncrement()));
        });

        return constraints;
    }

    /**
     * SI direct-edge constraint generation.
     *
     * The generated WW and RW edges are direct SI dependency candidates and
     * will be placed directly into:
     *   A = {SO, WR, WW, PR_WR}
     *   B = {RW, PR_RW}
     */
    static <KeyType, ValueType> Collection<SIConstraint<KeyType, ValueType>> generateConstraintsSI(
            History<KeyType, ValueType> history, KnownGraph<KeyType, ValueType> graph) {
        return generateConstraintsCoalesce(history, graph);
    }

}
