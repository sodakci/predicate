package verifier;

import graph.Edge;
import graph.EdgeType;
import graph.KnownGraph;
import history.Event;
import history.History;
import history.HistoryLoader;
import history.Transaction;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
import java.util.stream.Collectors;

import org.apache.commons.lang3.tuple.Pair;

import util.Profiler;
import util.TriConsumer;

@SuppressWarnings("UnstableApiUsage")
public class SERVerifier<KeyType, ValueType> {
    public enum PredicateSolvingMode {
        EAGER,
        GMWR
    }

    public enum PruningMode {
        NONE,
        REACHABILITY
    }

    public enum AuditResult {
        ACCEPT(0, "[[[[ ACCEPT ]]]]"),
        REJECT(-1, "[[[[ REJECT ]]]]"),
        INVALID_HISTORY(2, "[[[[ INVALID_HISTORY ]]]]");

        public final int exitCode;
        public final String marker;

        AuditResult(int exitCode, String marker) {
            this.exitCode = exitCode;
            this.marker = marker;
        }
    }

    public enum AuditStage {
        WW,
        GMWR,
        PREDICATE,
        SAT
    }

    /**
     * Injectable SAT backend used by {@link SERSolverAR}.
     */
    @FunctionalInterface
    public interface SatSolveBackend {
        /**
         * @param assumptions logical obligations enabled for this solve
         * @return {@code true} for SAT, {@code false} for UNSAT
         */
        boolean solve(
                monosat.Solver solver,
                Collection<monosat.Lit> assumptions);
    }

    /** Internal solver settings retained for differential tests and embedding. */
    public static final class SolverSettings {
        public PredicateSolvingMode predicateSolvingMode = PredicateSolvingMode.GMWR;
        public PruningMode pruningMode = PruningMode.REACHABILITY;
        public boolean gmwrPrepropagation = true;
        public boolean predicateWitnessCoalescing = true;
        public boolean graphEdgeInterning = true;
        public boolean detailedPredicateMetrics;
        public SatSolveBackend satSolveBackend;
        public Consumer<AuditStage> auditProgressListener = ignored -> { };

        public static SolverSettings forModes(PredicateSolvingMode predicate,
                                              PruningMode pruning) {
            var settings = new SolverSettings();
            settings.predicateSolvingMode = Objects.requireNonNull(
                    predicate, "predicateSolvingMode");
            settings.pruningMode = Objects.requireNonNull(pruning, "pruningMode");
            boolean gmwr = predicate == PredicateSolvingMode.GMWR;
            settings.gmwrPrepropagation = gmwr;
            settings.predicateWitnessCoalescing = true;
            settings.graphEdgeInterning = true;
            return settings;
        }
    }

    private final History<KeyType, ValueType> history;
    private final boolean detailedPredicateMetrics;
    private final PredicateSolvingMode predicateSolvingMode;
    private final PruningMode pruningMode;
    private final SolverSettings solverSettings;

    public SERVerifier(HistoryLoader<KeyType, ValueType> loader) {
        this(loader, false, PredicateSolvingMode.GMWR, PruningMode.REACHABILITY);
    }

    public SERVerifier(HistoryLoader<KeyType, ValueType> loader,
            boolean detailedPredicateMetrics) {
        this(loader, detailedPredicateMetrics, PredicateSolvingMode.GMWR,
                PruningMode.REACHABILITY);
    }

    public SERVerifier(HistoryLoader<KeyType, ValueType> loader,
            boolean detailedPredicateMetrics,
            PredicateSolvingMode predicateSolvingMode) {
        this(loader, detailedPredicateMetrics, predicateSolvingMode,
                PruningMode.REACHABILITY);
    }

    public SERVerifier(HistoryLoader<KeyType, ValueType> loader,
            boolean detailedPredicateMetrics,
            PredicateSolvingMode predicateSolvingMode,
            PruningMode pruningMode) {
        this(loader, SolverSettings.forModes(predicateSolvingMode, pruningMode),
                detailedPredicateMetrics);
    }

    public SERVerifier(HistoryLoader<KeyType, ValueType> loader,
            SolverSettings solverSettings,
            boolean detailedPredicateMetrics) {
        history = loader.loadHistory();
        this.solverSettings = Objects.requireNonNull(solverSettings, "solverSettings");
        this.solverSettings.detailedPredicateMetrics = detailedPredicateMetrics;
        this.detailedPredicateMetrics = detailedPredicateMetrics;
        this.predicateSolvingMode = Objects.requireNonNull(
                solverSettings.predicateSolvingMode, "predicateSolvingMode");
        this.pruningMode = Objects.requireNonNull(
                solverSettings.pruningMode, "pruningMode");
    }

