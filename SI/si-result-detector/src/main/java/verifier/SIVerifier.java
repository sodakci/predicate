package verifier;

import graph.Edge;
import graph.EdgeType;
import graph.KnownGraph;
import graph.MatrixGraph;
import history.Event;
import history.History;
import history.HistoryLoader;
import history.Transaction;
import history.query.MapVisibleState;
import history.query.QueryException;

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
        REJECT(-1, "[[[[ REJECT ]]]]"),
        TIMEOUT(124, "[[[[ TIMEOUT ]]]]");

        public final int exitCode;
        public final String marker;

        AuditResult(int exitCode, String marker) {
            this.exitCode = exitCode;
            this.marker = marker;
        }
    }

    @FunctionalInterface
    public interface SatSolveBackend {
        /** Empty means that the backend reached its resource limit. */
        Optional<Boolean> solve(monosat.Solver solver, int remainingSeconds);
    }

    public static final class SolverSettings {
        public PruningMode pruningMode = PruningMode.REACHABILITY;
        public boolean predicateWitnessCoalescing = true;
        public boolean graphEdgeInterning = true;
        public int solverTimeoutSeconds = 600;
        public boolean detailedPredicateMetrics;
        public SatSolveBackend satSolveBackend;

        public static SolverSettings defaults(PruningMode pruningMode) {
            var settings = new SolverSettings();
            settings.pruningMode = Objects.requireNonNull(pruningMode, "pruningMode");
            return settings;
        }
    }

    public enum PruningMode {
        NONE,
        REACHABILITY
    }

    private final History<KeyType, ValueType> history;
    private final SolverSettings solverSettings;

    public SIVerifier(HistoryLoader<KeyType, ValueType> loader) {
        this(loader, SolverSettings.defaults(PruningMode.REACHABILITY), false);
    }

    public SIVerifier(HistoryLoader<KeyType, ValueType> loader,
            boolean detailedPredicateMetrics) {
        this(loader, SolverSettings.defaults(PruningMode.REACHABILITY),
                detailedPredicateMetrics);
    }

    public SIVerifier(HistoryLoader<KeyType, ValueType> loader,
            SolverSettings solverSettings,
            boolean detailedPredicateMetrics) {
        history = loader.loadHistory();
        this.solverSettings = Objects.requireNonNull(solverSettings, "solverSettings");
        if (solverSettings.solverTimeoutSeconds < 0) {
            throw new IllegalArgumentException("solverTimeoutSeconds must be >= 0");
        }
        this.solverSettings.detailedPredicateMetrics = detailedPredicateMetrics;
        Objects.requireNonNull(solverSettings.pruningMode, "pruningMode");
        System.err.printf("Sessions count: %d\nTransactions count: %d\nEvents count: %d\n",
                history.getClientSessions().size(), history.getClientTransactions().size(), history.getEvents().size());
    }

    public boolean audit() {
        return auditResult() == AuditResult.ACCEPT;
    }

    public AuditResult auditResult() {
        var profiler = Profiler.getInstance();
        long checkerStartedNanos = System.nanoTime();

        profiler.startTick("ONESHOT_CONS");
        profiler.startTick("SI_VERIFY_INT");
        boolean satisfy_int = Utils.verifyInternalConsistency(history);
        profiler.endTick("SI_VERIFY_INT");
        if (!satisfy_int) {
            return AuditResult.REJECT;
        }

        profiler.startTick("SI_GEN_PREC_GRAPH");
        var graph = new KnownGraph<>(history);
        profiler.endTick("SI_GEN_PREC_GRAPH");
        System.err.printf("Mandatory typed dependency edges: A=%d, B=%d\n",
                graph.getKnownGraphA().edges().size(),
                graph.getKnownGraphB().edges().size());

        // ===== SI MODE (Adya typed dependency graphs with predicates) =====
        // A = {SO, WR, WW, PR_WR}, B = {RW, PR_RW}; the verdict checks
        // acyclicity of A union (A composition B).
        System.err.println("Mode: SI, solving Adya typed dependency graphs A/B and checking the induced SI graph");

        profiler.startTick("SI_GEN_CONSTRAINTS");
        var constraints = generateConstraintsSI(history, graph);
        profiler.endTick("SI_GEN_CONSTRAINTS");
        System.err.printf("Unresolved WW choices: %d\nConditional dependency implications: %d\n", constraints.size(),
                constraints.stream().map(c -> c.getEdges1().size() + c.getEdges2().size()).reduce(0, Integer::sum));

        int wwInitialConstraints = constraints.size();
        int wwInitialImplications = countConstraintImplications(constraints);
        profiler.addCount("WW_INITIAL_CONSTRAINTS", wwInitialConstraints);
        profiler.addCount("WW_INITIAL_IMPLICATIONS", wwInitialImplications);
        Optional<SIConstraint<KeyType, ValueType>> pruningConflict;
        profiler.startTick("WW_BASELINE_PRUNE_MS");
        try {
            switch (solverSettings.pruningMode) {
            case NONE:
                pruningConflict = Optional.empty();
                break;
            case REACHABILITY:
            default:
                pruningConflict = Pruning.pruneConstraints(graph, constraints);
                break;
            }
        } finally {
            profiler.endTick("WW_BASELINE_PRUNE_MS");
        }
        profiler.addCount("WW_BASELINE_FORCED",
                wwInitialConstraints - constraints.size());
        profiler.addCount("WW_AFTER_BASELINE", constraints.size());
        profiler.addCount("WW_AFTER_BASELINE_IMPLICATIONS",
                countConstraintImplications(constraints));

        if (pruningConflict.isPresent()) {
            profiler.endTick("ONESHOT_CONS");
            var conflicts = Pair.<Collection<Pair<com.google.common.graph.EndpointPair<Transaction<KeyType, ValueType>>, Collection<Edge<KeyType>>>>,
                    Collection<SIConstraint<KeyType, ValueType>>>of(
                            Collections.emptyList(), List.of(pruningConflict.get()));
            emitRejectDiagnostics(graph, constraints, conflicts);
            return AuditResult.REJECT;
        }
        profiler.endTick("ONESHOT_CONS");

        profiler.startTick("ONESHOT_SOLVE");
        SISolverInduced<KeyType, ValueType> solver;
        profiler.startTick("SI_GRAPH_ENCODE");
        try {
            solver = new SISolverInduced<>(
                    history, graph, constraints, true,
                    solverSettings.detailedPredicateMetrics, solverSettings);
        } finally {
            profiler.endTick("SI_GRAPH_ENCODE");
        }
        System.err.printf("Predicate source constraints: %d%n",
                solver.getPredicateSourceConstraintCount());
        profiler.startTick("SI_GRAPH_SOLVE");
        SolveStatus status;
        try {
            status = solver.solveStatus();
        } finally {
            profiler.endTick("SI_GRAPH_SOLVE");
        }
        profiler.endTick("ONESHOT_SOLVE");

        if (status == SolveStatus.TIMEOUT) {
            printTimeoutTimes(checkerStartedNanos);
            return AuditResult.TIMEOUT;
        }
        if (status == SolveStatus.UNSAT) {
            emitRejectDiagnostics(graph, constraints, solver.getConflicts());
            return AuditResult.REJECT;
        }
        return AuditResult.ACCEPT;
    }

    private void printTimeoutTimes(long checkerStartedNanos) {
        var profiler = Profiler.getInstance();
        System.err.printf(
                "[SI] timeout-scope=solver checker_ms=%d encode_ms=%s solve_ms=%s monosat_ms=%s%n",
                (System.nanoTime() - checkerStartedNanos) / 1_000_000L,
                metricOrDash(profiler, "SI_GRAPH_ENCODE"),
                metricOrDash(profiler, "SI_GRAPH_SOLVE"),
                metricOrDash(profiler, "SI_MONOSAT_SOLVE"));
    }

    private static String metricOrDash(Profiler profiler, String tag) {
        try {
            return Long.toString(profiler.getTime(tag));
        } catch (RuntimeException ignored) {
            return "-";
        }
    }

    private static <KeyType, ValueType> int countConstraintImplications(
            Collection<SIConstraint<KeyType, ValueType>> constraints) {
        return constraints.stream()
                .mapToInt(constraint -> constraint.getEdges1().size()
                        + constraint.getEdges2().size())
                .sum();
    }

    private void emitRejectDiagnostics(
            KnownGraph<KeyType, ValueType> graph,
            Collection<SIConstraint<KeyType, ValueType>> constraints,
            Pair<Collection<Pair<com.google.common.graph.EndpointPair<Transaction<KeyType, ValueType>>, Collection<Edge<KeyType>>>>,
                    Collection<SIConstraint<KeyType, ValueType>>> conflicts) {
        var txns = conflictTransactions(conflicts);
        var cycleWitness = buildCycleWitness(graph, constraints, txns);
        printRejectReason(graph, constraints, conflicts, cycleWitness);
        cycleWitness.ifPresent(cycle -> System.out.print(formatCycleWitness(cycle)));
    }


    private Set<Transaction<KeyType, ValueType>> conflictTransactions(
            Pair<Collection<Pair<com.google.common.graph.EndpointPair<Transaction<KeyType, ValueType>>, Collection<Edge<KeyType>>>>,
                    Collection<SIConstraint<KeyType, ValueType>>> conflicts) {
        var txns = new HashSet<Transaction<KeyType, ValueType>>();
        conflicts.getLeft().forEach(e -> {
            txns.add(e.getLeft().source());
            txns.add(e.getLeft().target());
        });
        conflicts.getRight().forEach(c -> {
            txns.add(c.getWriteTransaction1());
            txns.add(c.getWriteTransaction2());
            var addEdges = ((Consumer<Collection<SIEdge<KeyType, ValueType>>>) edges -> edges.forEach(e -> {
                txns.add(e.getFrom());
                txns.add(e.getTo());
            }));
            addEdges.accept(c.getEdges1());
            addEdges.accept(c.getEdges2());
        });
        return txns;
    }

    private void printRejectReason(
            KnownGraph<KeyType, ValueType> graph,
            Collection<SIConstraint<KeyType, ValueType>> constraints,
            Pair<Collection<Pair<com.google.common.graph.EndpointPair<Transaction<KeyType, ValueType>>, Collection<Edge<KeyType>>>>,
                    Collection<SIConstraint<KeyType, ValueType>>> conflicts,
            Optional<List<CycleEdge<KeyType, ValueType>>> cycleWitness) {
        int knownEdges = graph.getKnownGraphA().edges().size() + graph.getKnownGraphB().edges().size();
        int conditionalEdges = constraints.stream()
                .map(c -> c.getEdges1().size() + c.getEdges2().size())
                .reduce(0, Integer::sum);
        System.err.println("[SI] Reject reason: Adya typed dependency constraints make the induced SI graph unsatisfiable.");
        System.err.printf(
                "[SI] Diagnostic counts: knownEdges=%d, unresolvedWWChoices=%d, conditionalDependencyImplications=%d, predicateReads=%d\n",
                knownEdges, constraints.size(), conditionalEdges, graph.getPredicateObservations().size());
        cycleWitness.ifPresentOrElse(
                cycle -> System.err.printf("[SI] Cycle witness: %d edges explain the contradiction.\n", cycle.size()),
                () -> System.err.println("[SI] Cycle witness: not available from current mandatory/forced edges."));

        if (conflicts.getLeft().isEmpty() && conflicts.getRight().isEmpty()) {
            System.err.println("[SI] No compact conflict core was extracted; the contradiction may come from induced edges or predicate visibility.");
        } else {
            System.err.printf("[SI] Conflict core: knownEdges=%d, wwChoices=%d\n",
                    conflicts.getLeft().size(), conflicts.getRight().size());
        }
    }

    private Optional<List<CycleEdge<KeyType, ValueType>>> buildCycleWitness(
            KnownGraph<KeyType, ValueType> graph,
            Collection<SIConstraint<KeyType, ValueType>> constraints,
            Set<Transaction<KeyType, ValueType>> conflictTxns) {
        var labelsByPair = new LinkedHashMap<Pair<Transaction<KeyType, ValueType>, Transaction<KeyType, ValueType>>, List<String>>();
        Set<Transaction<KeyType, ValueType>> allowedTxns = conflictTxns.isEmpty() ? null : conflictTxns;

        addKnownCycleEdges(graph.getKnownGraphA(), labelsByPair, allowedTxns);
        addKnownCycleEdges(graph.getKnownGraphB(), labelsByPair, allowedTxns);
        addForcedBottomConstraintEdges(constraints, labelsByPair, allowedTxns);
        addPredicateCycleEdges(graph, labelsByPair, allowedTxns);

        return findCycle(labelsByPair);
    }

    private void addKnownCycleEdges(
            ValueGraph<Transaction<KeyType, ValueType>, Collection<Edge<KeyType>>> known,
            Map<Pair<Transaction<KeyType, ValueType>, Transaction<KeyType, ValueType>>, List<String>> labelsByPair,
            Set<Transaction<KeyType, ValueType>> allowedTxns) {
        for (var ep : known.edges()) {
            var labels = known.edgeValue(ep).orElse(List.of()).stream()
                    .map(edge -> String.format("known %s%s",
                            edge.getType(),
                            edge.getKey() == null ? "" : String.format(" key=%s", edge.getKey())))
                    .collect(Collectors.toList());
            if (!labels.isEmpty()) {
                addCycleEdge(labelsByPair, ep.source(), ep.target(), String.join("; ", labels), allowedTxns);
            }
        }
    }

    private void addForcedBottomConstraintEdges(
            Collection<SIConstraint<KeyType, ValueType>> constraints,
            Map<Pair<Transaction<KeyType, ValueType>, Transaction<KeyType, ValueType>>, List<String>> labelsByPair,
            Set<Transaction<KeyType, ValueType>> allowedTxns) {
        for (var constraint : constraints) {
            if (isBottomTxn(constraint.getWriteTransaction1()) && !isBottomTxn(constraint.getWriteTransaction2())) {
                addConstraintSideEdges(labelsByPair, constraint.getEdges1(), constraint.getWriteTransaction2(), allowedTxns);
            } else if (!isBottomTxn(constraint.getWriteTransaction1()) && isBottomTxn(constraint.getWriteTransaction2())) {
                addConstraintSideEdges(labelsByPair, constraint.getEdges2(), constraint.getWriteTransaction1(), allowedTxns);
            }
        }
    }

    private void addPredicateCycleEdges(
            KnownGraph<KeyType, ValueType> graph,
            Map<Pair<Transaction<KeyType, ValueType>, Transaction<KeyType, ValueType>>, List<String>> labelsByPair,
            Set<Transaction<KeyType, ValueType>> allowedTxns) {
        var writesByKey = buildWritesByKey(graph);
        for (var observation : graph.getPredicateObservations()) {
            var predicateRead = observation.getPredicateReadEvent();
            if (predicateRead.getPredicate() == null) {
                continue;
            }

            var resultSourceByKey = observation.getTupleSources().stream()
                    .collect(Collectors.toMap(
                            KnownGraph.PredicateTupleSource::getKey,
                            KnownGraph.PredicateTupleSource::getSourceWrite));

            for (var resultEntry : resultSourceByKey.entrySet()) {
                var key = resultEntry.getKey();
                if (observation.getPredicateReadType(key) != KnownGraph.PredicateReadType.EXTERNAL) {
                    continue;
                }
                var resultSource = resultEntry.getValue();
                if (!writeRowIsInPredicateResult(resultSource, predicateRead)) {
                    continue;
                }

                for (var write : writesByKey.getOrDefault(key, List.of())) {
                    if (write == resultSource || write.getTxn().equals(observation.getTxn())) {
                        continue;
                    }
                    if (isBottomTxn(write.getTxn())) {
                        continue;
                    }
                    if (writeRowIsInPredicateResult(write, predicateRead)) {
                        continue;
                    }
                    addCycleEdge(labelsByPair, observation.getTxn(), write.getTxn(),
                            String.format(
                                    "PR_RW key=%s (predicate result contained value %s from %s; %s writes value %s outside the result)",
                                    key,
                                    resultSource.getEvent().getValue(),
                                    resultSource.getTxn(),
                                    write.getTxn(),
                                    write.getEvent().getValue()),
                            allowedTxns);
                }
            }

            for (var entry : writesByKey.entrySet()) {
                var key = entry.getKey();
                if (observation.getPredicateReadType(key) != KnownGraph.PredicateReadType.EXTERNAL) {
                    continue;
                }
                if (resultSourceByKey.containsKey(key)) {
                    continue;
                }
                for (var write : entry.getValue()) {
                    if (write.getTxn().equals(observation.getTxn())) {
                        continue;
                    }
                    if (isBottomTxn(write.getTxn())) {
                        continue;
                    }
                    if (!writeRowIsInPredicateResult(write, predicateRead)) {
                        continue;
                    }
                    addCycleEdge(labelsByPair, observation.getTxn(), write.getTxn(),
                            String.format(
                                    "PR_RW key=%s (predicate result omitted this key; %s writes value %s into the result)",
                                    key, write.getTxn(), write.getEvent().getValue()),
                            allowedTxns);
                }
            }
        }
    }

    private void addConstraintSideEdges(
            Map<Pair<Transaction<KeyType, ValueType>, Transaction<KeyType, ValueType>>, List<String>> labelsByPair,
            Collection<SIEdge<KeyType, ValueType>> edges,
            Transaction<KeyType, ValueType> realWriter,
            Set<Transaction<KeyType, ValueType>> allowedTxns) {
        if (edges == null) {
            return;
        }
        for (var edge : edges) {
            addCycleEdge(labelsByPair, edge.getFrom(), edge.getTo(),
                    String.format("%s key=%s (forced by T_bottom < %s)",
                            edge.getType(), edge.getKey(), realWriter),
                    allowedTxns);
        }
    }

    private void addCycleEdge(
            Map<Pair<Transaction<KeyType, ValueType>, Transaction<KeyType, ValueType>>, List<String>> labelsByPair,
            Transaction<KeyType, ValueType> from,
            Transaction<KeyType, ValueType> to,
            String label,
            Set<Transaction<KeyType, ValueType>> allowedTxns) {
        if (allowedTxns != null && (!allowedTxns.contains(from) || !allowedTxns.contains(to))) {
            return;
        }
        labelsByPair.computeIfAbsent(Pair.of(from, to), ignored -> new ArrayList<>()).add(label);
    }

    private Optional<List<CycleEdge<KeyType, ValueType>>> findCycle(
            Map<Pair<Transaction<KeyType, ValueType>, Transaction<KeyType, ValueType>>, List<String>> labelsByPair) {
        var adjacency = new LinkedHashMap<Transaction<KeyType, ValueType>, LinkedHashSet<Transaction<KeyType, ValueType>>>();
        for (var pair : labelsByPair.keySet()) {
            adjacency.computeIfAbsent(pair.getLeft(), ignored -> new LinkedHashSet<>()).add(pair.getRight());
            adjacency.computeIfAbsent(pair.getRight(), ignored -> new LinkedHashSet<>());
        }

        List<CycleEdge<KeyType, ValueType>> best = null;
        for (var pair : labelsByPair.keySet()) {
            var path = shortestPath(pair.getRight(), pair.getLeft(), adjacency);
            if (path.isEmpty()) {
                continue;
            }
            var cycleNodes = new ArrayList<Transaction<KeyType, ValueType>>();
            cycleNodes.add(pair.getLeft());
            cycleNodes.addAll(path.get());
            var cycle = cycleEdgesFromNodes(cycleNodes, labelsByPair);
            if (best == null || cycle.size() < best.size()) {
                best = cycle;
            }
        }
        return Optional.ofNullable(best);
    }

    private Optional<List<Transaction<KeyType, ValueType>>> shortestPath(
            Transaction<KeyType, ValueType> start,
            Transaction<KeyType, ValueType> goal,
            Map<Transaction<KeyType, ValueType>, LinkedHashSet<Transaction<KeyType, ValueType>>> adjacency) {
        var queue = new ArrayDeque<Transaction<KeyType, ValueType>>();
        var predecessor = new HashMap<Transaction<KeyType, ValueType>, Transaction<KeyType, ValueType>>();
        queue.add(start);
        predecessor.put(start, null);

        while (!queue.isEmpty()) {
            var current = queue.removeFirst();
            if (current.equals(goal)) {
                break;
            }
            for (var next : adjacency.getOrDefault(current, new LinkedHashSet<>())) {
                if (predecessor.containsKey(next)) {
                    continue;
                }
                predecessor.put(next, current);
                queue.addLast(next);
            }
        }

        if (!predecessor.containsKey(goal)) {
            return Optional.empty();
        }

        var result = new ArrayList<Transaction<KeyType, ValueType>>();
        for (var node = goal; node != null; node = predecessor.get(node)) {
            result.add(node);
        }
        Collections.reverse(result);
        return Optional.of(result);
    }

    private List<CycleEdge<KeyType, ValueType>> cycleEdgesFromNodes(
            List<Transaction<KeyType, ValueType>> cycleNodes,
            Map<Pair<Transaction<KeyType, ValueType>, Transaction<KeyType, ValueType>>, List<String>> labelsByPair) {
        var result = new ArrayList<CycleEdge<KeyType, ValueType>>();
        for (int i = 0; i + 1 < cycleNodes.size(); i++) {
            var from = cycleNodes.get(i);
            var to = cycleNodes.get(i + 1);
            var labels = labelsByPair.getOrDefault(Pair.of(from, to), List.of("unknown edge"));
            result.add(new CycleEdge<>(from, to, String.join("; ", labels)));
        }
        return result;
    }

    private String formatCycleWitness(List<CycleEdge<KeyType, ValueType>> cycle) {
        var builder = new StringBuilder("Cycle witness:\n");
        for (int i = 0; i < cycle.size(); i++) {
            var edge = cycle.get(i);
            builder.append(String.format("  %d. %s -> %s: %s\n",
                    i + 1, edge.from, edge.to, edge.label));
        }
        return builder.toString();
    }

    private static boolean isBottomTxn(Transaction<?, ?> txn) {
        return txn.getId() == -1L
                && txn.getSession() != null
                && txn.getSession().getId() == -1L;
    }

    static final class InducedGraph {
        private InducedGraph() {
        }

        /**
         * Compact branch oracle for the SI criterion A union (A composition B).
         * It reuses the current endpoint matrices instead of rebuilding a full
         * KnownGraph and Guava/MatrixGraph stack for every constraint side.
         */
        static final class Oracle<KeyType, ValueType> {
            private final Map<Transaction<KeyType, ValueType>, Integer> nodeIndex =
                    new IdentityHashMap<>();
            private final BitSet[] directA;
            private final BitSet[] directB;

            Oracle(KnownGraph<KeyType, ValueType> graph) {
                int next = 0;
                for (var txn : graph.getKnownGraphA().nodes()) {
                    if (!isBottomTxn(txn)) {
                        nodeIndex.put(txn, next++);
                    }
                }
                directA = emptyRows(nodeIndex.size());
                directB = emptyRows(nodeIndex.size());
                addKnownEdges(graph.getKnownGraphA(), directA);
                addKnownEdges(graph.getKnownGraphB(), directB);
            }

            boolean hasCycle() {
                return !isAcyclic(directA, directB);
            }

            boolean canAddAll(Collection<SIEdge<KeyType, ValueType>> edges) {
                var trialA = cloneRows(directA);
                var trialB = cloneRows(directB);
                if (!addAll(edges, trialA, trialB)) {
                    return false;
                }
                return isAcyclic(trialA, trialB);
            }

            void addAll(Collection<SIEdge<KeyType, ValueType>> edges) {
                if (!addAll(edges, directA, directB)) {
                    throw new IllegalArgumentException(
                            "cannot commit invalid SI dependency branch");
                }
            }

            private boolean addAll(Collection<SIEdge<KeyType, ValueType>> edges,
                    BitSet[] targetA, BitSet[] targetB) {
                if (edges == null) {
                    return true;
                }
                for (var edge : edges) {
                    if (edge.getFrom().equals(edge.getTo()) || isBottomTxn(edge.getTo())) {
                        return false;
                    }
                    if (isBottomTxn(edge.getFrom())) {
                        continue;
                    }
                    var from = nodeIndex.get(edge.getFrom());
                    var to = nodeIndex.get(edge.getTo());
                    if (from == null || to == null) {
                        throw new IllegalStateException(
                                "transaction missing from SI induced-graph oracle");
                    }
                    if (isDependencyEdgeA(edge.getType())) {
                        targetA[from].set(to);
                    } else if (isDependencyEdgeB(edge.getType())) {
                        targetB[from].set(to);
                    } else {
                        throw new IllegalArgumentException(
                                "unsupported SI dependency edge type: " + edge.getType());
                    }
                }
                return true;
            }

            private void addKnownEdges(
                    ValueGraph<Transaction<KeyType, ValueType>, Collection<Edge<KeyType>>> graph,
                    BitSet[] target) {
                for (var endpoint : graph.edges()) {
                    if (isBottomTxn(endpoint.source()) || isBottomTxn(endpoint.target())) {
                        continue;
                    }
                    target[nodeIndex.get(endpoint.source())]
                            .set(nodeIndex.get(endpoint.target()));
                }
            }

            private static BitSet[] emptyRows(int size) {
                var rows = new BitSet[size];
                for (int i = 0; i < size; i++) {
                    rows[i] = new BitSet(size);
                }
                return rows;
            }

            private static BitSet[] cloneRows(BitSet[] source) {
                var rows = new BitSet[source.length];
                for (int i = 0; i < source.length; i++) {
                    rows[i] = (BitSet) source[i].clone();
                }
                return rows;
            }

            private static boolean isAcyclic(BitSet[] directA, BitSet[] directB) {
                var induced = cloneRows(directA);
                for (int from = 0; from < directA.length; from++) {
                    for (int middle = directA[from].nextSetBit(0); middle >= 0;
                            middle = directA[from].nextSetBit(middle + 1)) {
                        induced[from].or(directB[middle]);
                    }
                }

                var inDegree = new int[induced.length];
                for (var successors : induced) {
                    for (int to = successors.nextSetBit(0); to >= 0;
                            to = successors.nextSetBit(to + 1)) {
                        inDegree[to]++;
                    }
                }
                var queue = new ArrayDeque<Integer>();
                for (int node = 0; node < inDegree.length; node++) {
                    if (inDegree[node] == 0) {
                        queue.add(node);
                    }
                }
                int visited = 0;
                while (!queue.isEmpty()) {
                    int from = queue.removeFirst();
                    visited++;
                    for (int to = induced[from].nextSetBit(0); to >= 0;
                            to = induced[from].nextSetBit(to + 1)) {
                        if (--inDegree[to] == 0) {
                            queue.addLast(to);
                        }
                    }
                }
                return visited == induced.length;
            }

            private static boolean isDependencyEdgeA(EdgeType type) {
                return type == EdgeType.SO || type == EdgeType.WR
                        || type == EdgeType.WW || type == EdgeType.PR_WR;
            }

            private static boolean isDependencyEdgeB(EdgeType type) {
                return type == EdgeType.RW || type == EdgeType.PR_RW;
            }
        }

        static <KeyType, ValueType> MatrixGraph<Transaction<KeyType, ValueType>> depReachability(
                KnownGraph<KeyType, ValueType> graph) {
            return depGraph(graph).reachability();
        }

        static <KeyType, ValueType> boolean hasCycle(
                KnownGraph<KeyType, ValueType> graph) {
            return inducedGraph(graph).hasLoops();
        }

        static <KeyType, ValueType> boolean reaches(
                MatrixGraph<Transaction<KeyType, ValueType>> reachability,
                Transaction<KeyType, ValueType> from,
                Transaction<KeyType, ValueType> to) {
            return !from.equals(to) && reachability.hasEdgeConnecting(from, to);
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

    private static class CycleEdge<KeyType, ValueType> {
        private final Transaction<KeyType, ValueType> from;
        private final Transaction<KeyType, ValueType> to;
        private final String label;

        private CycleEdge(Transaction<KeyType, ValueType> from,
                          Transaction<KeyType, ValueType> to,
                          String label) {
            this.from = from;
            this.to = to;
            this.label = label;
        }
    }
    /* ================================================================
     * Constraint generation (WW / ordinary RW) — unchanged from the
     * original implementation.
     * ================================================================ */

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

    private static <KeyType, ValueType> Map<KeyType,
            List<KnownGraph.WriteRef<KeyType, ValueType>>> buildWritesByKey(
                    KnownGraph<KeyType, ValueType> graph) {
        var result = new HashMap<KeyType,
                List<KnownGraph.WriteRef<KeyType, ValueType>>>();
        for (var write : graph.getAllWrites()) {
            result.computeIfAbsent(write.getEvent().getKey(),
                    k -> new ArrayList<>()).add(write);
        }
        return result;
    }

    /**
     * Check whether the row produced by {@code writeRef} is present in the
     * predicate result set for {@code predicateReadEvent}.
     */
    private static <KeyType, ValueType> boolean writeRowIsInPredicateResult(
            KnownGraph.WriteRef<KeyType, ValueType> writeRef,
            Event<KeyType, ValueType> predicateReadEvent) {
        var ev = writeRef.getEvent();
        var predicate = predicateReadEvent.getPredicate();
        var relations = predicate.scope().relations();
        try {
            var evaluation = predicate.evaluate(new MapVisibleState<>(
                    Map.of(ev.getKey(), ev.getValue()), key -> {
                        var canonical = String.valueOf(key);
                        var separator = canonical.indexOf(':');
                        if (separator > 0) {
                            return canonical.substring(0, separator);
                        }
                        if (relations.size() == 1) {
                            return relations.iterator().next();
                        }
                        throw new QueryException(
                                "cannot resolve relation for key " + key);
                    }));
            return evaluation.inputs().containsKey(ev.getKey());
        } catch (QueryException exception) {
            return false;
        }
    }
}
