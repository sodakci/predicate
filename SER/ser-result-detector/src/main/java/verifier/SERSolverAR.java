package verifier;

import static history.query.PredicateReadSemantics.expectedPredicateInputs;
import static history.query.PredicateReadSemantics.predicateSnapshotMatches;
import static history.query.PredicateReadSemantics.relationResolverFor;

import graph.Edge;
import graph.EdgeType;
import graph.KnownGraph;
import history.Event;
import history.History;
import history.Transaction;
import history.query.MapVisibleState;
import history.query.QueryException;
import history.query.QueryPlan;
import history.query.QueryScope;
import history.query.RelationResolver;
import history.query.RowContribution;
import history.query.RowVersion;
import monosat.Graph;
import monosat.Lit;
import monosat.Logic;
import monosat.Solver;
import com.google.common.graph.EndpointPair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import util.Profiler;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Encodes serializability as typed logical dependencies projected onto one
 * serialization constraint graph.
 *
 * <p>Logical dependencies retain their type/key metadata
 * ({@code SO/WR/WW/RW/PR_WR/PR_RW}) for explanations and diagnostics. Their
 * guards constrain a single endpoint-only MonoSAT graph, which is also used
 * to choose predicate frontiers and checked for acyclicity.</p>
 */
class SERSolverAR<KeyType, ValueType> {
    private static final int COMPACT_MATCH_UNAVAILABLE = -1;
    private static final int COMPACT_MATCH_INVALID = 0;
    private static final int COMPACT_MATCH_FALSE = 1;
    private static final int COMPACT_MATCH_TRUE = 2;
    private static final int MAX_GENERAL_ROW_CONTRIBUTIONS = 32_768;

    private final History<KeyType, ValueType> history;
    private final KnownGraph<KeyType, ValueType> graph;
    private final Collection<SERConstraint<KeyType, ValueType>> constraints;
    private final SERVerifier.SolverSettings settings;
    private final Solver solver;
    private final boolean collectConflicts;
    private final boolean collectPredicateMetrics;
    private final SERVerifier.PredicateSolvingMode predicateSolvingMode;
    private final boolean gmwrPrepropagation;
    private final boolean predicateWitnessCoalescing;
    private final boolean graphEdgeInterning;
    private final PrecedenceOracle<Transaction<KeyType, ValueType>> precedence;
    private final LatestVisibleChecker<KeyType, ValueType> latestVisibleChecker =
            new LatestVisibleChecker<>();
    private boolean solverTimedOut;