    public int getTransactionCount() {
        return history.getClientTransactions().size();
    }

    public int getEventCount() {
        return history.getClientTransactions().stream()
                .mapToInt(transaction -> transaction.getEvents().size())
                .sum();
    }

    public long getPredicateObservationCount() {
        return history.getEvents().stream()
                .filter(event -> event.getType() == Event.EventType.PREDICATE_READ)
                .count();
    }

    public AuditResult audit() {
        var profiler = Profiler.getInstance();

        profiler.startTick("ONESHOT_CONS");
        profiler.startTick("SER_VERIFY_INT");
        boolean satisfy_int = Utils.verifyInternalConsistency(history);
        profiler.endTick("SER_VERIFY_INT");
        if (!satisfy_int) {
            return AuditResult.REJECT;
        }

        profiler.startTick("SER_GEN_PREC_GRAPH");
        var graph = new KnownGraph<>(history, false);
        var precedence = createPrecedenceOracle(history);
        var reachabilityPruning = new Pruning<KeyType, ValueType>(
                precedence, false);
        profiler.endTick("SER_GEN_PREC_GRAPH");

        // ===== SER MODE (logical dependencies -> serialization graph) =====
        // Typed SO/WR/WW/RW/PR_WR/PR_RW metadata remains in Java; MonoSAT sees
        // one endpoint-only serialization constraint graph.

        profiler.startTick("SER_GEN_CONSTRAINTS");
        var constraints = generateConstraintsSER(history, graph);
        profiler.endTick("SER_GEN_CONSTRAINTS");

        int wwInitialConstraints = constraints.size();
        profiler.addCount("WW_INITIAL_CHOICES", wwInitialConstraints);
        boolean pruningRejected = false;
        profiler.startTick("WW_REACHABILITY_PRUNE_MS");
        try {
            switch (pruningMode) {
            case NONE:
                pruningRejected = false;
                break;
            case REACHABILITY:
                pruningRejected = reachabilityPruning.pruneConstraints(
                        graph, constraints);
                break;
            }
        } finally {
            profiler.endTick("WW_REACHABILITY_PRUNE_MS");
        }
        profiler.addCount("WW_REACHABILITY_FORCED",
                wwInitialConstraints - constraints.size());
        profiler.addCount("WW_AFTER_REACHABILITY", constraints.size());

        if (predicateSolvingMode != PredicateSolvingMode.GMWR || pruningRejected) {
            notifyStage(AuditStage.WW);
        }

        if (pruningRejected) {
            profiler.endTick("ONESHOT_CONS");
            var conflicts = pruningMode == PruningMode.REACHABILITY
                    ? Pruning.<KeyType, ValueType>getLastConflicts()
                    : Pair.<Collection<Pair<com.google.common.graph.EndpointPair<Transaction<KeyType, ValueType>>, Collection<Edge<KeyType>>>>,
                            Collection<SERConstraint<KeyType, ValueType>>>of(
                                    Collections.emptyList(), Collections.emptyList());
            emitRejectDiagnostics(
                    graph, constraints, conflicts, Collections.emptyList());
            return AuditResult.REJECT;
        }
        profiler.endTick("ONESHOT_CONS");

        profiler.startTick("ONESHOT_SOLVE");
        SERSolverAR<KeyType, ValueType> solver;
        profiler.startTick("SER_AR_ENCODE");
        try {
            solver = new SERSolverAR<>(history, graph, constraints,
                    true, detailedPredicateMetrics, solverSettings, precedence);
        } finally {
            profiler.endTick("SER_AR_ENCODE");
        }
        if (predicateSolvingMode == PredicateSolvingMode.GMWR) {
            notifyStage(AuditStage.WW);
            notifyStage(AuditStage.GMWR);
        }
        notifyStage(AuditStage.PREDICATE);
        notifyStage(AuditStage.SAT);

        profiler.startTick("SER_AR_SOLVE");
        SolveStatus status;
        try {
            status = solver.solve();
        } finally {
            profiler.endTick("SER_AR_SOLVE");
        }
        profiler.endTick("ONESHOT_SOLVE");

        if (status == SolveStatus.UNSAT) {
            emitRejectDiagnostics(
                    graph, constraints, solver.getConflicts(),
                    solver.getConflictReasons());
            return AuditResult.REJECT;
        }
        return AuditResult.ACCEPT;
    }