    private final List<Transaction<KeyType, ValueType>> txns;
    private final Map<Transaction<KeyType, ValueType>, Integer> txnIndex;
    private KnownOrder knownOrder;
    private GmwrPropagationState<KeyType, ValueType> propagation;
    private boolean propagationConflict;
    private long residualWwChoiceVariables;
    private long residualWwChoiceConstraints;
    // The only MonoSAT graph. Logical dependency types/keys remain in the Java
    // layer and project their endpoint order into this serialization graph.
    private final Graph serializationGraph;
    private final int[] serializationNodes;
    private final Map<Pair<Transaction<KeyType, ValueType>, Transaction<KeyType, ValueType>>, Lit> serializationEdgeCache =
            new HashMap<>();
    private final Set<Pair<Transaction<KeyType, ValueType>, Transaction<KeyType, ValueType>>> comparablePairs =
            new HashSet<>();
    private final Map<Triple<Transaction<KeyType, ValueType>, Transaction<KeyType, ValueType>, KeyType>, Lit> wwOrder =
            new HashMap<>();
    // Per-key write lists provide the local write order candidates used by
    // predicate-read encodings.
    private final Map<KeyType, List<KnownGraph.WriteRef<KeyType, ValueType>>> writesByKey;
    // Rule 2 consumes only already fixed typed WW facts. A nested index avoids
    // probing every unresolved WW literal for every predicate source.
    private final Map<KeyType, Map<Transaction<KeyType, ValueType>,
            Set<Transaction<KeyType, ValueType>>>> knownWwSuccessorsByKey;
    // Sort the complete key universe once. Each index retains the original
    // write order and precomputes the last write of every writer transaction.
    private final List<KeyWriteIndex<KeyType, ValueType>> sortedKeyWriteIndexes;
    // Only scopes with an explicit stable value key participate in this cache.
    private final Map<Object, List<KeyWriteIndex<KeyType, ValueType>>> scopedWritesCache =
            new HashMap<>();
    // Row contribution caches are intentionally solver-local. Compact k/value
    // plans use three BitSets; general row-local projections use a bounded LRU.
    private final IdentityHashMap<KnownGraph.WriteRef<KeyType, ValueType>, Integer> writeRefIds =
            new IdentityHashMap<>();
    private final IdentityHashMap<QueryPlan<KeyType, ValueType>, CompactRowMatchCache>
            compactRowMatchCaches = new IdentityHashMap<>();
    private final IdentityHashMap<QueryPlan<KeyType, ValueType>, Integer>
            rowContributionPlanIds = new IdentityHashMap<>();
    private final IdentityHashMap<Event<KeyType, ValueType>, QueryPlan<KeyType, ValueType>>
            rowLocalQueryPlans = new IdentityHashMap<>();
    private int nextRowContributionPlanId;
    private final LinkedHashMap<Long, CachedRowContribution<KeyType>> generalRowContributions =
            new LinkedHashMap<Long, CachedRowContribution<KeyType>>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<Long, CachedRowContribution<KeyType>> eldest) {
                    return size() > MAX_GENERAL_ROW_CONTRIBUTIONS;
                }
            };
    private final PredicateEncodingMetrics predicateEncodingMetrics =
            new PredicateEncodingMetrics();
    private boolean encodingPredicateConstraints;
    private boolean collectingPredicateMetrics;
    // One source constraint is encoded for every external predicate-read key:
    // either a recorded source is fixed or a latest-visible frontier is chosen.
    private long predicateSourceConstraintCount;
    // GMWR row-local item obligations sharing (reader,bad-writer) reuse one
    // outside-snapshot branch and propagation state. Items remain explicit.
    // Multi-relation QueryPlans use the common eager explicit JOIN encoding;
    // GMWR remains a row-local acceleration only.
    private long gmwrItemObligations;
    private long gmwrAbsentItemObligations;
    private long gmwrResidualClauses;
    private long gmwrResidualLiterals;
    private long gmwrResolutionRounds;
    private long gmwrIntervalCandidatesPruned;
    private long gmwrBuildNanos;
    private long gmwrResolutionNanos;
    private long gmwrGeneralObservations;
    // Semantic PR_WR source alternatives are pruned before physical predicate
    // edge compression. The two counters distinguish a direct A->R cycle
    // from the PR_RW cycle forced by a known WW(A,B,k), a changed result, and
    // B->*R.
    private long gmwrPrWrSourceAlternatives;
    private long gmwrPrWrReachabilityPruned;
    private long gmwrPrWrPrRwCyclePruned;

    // Writer comparability is key-local and independent of the predicate read.
    // Initialize each key's writer pairs once, then reuse them across reads.
    private final Set<KeyType> initializedPredicateWriteOrders = new HashSet<>();
    // Dependency edges are created with their type/key metadata before their
    // guards are encoded into MonoSAT. This keeps edge construction separate
    // from constraint encoding and preserves RW/PR_RW as B-side dependencies.
    private final List<GuardedDependencyEdge<KeyType, ValueType>> dependencyEdgesA =
            new ArrayList<>();
    private final List<GuardedDependencyEdge<KeyType, ValueType>> dependencyEdgesB =
            new ArrayList<>();
    private final Map<List<Lit>, Set<SEREdge<KeyType, ValueType>>> dependencyEdgesByGuard =
            new HashMap<>();
    // Predicate witnesses share typed endpoint metadata while retaining their
    // activation terms. Each support implies the physical edge directly;
    // neither conjunction gates nor an OR of supports is materialized.
    private final Map<PredicateTransactionEdgeKey<KeyType, ValueType>,
            CoalescedPredicateDependency<KeyType, ValueType>>
            predicateDependencyAccumulators = new LinkedHashMap<>();
    private long predicateDependencyCandidateCount;
    // Set only while encoding one external predicate-read key. It lets the
    // later physical-edge pass attribute a witness to a recorded or absent
    // source without changing the typed dependency semantics.
    private PredicateDependencyOrigin currentPredicateDependencyOrigin =
            PredicateDependencyOrigin.KNOWN_OR_INTERNAL;
    private AssumptionReason<KeyType, ValueType> currentPredicateAssumption;
    private final IdentityHashMap<KnownGraph.PredicateObservation<KeyType, ValueType>,
            AssumptionReason<KeyType, ValueType>> predicateAssumptions =
            new IdentityHashMap<>();
    private long sourcedPhysicalPredicateMaterializeNanos;
    private long sourcelessPhysicalPredicateMaterializeNanos;
    private long mixedPhysicalPredicateMaterializeNanos;
    private long knownOrInternalPhysicalPredicateMaterializeNanos;
    private final Map<Pair<Transaction<KeyType, ValueType>,
            Transaction<KeyType, ValueType>>, List<SEREdge<KeyType, ValueType>>>
            logicalDependenciesByEndpoint =
            new HashMap<>();
    private final Set<PredicateWitnessIdentity<KeyType, ValueType>>
            predicateWitnessIdentities = new HashSet<>();
    private Collection<Pair<EndpointPair<Transaction<KeyType, ValueType>>, Collection<Edge<KeyType>>>> conflictEdges =
            Collections.emptyList();
    private Collection<SERConstraint<KeyType, ValueType>> conflictConstraints = Collections.emptyList();
    private final List<Lit> assumptionLiterals = new ArrayList<>();
    private final Map<Lit, AssumptionReason<KeyType, ValueType>> assumptionReasons =
            new HashMap<>();
    private Collection<AssumptionReason<KeyType, ValueType>> conflictReasons =
            Collections.emptyList();
    private long nextAssumptionId = 1L;

    SERSolverAR(History<KeyType, ValueType> history,
                KnownGraph<KeyType, ValueType> graph,
                Collection<SERConstraint<KeyType, ValueType>> constraints) {
        this(history, graph, constraints, true, false,
                SERVerifier.SolverSettings.forModes(
                        SERVerifier.PredicateSolvingMode.GMWR,
                        SERVerifier.PruningMode.REACHABILITY));
    }

    SERSolverAR(History<KeyType, ValueType> history,
                KnownGraph<KeyType, ValueType> graph,
                Collection<SERConstraint<KeyType, ValueType>> constraints,
                boolean collectConflicts,
                boolean collectPredicateMetrics) {
        this(history, graph, constraints, collectConflicts, collectPredicateMetrics,
                SERVerifier.SolverSettings.forModes(
                        SERVerifier.PredicateSolvingMode.GMWR,
                        SERVerifier.PruningMode.REACHABILITY));
    }

    SERSolverAR(History<KeyType, ValueType> history,
                KnownGraph<KeyType, ValueType> graph,
                Collection<SERConstraint<KeyType, ValueType>> constraints,
                boolean collectConflicts,
                boolean collectPredicateMetrics,
                SERVerifier.PredicateSolvingMode predicateSolvingMode) {
        this(history, graph, constraints, collectConflicts, collectPredicateMetrics,
                SERVerifier.SolverSettings.forModes(
                        predicateSolvingMode,
                        SERVerifier.PruningMode.REACHABILITY));
    }

    SERSolverAR(History<KeyType, ValueType> history,
                KnownGraph<KeyType, ValueType> graph,
                Collection<SERConstraint<KeyType, ValueType>> constraints,
                boolean collectConflicts,
                boolean collectPredicateMetrics,
                SERVerifier.SolverSettings solverSettings) {
        this(history, graph, constraints, collectConflicts, collectPredicateMetrics,
                solverSettings, SERVerifier.createPrecedenceOracle(history));
    }

    SERSolverAR(History<KeyType, ValueType> history,
                KnownGraph<KeyType, ValueType> graph,
                Collection<SERConstraint<KeyType, ValueType>> constraints,
                boolean collectConflicts,
                boolean collectPredicateMetrics,
                SERVerifier.SolverSettings solverSettings,
                PrecedenceOracle<Transaction<KeyType, ValueType>> precedence) {
        var profiler = Profiler.getInstance();
        profiler.startTick("SER_AR_ENCODE_SETUP");
        try {
            this.history = history;
            this.graph = graph;
            this.constraints = new ArrayList<>(constraints);
            this.collectConflicts = collectConflicts;
            this.collectPredicateMetrics = collectPredicateMetrics;
            this.settings = solverSettings == null
                    ? SERVerifier.SolverSettings.forModes(
                            SERVerifier.PredicateSolvingMode.GMWR,
                            SERVerifier.PruningMode.REACHABILITY)
                    : solverSettings;
            this.predicateSolvingMode = Objects.requireNonNull(
                    this.settings.predicateSolvingMode, "predicateSolvingMode");
            this.gmwrPrepropagation = this.settings.gmwrPrepropagation;
            this.predicateWitnessCoalescing = this.settings.predicateWitnessCoalescing;
            this.graphEdgeInterning = this.settings.graphEdgeInterning;
            this.precedence = Objects.requireNonNull(precedence, "precedence");
            validateSupportedPredicates();
            this.solver = new Solver();
            this.txns = history.getTransactions().stream()
                    .filter(txn -> !isBottomTxn(txn))
                    .collect(Collectors.toList());
            this.txnIndex = new HashMap<>();
            for (int i = 0; i < txns.size(); i++) {
                txnIndex.put(txns.get(i), i);
            }
            this.serializationGraph = new Graph(solver);
            this.serializationNodes = createSerializationNodes();
            this.writesByKey = buildWritesByKey(graph);
            this.knownWwSuccessorsByKey = buildKnownWwSuccessorsByKey(graph);
            for (int writeRefId = 0;
                    writeRefId < graph.getAllWrites().size(); writeRefId++) {
                writeRefIds.put(graph.getAllWrites().get(writeRefId), writeRefId);
            }
            this.sortedKeyWriteIndexes = buildKeyWriteIndexes(writesByKey);
            propagateBeforeEncoding();
            this.knownOrder = buildKnownOrder();
        } finally {
            profiler.endTick("SER_AR_ENCODE_SETUP");
        }
        profileVoid(profiler, "SER_AR_ENCODE_KNOWN_EDGES", this::encodeKnownEdges);
        profileVoid(profiler, "SER_AR_ENCODE_WW", this::encodeRemainingWwChoices);
        profileVoid(profiler, "SER_AR_ENCODE_PREDICATE", this::encodePredicateConstraints);
        profileVoid(profiler, "SER_AR_ENCODE_DEPENDENCIES", this::encodeDependencyEdges);
        profileVoid(profiler, "SER_AR_ENCODE_ACYCLIC", this::encodeSerializationAcyclicity);
        publishResidualSatStats();
    }

    PrecedenceOracle<Transaction<KeyType, ValueType>> precedenceOracle() {
        return precedence;
    }

    /**
     * Solves the typed dependency encoding under registered assumptions. On
     * UNSAT, MonoSAT's conflict clause is mapped directly to logical reasons.
     * Timeout is a distinct status and never reported as UNSAT.
     */
    SolveStatus solve() {
        var profiler = Profiler.getInstance();
        var sat = profileBooleanOptional(profiler, "SER_MONOSAT_SOLVE", this::solveOnce);
        solverTimedOut = sat == null;
        conflictEdges = Collections.emptyList();
        conflictConstraints = Collections.emptyList();
        conflictReasons = Collections.emptyList();
        final SolveStatus status;
        if (solverTimedOut) {
            status = SolveStatus.TIMEOUT;
        } else if (sat) {
            status = SolveStatus.SAT;
        } else {
            if (collectConflicts) {
                profileVoid(profiler, "SER_AR_CONFLICT_EXTRACTION", this::extractConflicts);
            }
            status = SolveStatus.UNSAT;
        }
        publishSolveStats();
        return status;
    }

    boolean timedOut() {
        return solverTimedOut;
    }

    private Boolean solveOnce() {
        int timeoutSeconds = Math.max(0, settings.solverTimeoutSeconds);
        var backend = settings.satSolveBackend;
        if (backend != null) {
            return backend.solve(
                    solver, timeoutSeconds, assumptionLiterals).orElse(null);
        }
        if (timeoutSeconds > 0) {
            solver.setTimeLimit(timeoutSeconds);
            var result = solver.solveLimited(assumptionLiterals);
            return result.isPresent() ? result.get() : null;
        }
        return solver.solve(assumptionLiterals);
    }

    private static Boolean profileBooleanOptional(
            Profiler profiler, String tag, java.util.function.Supplier<Boolean> action) {
        profiler.startTick(tag);
        try {
            return action.get();
        } finally {
            profiler.endTick(tag);
        }
    }

    private static void profileVoid(Profiler profiler, String tag, Runnable action) {
        profiler.startTick(tag);
        try {
            action.run();
        } finally {
            profiler.endTick(tag);
        }
    }

    Pair<Collection<Pair<EndpointPair<Transaction<KeyType, ValueType>>, Collection<Edge<KeyType>>>>, Collection<SERConstraint<KeyType, ValueType>>> getConflicts() {
        return Pair.of(conflictEdges, conflictConstraints);
    }

    Collection<AssumptionReason<KeyType, ValueType>> getConflictReasons() {
        return conflictReasons;
    }

    Collection<AssumptionReason<KeyType, ValueType>> getAssumptionReasons() {
        return assumptionLiterals.stream()
                .map(assumptionReasons::get)
                .collect(Collectors.toUnmodifiableList());
    }

    private AssumptionReason<KeyType, ValueType> newAssumption(
            AssumptionKind kind,
            String reason,
            SERConstraint<KeyType, ValueType> wwConstraint) {
        var literal = new Lit(solver);
        var assumption = new AssumptionReason<>(
                nextAssumptionId++, kind, reason, null, literal, wwConstraint);
        solver.addName(literal, assumption.assumptionId());
        assumptionLiterals.add(literal);
        assumptionReasons.put(literal, assumption);
        return assumption;
    }

    private AssumptionReason<KeyType, ValueType> newFactAssumption(
            GmwrPropagationState.DependencyFact<KeyType, ValueType> fact) {
        var literal = new Lit(solver);
        var assumption = new AssumptionReason<>(
                nextAssumptionId++, AssumptionKind.GMWR_RULE,
                null, fact, literal, null);
        solver.addName(literal, assumption.assumptionId());
        assumptionLiterals.add(literal);
        assumptionReasons.put(literal, assumption);
        return assumption;
    }

    private AssumptionReason<KeyType, ValueType> predicateAssumption(
            KnownGraph.PredicateObservation<KeyType, ValueType> observation) {
        return predicateAssumptions.computeIfAbsent(observation, ignored ->
                newAssumption(
                        AssumptionKind.PREDICATE_OBLIGATION,
                        String.format("reader=%s event=%d coverageEpoch=%d",
                                observation.getTxn(), observation.getEventIndex(),
                                observation.getCoverageEpoch()),
                        null));
    }

    private void assertUnderAssumption(
            AssumptionReason<KeyType, ValueType> assumption,
            Lit constraint) {
        solver.assertImplies(assumption.literal, constraint);
    }

    private void assertClauseUnderAssumption(
            AssumptionReason<KeyType, ValueType> assumption,
            Collection<Lit> clause) {
        var guarded = new ArrayList<Lit>(clause.size() + 1);
        guarded.add(Logic.not(assumption.literal));
        guarded.addAll(clause);
        solver.assertOr(guarded);
    }

    private void assertCurrentPredicate(Lit constraint) {
        assertUnderAssumption(
                Objects.requireNonNull(currentPredicateAssumption,
                        "currentPredicateAssumption"),
                constraint);
    }

    int getArVariableCount() {
        return txns.size() * Math.max(0, txns.size() - 1);
    }

    long getPredicateSourceConstraintCount() {
        return predicateSourceConstraintCount;
    }

    /** Logical dependency metadata retained independently of MonoSAT edges. */
    Collection<SEREdge<KeyType, ValueType>> getLogicalDependencies() {
        return logicalDependenciesByEndpoint.values().stream()
                .flatMap(Collection::stream)
                .collect(Collectors.toUnmodifiableList());
    }

    /** Allocates one serialization graph node per real transaction. */
    private int[] createSerializationNodes() {
        var result = new int[txns.size()];
        for (int i = 0; i < txns.size(); i++) {
            result[i] = serializationGraph.addNode();
        }
        return result;
    }

    /** Forbids cycles in the single serialization constraint graph. */
    private void encodeSerializationAcyclicity() {
        solver.assertTrue(serializationGraph.acyclic());
    }

    /**
     * Existing precedence edges are retained as typed logical metadata. Their
     * transitive reduction is also inserted into the serialization graph.
     */
    private void encodeKnownEdges() {
        encodeKnownTypedEdges(graph.getKnownGraphA());
        encodeKnownTypedEdges(graph.getKnownGraphB());
        if (propagationConflict) {
            var assumption = newAssumption(
                    AssumptionKind.GMWR_RULE,
                    "GMWR propagation found a deterministic contradiction",
                    null);
            assertUnderAssumption(assumption, Lit.False);
            return;
        }
        if (knownOrder.cyclic) {
            solver.assertTrue(Lit.False);
            return;
        }
        for (var edge : knownOrder.reductionEdges) {
            solver.assertTrue(directSerializationEdge(txns.get(edge[0]), txns.get(edge[1])));
        }
        if (propagation != null) {
            for (var fact : propagation.definiteFacts()) {
                if (isBottomTxn(fact.from) || isBottomTxn(fact.to)
                        || fact.from.equals(fact.to)) {
                    continue;
                }
                var assumption = newFactAssumption(fact);
                assertUnderAssumption(
                        assumption, directSerializationEdge(fact.from, fact.to));
                if (fact.isTypedDependency()) {
                    var edge = new SEREdge<KeyType, ValueType>(
                            fact.from, fact.to, fact.type, fact.key);
                    if (fact.type == EdgeType.WW) {
                        registerWwOrder(edge, assumption.literal);
                    }
                    addDependencyEdge(edge, assumption.literal);
                }
            }
        }
    }

    private void encodeKnownTypedEdges(
            com.google.common.graph.ValueGraph<Transaction<KeyType, ValueType>, Collection<Edge<KeyType>>> known) {
        for (var ep : known.edges()) {
            for (var edge : known.edgeValue(ep).orElse(Collections.emptyList())) {
                if (!isEncodedKnownEdge(edge.getType())) {
                    continue;
                }
                var serEdge = new SEREdge<KeyType, ValueType>(
                        ep.source(), ep.target(), edge.getType(), edge.getKey());
                if (edge.getType() == EdgeType.WW) {
                    registerWwOrder(serEdge, Lit.True);
                }
                addDependencyEdge(serEdge, Lit.True);
            }
        }
    }

    private KnownOrder buildKnownOrder() {
        var adjacency = new BitSet[txns.size()];
        for (int i = 0; i < adjacency.length; i++) {
            adjacency[i] = new BitSet(adjacency.length);
        }
        boolean invalid = addKnownOrderEdges(
                graph.getKnownGraphA(), adjacency, precedence);
        invalid |= addKnownOrderEdges(
                graph.getKnownGraphB(), adjacency, precedence);
        if (invalid) {
            return new KnownOrder(Collections.emptyList(), true);
        }

        var indegree = new int[txns.size()];
        for (var successors : adjacency) {
            for (int to = successors.nextSetBit(0); to >= 0; to = successors.nextSetBit(to + 1)) {
                indegree[to]++;
            }
        }

        var ready = new PriorityQueue<Integer>();
        for (int i = 0; i < indegree.length; i++) {
            if (indegree[i] == 0) {
                ready.add(i);
            }
        }

        var topologicalOrder = new int[txns.size()];
        int count = 0;
        while (!ready.isEmpty()) {
            int from = ready.remove();
            topologicalOrder[count++] = from;
            for (int to = adjacency[from].nextSetBit(0); to >= 0;
                    to = adjacency[from].nextSetBit(to + 1)) {
                if (--indegree[to] == 0) {
                    ready.add(to);
                }
            }
        }
        if (count != txns.size()) {
            return new KnownOrder(Collections.emptyList(), true);
        }

        var topologicalPosition = new int[txns.size()];
        for (int pos = 0; pos < topologicalOrder.length; pos++) {
            topologicalPosition[topologicalOrder[pos]] = pos;
        }

        var reductionEdges = new ArrayList<int[]>();
        for (int from = 0; from < adjacency.length; from++) {
            var covered = new HashSet<Transaction<KeyType, ValueType>>();
            for (int pos = topologicalPosition[from] + 1; pos < topologicalOrder.length; pos++) {
                int to = topologicalOrder[pos];
                var target = txns.get(to);
                if (!adjacency[from].get(to) || covered.contains(target)) {
                    continue;
                }
                reductionEdges.add(new int[] { from, to });
                covered.add(target);
                covered.addAll(precedence.successor(target));
            }
        }

        return new KnownOrder(reductionEdges, false);
    }

    private boolean addKnownOrderEdges(
            com.google.common.graph.ValueGraph<Transaction<KeyType, ValueType>, Collection<Edge<KeyType>>> known,
            BitSet[] adjacency,
            PrecedenceOracle<Transaction<KeyType, ValueType>> precedence) {
        boolean invalid = false;
        for (var ep : known.edges()) {
            var edges = known.edgeValue(ep).orElse(Collections.emptyList());
            if (edges.stream().noneMatch(edge -> isEncodedKnownEdge(edge.getType()))) {
                continue;
            }

            boolean fromBottom = isBottomTxn(ep.source());
            boolean toBottom = isBottomTxn(ep.target());
            if (fromBottom) {
                invalid |= toBottom;
                continue;
            }
            if (toBottom || ep.source().equals(ep.target())) {
                invalid = true;
                continue;
            }
            if (precedence.wouldCycle(ep.source(), ep.target())) {
                invalid = true;
                continue;
            }
            precedence.add(ep.source(), ep.target());
            adjacency[txnIndex.get(ep.source())].set(txnIndex.get(ep.target()));
        }
        return invalid;
    }

    private void propagateBeforeEncoding() {
        if (predicateSolvingMode != SERVerifier.PredicateSolvingMode.GMWR) {
            publishPropagationMetrics();
            return;
        }

        var profiler = Profiler.getInstance();
        profiler.startTick("GMWR_BUILD_MS");
        try {
            propagation = new GmwrPropagationState<>(history, graph, precedence);
            propagation.seedKnownDependencies();
            collectGmwrLogicalConstraints();
        } finally {
            profiler.endTick("GMWR_BUILD_MS");
        }
        if (gmwrPrepropagation) {
            propagationConflict |= propagation.propagate();
            knownWwSuccessorsByKey.clear();
            knownWwSuccessorsByKey.putAll(buildKnownWwSuccessorsByKey(graph));
        }
        publishPropagationMetrics();
        propagation.releasePropagationIndexes();
    }

    private void publishPropagationMetrics() {
        var profiler = Profiler.getInstance();
        if (profiler.getCounter("GMWR_BUILD_MS") == 0) {
            profiler.addDurationNanos("GMWR_BUILD_MS", 0L);
        }
        if (profiler.getCounter("GMWR_REDUCTION_MS") == 0) {
            profiler.addDurationNanos("GMWR_REDUCTION_MS", 0L);
        }
        long initial = propagation == null ? 0L : propagation.stats.initialConstraints;
        long residual = propagation == null ? 0L : propagation.stats.residualConstraints;
        long removed = propagation == null ? 0L : propagation.stats.removedCandidates;
        long forced = propagation == null ? 0L : propagation.stats.forcedFacts;
        profiler.addCount("GMWR_INITIAL_CONSTRAINTS", initial);
        profiler.addCount("GMWR_RESIDUAL_CONSTRAINTS", residual);
        profiler.addCount("GMWR_REMOVED_CANDIDATES", removed);
        profiler.addCount("GMWR_FORCED_FACTS", forced);
    }

    private void collectGmwrLogicalConstraints() {
        for (var observation : graph.getPredicateObservations()) {
            var predicateRead = observation.getPredicateReadEvent();
            var predicate = predicateRead.getPredicate();
            if (predicate == null || !predicate.isRowLocal()) {
                continue;
            }
            var resultSourcesByKey = new LinkedHashMap<KeyType,
                    KnownGraph.WriteRef<KeyType, ValueType>>();
            for (var source : observation.getTupleSources()) {
                resultSourcesByKey.putIfAbsent(source.getKey(), source.getSourceWrite());
            }
            var scopedEntries = scopedWrites(predicate.scope());
            var relationResolver = relationResolverFor(predicateRead);
            if (!recordedPredicateInputsValid(
                    predicateRead, resultSourcesByKey)) {
                propagationConflict = true;
                continue;
            }
            var reader = observation.getTxn();
            for (var entry : scopedEntries) {
                var key = entry.key;
                var recordedSource = resultSourcesByKey.get(key);
                if (observation.getPredicateReadType(key)
                        == KnownGraph.PredicateReadType.INTERNAL) {
                    continue;
                }
                var candidates = latestExternalWrites(entry, reader);
                if (recordedSource != null) {
                    if (!containsIdentity(candidates, recordedSource)) {
                        propagationConflict = true;
                    }
                    continue;
                }
                var analysis = analyzeAbsentKey(
                        observation, entry, predicateRead, relationResolver);
                for (var obligation : analysis.obligations) {
                    gmwrItemObligations++;
                    gmwrAbsentItemObligations++;
                    var repairs = obligation.repairs.isEmpty()
                            ? List.<Transaction<KeyType, ValueType>>of()
                            : new ArrayList<>(obligation.repairs.get(0));
                    propagation.addGmwrItem(
                            obligation.reader, obligation.badWriter, repairs, obligation.key);
                }
            }
        }
    }

    /**
     * Shared EAGER/GMWR obligation: a bad writer of an absent predicate key
     * must sit outside the snapshot or be repaired by a later good writer.
     */
    static final class BadWriterObligation<KeyType, ValueType> {
        final Transaction<KeyType, ValueType> reader;
        final Transaction<KeyType, ValueType> badWriter;
        final List<Set<Transaction<KeyType, ValueType>>> repairs;
        final KeyType key;

        BadWriterObligation(Transaction<KeyType, ValueType> reader,
                            Transaction<KeyType, ValueType> badWriter,
                            List<Set<Transaction<KeyType, ValueType>>> repairs,
                            KeyType key) {
            this.reader = reader;
            this.badWriter = badWriter;
            this.repairs = repairs;
            this.key = key;
        }
    }

    private static final class AbsentKeyAnalysis<KeyType, ValueType> {
        final List<Transaction<KeyType, ValueType>> goodWriterTxns;
        final Set<KnownGraph.WriteRef<KeyType, ValueType>> emptyContributions;
        final List<KnownGraph.WriteRef<KeyType, ValueType>> badWrites;
        final List<BadWriterObligation<KeyType, ValueType>> obligations;

        private AbsentKeyAnalysis(
                List<Transaction<KeyType, ValueType>> goodWriterTxns,
                Set<KnownGraph.WriteRef<KeyType, ValueType>> emptyContributions,
                List<KnownGraph.WriteRef<KeyType, ValueType>> badWrites,
                List<BadWriterObligation<KeyType, ValueType>> obligations) {
            this.goodWriterTxns = goodWriterTxns;
            this.emptyContributions = emptyContributions;
            this.badWrites = badWrites;
            this.obligations = obligations;
        }
    }

    private AbsentKeyAnalysis<KeyType, ValueType> analyzeAbsentKey(
            KnownGraph.PredicateObservation<KeyType, ValueType> observation,
            KeyWriteIndex<KeyType, ValueType> entry,
            Event<KeyType, ValueType> predicateRead,
            RelationResolver<KeyType> relationResolver) {
        var reader = observation.getTxn();
        var candidates = latestExternalWrites(entry, reader);
        var goodWriterTxns = new ArrayList<Transaction<KeyType, ValueType>>();
        var emptyContributions = Collections.newSetFromMap(
                new IdentityHashMap<KnownGraph.WriteRef<KeyType, ValueType>, Boolean>());
        for (var write : candidates) {
            if (hasEmptyPredicateContribution(
                    predicateRead, relationResolver, write)) {
                emptyContributions.add(write);
                goodWriterTxns.add(write.getTxn());
            }
        }
        var frontierWrites = predicateSolvingMode == SERVerifier.PredicateSolvingMode.GMWR
                ? possibleExternalFrontierWrites(candidates, reader)
                : candidates;
        var badWrites = new ArrayList<KnownGraph.WriteRef<KeyType, ValueType>>();
        var obligations = new ArrayList<BadWriterObligation<KeyType, ValueType>>();
        var repairSets = List.<Set<Transaction<KeyType, ValueType>>>of(
                new LinkedHashSet<>(goodWriterTxns));
        for (var write : frontierWrites) {
            if (emptyContributions.contains(write)) {
                continue;
            }
            badWrites.add(write);
            obligations.add(new BadWriterObligation<>(
                    reader, write.getTxn(), repairSets, entry.key));
        }
        return new AbsentKeyAnalysis<>(
                goodWriterTxns, emptyContributions, badWrites, obligations);
    }

    /**
     * Encodes each unresolved WW pair as a binary decision. The selected
     * branch is the sole source of its conditional ordinary dependencies: the
     * same guard activates both its WW edge and every corresponding RW edge.
     */
    private void encodeRemainingWwChoices() {
        for (var c : constraints) {
            var assumption = newAssumption(
                    AssumptionKind.WW_CHOICE,
                    String.format("constraint=%d writers=%s,%s",
                            c.getId(), c.getWriteTransaction1(),
                            c.getWriteTransaction2()),
                    c);
            var forward = new Lit(solver);
            var forwardGuard = and(assumption.literal, forward);
            var backwardGuard = and(assumption.literal, Logic.not(forward));
            residualWwChoiceVariables++;
            residualWwChoiceConstraints++;

            for (var edge : c.getEdges1()) {
                if (edge.getType() == EdgeType.WW) {
                    registerWwOrder(edge, forwardGuard);
                }
                addDependencyEdge(edge, forwardGuard);
            }
            for (var edge : c.getEdges2()) {
                if (edge.getType() == EdgeType.WW) {
                    registerWwOrder(edge, backwardGuard);
                }
                addDependencyEdge(edge, backwardGuard);
            }
        }
    }

    private void registerWwOrder(SEREdge<KeyType, ValueType> edge, Lit guard) {
        if (edge.getType() != EdgeType.WW || guard == Lit.False) {
            return;
        }
        var orderKey = Triple.of(edge.getFrom(), edge.getTo(), edge.getKey());
        wwOrder.merge(orderKey, guard, SERSolverAR::or);
    }

    private void addDependencyEdge(SEREdge<KeyType, ValueType> edge, Lit... conditions) {
        Lit assumption = Lit.True;
        if ((edge.getType() == EdgeType.PR_WR || edge.getType() == EdgeType.PR_RW)
                && currentPredicateAssumption != null) {
            assumption = currentPredicateAssumption.literal;
        }
        var guard = dependencyGuard(assumption, conditions);
        if (encodingPredicateConstraints) {
            predicateEncodingMetrics.dependencyEdgeAttempts++;
        }
        // A false guard cannot activate either an order edge or a typed edge.
        if (guard.contains(Lit.False)) {
            if (encodingPredicateConstraints) {
                predicateEncodingMetrics.dependencyEdgesSkipped++;
            }
            return;
        }
        if (edge.getType() == EdgeType.PR_WR || edge.getType() == EdgeType.PR_RW) {
            if (skipPredicateWitness(edge, guard)) {
                if (encodingPredicateConstraints) {
                    predicateEncodingMetrics.dependencyEdgesSkipped++;
                }
                return;
            }
            if (!predicateWitnessIdentities.add(
                    new PredicateWitnessIdentity<>(edge, guard))) {
                if (collectingPredicateMetrics) {
                    predicateEncodingMetrics.dependencyEdgeDuplicates++;
                }
                return;
            }
            predicateDependencyCandidateCount++;
            if (!encodingPredicateConstraints) {
                predicateEncodingMetrics.dependencyFixedEdgeCandidates++;
            }
            if (!predicateWitnessCoalescing) {
                queueUncoalescedDependencyEdge(
                        edge, guard, currentPredicateDependencyOrigin);
                return;
            }
            var key = new PredicateTransactionEdgeKey<>(
                    edge.getFrom(), edge.getTo(), edge.getType());
            var physical = predicateDependencyAccumulators.get(key);
            if (physical == null) {
                predicateDependencyAccumulators.put(key,
                        new CoalescedPredicateDependency<>(
                                edge, guard, currentPredicateDependencyOrigin));
            } else {
                physical.merge(edge, guard, currentPredicateDependencyOrigin);
            }
            return;
        }
        if (!dependencyEdgesByGuard
                .computeIfAbsent(guard, ignored -> new HashSet<>())
                .add(edge)) {
            if (collectingPredicateMetrics) {
                predicateEncodingMetrics.dependencyEdgeDuplicates++;
            }
            return;
        }

        if (collectingPredicateMetrics) {
            predicateEncodingMetrics.dependencyEdgesQueued++;
        }

        queueGuardedDependency(edge, guard);
    }

    /** A canonical conjunction represented without allocating a SAT variable. */
    private static List<Lit> dependencyGuard(Lit first, Lit[] terms) {
        ArrayList<Lit> result = null;
        for (var term : terms) {
            if (term == Lit.False) {
                return List.of(Lit.False);
            }
            if (term == Lit.True || term == first) {
                continue;
            }
            if (first == Lit.True) {
                first = term;
                continue;
            }
            if (term == first.not() || result != null && result.contains(term.not())) {
                return List.of(Lit.False);
            }
            if (result == null) {
                result = new ArrayList<>(terms.length + 1);
                result.add(first);
            } else if (result.contains(term)) {
                continue;
            }
            result.add(term);
        }
        if (result == null) {
            return first == Lit.True ? List.of() : List.of(first);
        }
        result.sort(Comparator.comparingInt(Lit::toInt));
        return List.copyOf(result);
    }

    private void queueGuardedDependency(
            SEREdge<KeyType, ValueType> edge, List<Lit> guard) {
        queueGuardedDependency(edge, List.of(guard), PredicateDependencyOrigin.KNOWN_OR_INTERNAL);
    }

    private void queueGuardedDependency(
            SEREdge<KeyType, ValueType> edge,
            Collection<List<Lit>> guards,
            PredicateDependencyOrigin origin) {
        var guarded = new GuardedDependencyEdge<>(edge, guards, origin);
        switch (edge.getType()) {
        case SO:
        case WR:
        case WW:
        case PR_WR:
            dependencyEdgesA.add(guarded);
            break;
        case RW:
        case PR_RW:
            dependencyEdgesB.add(guarded);
            break;
        }
    }

    private void encodeDependencyEdges() {
        try {
            for (var guarded : dependencyEdgesA) {
                encodeDependencyEdge(guarded);
            }
            for (var guarded : dependencyEdgesB) {
                encodeDependencyEdge(guarded);
            }
        } finally {
            dependencyEdgesA.clear();
            dependencyEdgesB.clear();
            dependencyEdgesByGuard.clear();
            if (collectPredicateMetrics) {
                var profiler = Profiler.getInstance();
                profiler.addDurationNanos("SER_PRED_PHYSICAL_SOURCED_MATERIALIZE",
                        sourcedPhysicalPredicateMaterializeNanos);
                profiler.addDurationNanos("SER_PRED_PHYSICAL_SOURCELESS_MATERIALIZE",
                        sourcelessPhysicalPredicateMaterializeNanos);
                profiler.addDurationNanos("SER_PRED_PHYSICAL_MIXED_MATERIALIZE",
                        mixedPhysicalPredicateMaterializeNanos);
                profiler.addDurationNanos("SER_PRED_PHYSICAL_KNOWN_INTERNAL_MATERIALIZE",
                        knownOrInternalPhysicalPredicateMaterializeNanos);
            }
        }
    }

    private void recordPhysicalPredicateMaterialization(
            PredicateDependencyOrigin origin, long elapsedNanos) {
        switch (origin) {
        case SOURCED:
            sourcedPhysicalPredicateMaterializeNanos += elapsedNanos;
            break;
        case SOURCELESS:
            sourcelessPhysicalPredicateMaterializeNanos += elapsedNanos;
            break;
        case MIXED:
            mixedPhysicalPredicateMaterializeNanos += elapsedNanos;
            break;
        case KNOWN_OR_INTERNAL:
            knownOrInternalPhysicalPredicateMaterializeNanos += elapsedNanos;
            break;
        }
    }

    private void encodeDependencyEdge(
            GuardedDependencyEdge<KeyType, ValueType> guarded) {
        var endpoint = Pair.of(guarded.edge.getFrom(), guarded.edge.getTo());
        logicalDependenciesByEndpoint
                .computeIfAbsent(endpoint, ignored -> new ArrayList<>())
                .add(guarded.edge);
        boolean predicatePhysical = guarded.edge.getType() == EdgeType.PR_WR
                || guarded.edge.getType() == EdgeType.PR_RW;
        long started = predicatePhysical ? System.nanoTime() : 0L;
        try {
            var orderTarget = orderLiteral(guarded.edge.getFrom(), guarded.edge.getTo());
            var serializationTarget = orderTarget;
            if (orderTarget != Lit.False
                    && !graphEdgeInterning && canEncodeDependencyEdge(guarded.edge)) {
                serializationTarget = serializationGraph.addEdge(
                        serializationNodes[txnIndex.get(guarded.edge.getFrom())],
                        serializationNodes[txnIndex.get(guarded.edge.getTo())]);
            }
            if (serializationTarget == Lit.True) {
                return;
            }
            var clause = new ArrayList<Lit>();
            for (var guard : guarded.guards) {
                if (guard.contains(serializationTarget)) {
                    continue;
                }
                clause.clear();
                for (var term : guard) {
                    clause.add(term.not());
                }
                if (serializationTarget != Lit.False) {
                    clause.add(serializationTarget);
                }
                solver.assertOr(clause);
            }
        } finally {
            if (predicatePhysical) {
                recordPhysicalPredicateMaterialization(
                        guarded.origin, System.nanoTime() - started);
            }
        }
    }

    private boolean canEncodeDependencyEdge(SEREdge<KeyType, ValueType> edge) {
        if (edge.getFrom().equals(edge.getTo())) {
            return false;
        }
        if (isBottomTxn(edge.getFrom())) {
            return false;
        }
        return !isBottomTxn(edge.getTo());
    }

    private boolean skipPredicateWitness(SEREdge<KeyType, ValueType> edge, List<Lit> guard) {
        for (var term : guard) {
            if (term.isConstFalse()) {
                return true;
            }
        }
        if (!canEncodeDependencyEdge(edge)) {
            return true;
        }
        if (edge.getType() == EdgeType.PR_WR && knownBefore(edge.getTo(), edge.getFrom())) {
            return true;
        }
        if (edge.getType() == EdgeType.PR_WR && isKnownShadowedSource(edge)) {
            return true;
        }
        return false;
    }

    private boolean isKnownShadowedSource(SEREdge<KeyType, ValueType> edge) {
        var successors = knownWwSuccessorsByKey
                .getOrDefault(edge.getKey(), Collections.emptyMap())
                .getOrDefault(edge.getFrom(), Collections.emptySet());
        for (var later : successors) {
            if (knownBefore(later, edge.getTo())) {
                return true;
            }
        }
        return false;
    }

    private void addKnownPredicateEdge(SEREdge<KeyType, ValueType> edge) {
        if (collectingPredicateMetrics) {
            predicateEncodingMetrics.knownPredicateEdgeAttempts++;
        }
        var existing = graph.getKnownGraphA()
                .edgeValue(edge.getFrom(), edge.getTo())
                .orElse(Collections.emptyList());
        var graphEdge = new Edge<KeyType>(edge.getType(), edge.getKey());
        if (!existing.contains(graphEdge)) {
            graph.putEdge(edge.getFrom(), edge.getTo(), graphEdge);
        } else if (collectingPredicateMetrics) {
            predicateEncodingMetrics.knownPredicateEdgeDuplicates++;
        }
        if (propagation != null) {
            propagation.addKnownFact(
                    edge.getFrom(), edge.getTo(), edge.getType(), edge.getKey());
        }
    }

    /** Rejects unsupported semantics before allocating the native solver. */
    private void validateSupportedPredicates() {
        for (var observation : graph.getPredicateObservations()) {
            var predicate = observation.getPredicateReadEvent().getPredicate();
            if (predicate == null) {
                continue;
            }
            if (predicate instanceof QueryPlan && ((QueryPlan<?, ?>) predicate).distinct()) {
                throw new QueryException("SER does not support DISTINCT under the unique key/value model");
            }
            if (!predicate.isRowLocal()
                    && (!(predicate instanceof QueryPlan)
                            || !((QueryPlan<?, ?>) predicate).isMonotone())) {
                throw new QueryException("SER does not support custom whole-snapshot predicates; "
                        + "use a row-local predicate or a supported monotone QueryPlan");
            }
        }
    }

    /** Completely encodes supported predicates before the single SAT solve. */
    private void encodePredicateConstraints() {
        encodingPredicateConstraints = true;
        collectingPredicateMetrics = collectPredicateMetrics;
        try {
            for (var observation : graph.getPredicateObservations()) {
                predicateEncodingMetrics.observations++;
                var predicateRead = observation.getPredicateReadEvent();
                var predicate = predicateRead.getPredicate();
                if (predicate == null) {
                    if (collectingPredicateMetrics) {
                        predicateEncodingMetrics.nullPredicates++;
                    }
                    continue;
                }

                currentPredicateAssumption = predicateAssumption(observation);
                var started = System.nanoTime();
                var resultSourcesByKey = new LinkedHashMap<KeyType,
                        KnownGraph.WriteRef<KeyType, ValueType>>();
                for (var source : observation.getTupleSources()) {
                    if (collectingPredicateMetrics) {
                        predicateEncodingMetrics.resultSources++;
                    }
                    if (resultSourcesByKey.putIfAbsent(
                            source.getKey(), source.getSourceWrite()) != null) {
                        if (collectingPredicateMetrics) {
                            predicateEncodingMetrics.duplicateResultSources++;
                        }
                        assertCurrentPredicate(Lit.False);
                    }
                }
                predicateEncodingMetrics.sourceIndexNanos += System.nanoTime() - started;

                started = System.nanoTime();
                var scopedEntries = scopedWrites(predicate.scope());
                predicateEncodingMetrics.scopeLookupNanos += System.nanoTime() - started;
                if (collectingPredicateMetrics) {
                    predicateEncodingMetrics.scopedKeys += scopedEntries.size();
                }

                started = System.nanoTime();
                var recordedInputsValid = recordedPredicateInputsValid(
                        predicateRead, resultSourcesByKey);
                predicateEncodingMetrics.snapshotValidationNanos +=
                        System.nanoTime() - started;
                if (!recordedInputsValid) {
                    assertCurrentPredicate(Lit.False);
                    continue;
                }

                if (predicate.isRowLocal()) {
                    if (collectingPredicateMetrics) {
                        predicateEncodingMetrics.rowLocalAttempts++;
                    }
                    switch (predicateSolvingMode) {
                    case GMWR:
                        encodeRowLocalPredicateGmwr(observation, scopedEntries, resultSourcesByKey);
                        break;
                    case EAGER:
                    default:
                        encodeRowLocalPredicateEager(observation, scopedEntries, resultSourcesByKey);
                        break;
                    }
                    if (collectingPredicateMetrics) {
                        predicateEncodingMetrics.rowLocalEncoded++;
                    }
                } else {
                    @SuppressWarnings("unchecked")
                    var plan = (QueryPlan<KeyType, ValueType>) predicate;
                    encodeExplicitMultiRelationPredicate(
                            observation, scopedEntries, resultSourcesByKey, plan);
                }
            }
            currentPredicateAssumption = null;
            if (predicateSolvingMode == SERVerifier.PredicateSolvingMode.GMWR) {
                publishGmwrSourcePruning();
                resolveAndEncodeGmwrObligations();
                propagation.releaseEncodedState();
            }
            flushPredicateDependencies();
        } finally {
            predicateDependencyAccumulators.clear();
            predicateWitnessIdentities.clear();
            encodingPredicateConstraints = false;
            collectingPredicateMetrics = false;
            predicateEncodingMetrics.publish(
                    Profiler.getInstance(), collectPredicateMetrics);
        }
    }

    /**
     * Complete eager encoding for supported non-row-local QueryPlan predicates.
     *
     * <p>It enumerates only
     * result-producing query bindings over the finite version pool, then:
     * (1) fixes every recorded source as latest-visible, (2) forbids every
     * additional contributing binding, and (3) emits context-guarded PR_WR /
     * PR_RW dependencies into the same typed dependency pipeline used by the
     * row-local encoders.</p>
     */
    private void encodeExplicitMultiRelationPredicate(
            KnownGraph.PredicateObservation<KeyType, ValueType> observation,
            List<KeyWriteIndex<KeyType, ValueType>> scopedEntries,
            Map<KeyType, KnownGraph.WriteRef<KeyType, ValueType>> resultSourcesByKey,
            QueryPlan<KeyType, ValueType> plan) {
        var predicateRead = observation.getPredicateReadEvent();
        var reader = observation.getTxn();
        var resolver = plan.scope().relationResolver();
        var recordedInputs = expectedPredicateInputs(predicateRead);
        if (collectingPredicateMetrics) {
            predicateEncodingMetrics.generalObservations++;
            predicateEncodingMetrics.generalExternalKeys += scopedEntries.size();
        }
        if (predicateSolvingMode == SERVerifier.PredicateSolvingMode.GMWR) {
            gmwrGeneralObservations++;
        }

        var frontiersByKey = new LinkedHashMap<KeyType,
                KeyFrontier<KeyType, ValueType>>();
        var externalWritesByKey = new LinkedHashMap<KeyType,
                List<KnownGraph.WriteRef<KeyType, ValueType>>>();
        var versionsByKey = new LinkedHashMap<KeyType,
                List<QueryVersionWitness<KeyType, ValueType>>>();
        var versionPool = new ArrayList<RowVersion<KeyType, ValueType>>();

        for (var entry : scopedEntries) {
            var recordedSource = resultSourcesByKey.get(entry.key);
            var latestSelf = entry.latestSelfBefore(reader, observation.getEventIndex());
            var externalWrites = entry.latestExternalWrites(reader);
            externalWritesByKey.put(entry.key, externalWrites);

            if (latestSelf != null) {
                if (recordedSource != null && recordedSource != latestSelf) {
                    assertCurrentPredicate(Lit.False);
                    return;
                }
                var frontier = new KeyFrontier<KeyType, ValueType>(
                        entry.key, reader,
                        List.of(new FrontierCandidate<>(latestSelf, Lit.True, Lit.True)),
                        latestSelf);
                frontiersByKey.put(entry.key, frontier);
                addQueryVersionWitness(
                        versionsByKey, versionPool, resolver, latestSelf, Lit.True);
                continue;
            }

            var externalStarted = startExternalKeyEncoding(recordedSource != null);
            try {
                var frontier = createExplicitQueryFrontier(
                        observation, entry, recordedSource);
                frontiersByKey.put(entry.key, frontier);

                // The current source candidates carry their latest-visible guard.
                // Additional writes remain in the version pool as hypothetical
                // replacement targets for contextual PR_RW construction.
                var selectionByWrite = new IdentityHashMap<
                        KnownGraph.WriteRef<KeyType, ValueType>, Lit>();
                for (var candidate : frontier.candidates) {
                    selectionByWrite.put(candidate.write, candidate.latest);
                }
                for (var write : externalWrites) {
                    addQueryVersionWitness(
                            versionsByKey, versionPool, resolver, write,
                            selectionByWrite.get(write));
                }

                encodeExplicitPrWr(frontier, recordedSource);
            } finally {
                finishExternalKeyEncoding(recordedSource != null, externalStarted);
            }
        }

        final List<Map<KeyType, ValueType>> bindings;
        try {
            bindings = plan.candidateInputBindings(versionPool);
        } catch (QueryException exception) {
            assertCurrentPredicate(Lit.False);
            return;
        }

        var contributionContexts = new IdentityHashMap<
                KnownGraph.WriteRef<KeyType, ValueType>, Lit>();
        for (var binding : bindings) {
            var resolved = resolveQueryBinding(binding, versionsByKey);
            if (resolved == null) {
                // The detector contract requires globally unique (key,value)
                // sources. Ambiguity is therefore an invalid history/model.
                assertCurrentPredicate(Lit.False);
                return;
            }

            encodeAdditionalBindingExclusion(binding, recordedInputs, resolved);
            accumulateContributionContexts(resolved, contributionContexts);
        }

        // Result-change dependencies are key-local in their target write, but
        // their activation context can mention latest-visible versions from the
        // other relations participating in the JOIN.
        for (var entry : scopedEntries) {
            var frontier = frontiersByKey.get(entry.key);
            if (frontier == null) {
                continue;
            }
            if (frontier.fixedWrite != null
                    && frontier.fixedWrite.getTxn().equals(frontier.reader)) {
                // Only a query-before self-write frontier is internal.  An
                // external recorded source is also stored as fixedWrite, but it
                // is the PR_WR anchor from which later same-key writes must be
                // checked for contextual PR_RW dependencies.
                continue;
            }
            var externalStarted = startExternalKeyEncoding(
                    resultSourcesByKey.containsKey(entry.key));
            try {
                encodeExplicitPrRw(
                        frontier,
                        externalWritesByKey.getOrDefault(
                                entry.key, Collections.emptyList()),
                        contributionContexts);
            } finally {
                finishExternalKeyEncoding(
                        resultSourcesByKey.containsKey(entry.key), externalStarted);
            }
        }
    }

    /** Builds a latest-visible frontier without row-local GMWR pruning or
     * row-local predicate-dependency side effects. */
    private KeyFrontier<KeyType, ValueType> createExplicitQueryFrontier(
            KnownGraph.PredicateObservation<KeyType, ValueType> observation,
            KeyWriteIndex<KeyType, ValueType> writeIndex,
            KnownGraph.WriteRef<KeyType, ValueType> recordedSource) {
        predicateSourceConstraintCount++;
        var key = writeIndex.key;
        if (collectingPredicateMetrics) {
            predicateEncodingMetrics.frontiers++;
        }

        var externalWrites = writeIndex.latestExternalWrites(observation.getTxn());
        if (recordedSource != null) {
            return fixedSourceFrontier(observation, key, recordedSource, externalWrites);
        }

        if (initializedPredicateWriteOrders.add(key)) {
            var comparableWrites = writeIndex.latestWritesByWriter;
            for (int i = 0; i < comparableWrites.size(); i++) {
                for (int j = i + 1; j < comparableWrites.size(); j++) {
                    beforeWrite(comparableWrites.get(i), comparableWrites.get(j));
                }
            }
        }

        var candidates = latestVisibleChecker.check(
                        observation.getTxn(), key, externalWrites,
                        new LatestVisibleChecker.SerializationOrder<KeyType, ValueType>() {
                            @Override
                            public Lit beforeReader(
                                    KeyType candidateKey,
                                    KnownGraph.WriteRef<KeyType, ValueType> writer,
                                    Transaction<KeyType, ValueType> reader) {
                                return orderLiteral(writer.getTxn(), reader);
                            }

                            @Override
                            public Lit beforeWriter(
                                    KeyType candidateKey,
                                    KnownGraph.WriteRef<KeyType, ValueType> left,
                                    KnownGraph.WriteRef<KeyType, ValueType> right) {
                                return beforeWrite(left, right);
                            }
                        }).stream()
                .map(validity -> new FrontierCandidate<>(
                        validity.writer, validity.visible, validity.valid))
                .filter(candidate -> candidate.visible != Lit.False)
                .collect(Collectors.toList());
        if (collectingPredicateMetrics) {
            predicateEncodingMetrics.frontierCandidates += candidates.size();
        }

        return new KeyFrontier<>(key, observation.getTxn(), candidates, null);
    }

    private void encodeExplicitPrWr(
            KeyFrontier<KeyType, ValueType> frontier,
            KnownGraph.WriteRef<KeyType, ValueType> recordedSource) {
        for (var source : frontier.candidates) {
            if (source.write.getTxn().equals(frontier.reader)) {
                continue;
            }
            var edge = new SEREdge<KeyType, ValueType>(
                    source.write.getTxn(), frontier.reader,
                    EdgeType.PR_WR, frontier.key);
            if (recordedSource == source.write) {
                addKnownPredicateEdge(edge);
            }
            addDependencyEdge(edge, source.latest);
        }
    }

    private void encodeExplicitPrRw(
            KeyFrontier<KeyType, ValueType> frontier,
            List<KnownGraph.WriteRef<KeyType, ValueType>> writes,
            IdentityHashMap<KnownGraph.WriteRef<KeyType, ValueType>, Lit>
                    contributionContexts) {
        for (var source : frontier.candidates) {
            var sourceContext = contributionContexts.getOrDefault(
                    source.write, Lit.False);
            for (var later : writes) {
                if (later == source.write) {
                    continue;
                }
                var laterContext = contributionContexts.getOrDefault(
                        later, Lit.False);
                var changeContext = or(sourceContext, laterContext);
                if (changeContext == Lit.False) {
                    continue;
                }
                addDependencyEdge(
                        new SEREdge<>(frontier.reader, later.getTxn(),
                                EdgeType.PR_RW, frontier.key),
                        source.latest, beforeWrite(source.write, later), changeContext);
            }
        }
    }

    private void encodeAdditionalBindingExclusion(
            Map<KeyType, ValueType> binding,
            Map<KeyType, ValueType> recordedInputs,
            List<QueryVersionWitness<KeyType, ValueType>> resolved) {
        boolean recordedOnly = true;
        for (var source : binding.entrySet()) {
            if (!recordedInputs.containsKey(source.getKey())
                    || !Objects.equals(
                            recordedInputs.get(source.getKey()), source.getValue())) {
                recordedOnly = false;
                break;
            }
        }
        if (recordedOnly) {
            return;
        }

        var clause = new ArrayList<Lit>(resolved.size());
        for (var source : resolved) {
            if (source.selection == null) {
                // This version is retained only as a hypothetical later PR_RW
                // target; it cannot contribute to the current visible result.
                return;
            }
            if (source.selection == Lit.False) {
                return;
            }
            if (source.selection != Lit.True) {
                clause.add(Logic.not(source.selection));
            }
        }
        if (collectingPredicateMetrics) {
            predicateEncodingMetrics.blockingClauses++;
            predicateEncodingMetrics.blockingClauseLiterals +=
                    Math.max(1, clause.size());
        }
        assertClauseUnderAssumption(currentPredicateAssumption, clause);
    }

    private void accumulateContributionContexts(
            List<QueryVersionWitness<KeyType, ValueType>> resolved,
            IdentityHashMap<KnownGraph.WriteRef<KeyType, ValueType>, Lit> contexts) {
        for (var target : resolved) {
            var terms = new ArrayList<Lit>(Math.max(0, resolved.size() - 1));
            boolean valid = true;
            for (var other : resolved) {
                if (other == target) {
                    continue;
                }
                if (other.selection == null || other.selection == Lit.False) {
                    valid = false;
                    break;
                }
                terms.add(other.selection);
            }
            if (!valid) {
                continue;
            }
            var context = and(terms);
            contexts.merge(target.write, context, SERSolverAR::or);
        }
    }

    private List<QueryVersionWitness<KeyType, ValueType>> resolveQueryBinding(
            Map<KeyType, ValueType> binding,
            Map<KeyType, List<QueryVersionWitness<KeyType, ValueType>>> versionsByKey) {
        var resolved = new ArrayList<QueryVersionWitness<KeyType, ValueType>>(
                binding.size());
        for (var source : binding.entrySet()) {
            QueryVersionWitness<KeyType, ValueType> match = null;
            for (var candidate : versionsByKey.getOrDefault(
                    source.getKey(), Collections.emptyList())) {
                if (!Objects.equals(
                        candidate.write.getEvent().getValue(), source.getValue())) {
                    continue;
                }
                if (match != null && match.write != candidate.write) {
                    return null;
                }
                match = candidate;
            }
            if (match == null) {
                return null;
            }
            resolved.add(match);
        }
        return resolved;
    }

    private void addQueryVersionWitness(
            Map<KeyType, List<QueryVersionWitness<KeyType, ValueType>>> versionsByKey,
            List<RowVersion<KeyType, ValueType>> versionPool,
            RelationResolver<KeyType> resolver,
            KnownGraph.WriteRef<KeyType, ValueType> write,
            Lit selection) {
        var event = write.getEvent();
        if (event.getValue() == null) {
            return;
        }
        versionsByKey.computeIfAbsent(event.getKey(), ignored -> new ArrayList<>())
                .add(new QueryVersionWitness<>(write, selection));
        versionPool.add(new RowVersion<>(
                event.getKey(), resolver.relationOf(event.getKey()), event.getValue()));
    }

    /**
     * Coalesces per-key predicate witnesses with the same transaction-level
     * PR_WR or PR_RW relation. Each witness independently implies the shared
     * physical edge; no auxiliary OR of witness guards is needed.
     */
    private void flushPredicateDependencies() {
        if (!predicateWitnessCoalescing
                || predicateDependencyAccumulators.isEmpty()) {
            return;
        }

        var profiler = Profiler.getInstance();
        profiler.startTick("SER_PRED_DEPENDENCY_PRUNE");
        try {
            long physicalPrWrEdges = 0L;
            long physicalPrRwEdges = 0L;
            long sourcedPhysicalEdges = 0L;
            long sourcelessPhysicalEdges = 0L;
            long mixedPhysicalEdges = 0L;
            long knownOrInternalPhysicalEdges = 0L;
            for (var physical : predicateDependencyAccumulators.values()) {
                if (physical.edge.getType() == EdgeType.PR_WR) {
                    physicalPrWrEdges++;
                } else if (physical.edge.getType() == EdgeType.PR_RW) {
                    physicalPrRwEdges++;
                }
                switch (physical.origin) {
                case SOURCED:
                    sourcedPhysicalEdges++;
                    break;
                case SOURCELESS:
                    sourcelessPhysicalEdges++;
                    break;
                case MIXED:
                    mixedPhysicalEdges++;
                    break;
                case KNOWN_OR_INTERNAL:
                    knownOrInternalPhysicalEdges++;
                    break;
                }
            }

            predicateEncodingMetrics.dependencyEdgeCandidates +=
                    predicateDependencyCandidateCount;
            predicateEncodingMetrics.dependencyPhysicalEdges +=
                    predicateDependencyAccumulators.size();
            if (collectingPredicateMetrics) {
                predicateEncodingMetrics.dependencyPhysicalPrWrEdges += physicalPrWrEdges;
                predicateEncodingMetrics.dependencyPhysicalPrRwEdges += physicalPrRwEdges;
                predicateEncodingMetrics.dependencyPhysicalSourcedEdges += sourcedPhysicalEdges;
                predicateEncodingMetrics.dependencyPhysicalSourcelessEdges += sourcelessPhysicalEdges;
                predicateEncodingMetrics.dependencyPhysicalMixedEdges += mixedPhysicalEdges;
                predicateEncodingMetrics.dependencyPhysicalKnownOrInternalEdges +=
                        knownOrInternalPhysicalEdges;
                predicateEncodingMetrics.dependencyEdgesCoalesced +=
                        predicateDependencyCandidateCount
                                - predicateDependencyAccumulators.size();
                predicateEncodingMetrics.dependencyEdgesQueued +=
                        predicateDependencyAccumulators.size();
            }
            for (var physical : predicateDependencyAccumulators.values()) {
                queueGuardedDependency(physical.edge, physical.guards, physical.origin);
            }
        } finally {
            predicateDependencyAccumulators.clear();
            profiler.endTick("SER_PRED_DEPENDENCY_PRUNE");
        }
    }

    private void publishGmwrSourcePruning() {
        if (gmwrPrWrSourceAlternatives == 0) {
            return;
        }
        if (collectingPredicateMetrics) {
            var profiler = Profiler.getInstance();
            profiler.addCount("SER_PRED_PR_WR_SOURCE_ALTERNATIVES_COUNT",
                    gmwrPrWrSourceAlternatives);
            profiler.addCount("SER_PRED_PR_WR_REACHABILITY_PRUNED_COUNT",
                    gmwrPrWrReachabilityPruned);
            profiler.addCount("SER_PRED_PR_WR_PR_RW_CYCLE_PRUNED_COUNT",
                    gmwrPrWrPrRwCyclePruned);
            profiler.addCount("SER_PRED_PR_WR_REACHABILITY_FORCED_COUNT",
                    0L);
        }
    }

    private void queueUncoalescedDependencyEdge(
            SEREdge<KeyType, ValueType> edge,
            List<Lit> guard,
            PredicateDependencyOrigin origin) {
        if (!dependencyEdgesByGuard
                .computeIfAbsent(guard, ignored -> new HashSet<>())
                .add(edge)) {
            if (collectingPredicateMetrics) {
                predicateEncodingMetrics.dependencyEdgeDuplicates++;
            }
            return;
        }
        if (collectingPredicateMetrics) {
            predicateEncodingMetrics.dependencyEdgesQueued++;
        }
        queueGuardedDependency(edge, List.of(guard), origin);
    }

    private long startExternalKeyEncoding(boolean sourced) {
        currentPredicateDependencyOrigin = sourced
                ? PredicateDependencyOrigin.SOURCED
                : PredicateDependencyOrigin.SOURCELESS;
        if (collectingPredicateMetrics) {
            if (sourced) {
                predicateEncodingMetrics.externalSourcedKeys++;
            } else {
                predicateEncodingMetrics.externalSourcelessKeys++;
            }
        }
        return System.nanoTime();
    }

    private void finishExternalKeyEncoding(boolean sourced, long started) {
        var elapsed = System.nanoTime() - started;
        if (sourced) {
            predicateEncodingMetrics.externalSourcedEncodeNanos += elapsed;
        } else {
            predicateEncodingMetrics.externalSourcelessEncodeNanos += elapsed;
        }
        currentPredicateDependencyOrigin = PredicateDependencyOrigin.KNOWN_OR_INTERNAL;
    }

    private List<KeyWriteIndex<KeyType, ValueType>> scopedWrites(QueryScope<KeyType> scope) {
        var cacheKey = scope.cacheKey();
        if (cacheKey.isEmpty()) {
            return buildScopedWrites(scope);
        }
        return scopedWritesCache.computeIfAbsent(cacheKey.get(), ignored -> buildScopedWrites(scope));
    }

    private List<KeyWriteIndex<KeyType, ValueType>> buildScopedWrites(QueryScope<KeyType> scope) {
        return sortedKeyWriteIndexes.stream()
                .filter(entry -> scope.covers(entry.key))
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Independent GMWR encoding for row-local predicate reads.
     *
     * <p>The formal predicate semantics remain item-wise. Every bad writer B
     * for reader R contributes an explicit item clause. Items sharing (R,B)
     * reuse the same outside-snapshot branch R&lt;B and propagation state, but
     * their repair sets are neither deduplicated nor subsumed. Recorded sources
     * and absent-result candidates are separately passed through the
     * source-aware typed frontier encoder.</p>
     */
    private void encodeRowLocalPredicateGmwr(
            KnownGraph.PredicateObservation<KeyType, ValueType> observation,
            List<KeyWriteIndex<KeyType, ValueType>> scopedEntries,
            Map<KeyType, KnownGraph.WriteRef<KeyType, ValueType>> resultSourcesByKey) {
        var started = System.nanoTime();
        try {
            var predicateRead = observation.getPredicateReadEvent();
            var relationResolver = relationResolverFor(predicateRead);
            var reader = observation.getTxn();
            for (var entry : scopedEntries) {
                var key = entry.key;
                var recordedSource = resultSourcesByKey.get(key);
                if (collectingPredicateMetrics) {
                    predicateEncodingMetrics.rowLocalKeyVisits++;
                }

                if (observation.getPredicateReadType(key)
                        == KnownGraph.PredicateReadType.INTERNAL) {
                    if (collectingPredicateMetrics) {
                        predicateEncodingMetrics.internalKeys++;
                        predicateEncodingMetrics.latestWriterLookups++;
                        predicateEncodingMetrics.latestWriterInputWrites += entry.writes.size();
                    }
                    var latestSelf = entry.latestSelfBefore(
                            reader, observation.getEventIndex());
                    if (latestSelf == null) {
                        latestSelf = recordedSource;
                    }
                    if (recordedSource != null) {
                        if (latestSelf != recordedSource) {
                            assertCurrentPredicate(Lit.False);
                        }
                    } else if (latestSelf != null
                            && !hasEmptyPredicateContribution(
                                    predicateRead, relationResolver, latestSelf)) {
                        assertCurrentPredicate(Lit.False);
                    }
                    continue;
                }

                if (collectingPredicateMetrics) {
                    predicateEncodingMetrics.externalKeys++;
                }
                var externalStarted = startExternalKeyEncoding(
                        recordedSource != null);
                try {
                    var candidates = latestExternalWrites(entry, reader);

                    if (recordedSource != null) {
                        if (collectingPredicateMetrics) {
                            predicateEncodingMetrics.recordedSourceKeys++;
                        }
                        if (!containsIdentity(candidates, recordedSource)) {
                            assertCurrentPredicate(Lit.False);
                            continue;
                        }

                        encodeFixedPredicateSource(observation, recordedSource, candidates);
                        continue;
                    }

                    var analysis = analyzeAbsentKey(
                            observation, entry, predicateRead, relationResolver);
                    if (collectingPredicateMetrics) {
                        predicateEncodingMetrics.badWrites += analysis.badWrites.size();
                    }

                    // GMWR builds the result-validity obligations below, while
                    // typed predicate dependencies remain source-aware.  ARmax is
                    // still computed over every visible writer, but only good
                    // writers can be a legal source on an absent key, so guarded
                    // PR_WR is emitted only for those candidates.  PR_RW from a
                    // selected good source to a later result-changing writer is
                    // unchanged.
                    encodeAbsentRowLocalKey(observation, entry, candidates, analysis);
                } finally {
                    finishExternalKeyEncoding(
                            recordedSource != null, externalStarted);
                }
            }

        } finally {
            gmwrBuildNanos += System.nanoTime() - started;
        }
    }

    /**
     * Encodes only residual GMWR obligations that the live fixpoint could not
     * decide. Satisfied and forced obligations stay out of MonoSAT.
     */
    private void resolveAndEncodeGmwrObligations() {
        var started = System.nanoTime();
        try {
            if (predicateSolvingMode != SERVerifier.PredicateSolvingMode.GMWR) {
                return;
            }
            Objects.requireNonNull(propagation, "GMWR propagation state");
            gmwrResolutionRounds = Math.max(1L, propagation.stats.reductionSteps);
            long materializedItemClauses = 0L;
            for (var gmwr : propagation.gmwrObligations()) {
                if (gmwr.resolved || gmwr.satisfied) {
                    continue;
                }
                materializedItemClauses += gmwr.items.size();
                encodeResidualGmwr(gmwr);
            }
            this.gmwrMaterializedItemClauses = materializedItemClauses;
        } finally {
            gmwrResolutionNanos += System.nanoTime() - started;
            publishGmwrMetrics();
        }
    }

    private long gmwrMaterializedItemClauses;

    private void encodeResidualGmwr(
            GmwrPropagationState.GmwrObligation<KeyType, ValueType> gmwr) {
        for (var item : gmwr.items) {
            if (gmwrRepairSatisfied(gmwr.badWriter, gmwr.reader, item.repairs)) {
                continue;
            }
            var clause = new ArrayList<Lit>();
            if (gmwr.outsidePossible) {
                var outside = sharedOrderLiteral(gmwr.reader, gmwr.badWriter);
                if (outside != Lit.False) {
                    clause.add(outside);
                }
            }
            for (var repair : item.repairs) {
                if (!gmwrCanPlaceBetween(gmwr.badWriter, repair, gmwr.reader)) {
                    continue;
                }
                var repairTerm = and(
                        sharedOrderLiteral(gmwr.badWriter, repair),
                        sharedOrderLiteral(repair, gmwr.reader));
                if (repairTerm == Lit.True) {
                    clause.clear();
                    clause.add(Lit.True);
                    break;
                }
                if (repairTerm != Lit.False && !repairTerm.isConstFalse()) {
                    clause.add(repairTerm);
                }
            }
            if (clause.contains(Lit.True)) {
                continue;
            }
            gmwrResidualClauses++;
            gmwrResidualLiterals += Math.max(1, clause.size());
            var assumption = newAssumption(
                    AssumptionKind.GMWR_RULE,
                    String.format(
                            "reader=%s badWriter=%s keys=%s repairs=%s",
                            gmwr.reader, gmwr.badWriter, gmwr.keys, item.repairs),
                    null);
            assertClauseUnderAssumption(assumption, clause);
        }
    }

    private Lit sharedOrderLiteral(
            Transaction<KeyType, ValueType> from,
            Transaction<KeyType, ValueType> to) {
        return orderLiteral(from, to);
    }

    private boolean gmwrRepairSatisfied(
            Transaction<KeyType, ValueType> bad,
            Transaction<KeyType, ValueType> reader,
            Collection<Transaction<KeyType, ValueType>> repairs) {
        for (var repair : repairs) {
            if (gmwrBefore(bad, repair)
                    && gmwrBefore(repair, reader)) {
                return true;
            }
        }
        return false;
    }

    private void publishGmwrMetrics() {
        if (predicateSolvingMode != SERVerifier.PredicateSolvingMode.GMWR) {
            return;
        }
        var profiler = Profiler.getInstance();
        profiler.addDurationNanos("SER_GMWR_BUILD", gmwrBuildNanos);
        profiler.addDurationNanos("SER_GMWR_RESOLUTION", gmwrResolutionNanos);
        long forcedOrders = propagation == null ? 0L : propagation.stats.forcedFacts;
        profiler.addCount("SER_GMWR_FORCED_ORDERS_COUNT", forcedOrders);
        if (!collectPredicateMetrics) {
            return;
        }
        long materializedItemClauses = gmwrMaterializedItemClauses;
        if (materializedItemClauses == 0L && propagation != null) {
            for (var gmwr : propagation.gmwrObligations()) {
                materializedItemClauses += gmwr.items.size();
            }
        }
        profiler.addCount("SER_GMWR_ITEM_OBLIGATIONS_COUNT", gmwrItemObligations);
        profiler.addCount("SER_GMWR_SEMANTIC_ITEM_OBLIGATIONS_COUNT",
                gmwrItemObligations);
        profiler.addCount("SER_GMWR_RETURNED_ITEM_OBLIGATIONS_COUNT", 0L);
        profiler.addCount("SER_GMWR_ABSENT_ITEM_OBLIGATIONS_COUNT",
                gmwrAbsentItemObligations);
        profiler.addCount("SER_GMWR_MATERIALIZED_ITEM_CLAUSES_COUNT",
                materializedItemClauses);
        profiler.addCount("SER_GMWR_RESIDUAL_CLAUSES_COUNT", gmwrResidualClauses);
        profiler.addCount("SER_GMWR_RESIDUAL_LITERALS_COUNT", gmwrResidualLiterals);
        profiler.addCount("SER_GMWR_INTERVAL_CANDIDATES_PRUNED_COUNT",
                gmwrIntervalCandidatesPruned);
        profiler.addCount("SER_GMWR_RESOLUTION_ROUNDS_COUNT", gmwrResolutionRounds);
        profiler.addCount("SER_GMWR_GENERAL_OBSERVATIONS_COUNT",
                gmwrGeneralObservations);
    }

    /** Eagerly materializes every row-local reader-key constraint before solve(). */
    private void encodeRowLocalPredicateEager(
            KnownGraph.PredicateObservation<KeyType, ValueType> observation,
            List<KeyWriteIndex<KeyType, ValueType>> scopedEntries,
            Map<KeyType, KnownGraph.WriteRef<KeyType, ValueType>> resultSourcesByKey) {
        var predicateRead = observation.getPredicateReadEvent();
        var relationResolver = relationResolverFor(predicateRead);
        var started = System.nanoTime();
        for (var entry : scopedEntries) {
            var key = entry.key;
            var writes = entry.writes;
            var recordedSource = resultSourcesByKey.get(key);
            if (collectingPredicateMetrics) {
                predicateEncodingMetrics.rowLocalKeyVisits++;
            }

            if (observation.getPredicateReadType(key)
                    == KnownGraph.PredicateReadType.INTERNAL) {
                if (collectingPredicateMetrics) {
                    predicateEncodingMetrics.internalKeys++;
                    predicateEncodingMetrics.latestWriterLookups++;
                    predicateEncodingMetrics.latestWriterInputWrites += writes.size();
                }
                var latestSelf = entry.latestSelfBefore(
                        observation.getTxn(), observation.getEventIndex());
                if (latestSelf == null) {
                    latestSelf = recordedSource;
                }
                if (recordedSource != null) {
                    if (latestSelf != recordedSource) {
                        assertCurrentPredicate(Lit.False);
                    }
                } else if (latestSelf != null
                        && !hasEmptyPredicateContribution(
                                predicateRead, relationResolver, latestSelf)) {
                    assertCurrentPredicate(Lit.False);
                }
                continue;
            }
            if (collectingPredicateMetrics) {
                predicateEncodingMetrics.externalKeys++;
            }
            var externalStarted = startExternalKeyEncoding(
                    recordedSource != null);
            try {
                if (recordedSource != null) {
                    if (collectingPredicateMetrics) {
                        predicateEncodingMetrics.recordedSourceKeys++;
                    }
                    encodeFixedPredicateSource(observation, recordedSource,
                            latestExternalWrites(entry, observation.getTxn()));
                    continue;
                }

                var analysis = analyzeAbsentKey(
                        observation, entry, predicateRead, relationResolver);
                var badWrites = analysis.badWrites;
                if (collectingPredicateMetrics) {
                    predicateEncodingMetrics.badWrites += badWrites.size();
                }
                encodeAbsentRowLocalKey(observation, entry,
                        latestExternalWrites(entry, observation.getTxn()), analysis);
            } finally {
                finishExternalKeyEncoding(
                        recordedSource != null, externalStarted);
            }
        }

        predicateEncodingMetrics.rowLocalKeyScanNanos +=
                System.nanoTime() - started;
    }

    /** Handles a row-local absent key without a frontier when its source is known. */
    private void encodeAbsentRowLocalKey(
            KnownGraph.PredicateObservation<KeyType, ValueType> observation,
            KeyWriteIndex<KeyType, ValueType> entry,
            List<KnownGraph.WriteRef<KeyType, ValueType>> externalWrites,
            AbsentKeyAnalysis<KeyType, ValueType> analysis) {
        predicateSourceConstraintCount++;
        if (collectingPredicateMetrics) {
            predicateEncodingMetrics.frontiers++;
        }
        var predicateRead = observation.getPredicateReadEvent();
        var reader = observation.getTxn();
        var eligibleSources = predicateSolvingMode == SERVerifier.PredicateSolvingMode.GMWR
                ? Set.copyOf(analysis.goodWriterTxns) : null;
        var sourceWrites = predicateSourceWrites(observation, externalWrites, eligibleSources);
        if (sourceWrites.size() == 1 && knownBefore(sourceWrites.get(0).getTxn(), reader)) {
            var source = sourceWrites.get(0);
            if (collectingPredicateMetrics) {
                predicateEncodingMetrics.frontierCandidates++;
            }
            if (eligibleSources == null || eligibleSources.contains(source.getTxn())) {
                encodeSelectedSourceDependencies(reader, entry.key, source, Lit.True,
                        externalWrites, predicateRead, sourceWrites);
            }
            if (!analysis.emptyContributions.contains(source)) {
                assertCurrentPredicate(Lit.False);
            }
            return;
        }
        var frontier = createExternalKeyFrontier(observation, entry.key,
                externalWrites, sourceWrites, eligibleSources);
        if (predicateSolvingMode == SERVerifier.PredicateSolvingMode.GMWR) {
            return;
        }
        var badWrites = analysis.badWrites;
        if (badWrites.isEmpty()) {
            return;
        }

        var badWriteSet = Collections.newSetFromMap(
                new IdentityHashMap<KnownGraph.WriteRef<KeyType, ValueType>, Boolean>());
        badWriteSet.addAll(badWrites);
        for (var badWrite : badWrites) {
            var badCandidate = candidateFor(frontier, badWrite);
            if (badCandidate == null) {
                continue;
            }
            var blockingClause = new ArrayList<Lit>();
            blockingClause.add(Logic.not(badCandidate.visible));
            for (var goodCandidate : frontier.candidates) {
                if (badWriteSet.contains(goodCandidate.write)) {
                    continue;
                }
                var laterVisible = and(goodCandidate.visible,
                        beforeWrite(badWrite, goodCandidate.write));
                if (laterVisible != Lit.False && !laterVisible.isConstFalse()) {
                    blockingClause.add(laterVisible);
                }
            }
            if (collectingPredicateMetrics) {
                predicateEncodingMetrics.blockingClauses++;
                predicateEncodingMetrics.blockingClauseLiterals +=
                        Math.max(1, blockingClause.size());
            }
            assertClauseUnderAssumption(
                    currentPredicateAssumption, blockingClause);
        }
    }

    /** Common recorded-source contract; no full-snapshot materialization is retained. */
    private boolean recordedPredicateInputsValid(
            Event<KeyType, ValueType> predicateRead,
            Map<KeyType, KnownGraph.WriteRef<KeyType, ValueType>> resultSourcesByKey) {
        final Map<KeyType, ValueType> expectedInputs;
        try {
            expectedInputs = expectedPredicateInputs(predicateRead);
        } catch (QueryException exception) {
            return false;
        }
        if (!expectedInputs.keySet().equals(resultSourcesByKey.keySet())) {
            return false;
        }
        var predicate = predicateRead.getPredicate();
        for (var source : resultSourcesByKey.entrySet()) {
            if (!predicate.scope().covers(source.getKey())
                    || !writesByKey.containsKey(source.getKey())
                    || !Objects.equals(expectedInputs.get(source.getKey()),
                            source.getValue().getEvent().getValue())) {
                return false;
            }
        }
        // Complete contributing inputs must reproduce the recorded bag and provenance.
        return predicateSnapshotMatches(predicateRead, expectedInputs);
    }

    private boolean hasEmptyPredicateContribution(
            Event<KeyType, ValueType> predicateRead,
            RelationResolver<KeyType> relationResolver,
            KnownGraph.WriteRef<KeyType, ValueType> write) {
        if (write.getEvent().getValue() == null) {
            return true;
        }
        var compactStatus = compactRowMatchStatus(predicateRead, write);
        if (compactStatus != COMPACT_MATCH_UNAVAILABLE) {
            return compactStatus == COMPACT_MATCH_FALSE;
        }

        var evaluated = rowContribution(predicateRead, relationResolver, write);
        if (!evaluated.valid) {
            return false;
        }

        if (predicateRead.getRecordedPredicateResult() == null) {
            return evaluated.contribution.inputsEmpty();
        }
        return evaluated.contribution.inputsEmpty()
                && evaluated.contribution.valuesEmpty();
    }

    private List<KnownGraph.WriteRef<KeyType, ValueType>> latestExternalWrites(
            KeyWriteIndex<KeyType, ValueType> writeIndex,
            Transaction<KeyType, ValueType> reader) {
        if (collectingPredicateMetrics) {
            predicateEncodingMetrics.latestWriterLookups++;
            // Keep this logical-work counter comparable with the pre-index
            // implementation: these are versions whose reduction is now reused.
            predicateEncodingMetrics.latestWriterInputWrites += writeIndex.writes.size();
        }
        var latestWrites = writeIndex.latestExternalWrites(reader);
        if (collectingPredicateMetrics) {
            predicateEncodingMetrics.latestWriterResults += latestWrites.size();
        }
        return latestWrites;
    }

    private void addPredicateRwDependency(
            Transaction<KeyType, ValueType> reader,
            KeyType key,
            KnownGraph.WriteRef<KeyType, ValueType> source,
            KnownGraph.WriteRef<KeyType, ValueType> later) {
        addDependencyEdge(
                new SEREdge<>(reader, later.getTxn(), EdgeType.PR_RW, key),
                beforeWrite(source, later));
    }

    private List<KnownGraph.WriteRef<KeyType, ValueType>> predicateSourceWrites(
            KnownGraph.PredicateObservation<KeyType, ValueType> observation,
            List<KnownGraph.WriteRef<KeyType, ValueType>> externalWrites,
            Set<Transaction<KeyType, ValueType>> prWrSourceTxns) {
        var sourceWrites = externalWrites;
        if (predicateSolvingMode == SERVerifier.PredicateSolvingMode.GMWR) {
            sourceWrites = pruneGmwrPrWrSourceAlternatives(
                    observation.getTxn(), sourceWrites,
                    observation.getPredicateReadEvent(), prWrSourceTxns);
        }
        if (predicateSolvingMode == SERVerifier.PredicateSolvingMode.GMWR) {
            sourceWrites = possibleExternalFrontierWrites(
                    sourceWrites, observation.getTxn());
        }
        return sourceWrites;
    }

    private KeyFrontier<KeyType, ValueType> createExternalKeyFrontier(
            KnownGraph.PredicateObservation<KeyType, ValueType> observation,
            KeyType key,
            List<KnownGraph.WriteRef<KeyType, ValueType>> externalWrites,
            List<KnownGraph.WriteRef<KeyType, ValueType>> sourceWrites,
            Set<Transaction<KeyType, ValueType>> prWrSourceTxns) {
        var candidates = latestVisibleChecker.check(
                        observation.getTxn(), key, sourceWrites,
                        new LatestVisibleChecker.SerializationOrder<KeyType, ValueType>() {
                            @Override
                            public Lit beforeReader(
                                    KeyType candidateKey,
                                    KnownGraph.WriteRef<KeyType, ValueType> writer,
                                    Transaction<KeyType, ValueType> reader) {
                                return orderLiteral(writer.getTxn(), reader);
                            }

                            @Override
                            public Lit beforeWriter(
                                    KeyType candidateKey,
                                    KnownGraph.WriteRef<KeyType, ValueType> left,
                                    KnownGraph.WriteRef<KeyType, ValueType> right) {
                                return beforeWrite(left, right);
                            }
                        }).stream()
                .map(validity -> new FrontierCandidate<>(
                        validity.writer, validity.visible, validity.valid))
                .filter(candidate -> candidate.visible != Lit.False)
                .collect(Collectors.toList());
        if (collectingPredicateMetrics) {
            predicateEncodingMetrics.frontierCandidates += candidates.size();
        }

        var frontier = new KeyFrontier<KeyType, ValueType>(
                key, observation.getTxn(), candidates, null);

        encodeSelectedPredicateDependencies(
                frontier, externalWrites, observation.getPredicateReadEvent(), prWrSourceTxns);
        return frontier;
    }

    /**
     * Applies only semantic-level PR_WR source pruning. It never fixes or
     * removes an unresolved WW choice. Writers that are not legal PR_WR
     * sources for this item remain in the frontier because they can still be
     * the bad AR-max writer handled by the GMWR projection clause.
     */
    private List<KnownGraph.WriteRef<KeyType, ValueType>>
            pruneGmwrPrWrSourceAlternatives(
                    Transaction<KeyType, ValueType> reader,
                    List<KnownGraph.WriteRef<KeyType, ValueType>> candidates,
                    Event<KeyType, ValueType> predicateRead,
                    Set<Transaction<KeyType, ValueType>> eligibleSourceTxns) {
        if (candidates.isEmpty()) {
            return candidates;
        }
        var retained = new ArrayList<KnownGraph.WriteRef<KeyType, ValueType>>(
                candidates.size());
        for (var candidate : candidates) {
            if (eligibleSourceTxns != null
                    && !eligibleSourceTxns.contains(candidate.getTxn())) {
                retained.add(candidate);
                continue;
            }

            gmwrPrWrSourceAlternatives++;
            if (gmwrBefore(reader, candidate.getTxn())) {
                gmwrPrWrReachabilityPruned++;
                continue;
            }
            if (gmwrPrWrWouldForcePrRwCycle(
                    candidate, reader, candidates, predicateRead)) {
                gmwrPrWrPrRwCyclePruned++;
                continue;
            }
            retained.add(candidate);
        }
        return retained;
    }

    /**
     * WW(A,B,k) and a result-changing B force PR_RW(R,B,k) when A is the
     * selected source. If B already reaches R, that source would close a typed
     * dependency cycle and can be deleted without deciding any WW alternative.
     */
    private boolean gmwrPrWrWouldForcePrRwCycle(
            KnownGraph.WriteRef<KeyType, ValueType> source,
            Transaction<KeyType, ValueType> reader,
            List<KnownGraph.WriteRef<KeyType, ValueType>> writes,
            Event<KeyType, ValueType> predicateRead) {
        for (var later : writes) {
            if (later == source
                    || !knownWwBefore(source, later)
                    || !writeChangesPredicateResult(source, later, predicateRead)) {
                continue;
            }
            if (gmwrBefore(later.getTxn(), reader)) {
                return true;
            }
        }
        return false;
    }

    /** Returns true only for an already fixed key-local WW fact. */
    private boolean knownWwBefore(
            KnownGraph.WriteRef<KeyType, ValueType> from,
            KnownGraph.WriteRef<KeyType, ValueType> to) {
        if (from == to) {
            return false;
        }
        if (from.getTxn().equals(to.getTxn())) {
            return from.getIndex() < to.getIndex();
        }
        if (isBottomTxn(from.getTxn())) {
            return !isBottomTxn(to.getTxn());
        }
        return knownWwSuccessorsByKey
                .getOrDefault(from.getEvent().getKey(), Collections.emptyMap())
                .getOrDefault(from.getTxn(), Collections.emptySet())
                .contains(to.getTxn());
    }

    /**
     * Uses the mandatory typed-dependency closure as a conservative interval
     * around one external predicate read.  Incomparable writers remain in the
     * interval because an unresolved WW/serialization choice may still place
     * them immediately before the reader.
     */
    private List<KnownGraph.WriteRef<KeyType, ValueType>> possibleExternalFrontierWrites(
            List<KnownGraph.WriteRef<KeyType, ValueType>> externalWrites,
            Transaction<KeyType, ValueType> reader) {
        var knownBeforeReader = new BitSet(txns.size());
        for (var write : externalWrites) {
            var writer = write.getTxn();
            if (!isBottomTxn(writer) && knownBefore(writer, reader)) {
                knownBeforeReader.set(txnIndex.get(writer));
            }
        }

        var result = new ArrayList<KnownGraph.WriteRef<KeyType, ValueType>>();
        for (var write : externalWrites) {
            var writer = write.getTxn();
            if (knownBefore(reader, writer)) {
                gmwrIntervalCandidatesPruned++;
                continue;
            }
            if (!knownBefore(writer, reader)) {
                result.add(write);
                continue;
            }

            // Keep only maximal writers in the mandatory-before portion of
            // this key's interval.  Bottom is maximal only when no real writer
            // of the key is already known visible before the reader.
            if (isBottomTxn(writer)) {
                if (knownBeforeReader.isEmpty()) {
                    result.add(write);
                } else {
                    gmwrIntervalCandidatesPruned++;
                }
            } else if (!writerReachesKnownBeforeReader(writer, knownBeforeReader)) {
                result.add(write);
            } else {
                gmwrIntervalCandidatesPruned++;
            }
        }
        return result;
    }

    private boolean writerReachesKnownBeforeReader(
            Transaction<KeyType, ValueType> writer,
            BitSet knownBeforeReader) {
        for (int other = knownBeforeReader.nextSetBit(0); other >= 0;
                other = knownBeforeReader.nextSetBit(other + 1)) {
            if (knownBefore(writer, txns.get(other))) {
                return true;
            }
        }
        return false;
    }

    private void encodeSelectedPredicateDependencies(
            KeyFrontier<KeyType, ValueType> frontier,
            List<KnownGraph.WriteRef<KeyType, ValueType>> externalWrites,
            Event<KeyType, ValueType> predicateRead,
            Set<Transaction<KeyType, ValueType>> prWrSourceTxns) {
        var possibleSources = Collections.newSetFromMap(
                new IdentityHashMap<KnownGraph.WriteRef<KeyType, ValueType>, Boolean>());
        for (var candidate : frontier.candidates) {
            possibleSources.add(candidate.write);
        }
        var prWrSources = frontier.candidates.stream()
                .filter(source -> prWrSourceTxns == null
                        || prWrSourceTxns.contains(source.write.getTxn()))
                .collect(Collectors.toList());
        for (var source : prWrSources) {
            encodeSelectedSourceDependencies(frontier.reader, frontier.key,
                    source.write, source.latest, externalWrites, predicateRead, possibleSources);
        }
    }

    private void encodeSelectedSourceDependencies(
            Transaction<KeyType, ValueType> reader,
            KeyType key,
            KnownGraph.WriteRef<KeyType, ValueType> source,
            Lit selectedGuard,
            List<KnownGraph.WriteRef<KeyType, ValueType>> externalWrites,
            Event<KeyType, ValueType> predicateRead,
            Collection<KnownGraph.WriteRef<KeyType, ValueType>> possibleSources) {
        var sourceEdge = new SEREdge<>(
                source.getTxn(),
                reader,
                EdgeType.PR_WR,
                key);
        addDependencyEdge(sourceEdge, selectedGuard);

        for (var later : externalWrites) {
            if (later == source
                    // A known-visible write removed from the interval is
                    // shadowed by another known-visible write. Therefore
                    // no selected source can also be before it, and its
                    // guarded PR_RW is unactivatable. Writers known after
                    // the reader are deliberately retained here because
                    // their PR_RW edge is active in the typed Adya graph.
                    || (knownBefore(later.getTxn(), reader)
                            && !possibleSources.contains(later))
                    || !writeChangesPredicateResult(
                            source, later, predicateRead)) {
                continue;
            }
            addDependencyEdge(new SEREdge<>(
                    reader,
                    later.getTxn(),
                    EdgeType.PR_RW,
                    key),
                    selectedGuard, beforeWrite(source, later));
        }
    }

    private static boolean containsIdentity(List<?> candidates, Object expected) {
        for (var candidate : candidates) {
            if (candidate == expected) {
                return true;
            }
        }
        return false;
    }

    private FrontierCandidate<KeyType, ValueType> candidateFor(
            KeyFrontier<KeyType, ValueType> frontier,
            KnownGraph.WriteRef<KeyType, ValueType> write) {
        for (var candidate : frontier.candidates) {
            if (candidate.write == write) {
                return candidate;
            }
        }
        return null;
    }

    /** Checks only the recorded source, against every external competing write. */
    private Lit assertFixedSourceLatest(Transaction<KeyType, ValueType> reader,
            KnownGraph.WriteRef<KeyType, ValueType> source,
            List<KnownGraph.WriteRef<KeyType, ValueType>> externalWrites) {
        if (!containsIdentity(externalWrites, source)) {
            assertCurrentPredicate(Lit.False);
            return Lit.False;
        }
        var visible = orderLiteral(source.getTxn(), reader);
        assertCurrentPredicate(visible);
        if (visible == Lit.False) {
            return visible;
        }
        for (var other : externalWrites) {
            if (other == source) {
                continue;
            }
            var afterSource = beforeWrite(source, other);
            if (afterSource == Lit.False) {
                continue;
            }
            var beforeReader = orderLiteral(other.getTxn(), reader);
            if (beforeReader != Lit.False) {
                assertClauseUnderAssumption(currentPredicateAssumption,
                        List.of(afterSource.not(), beforeReader.not()));
            }
        }
        return visible;
    }

    /** General queries still need a frontier handle, but only for the fixed write. */
    private KeyFrontier<KeyType, ValueType> fixedSourceFrontier(
            KnownGraph.PredicateObservation<KeyType, ValueType> observation,
            KeyType key,
            KnownGraph.WriteRef<KeyType, ValueType> source,
            List<KnownGraph.WriteRef<KeyType, ValueType>> externalWrites) {
        var visible = assertFixedSourceLatest(observation.getTxn(), source, externalWrites);
        var candidates = visible == Lit.False
                ? List.<FrontierCandidate<KeyType, ValueType>>of()
                : List.of(new FrontierCandidate<>(source, visible, Lit.True));
        if (collectingPredicateMetrics) {
            predicateEncodingMetrics.frontierCandidates += candidates.size();
        }
        return new KeyFrontier<>(key, observation.getTxn(), candidates, source);
    }

    /** A fixed row-local source needs no general frontier or candidate objects. */
    private void encodeFixedPredicateSource(
            KnownGraph.PredicateObservation<KeyType, ValueType> observation,
            KnownGraph.WriteRef<KeyType, ValueType> source,
            List<KnownGraph.WriteRef<KeyType, ValueType>> externalWrites) {
        predicateSourceConstraintCount++;
        if (collectingPredicateMetrics) {
            predicateEncodingMetrics.frontiers++;
        }
        if (assertFixedSourceLatest(observation.getTxn(), source, externalWrites) == Lit.False) {
            return;
        }
        if (collectingPredicateMetrics) {
            predicateEncodingMetrics.frontierCandidates++;
        }
        encodeRecordedSourceDependencies(observation.getTxn(), source.getEvent().getKey(),
                source, externalWrites, observation.getPredicateReadEvent());
    }

    private void encodeRecordedSourceDependencies(Transaction<KeyType, ValueType> reader,
            KeyType key,
            KnownGraph.WriteRef<KeyType, ValueType> source,
            List<KnownGraph.WriteRef<KeyType, ValueType>> externalWrites,
            Event<KeyType, ValueType> predicateRead) {
        if (!source.getTxn().equals(reader)) {
            var edge = new SEREdge<KeyType, ValueType>(
                    source.getTxn(), reader, EdgeType.PR_WR, key);
            addKnownPredicateEdge(edge);
            addDependencyEdge(edge, Lit.True);
        }
        for (var other : externalWrites) {
            if (other == source) {
                continue;
            }
            if (!writeChangesPredicateResult(
                    source, other, predicateRead)) {
                continue;
            }
            addPredicateRwDependency(reader, key, source, other);
        }
    }

    private boolean writeChangesPredicateResult(
            KnownGraph.WriteRef<KeyType, ValueType> source,
            KnownGraph.WriteRef<KeyType, ValueType> later,
            Event<KeyType, ValueType> predicateRead) {
        boolean sourceMatches = writeMatchesPredicate(source, predicateRead);
        boolean laterMatches = writeMatchesPredicate(later, predicateRead);

        if (sourceMatches != laterMatches) {
            return true;
        }
        if (!sourceMatches) {
            return false;
        }

        var plan = rowLocalQueryPlan(predicateRead);
        if (plan != null && plan.compactResultProjection() == null) {
            var resolver = relationResolverFor(predicateRead);
            var sourceContribution = rowContribution(predicateRead, resolver, source);
            var laterContribution = rowContribution(predicateRead, resolver, later);
            if (sourceContribution.valid && laterContribution.valid) {
                return !sourceContribution.contribution.canonicalEquals(
                        laterContribution.contribution);
            }
        }
        return !Objects.equals(source.getEvent().getKey(), later.getEvent().getKey())
                || !Objects.equals(source.getEvent().getValue(), later.getEvent().getValue());
    }

    private boolean writeMatchesPredicate(
            KnownGraph.WriteRef<KeyType, ValueType> write,
            Event<KeyType, ValueType> predicateRead) {
        if (write.getEvent().getValue() == null) {
            return false;
        }
        var compactStatus = compactRowMatchStatus(predicateRead, write);
        if (compactStatus != COMPACT_MATCH_UNAVAILABLE) {
            return compactStatus == COMPACT_MATCH_TRUE;
        }

        var event = write.getEvent();
        var evaluated = rowContribution(
                predicateRead, relationResolverFor(predicateRead), write);
        return evaluated.valid && evaluated.contribution.containsInput(event.getKey());
    }

    private int compactRowMatchStatus(
            Event<KeyType, ValueType> predicateRead,
            KnownGraph.WriteRef<KeyType, ValueType> write) {
        var plan = rowLocalQueryPlan(predicateRead);
        if (plan == null || plan.compactResultProjection() == null
                || plan.compiledRowMatcher().isEmpty()) {
            return COMPACT_MATCH_UNAVAILABLE;
        }
        var writeRefId = writeRefIds.get(write);
        if (writeRefId == null) {
            return COMPACT_MATCH_UNAVAILABLE;
        }
        var cache = compactRowMatchCaches.computeIfAbsent(
                plan, ignored -> new CompactRowMatchCache());
        if (cache.computed.get(writeRefId)) {
            if (cache.invalid.get(writeRefId)) {
                return COMPACT_MATCH_INVALID;
            }
            return cache.matched.get(writeRefId)
                    ? COMPACT_MATCH_TRUE : COMPACT_MATCH_FALSE;
        }

        cache.computed.set(writeRefId);
        var event = write.getEvent();
        try {
            if (plan.compiledRowMatcher().get().test(
                    event.getKey(), event.getValue())) {
                cache.matched.set(writeRefId);
                return COMPACT_MATCH_TRUE;
            }
            return COMPACT_MATCH_FALSE;
        } catch (QueryException exception) {
            cache.invalid.set(writeRefId);
            return COMPACT_MATCH_INVALID;
        }
    }

    private CachedRowContribution<KeyType> rowContribution(
            Event<KeyType, ValueType> predicateRead,
            RelationResolver<KeyType> relationResolver,
            KnownGraph.WriteRef<KeyType, ValueType> write) {
        var plan = rowLocalQueryPlan(predicateRead);
        var writeRefId = writeRefIds.get(write);
        if (plan == null || plan.compactResultProjection() != null
                || writeRefId == null) {
            return evaluateRowContribution(predicateRead, relationResolver, write, plan);
        }

        var planId = rowContributionPlanIds.computeIfAbsent(
                plan, ignored -> nextRowContributionPlanId++);
        long eventId = System.identityHashCode(predicateRead) & 0xffffffffL;
        var cacheKey = (eventId << 32) | (writeRefId & 0xffffffffL);
        cacheKey ^= ((long) planId << 16);
        var cached = generalRowContributions.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        var evaluated = evaluateRowContribution(predicateRead, relationResolver, write, plan);
        generalRowContributions.put(cacheKey, evaluated);
        return evaluated;
    }

    private CachedRowContribution<KeyType> evaluateRowContribution(
            Event<KeyType, ValueType> predicateRead,
            RelationResolver<KeyType> relationResolver,
            KnownGraph.WriteRef<KeyType, ValueType> write,
            QueryPlan<KeyType, ValueType> plan) {
        var event = write.getEvent();
        try {
            var contribution = plan == null
                    ? RowContribution.from(predicateRead.getPredicate().evaluate(
                            new MapVisibleState<>(Map.of(event.getKey(), event.getValue()),
                                    relationResolver)))
                    : plan.evaluateRowContribution(
                            event.getKey(), event.getValue(), relationResolver);
            return CachedRowContribution.valid(contribution);
        } catch (QueryException exception) {
            return CachedRowContribution.invalid();
        }
    }

    @SuppressWarnings("unchecked")
    private QueryPlan<KeyType, ValueType> rowLocalQueryPlan(
            Event<KeyType, ValueType> predicateRead) {
        if (rowLocalQueryPlans.containsKey(predicateRead)) {
            return rowLocalQueryPlans.get(predicateRead);
        }
        if (!(predicateRead.getPredicate() instanceof QueryPlan)) {
            rowLocalQueryPlans.put(predicateRead, null);
            return null;
        }
        var plan = (QueryPlan<KeyType, ValueType>) predicateRead.getPredicate();
        var rowLocalPlan = plan.isRowLocal() ? plan : null;
        rowLocalQueryPlans.put(predicateRead, rowLocalPlan);
        return rowLocalPlan;
    }

    private static final class CompactRowMatchCache {
        private final BitSet computed = new BitSet();
        private final BitSet matched = new BitSet();
        private final BitSet invalid = new BitSet();
    }

    private static final class CachedRowContribution<KeyType> {
        private final boolean valid;
        private final RowContribution<KeyType> contribution;

        private CachedRowContribution(boolean valid, RowContribution<KeyType> contribution) {
            this.valid = valid;
            this.contribution = contribution;
        }

        private static <KeyType> CachedRowContribution<KeyType> valid(
                RowContribution<KeyType> contribution) {
            return new CachedRowContribution<>(true,
                    Objects.requireNonNull(contribution, "contribution"));
        }

        private static <KeyType> CachedRowContribution<KeyType> invalid() {
            return new CachedRowContribution<>(false, null);
        }
    }

    enum AssumptionKind {
        WW_CHOICE,
        PREDICATE_OBLIGATION,
        GMWR_RULE
    }

    static final class AssumptionReason<KeyType, ValueType> {
        private final long id;
        private final AssumptionKind kind;
        private final String reason;
        private final GmwrPropagationState.DependencyFact<KeyType, ValueType> fact;
        private final Lit literal;
        private final SERConstraint<KeyType, ValueType> wwConstraint;

        private AssumptionReason(
                long id,
                AssumptionKind kind,
                String reason,
                GmwrPropagationState.DependencyFact<KeyType, ValueType> fact,
                Lit literal,
                SERConstraint<KeyType, ValueType> wwConstraint) {
            this.id = id;
            this.kind = kind;
            this.reason = reason;
            this.fact = fact;
            this.literal = literal;
            this.wwConstraint = wwConstraint;
        }

        long getId() {
            return id;
        }

        AssumptionKind getKind() {
            return kind;
        }

        String getReason() {
            if (reason != null) {
                return reason;
            }
            return String.format("%s forces %s < %s%s%s",
                    fact.rule, fact.from, fact.to,
                    fact.type == null ? "" : " type=" + fact.type,
                    fact.key == null ? "" : " key=" + fact.key);
        }

        String assumptionId() {
            return "A" + id;
        }
    }

    private static final class KeyFrontier<KeyType, ValueType> {
        private final KeyType key;
        private final Transaction<KeyType, ValueType> reader;
        private final List<FrontierCandidate<KeyType, ValueType>> candidates;
        private final KnownGraph.WriteRef<KeyType, ValueType> fixedWrite;

        private KeyFrontier(KeyType key,
                Transaction<KeyType, ValueType> reader,
                List<FrontierCandidate<KeyType, ValueType>> candidates,
                KnownGraph.WriteRef<KeyType, ValueType> fixedWrite) {
            this.key = key;
            this.reader = reader;
            this.candidates = candidates;
            this.fixedWrite = fixedWrite;
        }
    }

    private static final class FrontierCandidate<KeyType, ValueType> {
        private final KnownGraph.WriteRef<KeyType, ValueType> write;
        private final Lit visible;
        private final Lit latest;

        private FrontierCandidate(
                KnownGraph.WriteRef<KeyType, ValueType> write,
                Lit visible,
                Lit latest) {
            this.write = write;
            this.visible = visible;
            this.latest = latest;
        }
    }

    private static final class QueryVersionWitness<KeyType, ValueType> {
        private final KnownGraph.WriteRef<KeyType, ValueType> write;
        /** Null means target-only: valid for PR_RW replacement analysis but not
         * selectable in the current predicate snapshot. */
        private final Lit selection;

        private QueryVersionWitness(
                KnownGraph.WriteRef<KeyType, ValueType> write, Lit selection) {
            this.write = write;
            this.selection = selection;
        }
    }

    private static final class GuardedDependencyEdge<KeyType, ValueType> {
        private final SEREdge<KeyType, ValueType> edge;
        private final Collection<List<Lit>> guards;
        private final PredicateDependencyOrigin origin;

        private GuardedDependencyEdge(
                SEREdge<KeyType, ValueType> edge,
                Collection<List<Lit>> guards,
                PredicateDependencyOrigin origin) {
            this.edge = edge;
            this.guards = guards;
            this.origin = origin;
        }
    }

    /** Origin of a physical predicate edge; MIXED keeps the partition exact. */
    private enum PredicateDependencyOrigin {
        SOURCED,
        SOURCELESS,
        MIXED,
        KNOWN_OR_INTERNAL;

        private static PredicateDependencyOrigin merge(
                PredicateDependencyOrigin left,
                PredicateDependencyOrigin right) {
            if (left == right) {
                return left;
            }
            return MIXED;
        }
    }

    /** Transaction-level identity of a physical typed predicate edge. */
    private static final class PredicateTransactionEdgeKey<KeyType, ValueType> {
        private final Transaction<KeyType, ValueType> from;
        private final Transaction<KeyType, ValueType> to;
        private final EdgeType type;

        private PredicateTransactionEdgeKey(
                Transaction<KeyType, ValueType> from,
                Transaction<KeyType, ValueType> to,
                EdgeType type) {
            this.from = from;
            this.to = to;
            this.type = type;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof PredicateTransactionEdgeKey)) {
                return false;
            }
            var other = (PredicateTransactionEdgeKey<?, ?>) object;
            return type == other.type
                    && Objects.equals(from, other.from)
                    && Objects.equals(to, other.to);
        }

        @Override
        public int hashCode() {
            return Objects.hash(from, to, type);
        }
    }

    private static final class CoalescedPredicateDependency<KeyType, ValueType> {
        private final SEREdge<KeyType, ValueType> edge;
        private final Set<List<Lit>> guards = new LinkedHashSet<>();
        private PredicateDependencyOrigin origin;

        private CoalescedPredicateDependency(SEREdge<KeyType, ValueType> edge,
                List<Lit> guard, PredicateDependencyOrigin origin) {
            this.edge = edge;
            this.guards.add(guard);
            this.origin = origin;
        }

        private void merge(SEREdge<KeyType, ValueType> witness,
                List<Lit> witnessGuard,
                PredicateDependencyOrigin witnessOrigin) {
            witness.addKeysTo(edge);
            guards.add(witnessGuard);
            origin = PredicateDependencyOrigin.merge(origin, witnessOrigin);
        }
    }

    /** Exact (from,to,type,key,guard) identity used to skip duplicate witnesses. */
    private static final class PredicateWitnessIdentity<KeyType, ValueType> {
        private final Transaction<KeyType, ValueType> from;
        private final Transaction<KeyType, ValueType> to;
        private final EdgeType type;
        private final KeyType key;
        private final List<Lit> guard;

        private PredicateWitnessIdentity(SEREdge<KeyType, ValueType> edge, List<Lit> guard) {
            this.from = edge.getFrom();
            this.to = edge.getTo();
            this.type = edge.getType();
            this.key = edge.getKey();
            this.guard = guard;
        }

        @Override
        public boolean equals(Object object) {
            if (!(object instanceof PredicateWitnessIdentity)) {
                return false;
            }
            var other = (PredicateWitnessIdentity<?, ?>) object;
            return type == other.type && guard.equals(other.guard)
                    && Objects.equals(from, other.from)
                    && Objects.equals(to, other.to)
                    && Objects.equals(key, other.key);
        }

        @Override
        public int hashCode() {
            return Objects.hash(from, to, type, key, guard);
        }
    }

    /**
     * Compares two writes by program order inside one transaction, or by the
     * key-local WW order when they come from different transactions.
     */
    private Lit beforeWrite(KnownGraph.WriteRef<KeyType, ValueType> left,
                            KnownGraph.WriteRef<KeyType, ValueType> right) {
        if (collectingPredicateMetrics) {
            predicateEncodingMetrics.beforeWriteCalls++;
        }
        if (left == right) {
            return Lit.False;
        }
        if (left.getTxn().equals(right.getTxn())) {
            return left.getIndex() < right.getIndex() ? Lit.True : Lit.False;
        }
        return wwOrderLiteral(left.getTxn(), right.getTxn(),
                left.getEvent().getKey());
    }

    private Lit wwOrderLiteral(
            Transaction<KeyType, ValueType> from,
            Transaction<KeyType, ValueType> to,
            KeyType key) {
        boolean fromBottom = isBottomTxn(from);
        boolean toBottom = isBottomTxn(to);
        if (fromBottom && toBottom) {
            return Lit.False;
        }
        if (fromBottom) {
            return Lit.True;
        }
        if (toBottom || from.equals(to)) {
            return Lit.False;
        }
        var literal = wwOrder.get(Triple.of(from, to, key));
        if (literal != null) {
            return literal;
        }
        return orderLiteral(from, to);
    }

    private static Lit and(Lit left, Lit right) {
        if (left == Lit.False || right == Lit.False) {
            return Lit.False;
        }
        if (left == Lit.True) {
            return right;
        }
        if (right == Lit.True) {
            return left;
        }
        if (left == right) {
            return left;
        }
        return Logic.and(left, right);
    }

    private static Lit and(Collection<Lit> terms) {
        var filtered = new ArrayList<Lit>(terms.size());
        for (var term : terms) {
            if (term == Lit.False) {
                return Lit.False;
            }
            if (term != Lit.True) {
                filtered.add(term);
            }
        }
        if (filtered.isEmpty()) {
            return Lit.True;
        }
        if (filtered.size() == 1) {
            return filtered.get(0);
        }
        return Logic.and(filtered);
    }

    private static Lit or(Lit left, Lit right) {
        if (left == Lit.True || right == Lit.True) {
            return Lit.True;
        }
        if (left == Lit.False) {
            return right;
        }
        if (right == Lit.False) {
            return left;
        }
        if (left == right) {
            return left;
        }
        return Logic.or(left, right);
    }

    /** Groups writes by key and gives each key a deterministic iteration order. */
    private Map<KeyType, List<KnownGraph.WriteRef<KeyType, ValueType>>> buildWritesByKey(KnownGraph<KeyType, ValueType> graph) {
        var result = new HashMap<KeyType, List<KnownGraph.WriteRef<KeyType, ValueType>>>();
        for (var write : graph.getAllWrites()) {
            result.computeIfAbsent(write.getEvent().getKey(), ignored -> new ArrayList<>()).add(write);
        }
        for (var writes : result.values()) {
            writes.sort(Comparator
                    .comparing((KnownGraph.WriteRef<KeyType, ValueType> w) -> w.getTxn().getId())
                    .thenComparingInt(KnownGraph.WriteRef::getIndex));
        }
        return result;
    }

    private Map<KeyType, Map<Transaction<KeyType, ValueType>,
            Set<Transaction<KeyType, ValueType>>>> buildKnownWwSuccessorsByKey(
                    KnownGraph<KeyType, ValueType> knownGraph) {
        var result = new HashMap<KeyType, Map<Transaction<KeyType, ValueType>,
                Set<Transaction<KeyType, ValueType>>>>();
        for (var endpoint : knownGraph.getKnownGraphA().edges()) {
            for (var edge : knownGraph.getKnownGraphA().edgeValue(endpoint)
                    .orElse(Collections.emptyList())) {
                if (edge.getType() != EdgeType.WW) {
                    continue;
                }
                result.computeIfAbsent(edge.getKey(), ignored -> new HashMap<>())
                        .computeIfAbsent(endpoint.source(), ignored -> new HashSet<>())
                        .add(endpoint.target());
            }
        }
        return result;
    }

    private static <KeyType, ValueType> List<KeyWriteIndex<KeyType, ValueType>>
            buildKeyWriteIndexes(
                    Map<KeyType, List<KnownGraph.WriteRef<KeyType, ValueType>>> writesByKey) {
        var entries = writesByKey.entrySet().stream()
                .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                .collect(Collectors.toList());
        var indexes = new ArrayList<KeyWriteIndex<KeyType, ValueType>>(entries.size());
        for (int keyId = 0; keyId < entries.size(); keyId++) {
            var entry = entries.get(keyId);
            indexes.add(new KeyWriteIndex<>(keyId, entry.getKey(), entry.getValue()));
        }
        return Collections.unmodifiableList(indexes);
    }

    private Lit orderLiteral(Transaction<KeyType, ValueType> from, Transaction<KeyType, ValueType> to) {
        boolean fromBottom = isBottomTxn(from);
        boolean toBottom = isBottomTxn(to);

        if (fromBottom && toBottom) {
            return Lit.False;
        }
        if (fromBottom) {
            return Lit.True;
        }
        if (toBottom) {
            return Lit.False;
        }

        if (from.equals(to)) {
            return Lit.False;
        }

        if (!knownOrder.cyclic) {
            if (precedence.before(from, to)) {
                return Lit.True;
            }
            if (precedence.before(to, from)) {
                return Lit.False;
            }
        }

        ensureComparable(from, to);
        return directSerializationEdge(from, to);
    }

    private boolean knownBefore(
            Transaction<KeyType, ValueType> from,
            Transaction<KeyType, ValueType> to) {
        if (from.equals(to)) {
            return false;
        }
        if (isBottomTxn(from)) {
            return !isBottomTxn(to);
        }
        if (isBottomTxn(to)) {
            return false;
        }
        if (knownOrder != null && knownOrder.cyclic) {
            return false;
        }
        return precedence.before(from, to);
    }

    private boolean gmwrBefore(
            Transaction<KeyType, ValueType> from,
            Transaction<KeyType, ValueType> to) {
        return knownBefore(from, to);
    }

    private boolean gmwrCanPlaceBetween(
            Transaction<KeyType, ValueType> bad,
            Transaction<KeyType, ValueType> repair,
            Transaction<KeyType, ValueType> reader) {
        if (bad.equals(repair) || repair.equals(reader) || bad.equals(reader)) {
            return false;
        }
        if (gmwrBefore(reader, bad) || gmwrBefore(repair, bad)
                || gmwrBefore(reader, repair)) {
            return false;
        }
        return true;
    }

    private void ensureComparable(Transaction<KeyType, ValueType> left,
                                  Transaction<KeyType, ValueType> right) {
        int leftIndex = txnIndex.get(left);
        int rightIndex = txnIndex.get(right);
        if (leftIndex == rightIndex) {
            return;
        }
        Transaction<KeyType, ValueType> first = leftIndex < rightIndex ? left : right;
        Transaction<KeyType, ValueType> second = leftIndex < rightIndex ? right : left;
        if (comparablePairs.add(Pair.of(first, second))) {
            if (collectingPredicateMetrics) {
                predicateEncodingMetrics.comparablePairsCreated++;
            }
            solver.assertTrue(Logic.xor(directSerializationEdge(first, second), directSerializationEdge(second, first)));
        }
    }

    private Lit directSerializationEdge(Transaction<KeyType, ValueType> from,
                                        Transaction<KeyType, ValueType> to) {
        return serializationEdgeCache.computeIfAbsent(Pair.of(from, to), ignored ->
                serializationGraph.addEdge(
                        serializationNodes[txnIndex.get(from)],
                        serializationNodes[txnIndex.get(to)]));
    }

    private void publishResidualSatStats() {
        var profiler = Profiler.getInstance();
        profiler.addCount("SER_PROP_WW_CHOICE_VARIABLES_COUNT",
                residualWwChoiceVariables);
        profiler.addCount("SER_PROP_WW_CHOICE_CONSTRAINTS_COUNT",
                residualWwChoiceConstraints);
        profiler.addCount("SER_PROP_RESIDUAL_SAT_VARIABLES_COUNT",
                solver.nVars());
        profiler.addCount("SER_PROP_RESIDUAL_SAT_CONSTRAINTS_COUNT",
                solver.nClauses());
        profiler.addCount("SER_PROP_MONOSAT_GRAPH_NODES_COUNT",
                serializationGraph.nNodes());
        profiler.addCount("SER_PROP_MONOSAT_GRAPH_EDGES_COUNT",
                serializationGraph.nEdges());
        profiler.addCount("SER_PRECEDENCE_CLOSURE_BUILDS_COUNT", 1L);
        profiler.addCount("SER_PRECEDENCE_ADD_ATTEMPTS_COUNT",
                precedence.addAttemptCount());
        profiler.addCount("SER_PRECEDENCE_CLOSURE_UPDATES_COUNT",
                precedence.closureUpdateCount());
        profiler.addCount("SER_PRECEDENCE_REJECTED_ADDS_COUNT",
                precedence.rejectedAddCount());
        profiler.addCount("SER_PRECEDENCE_CYCLE_CHECKS_COUNT",
                precedence.cycleCheckCount());
        profiler.addCount("SER_PRECEDENCE_RELATIONS_COUNT",
                precedence.relationCount());
    }

    private void publishSolveStats() {
        var profiler = Profiler.getInstance();
        profiler.addCount("SER_PROP_MONOSAT_PROPAGATIONS_COUNT",
                solver.nPropagations());
        profiler.addCount("SER_PROP_MONOSAT_CONFLICTS_COUNT",
                solver.nConflicts());
    }

    /**
     * Immutable solver-local index over one key's already sorted write list.
     * Writer groups retain their first-occurrence order while the candidate for
     * each group is its final write, matching LinkedHashMap put replacement.
     */
    private static final class KeyWriteIndex<KeyType, ValueType> {
        private final int keyId;
        private final KeyType key;
        private final List<KnownGraph.WriteRef<KeyType, ValueType>> writes;
        private final List<Transaction<KeyType, ValueType>> writers;
        private final List<List<KnownGraph.WriteRef<KeyType, ValueType>>> writesByWriter;
        private final List<KnownGraph.WriteRef<KeyType, ValueType>> latestWritesByWriter;

        private KeyWriteIndex(int keyId, KeyType key,
                List<KnownGraph.WriteRef<KeyType, ValueType>> writes) {
            this.keyId = keyId;
            this.key = key;
            this.writes = writes;

            var groupedWrites = new LinkedHashMap<Transaction<KeyType, ValueType>,
                    List<KnownGraph.WriteRef<KeyType, ValueType>>>();
            for (var write : writes) {
                groupedWrites.computeIfAbsent(
                        write.getTxn(), ignored -> new ArrayList<>()).add(write);
            }
            this.writers = new ArrayList<>(groupedWrites.keySet());
            var writerWrites = new ArrayList<
                    List<KnownGraph.WriteRef<KeyType, ValueType>>>(groupedWrites.size());
            var latestWrites = new ArrayList<KnownGraph.WriteRef<KeyType, ValueType>>();
            for (var writerGroup : groupedWrites.values()) {
                writerWrites.add(writerGroup);
                latestWrites.add(writerGroup.get(writerGroup.size() - 1));
            }
            this.writesByWriter = writerWrites;
            this.latestWritesByWriter = latestWrites;
        }

        private KnownGraph.WriteRef<KeyType, ValueType> latestSelfBefore(
                Transaction<KeyType, ValueType> reader, int eventIndex) {
            int writer = writerIndex(reader);
            if (writer < 0) {
                return null;
            }
            var writerWrites = writesByWriter.get(writer);
            int low = 0;
            int high = writerWrites.size();
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (writerWrites.get(middle).getIndex() < eventIndex) {
                    low = middle + 1;
                } else {
                    high = middle;
                }
            }
            return low == 0 ? null : writerWrites.get(low - 1);
        }

        private List<KnownGraph.WriteRef<KeyType, ValueType>> latestExternalWrites(
                Transaction<KeyType, ValueType> reader) {
            int readerWriter = writerIndex(reader);
            if (readerWriter < 0) {
                return latestWritesByWriter;
            }
            if (latestWritesByWriter.size() == 1) {
                return Collections.emptyList();
            }
            var externalWrites = new ArrayList<KnownGraph.WriteRef<KeyType, ValueType>>(
                    latestWritesByWriter.size() - 1);
            for (int index = 0; index < latestWritesByWriter.size(); index++) {
                if (index != readerWriter) {
                    externalWrites.add(latestWritesByWriter.get(index));
                }
            }
            return externalWrites;
        }

        private int writerIndex(Transaction<KeyType, ValueType> writer) {
            for (int index = 0; index < writers.size(); index++) {
                var candidate = writers.get(index);
                if (candidate == writer || candidate.equals(writer)) {
                    return index;
                }
            }
            return -1;
        }
    }

    private static final class PredicateEncodingMetrics {
        private long sourceIndexNanos;
        private long scopeLookupNanos;
        private long snapshotValidationNanos;
        private long rowLocalKeyScanNanos;
        private long externalSourcedEncodeNanos;
        private long externalSourcelessEncodeNanos;
        private long observations;
        private long nullPredicates;
        private long resultSources;
        private long duplicateResultSources;
        private long scopedKeys;
        private long rowLocalAttempts;
        private long rowLocalEncoded;
        private long generalObservations;
        private long generalExternalKeys;
        private long rowLocalKeyVisits;
        private long internalKeys;
        private long externalKeys;
        private long recordedSourceKeys;
        private long externalSourcedKeys;
        private long externalSourcelessKeys;
        private long latestWriterLookups;
        private long latestWriterInputWrites;
        private long latestWriterResults;
        private long frontiers;
        private long frontierCandidates;
        private long badWrites;
        private long beforeWriteCalls;
        private long comparablePairsCreated;
        private long knownPredicateEdgeAttempts;
        private long knownPredicateEdgeDuplicates;
        private long dependencyEdgeAttempts;
        private long dependencyEdgeDuplicates;
        private long dependencyEdgesSkipped;
        private long dependencyEdgesQueued;
        private long dependencyEdgeCandidates;
        private long dependencyFixedEdgeCandidates;
        private long dependencyPhysicalEdges;
        private long dependencyPhysicalPrWrEdges;
        private long dependencyPhysicalPrRwEdges;
        private long dependencyPhysicalSourcedEdges;
        private long dependencyPhysicalSourcelessEdges;
        private long dependencyPhysicalMixedEdges;
        private long dependencyPhysicalKnownOrInternalEdges;
        private long dependencyEdgesCoalesced;
        private long blockingClauses;
        private long blockingClauseLiterals;

        private void publish(Profiler profiler, boolean includeCounts) {
            profiler.addDurationNanos("SER_PRED_SOURCE_INDEX", sourceIndexNanos);
            profiler.addDurationNanos("SER_PRED_SCOPE_LOOKUP", scopeLookupNanos);
            profiler.addDurationNanos(
                    "SER_PRED_SNAPSHOT_VALIDATE", snapshotValidationNanos);
            profiler.addDurationNanos("SER_PRED_ROW_LOCAL_KEY_SCAN", rowLocalKeyScanNanos);
            profiler.addDurationNanos(
                    "SER_PRED_EXTERNAL_SOURCED_ENCODE", externalSourcedEncodeNanos);
            profiler.addDurationNanos(
                    "SER_PRED_EXTERNAL_SOURCELESS_ENCODE", externalSourcelessEncodeNanos);
            profiler.addCount("SER_PRED_OBSERVATIONS_COUNT", observations);
            profiler.addCount("SER_PRED_DEPENDENCY_ATTEMPTS_COUNT", dependencyEdgeAttempts);
            profiler.addCount("SER_PRED_DEPENDENCY_SKIPPED_COUNT", dependencyEdgesSkipped);
            profiler.addCount("SER_PRED_DEPENDENCY_CANDIDATES_COUNT",
                    dependencyEdgeCandidates);
            profiler.addCount("SER_PRED_DEPENDENCY_FIXED_CANDIDATES_COUNT",
                    dependencyFixedEdgeCandidates);
            profiler.addCount("SER_PRED_DEPENDENCY_PHYSICAL_EDGES_COUNT",
                    dependencyPhysicalEdges);
            if (!includeCounts) {
                return;
            }
            profiler.addCount("SER_PRED_NULL_COUNT", nullPredicates);
            profiler.addCount("SER_PRED_RESULT_SOURCES_COUNT", resultSources);
            profiler.addCount("SER_PRED_DUPLICATE_SOURCES_COUNT", duplicateResultSources);
            profiler.addCount("SER_PRED_SCOPED_KEYS_COUNT", scopedKeys);
            profiler.addCount("SER_PRED_ROW_LOCAL_ATTEMPTS_COUNT", rowLocalAttempts);
            profiler.addCount("SER_PRED_ROW_LOCAL_ENCODED_COUNT", rowLocalEncoded);
            profiler.addCount("SER_PRED_GENERAL_COUNT", generalObservations);
            profiler.addCount("SER_PRED_GENERAL_EXTERNAL_KEYS_COUNT", generalExternalKeys);
            profiler.addCount("SER_PRED_ROW_LOCAL_KEY_VISITS_COUNT", rowLocalKeyVisits);
            profiler.addCount("SER_PRED_INTERNAL_KEYS_COUNT", internalKeys);
            profiler.addCount("SER_PRED_EXTERNAL_KEYS_COUNT", externalKeys);
            profiler.addCount("SER_PRED_RECORDED_SOURCE_KEYS_COUNT", recordedSourceKeys);
            profiler.addCount(
                    "SER_PRED_EXTERNAL_SOURCED_KEYS_COUNT", externalSourcedKeys);
            profiler.addCount(
                    "SER_PRED_EXTERNAL_SOURCELESS_KEYS_COUNT", externalSourcelessKeys);
            profiler.addCount("SER_PRED_LATEST_WRITER_LOOKUPS_COUNT", latestWriterLookups);
            profiler.addCount("SER_PRED_LATEST_WRITER_INPUT_WRITES_COUNT",
                    latestWriterInputWrites);
            profiler.addCount("SER_PRED_LATEST_WRITER_RESULTS_COUNT", latestWriterResults);
            profiler.addCount("SER_PRED_FRONTIERS_COUNT", frontiers);
            profiler.addCount("SER_PRED_FRONTIER_CANDIDATES_COUNT", frontierCandidates);
            profiler.addCount("SER_PRED_BAD_WRITES_COUNT", badWrites);
            profiler.addCount("SER_PRED_BEFORE_WRITE_CALLS_COUNT", beforeWriteCalls);
            profiler.addCount("SER_PRED_COMPARABLE_PAIRS_CREATED_COUNT",
                    comparablePairsCreated);
            profiler.addCount("SER_PRED_KNOWN_EDGE_ATTEMPTS_COUNT",
                    knownPredicateEdgeAttempts);
            profiler.addCount("SER_PRED_KNOWN_EDGE_DUPLICATES_COUNT",
                    knownPredicateEdgeDuplicates);
            profiler.addCount("SER_PRED_DEPENDENCY_DUPLICATES_COUNT",
                    dependencyEdgeDuplicates);
            profiler.addCount("SER_PRED_DEPENDENCY_QUEUED_COUNT", dependencyEdgesQueued);
            profiler.addCount("SER_PRED_DEPENDENCY_PHYSICAL_PR_WR_EDGES_COUNT",
                    dependencyPhysicalPrWrEdges);
            profiler.addCount("SER_PRED_DEPENDENCY_PHYSICAL_PR_RW_EDGES_COUNT",
                    dependencyPhysicalPrRwEdges);
            profiler.addCount("SER_PRED_DEPENDENCY_PHYSICAL_SOURCED_EDGES_COUNT",
                    dependencyPhysicalSourcedEdges);
            profiler.addCount("SER_PRED_DEPENDENCY_PHYSICAL_SOURCELESS_EDGES_COUNT",
                    dependencyPhysicalSourcelessEdges);
            profiler.addCount("SER_PRED_DEPENDENCY_PHYSICAL_MIXED_EDGES_COUNT",
                    dependencyPhysicalMixedEdges);
            profiler.addCount("SER_PRED_DEPENDENCY_PHYSICAL_KNOWN_INTERNAL_EDGES_COUNT",
                    dependencyPhysicalKnownOrInternalEdges);
            profiler.addCount("SER_PRED_DEPENDENCY_COALESCED_COUNT",
                    dependencyEdgesCoalesced);
            profiler.addCount("SER_PRED_BLOCKING_CLAUSES_COUNT", blockingClauses);
            profiler.addCount("SER_PRED_BLOCKING_LITERALS_COUNT", blockingClauseLiterals);
        }
    }

    private static boolean isBottomTxn(Transaction<?, ?> txn) {
        return txn.getId() == -1L
                && txn.getSession() != null
                && txn.getSession().getId() == -1L;
    }

    private final class KnownOrder {
        private final List<int[]> reductionEdges;
        private final boolean cyclic;

        private KnownOrder(List<int[]> reductionEdges,
                boolean cyclic) {
            this.reductionEdges = reductionEdges;
            this.cyclic = cyclic;
        }
    }

    /** Maps MonoSAT's direct assumption conflict clause back to logical reasons. */
    private void extractConflicts() {
        var reasons = new ArrayList<AssumptionReason<KeyType, ValueType>>();
        for (var conflictLiteral : solver.getConflictClause()) {
            var assumption = assumptionReasons.get(Logic.not(conflictLiteral));
            if (assumption != null && !reasons.contains(assumption)) {
                reasons.add(assumption);
            }
        }
        conflictReasons = Collections.unmodifiableList(reasons);

        var wwConstraints = reasons.stream()
                .filter(reason -> reason.kind == AssumptionKind.WW_CHOICE)
                .map(reason -> reason.wwConstraint)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(ArrayList::new));
        conflictConstraints = Collections.unmodifiableList(wwConstraints);

        if (reasons.isEmpty()) {
            conflictEdges = extractKnownEdgeCycle();
            return;
        }
        conflictEdges = supportingKnownEdges(wwConstraints);
    }

    /**
     * Reports known edges that touch the transactions participating in the
     * minimized unresolved constraint core.
     */
    private Collection<Pair<EndpointPair<Transaction<KeyType, ValueType>>, Collection<Edge<KeyType>>>> supportingKnownEdges(
            Collection<SERConstraint<KeyType, ValueType>> coreConstraints) {
        if (coreConstraints.isEmpty()) {
            return Collections.emptyList();
        }

        var txnsInCore = new HashSet<Transaction<KeyType, ValueType>>();
        for (var constraint : coreConstraints) {
            txnsInCore.add(constraint.getWriteTransaction1());
            txnsInCore.add(constraint.getWriteTransaction2());
            for (var edge : constraint.getEdges1()) {
                txnsInCore.add(edge.getFrom());
                txnsInCore.add(edge.getTo());
            }
            for (var edge : constraint.getEdges2()) {
                txnsInCore.add(edge.getFrom());
                txnsInCore.add(edge.getTo());
            }
        }

        var result = new ArrayList<Pair<EndpointPair<Transaction<KeyType, ValueType>>, Collection<Edge<KeyType>>>>();
        collectKnownEdgesAmong(graph.getKnownGraphA(), txnsInCore, result);
        collectKnownEdgesAmong(graph.getKnownGraphB(), txnsInCore, result);
        return result;
    }

    private void collectKnownEdgesAmong(
            com.google.common.graph.ValueGraph<Transaction<KeyType, ValueType>, Collection<Edge<KeyType>>> known,
            Set<Transaction<KeyType, ValueType>> txnsInCore,
            List<Pair<EndpointPair<Transaction<KeyType, ValueType>>, Collection<Edge<KeyType>>>> out) {
        for (var ep : known.edges()) {
            if (!txnsInCore.contains(ep.source()) || !txnsInCore.contains(ep.target())) {
                continue;
            }
            var edges = known.edgeValue(ep).orElse(List.of()).stream()
                    .filter(edge -> isEncodedKnownEdge(edge.getType()))
                    .collect(Collectors.toList());
            if (!edges.isEmpty()) {
                out.add(Pair.of(EndpointPair.ordered(ep.source(), ep.target()), edges));
            }
        }
    }

    /** Finds a concrete directed cycle formed only by mandatory known edges. */
    private Collection<Pair<EndpointPair<Transaction<KeyType, ValueType>>, Collection<Edge<KeyType>>>> extractKnownEdgeCycle() {
        var adjacency = buildKnownEdgeAdjacency();
        var color = new HashMap<Transaction<KeyType, ValueType>, Integer>();
        var stack = new ArrayList<Transaction<KeyType, ValueType>>();
        var stackIndex = new HashMap<Transaction<KeyType, ValueType>, Integer>();

        for (var txn : txns) {
            if (color.getOrDefault(txn, 0) != 0) {
                continue;
            }
            var cycle = dfsKnownEdgeCycle(txn, adjacency, color, stack, stackIndex);
            if (!cycle.isEmpty()) {
                return cycle;
            }
        }
        return Collections.emptyList();
    }

    /** Builds adjacency for the known-edge subgraph used by cycle extraction. */
    private Map<Transaction<KeyType, ValueType>, Set<Transaction<KeyType, ValueType>>> buildKnownEdgeAdjacency() {
        var adjacency = new HashMap<Transaction<KeyType, ValueType>, Set<Transaction<KeyType, ValueType>>>();
        addAdjacency(graph.getKnownGraphA(), adjacency);
        addAdjacency(graph.getKnownGraphB(), adjacency);
        return adjacency;
    }

    private void addAdjacency(
            com.google.common.graph.ValueGraph<Transaction<KeyType, ValueType>, Collection<Edge<KeyType>>> known,
            Map<Transaction<KeyType, ValueType>, Set<Transaction<KeyType, ValueType>>> adjacency) {
        for (var ep : known.edges()) {
            var edges = known.edgeValue(ep).orElse(Collections.emptyList());
            if (edges.stream().anyMatch(edge -> isEncodedKnownEdge(edge.getType()))) {
                adjacency.computeIfAbsent(ep.source(), ignored -> new LinkedHashSet<>()).add(ep.target());
            }
        }
    }

    private Collection<Pair<EndpointPair<Transaction<KeyType, ValueType>>, Collection<Edge<KeyType>>>> dfsKnownEdgeCycle(
            Transaction<KeyType, ValueType> node,
            Map<Transaction<KeyType, ValueType>, Set<Transaction<KeyType, ValueType>>> adjacency,
            Map<Transaction<KeyType, ValueType>, Integer> color,
            List<Transaction<KeyType, ValueType>> stack,
            Map<Transaction<KeyType, ValueType>, Integer> stackIndex) {
        color.put(node, 1);
        stackIndex.put(node, stack.size());
        stack.add(node);

        for (var succ : adjacency.getOrDefault(node, Collections.emptySet())) {
            int succColor = color.getOrDefault(succ, 0);
            if (succColor == 0) {
                var cycle = dfsKnownEdgeCycle(succ, adjacency, color, stack, stackIndex);
                if (!cycle.isEmpty()) {
                    return cycle;
                }
            } else if (succColor == 1) {
                var cycleNodes = new ArrayList<>(stack.subList(stackIndex.get(succ), stack.size()));
                cycleNodes.add(succ);
                return cycleEdgesFromNodes(cycleNodes);
            }
        }

        stack.remove(stack.size() - 1);
        stackIndex.remove(node);
        color.put(node, 2);
        return Collections.emptyList();
    }

    private Collection<Pair<EndpointPair<Transaction<KeyType, ValueType>>, Collection<Edge<KeyType>>>> cycleEdgesFromNodes(
            List<Transaction<KeyType, ValueType>> cycleNodes) {
        var result = new ArrayList<Pair<EndpointPair<Transaction<KeyType, ValueType>>, Collection<Edge<KeyType>>>>();
        for (int i = 0; i + 1 < cycleNodes.size(); i++) {
            var from = cycleNodes.get(i);
            var to = cycleNodes.get(i + 1);
            var edges = new ArrayList<Edge<KeyType>>();
            graph.getKnownGraphA().edgeValue(from, to).orElse(List.of()).stream()
                    .filter(edge -> isEncodedKnownEdge(edge.getType()))
                    .forEach(edges::add);
            graph.getKnownGraphB().edgeValue(from, to).orElse(List.of()).stream()
                    .filter(edge -> isEncodedKnownEdge(edge.getType()))
                    .forEach(edges::add);
            result.add(Pair.of(EndpointPair.ordered(from, to), edges));
        }
        return result;
    }

    private static boolean isEncodedKnownEdge(EdgeType type) {
        return true;
    }
}