    private void notifyStage(AuditStage stage) {
        solverSettings.auditProgressListener.accept(stage);
    }

    static <KeyType, ValueType> PrecedenceOracle<Transaction<KeyType, ValueType>>
            createPrecedenceOracle(History<KeyType, ValueType> history) {
        return new PrecedenceOracle<>(history.getTransactions());
    }

    private void emitRejectDiagnostics(
            KnownGraph<KeyType, ValueType> graph,
            Collection<SERConstraint<KeyType, ValueType>> constraints,
            Pair<Collection<Pair<com.google.common.graph.EndpointPair<Transaction<KeyType, ValueType>>, Collection<Edge<KeyType>>>>,
                    Collection<SERConstraint<KeyType, ValueType>>> conflicts,
            Collection<SERSolverAR.AssumptionReason<KeyType, ValueType>> conflictReasons) {
        int knownEdges = graph.getKnownGraphA().edges().size()
                + graph.getKnownGraphB().edges().size();
        System.err.println("[SER] Reject reason: Adya typed dependency constraints are UNSAT.");
        System.err.printf(
                "[SER] Diagnostic counts: knownEdges=%d, unresolvedWWChoices=%d, predicateReads=%d%n",
                knownEdges, constraints.size(), graph.getPredicateObservations().size());

        if (!conflictReasons.isEmpty()) {
            System.err.printf("[SER] Conflict clause: %s%n",
                    conflictReasons.stream()
                            .map(reason -> "!" + reason.assumptionId())
                            .collect(Collectors.joining(" | ")));
            System.err.println("[SER]   |");
            for (var reason : conflictReasons) {
                System.err.printf("[SER]   +-- %s [%s] %s%n",
                        reason.assumptionId(), reason.getKind(), reason.getReason());
            }
        } else if (conflicts.getLeft().isEmpty() && conflicts.getRight().isEmpty()) {
            System.err.println("[SER] No assumption conflict clause was produced; "
                    + "the contradiction is in deterministic graph or pruning facts.");
        } else {
            System.err.printf("[SER] Conflict core: knownEdges=%d, wwChoices=%d%n",
                    conflicts.getLeft().size(), conflicts.getRight().size());
        }
    }

    /* ================================================================
     * Constraint generation (WW / ordinary RW) — unchanged from the
     * original implementation.
     * ================================================================ */

    private static <KeyType, ValueType> Collection<SERConstraint<KeyType, ValueType>> generateConstraintsCoalesce(
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

        var constraintEdges = new HashMap<Pair<Transaction<KeyType, ValueType>, Transaction<KeyType, ValueType>>, Collection<SEREdge<KeyType, ValueType>>>();
        forEachWriteSameKey.accept((a, c, key) -> {
            var addEdge = ((BiConsumer<Transaction<KeyType, ValueType>, Transaction<KeyType, ValueType>>) (m, n) -> {
                constraintEdges.computeIfAbsent(Pair.of(m, n), p -> new ArrayList<>())
                        .add(new SEREdge<>(m, n, EdgeType.WW, key));
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

                        constraintEdges.get(Pair.of(a, c)).add(new SEREdge<>(b, c, EdgeType.RW, edge.getKey()));
                    }
                }
            }
        }

        var constraints = new HashSet<SERConstraint<KeyType, ValueType>>();
        var addedPairs = new HashSet<Pair<Transaction<KeyType, ValueType>, Transaction<KeyType, ValueType>>>();
        AtomicInteger constraintId = new AtomicInteger();
        forEachWriteSameKey.accept((a, c, key) -> {
            if (addedPairs.contains(Pair.of(a, c)) || addedPairs.contains(Pair.of(c, a))) {
                return;
            }
            addedPairs.add(Pair.of(a, c));
            constraints.add(new SERConstraint<>(constraintEdges.get(Pair.of(a, c)), constraintEdges.get(Pair.of(c, a)),
                    a, c, constraintId.getAndIncrement()));
        });

        return constraints;
    }

    /**
     * SER direct-edge constraint generation.
     *
     * The generated WW and RW edges are direct serial-precedence candidates and
     * will be placed directly into:
     *   A = {SO, WR, WW, PR_WR}
     *   B = {RW, PR_RW}
     */
    static <KeyType, ValueType> Collection<SERConstraint<KeyType, ValueType>> generateConstraintsSER(
            History<KeyType, ValueType> history, KnownGraph<KeyType, ValueType> graph) {
        return generateConstraintsCoalesce(history, graph);
    }

}
