package verifier;

import com.google.common.graph.EndpointPair;
import graph.Edge;
import graph.EdgeType;
import graph.KnownGraph;
import history.Event;
import history.History;
import history.Transaction;
import history.query.MapVisibleState;
import history.query.QueryEvaluation;
import history.query.QueryException;
import history.query.QueryPlan;
import history.query.QueryScope;
import history.query.RelationResolver;
import history.query.RowContribution;
import monosat.Graph;
import monosat.Lit;
import monosat.Logic;
import monosat.Solver;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import util.Profiler;

import java.util.*;
import java.util.stream.Collectors;

class SISolverInduced<KeyType, ValueType> {
    private static final int COMPACT_MATCH_UNAVAILABLE = -1;
    private static final int COMPACT_MATCH_INVALID = 0;
    private static final int COMPACT_MATCH_FALSE = 1;
    private static final int COMPACT_MATCH_TRUE = 2;
    private static final int MAX_GENERAL_ROW_CONTRIBUTIONS = 32_768;

    private final History<KeyType, ValueType> history;
    private final KnownGraph<KeyType, ValueType> graph;
    private final Collection<SIConstraint<KeyType, ValueType>> constraints;
    private final SIVerifier.SolverSettings settings;
    private final boolean collectConflicts;
    private final boolean predicateWitnessCoalescing;
    private final boolean graphEdgeInterning;
    private final Solver solver;
    private final Graph depGraph;
    private final Graph inducedGraph;
    private long solveDeadlineNanos;
    private boolean solverTimedOut;
    private final Map<Transaction<KeyType, ValueType>, Integer> depNodes = new HashMap<>();
    private final Map<Transaction<KeyType, ValueType>, Integer> inducedNodes = new HashMap<>();
    private final List<GuardedDependencyEdge<KeyType, ValueType>> dependencyEdgesA =
            new ArrayList<>();
    private final List<GuardedDependencyEdge<KeyType, ValueType>> dependencyEdgesB =
            new ArrayList<>();
    private final Map<Lit, Set<SIEdge<KeyType, ValueType>>> guardedEdgesByGuard =
            new IdentityHashMap<>();
    private final Map<Lit, Set<Pair<Transaction<KeyType, ValueType>,
            Transaction<KeyType, ValueType>>>> inducedEdgesByGuard =
            new IdentityHashMap<>();
    private final List<GuardedDependencyEdge<KeyType, ValueType>>
            predicateDependencyCandidates = new ArrayList<>();
    private final Map<Pair<Integer, Integer>, Lit> physicalDepEdges =
            new HashMap<>();
    private final Map<Pair<Integer, Integer>, Lit> physicalInducedEdges =
            new HashMap<>();
    private final Map<Pair<Integer, Integer>, List<Lit>> physicalDepEdgeGuards =
            new HashMap<>();
    private final Map<Pair<Integer, Integer>, List<Lit>> physicalInducedEdgeGuards =
            new HashMap<>();
    private final Map<Triple<Transaction<KeyType, ValueType>, Transaction<KeyType, ValueType>, KeyType>, Lit> wwOrder =
            new HashMap<>();
    private final Map<KeyType, List<KnownGraph.WriteRef<KeyType, ValueType>>> writesByKey;
    private final Map<Object, List<Map.Entry<KeyType,
            List<KnownGraph.WriteRef<KeyType, ValueType>>>>> scopedWritesCache =
            new HashMap<>();
    private final IdentityHashMap<KnownGraph.WriteRef<KeyType, ValueType>, Integer>
            writeRefIds = new IdentityHashMap<>();
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
    private final List<PredicateCheck<KeyType, ValueType>> predicateChecks =
            new ArrayList<>();
    private long predicateSourceConstraintCount;
    private long residualWwChoiceVariables;
    private long residualWwChoiceConstraints;
    private final boolean collectPredicateMetrics;
    private final PredicateEncodingMetrics predicateEncodingMetrics =
            new PredicateEncodingMetrics();

    private Collection<Pair<EndpointPair<Transaction<KeyType, ValueType>>, Collection<Edge<KeyType>>>> conflictEdges =
            Collections.emptyList();
    private Collection<SIConstraint<KeyType, ValueType>> conflictConstraints = Collections.emptyList();

    SISolverInduced(
            History<KeyType, ValueType> history,
            KnownGraph<KeyType, ValueType> graph,
            Collection<SIConstraint<KeyType, ValueType>> constraints) {
        this(history, graph, constraints, true, false,
                SIVerifier.SolverSettings.defaults(
                        SIVerifier.PruningMode.REACHABILITY));
    }

    SISolverInduced(
            History<KeyType, ValueType> history,
            KnownGraph<KeyType, ValueType> graph,
            Collection<SIConstraint<KeyType, ValueType>> constraints,
            boolean collectPredicateMetrics) {
        this(history, graph, constraints, true, collectPredicateMetrics,
                SIVerifier.SolverSettings.defaults(
                        SIVerifier.PruningMode.REACHABILITY));
    }

    SISolverInduced(
            History<KeyType, ValueType> history,
            KnownGraph<KeyType, ValueType> graph,
            Collection<SIConstraint<KeyType, ValueType>> constraints,
            boolean collectConflicts,
            boolean collectPredicateMetrics) {
        this(history, graph, constraints, collectConflicts,
                collectPredicateMetrics,
                SIVerifier.SolverSettings.defaults(
                        SIVerifier.PruningMode.REACHABILITY));
    }

    SISolverInduced(
            History<KeyType, ValueType> history,
            KnownGraph<KeyType, ValueType> graph,
            Collection<SIConstraint<KeyType, ValueType>> constraints,
            boolean collectConflicts,
            boolean collectPredicateMetrics,
            SIVerifier.SolverSettings solverSettings) {
        this.history = history;
        this.graph = graph;
        this.constraints = constraints;
        this.settings = solverSettings == null
                ? SIVerifier.SolverSettings.defaults(
                        SIVerifier.PruningMode.REACHABILITY)
                : solverSettings;
        this.predicateWitnessCoalescing = this.settings.predicateWitnessCoalescing;
        this.graphEdgeInterning = this.settings.graphEdgeInterning;
        this.collectConflicts = collectConflicts;
        this.collectPredicateMetrics = collectPredicateMetrics;
        this.solver = new Solver();
        this.depGraph = new Graph(solver);
        this.inducedGraph = new Graph(solver);
        this.writesByKey = buildWritesByKey(graph);
        int writeRefId = 0;
        for (var write : graph.getAllWrites()) {
            writeRefIds.put(write, writeRefId++);
        }
        var profiler = Profiler.getInstance();
        profileVoid(profiler, "SI_GRAPH_ENCODE_SETUP", this::createNodes);
        profileVoid(profiler, "SI_GRAPH_ENCODE_KNOWN_EDGES", this::encodeKnownEdges);
        profileVoid(profiler, "SI_GRAPH_ENCODE_WW", this::encodeWwChoices);
        profileVoid(profiler, "SI_GRAPH_ENCODE_RW", this::encodeRwFromWrAndWw);
        profileVoid(profiler, "SI_GRAPH_ENCODE_PREDICATE", this::encodePredicateConstraints);
        profileVoid(profiler, "SI_GRAPH_ENCODE_DEPENDENCIES",
                () -> {
                    encodePredicateDependencies();
                    sealInternedGraphEdges();
                });
        profileVoid(profiler, "SI_GRAPH_ENCODE_ACYCLIC",
                () -> solver.assertTrue(inducedGraph.acyclic()));
        publishResidualSatStats();
    }

    boolean solve() {
        return solveStatus() == SolveStatus.SAT;
    }

    SolveStatus solveStatus() {
        conflictEdges = Collections.emptyList();
        conflictConstraints = Collections.emptyList();
        solverTimedOut = false;
        if (settings.solverTimeoutSeconds > 0) {
            solveDeadlineNanos = System.nanoTime()
                    + settings.solverTimeoutSeconds * 1_000_000_000L;
        } else {
            solveDeadlineNanos = 0L;
        }

        var profiler = Profiler.getInstance();
        try {
            while (true) {
                var sat = profileBooleanOptional(
                        profiler, "SI_MONOSAT_SOLVE", this::solveOnce);
                if (sat == null) {
                    solverTimedOut = true;
                    System.err.println("SAT solver timed out");
                    conflictEdges = Collections.emptyList();
                    conflictConstraints = Collections.emptyList();
                    return SolveStatus.TIMEOUT;
                }
                if (!sat) {
                    break;
                }
                if (profileBoolean(profiler, "SI_GRAPH_PREDICATE_REFINEMENT",
                        this::refinePredicateConstraints)) {
                    continue;
                }
                return SolveStatus.SAT;
            }
        } finally {
            predicateEncodingMetrics.publish(profiler, collectPredicateMetrics);
        }
        if (!collectConflicts) {
            conflictEdges = Collections.emptyList();
            conflictConstraints = Collections.emptyList();
            return SolveStatus.UNSAT;
        }
        profileVoid(profiler, "SI_GRAPH_CONFLICT_EXTRACTION", this::extractConflicts);
        return SolveStatus.UNSAT;
    }

    boolean timedOut() {
        return solverTimedOut;
    }

    private Boolean solveOnce() {
        int remainingSeconds = 0;
        if (solveDeadlineNanos > 0L) {
            long remainingNanos = solveDeadlineNanos - System.nanoTime();
            if (remainingNanos <= 0L) {
                return null;
            }
            long remaining = (remainingNanos + 999_999_999L)
                    / 1_000_000_000L;
            remainingSeconds = (int) Math.min(Integer.MAX_VALUE, remaining);
        }
        if (settings.satSolveBackend != null) {
            return settings.satSolveBackend.solve(solver, remainingSeconds)
                    .orElse(null);
        }
        if (remainingSeconds > 0) {
            solver.setTimeLimit(remainingSeconds);
            return solver.solveLimited().orElse(null);
        }
        return solver.solve();
    }

    Pair<Collection<Pair<EndpointPair<Transaction<KeyType, ValueType>>, Collection<Edge<KeyType>>>>,
            Collection<SIConstraint<KeyType, ValueType>>> getConflicts() {
        return Pair.of(conflictEdges, conflictConstraints);
    }

    long getPredicateSourceConstraintCount() {
        return predicateSourceConstraintCount;
    }

    private static void profileVoid(Profiler profiler, String tag, Runnable action) {
        profiler.startTick(tag);
        try {
            action.run();
        } finally {
            profiler.endTick(tag);
        }
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

    private static boolean profileBoolean(
            Profiler profiler, String tag, java.util.function.BooleanSupplier action) {
        profiler.startTick(tag);
        try {
            return action.getAsBoolean();
        } finally {
            profiler.endTick(tag);
        }
    }

    private void createNodes() {
        for (var txn : history.getTransactions()) {
            if (isBottomTxn(txn)) {
                continue;
            }
            depNodes.put(txn, depGraph.addNode());
            inducedNodes.put(txn, inducedGraph.addNode());
        }
    }

    private void encodeKnownEdges() {
        for (var ep : graph.getKnownGraphA().edges()) {
            for (var edge : graph.getKnownGraphA().edgeValue(ep).orElse(List.of())) {
                if (isDependencyEdgeA(edge.getType())) {
                    addDependencyEdge(new SIEdge<>(
                            ep.source(), ep.target(), edge.getType(), edge.getKey()),
                            Lit.True);
                }
            }
        }
        for (var ep : graph.getKnownGraphB().edges()) {
            for (var edge : graph.getKnownGraphB().edgeValue(ep).orElse(List.of())) {
                if (isDependencyEdgeB(edge.getType())) {
                    addDependencyEdge(new SIEdge<>(
                            ep.source(), ep.target(), edge.getType(), edge.getKey()),
                            Lit.True);
                }
            }
        }
    }

    private void encodeWwChoices() {
        for (var constraint : constraints) {
            var forward = new Lit(solver);
            residualWwChoiceVariables++;
            residualWwChoiceConstraints++;
            addConstraintSide(constraint.getEdges1(), forward);
            addConstraintSide(constraint.getEdges2(), Logic.not(forward));
        }
    }

    private void addConstraintSide(Collection<SIEdge<KeyType, ValueType>> edges, Lit guard) {
        if (edges == null) {
            return;
        }
        for (var edge : edges) {
            if (edge.getType() != EdgeType.WW) {
                continue;
            }
            registerWwOrder(edge.getFrom(), edge.getTo(), edge.getKey(), guard);
            addDependencyEdge(edge, guard);
        }
    }

    private void registerWwOrder(
            Transaction<KeyType, ValueType> from,
            Transaction<KeyType, ValueType> to,
            KeyType key,
            Lit guard) {
        if (guard == Lit.False) {
            return;
        }
        var orderKey = Triple.of(from, to, key);
        wwOrder.merge(orderKey, guard, SISolverInduced::or);
    }

    /**
     * Materializes ordinary anti-dependencies in their own encoding stage.
     * If T' --WR(x)--> T and T' --WW(x)--> U, then T --RW(x)--> U.
     */
    private void encodeRwFromWrAndWw() {
        for (var ep : graph.getReadFrom().edges()) {
            var reads = graph.getReadFrom().edgeValue(ep.source(), ep.target())
                    .orElse(Collections.emptyList());
            for (var wrEdge : reads) {
                var key = wrEdge.getKey();
                for (var writer : writesByKey.getOrDefault(key, Collections.emptyList())) {
                    var laterWriter = writer.getTxn();
                    if (laterWriter.equals(ep.source()) || laterWriter.equals(ep.target())) {
                        continue;
                    }
                    addDependencyEdge(new SIEdge<>(
                            ep.target(), laterWriter, EdgeType.RW, key),
                            wwOrderLiteral(ep.source(), laterWriter, key));
                }
            }
        }
    }

    private void encodePredicateConstraints() {
        for (var observation : graph.getPredicateObservations()) {
            predicateEncodingMetrics.observations++;
            var predicateRead = observation.getPredicateReadEvent();
            var predicate = predicateRead.getPredicate();
            if (predicate == null) {
                predicateEncodingMetrics.nullPredicates++;
                continue;
            }

            var started = System.nanoTime();
            var resultSourcesByKey = new LinkedHashMap<KeyType,
                    KnownGraph.WriteRef<KeyType, ValueType>>();
            for (var source : observation.getTupleSources()) {
                predicateEncodingMetrics.resultSources++;
                if (resultSourcesByKey.putIfAbsent(
                        source.getKey(), source.getSourceWrite()) != null) {
                    predicateEncodingMetrics.duplicateResultSources++;
                    solver.assertTrue(Lit.False);
                }
            }
            predicateEncodingMetrics.sourceIndexNanos += System.nanoTime() - started;

            started = System.nanoTime();
            var scopedEntries = scopedWrites(predicate.scope());
            predicateEncodingMetrics.scopeLookupNanos += System.nanoTime() - started;
            predicateEncodingMetrics.scopedKeys += scopedEntries.size();

            if (predicate instanceof QueryPlan
                    && ((QueryPlan<?, ?>) predicate).isRowLocal()) {
                predicateEncodingMetrics.rowLocalAttempts++;
                if (encodeRowLocalPredicateEager(
                        observation, scopedEntries, resultSourcesByKey)) {
                    predicateEncodingMetrics.rowLocalEncoded++;
                    continue;
                }
                predicateEncodingMetrics.rowLocalFallbacks++;
            }
            predicateEncodingMetrics.generalObservations++;

            started = System.nanoTime();
            var frontierEntries = scopedEntries.stream()
                    .filter(entry -> observation.getPredicateReadType(entry.getKey())
                            == KnownGraph.PredicateReadType.EXTERNAL)
                    .collect(Collectors.toList());
            predicateEncodingMetrics.generalKeyScanNanos +=
                    System.nanoTime() - started;
            predicateEncodingMetrics.generalExternalKeys += frontierEntries.size();
            if (frontierEntries.isEmpty()) {
                continue;
            }

            var frontiers = new ArrayList<KeyFrontier<KeyType, ValueType>>(
                    frontierEntries.size());
            for (var entry : frontierEntries) {
                frontiers.add(createKeyFrontier(
                        observation, entry.getKey(), entry.getValue(),
                        resultSourcesByKey.get(entry.getKey())));
            }
            for (var resultKey : resultSourcesByKey.keySet()) {
                if (!predicate.scope().covers(resultKey)
                        || !writesByKey.containsKey(resultKey)) {
                    solver.assertTrue(Lit.False);
                }
            }
            var snapshot = new LinkedHashMap<KeyType, ValueType>();
            var frontierKeys = frontierEntries.stream().map(Map.Entry::getKey)
                    .collect(Collectors.toSet());
            for (var entry : scopedEntries) {
                if (frontierKeys.contains(entry.getKey())) {
                    continue;
                }
                var latestSelf = entry.getValue().stream()
                        .filter(write -> write.getTxn().equals(
                                observation.getTxn())
                                && write.getIndex()
                                < observation.getEventIndex())
                        .max(Comparator.comparingInt(
                                KnownGraph.WriteRef::getIndex))
                        .orElse(resultSourcesByKey.get(entry.getKey()));
                if (latestSelf != null) {
                    snapshot.put(entry.getKey(),
                            latestSelf.getEvent().getValue());
                }
            }
            predicateChecks.add(new PredicateCheck<>(
                    predicateRead, frontiers, snapshot,
                    relationResolverFor(predicateRead)));
        }
    }

    private void encodePredicateDependencies() {
        if (predicateDependencyCandidates.isEmpty()) {
            return;
        }

        int candidateCount = predicateDependencyCandidates.size();
        if (!predicateWitnessCoalescing) {
            int physicalEdgeCountBefore =
                    dependencyEdgesA.size() + dependencyEdgesB.size();
            for (var candidate : predicateDependencyCandidates) {
                materializeDependencyEdge(candidate.edge, candidate.guard);
            }
            int physicalEdgeCount = dependencyEdgesA.size()
                    + dependencyEdgesB.size() - physicalEdgeCountBefore;
            predicateEncodingMetrics.dependencyEdgeCandidates += candidateCount;
            predicateEncodingMetrics.dependencyPhysicalEdges += physicalEdgeCount;
            predicateDependencyCandidates.clear();
            return;
        }

        var coalesced = new LinkedHashMap<
                PredicateTransactionEdgeKey<KeyType, ValueType>,
                CoalescedPredicateDependency<KeyType, ValueType>>();
        for (var candidate : predicateDependencyCandidates) {
            var edge = candidate.edge;
            var key = new PredicateTransactionEdgeKey<>(
                    edge.getFrom(), edge.getTo(), edge.getType());
            var existing = coalesced.get(key);
            if (existing == null) {
                coalesced.put(key, new CoalescedPredicateDependency<>(
                        edge, candidate.guard));
            } else {
                existing.merge(edge, candidate.guard);
            }
        }
        for (var dependency : coalesced.values()) {
            materializeDependencyEdge(dependency.edge, dependency.guard);
        }
        predicateEncodingMetrics.dependencyEdgeCandidates += candidateCount;
        predicateEncodingMetrics.dependencyPhysicalEdges += coalesced.size();
        predicateEncodingMetrics.dependencyEdgesCoalesced +=
                candidateCount - coalesced.size();
        System.err.printf(
                "Predicate dependency coalescing: %d -> %d typed edges, coalesced=%d%n",
                candidateCount, coalesced.size(), candidateCount - coalesced.size());
        predicateDependencyCandidates.clear();
    }

    /** Eagerly materializes every row-local reader-key constraint before solve(). */
    private boolean encodeRowLocalPredicateEager(
            KnownGraph.PredicateObservation<KeyType, ValueType> observation,
            List<Map.Entry<KeyType, List<KnownGraph.WriteRef<KeyType, ValueType>>>> scopedEntries,
            Map<KeyType, KnownGraph.WriteRef<KeyType, ValueType>> resultSourcesByKey) {
        var predicateRead = observation.getPredicateReadEvent();
        var relationResolver = relationResolverFor(predicateRead);
        var started = System.nanoTime();
        var snapshotValid = rowLocalSnapshotValid(
                predicateRead, relationResolver, resultSourcesByKey);
        predicateEncodingMetrics.snapshotValidationNanos +=
                System.nanoTime() - started;
        if (!snapshotValid) {
            return false;
        }

        started = System.nanoTime();
        for (var entry : scopedEntries) {
            var key = entry.getKey();
            var writes = entry.getValue();
            var recordedSource = resultSourcesByKey.get(key);
            predicateEncodingMetrics.rowLocalKeyVisits++;

            if (observation.getPredicateReadType(key)
                    == KnownGraph.PredicateReadType.INTERNAL) {
                predicateEncodingMetrics.internalKeys++;
                var latestSelf = latestSelfBefore(
                        writes, observation.getTxn(), observation.getEventIndex());
                if (latestSelf == null) {
                    latestSelf = recordedSource;
                }
                if (recordedSource != null) {
                    if (latestSelf != recordedSource) {
                        solver.assertTrue(Lit.False);
                    }
                } else if (latestSelf != null
                        && !hasEmptyPredicateContribution(
                                predicateRead, relationResolver, latestSelf)) {
                    solver.assertTrue(Lit.False);
                }
                continue;
            }

            predicateEncodingMetrics.externalKeys++;
            if (recordedSource != null) {
                predicateEncodingMetrics.recordedSourceKeys++;
                createKeyFrontier(observation, key, writes, recordedSource);
                continue;
            }

            var badWrites = latestExternalWritesRaw(writes, observation.getTxn()).stream()
                    .filter(write -> !hasEmptyPredicateContribution(
                            predicateRead, relationResolver, write))
                    .collect(Collectors.toList());
            predicateEncodingMetrics.badWrites += badWrites.size();
            var frontier = createKeyFrontier(observation, key, writes, null);
            if (badWrites.isEmpty()) {
                continue;
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
                predicateEncodingMetrics.blockingClauses++;
                predicateEncodingMetrics.blockingClauseLiterals +=
                        Math.max(1, blockingClause.size());
                solver.assertOr(blockingClause);
            }
        }

        for (var resultKey : resultSourcesByKey.keySet()) {
            if (!predicateRead.getPredicate().scope().covers(resultKey)
                    || !writesByKey.containsKey(resultKey)) {
                solver.assertTrue(Lit.False);
            }
        }
        predicateEncodingMetrics.rowLocalKeyScanNanos +=
                System.nanoTime() - started;
        return true;
    }

    private boolean rowLocalSnapshotValid(
            Event<KeyType, ValueType> predicateRead,
            RelationResolver<KeyType> relationResolver,
            Map<KeyType, KnownGraph.WriteRef<KeyType, ValueType>> resultSourcesByKey) {
        var expectedInputs = expectedPredicateInputs(predicateRead);
        if (!expectedInputs.keySet().equals(resultSourcesByKey.keySet())
                || !predicateSnapshotMatches(predicateRead, expectedInputs, relationResolver)) {
            return false;
        }
        for (var source : resultSourcesByKey.entrySet()) {
            if (!Objects.equals(expectedInputs.get(source.getKey()),
                    source.getValue().getEvent().getValue())) {
                return false;
            }
        }
        return true;
    }

    private Map<KeyType, ValueType> expectedPredicateInputs(
            Event<KeyType, ValueType> predicateRead) {
        var recorded = predicateRead.getRecordedPredicateResult();
        if (recorded != null) {
            return recorded.inputs();
        }
        var expected = new LinkedHashMap<KeyType, ValueType>();
        for (var result : predicateRead.getPredResults()) {
            if (expected.putIfAbsent(result.getKey(), result.getValue()) != null) {
                return Collections.emptyMap();
            }
        }
        return expected;
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

    private KnownGraph.WriteRef<KeyType, ValueType> latestSelfBefore(
            List<KnownGraph.WriteRef<KeyType, ValueType>> writes,
            Transaction<KeyType, ValueType> reader,
            int eventIndex) {
        return writes.stream()
                .filter(write -> write.getTxn().equals(reader)
                        && write.getIndex() < eventIndex)
                .max(Comparator.comparingInt(KnownGraph.WriteRef::getIndex))
                .orElse(null);
    }

    private List<KnownGraph.WriteRef<KeyType, ValueType>> latestExternalWrites(
            List<KnownGraph.WriteRef<KeyType, ValueType>> writes,
            Transaction<KeyType, ValueType> reader) {
        predicateEncodingMetrics.latestWriterLookups++;
        predicateEncodingMetrics.latestWriterInputWrites += writes.size();
        var result = latestExternalWritesRaw(writes, reader);
        predicateEncodingMetrics.latestWriterResults += result.size();
        return result;
    }

    private List<KnownGraph.WriteRef<KeyType, ValueType>> latestExternalWritesRaw(
            List<KnownGraph.WriteRef<KeyType, ValueType>> writes,
            Transaction<KeyType, ValueType> reader) {
        var latestByWriter = new LinkedHashMap<Transaction<KeyType, ValueType>,
                KnownGraph.WriteRef<KeyType, ValueType>>();
        for (var write : writes) {
            if (!write.getTxn().equals(reader)) {
                latestByWriter.put(write.getTxn(), write);
            }
        }
        return new ArrayList<>(latestByWriter.values());
    }

    private List<Map.Entry<KeyType, List<KnownGraph.WriteRef<KeyType, ValueType>>>>
            scopedWrites(QueryScope<KeyType> scope) {
        var cacheKey = scope.cacheKey();
        if (cacheKey.isEmpty()) {
            return buildScopedWrites(scope);
        }
        return scopedWritesCache.computeIfAbsent(
                cacheKey.get(), ignored -> buildScopedWrites(scope));
    }

    private List<Map.Entry<KeyType, List<KnownGraph.WriteRef<KeyType, ValueType>>>>
            buildScopedWrites(QueryScope<KeyType> scope) {
        return writesByKey.entrySet().stream()
                .filter(entry -> scope.covers(entry.getKey()))
                .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                .map(entry -> Map.entry(entry.getKey(), entry.getValue()))
                .collect(Collectors.toUnmodifiableList());
    }

    private KeyFrontier<KeyType, ValueType> createKeyFrontier(
            KnownGraph.PredicateObservation<KeyType, ValueType> observation,
            KeyType key,
            List<KnownGraph.WriteRef<KeyType, ValueType>> writes,
            KnownGraph.WriteRef<KeyType, ValueType> recordedSource) {
        predicateSourceConstraintCount++;
        predicateEncodingMetrics.frontiers++;
        var latestSelf = latestSelfBefore(
                writes, observation.getTxn(), observation.getEventIndex());
        if (latestSelf != null) {
            if (recordedSource != null && recordedSource != latestSelf) {
                solver.assertTrue(Lit.False);
            }
            predicateEncodingMetrics.frontierCandidates++;
            return new KeyFrontier<>(key, observation.getTxn(),
                    List.of(new FrontierCandidate<>(latestSelf, Lit.True)),
                    latestSelf);
        }

        var externalWrites = latestExternalWrites(writes, observation.getTxn());
        var candidates = externalWrites.stream()
                .map(write -> new FrontierCandidate<>(write,
                        visibleToPredicateRead(write, observation)))
                .filter(candidate -> candidate.visible != Lit.False)
                .collect(Collectors.toList());
        predicateEncodingMetrics.frontierCandidates += candidates.size();
        var frontier = new KeyFrontier<>(
                key, observation.getTxn(), candidates, recordedSource);
        if (recordedSource == null) {
            encodeSelectedPredicateDependencies(
                    frontier, externalWrites,
                    observation.getPredicateReadEvent());
            return frontier;
        }

        var source = candidateFor(frontier, recordedSource);
        if (source == null) {
            solver.assertTrue(Lit.False);
            return frontier;
        }
        assertLatestVisible(
                frontier, source, externalWrites,
                observation.getPredicateReadEvent());
        return frontier;
    }

    private void encodeSelectedPredicateDependencies(
            KeyFrontier<KeyType, ValueType> frontier,
            List<KnownGraph.WriteRef<KeyType, ValueType>> externalWrites,
            Event<KeyType, ValueType> predicateRead) {
        for (var source : frontier.candidates) {
            var selectedGuard = selectionGuard(frontier, source);
            if (!isBottomTxn(source.write.getTxn())) {
                addDependencyEdge(new SIEdge<>(
                        source.write.getTxn(), frontier.reader,
                        EdgeType.PR_WR, frontier.key), selectedGuard);
            }
            for (var later : externalWrites) {
                if (later == source.write
                        || !writeChangesPredicateResult(
                                source.write, later, predicateRead)) {
                    continue;
                }
                addDependencyEdge(new SIEdge<>(
                        frontier.reader, later.getTxn(),
                        EdgeType.PR_RW, frontier.key),
                        and(selectedGuard,
                                beforeWrite(source.write, later)));
            }
        }
    }

    private void assertLatestVisible(
            KeyFrontier<KeyType, ValueType> frontier,
            FrontierCandidate<KeyType, ValueType> source,
            List<KnownGraph.WriteRef<KeyType, ValueType>> externalWrites,
            Event<KeyType, ValueType> predicateRead) {
        if (!isBottomTxn(source.write.getTxn())
                && !source.write.getTxn().equals(frontier.reader)) {
            addDependencyEdge(new SIEdge<>(
                    source.write.getTxn(), frontier.reader,
                    EdgeType.PR_WR, frontier.key), Lit.True);
        } else {
            solver.assertTrue(source.visible);
        }
        for (var other : externalWrites) {
            if (other == source.write) {
                continue;
            }
            if (!writeChangesPredicateResult(
                    source.write, other, predicateRead)) {
                continue;
            }
            var afterSource = beforeWrite(source.write, other);
            if (afterSource == Lit.False) {
                continue;
            }
            addDependencyEdge(new SIEdge<>(
                    frontier.reader, other.getTxn(),
                    EdgeType.PR_RW, frontier.key), afterSource);
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
        if (plan == null || plan.compactResultProjection() == null) {
            var resolver = relationResolverFor(predicateRead);
            var sourceContribution = rowContribution(
                    predicateRead, resolver, source);
            var laterContribution = rowContribution(
                    predicateRead, resolver, later);
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
        return evaluated.valid
                && evaluated.contribution.containsInput(event.getKey());
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
            predicateEncodingMetrics.compactCacheHits++;
            if (cache.invalid.get(writeRefId)) {
                return COMPACT_MATCH_INVALID;
            }
            return cache.matched.get(writeRefId)
                    ? COMPACT_MATCH_TRUE : COMPACT_MATCH_FALSE;
        }

        predicateEncodingMetrics.compactCacheMisses++;
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
            return evaluateRowContribution(
                    predicateRead, relationResolver, write, plan);
        }

        var planId = rowContributionPlanIds.computeIfAbsent(
                plan, ignored -> nextRowContributionPlanId++);
        var cacheKey = ((long) planId << 32) | (writeRefId & 0xffffffffL);
        var cached = generalRowContributions.get(cacheKey);
        if (cached != null) {
            predicateEncodingMetrics.rowContributionCacheHits++;
            return cached;
        }
        predicateEncodingMetrics.rowContributionCacheMisses++;
        var evaluated = evaluateRowContribution(
                predicateRead, relationResolver, write, plan);
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
                            new MapVisibleState<>(
                                    Map.of(event.getKey(), event.getValue()),
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

    private boolean refinePredicateConstraints() {
        var refined = false;
        for (var check : predicateChecks) {
            var snapshot = new LinkedHashMap<>(check.fixedSnapshot);
            var selected = new ArrayList<FrontierCandidate<KeyType, ValueType>>(
                    check.frontiers.size());
            for (var frontier : check.frontiers) {
                var candidate = selectedCandidate(frontier);
                selected.add(candidate);
                if (candidate == null) {
                    snapshot.remove(frontier.key);
                } else {
                    snapshot.put(frontier.key,
                            candidate.write.getEvent().getValue());
                }
            }
            if (predicateSnapshotMatches(
                    check.predicateRead, snapshot, check.relationResolver)) {
                continue;
            }

            var blockingClause = new ArrayList<Lit>();
            for (int i = 0; i < check.frontiers.size(); i++) {
                appendNegatedSelection(
                        check.frontiers.get(i), selected.get(i),
                        blockingClause);
            }
            if (blockingClause.isEmpty()) {
                solver.addClause(Lit.False);
            } else {
                solver.assertOr(blockingClause);
            }
            predicateEncodingMetrics.blockingClauses++;
            predicateEncodingMetrics.blockingClauseLiterals += blockingClause.size();
            refined = true;
        }
        return refined;
    }

    private Lit selectionGuard(
            KeyFrontier<KeyType, ValueType> frontier,
            FrontierCandidate<KeyType, ValueType> selected) {
        if (frontier.fixedWrite != null) {
            return Lit.True;
        }
        var terms = new ArrayList<Lit>();
        if (selected == null) {
            for (var candidate : frontier.candidates) {
                terms.add(Logic.not(candidate.visible));
            }
            return and(terms);
        }

        terms.add(selected.visible);
        for (var other : frontier.candidates) {
            if (other == selected) {
                continue;
            }
            terms.add(Logic.not(and(other.visible,
                    beforeWrite(selected.write, other.write))));
        }
        return and(terms);
    }

    private FrontierCandidate<KeyType, ValueType> selectedCandidate(
            KeyFrontier<KeyType, ValueType> frontier) {
        if (frontier.fixedWrite != null) {
            return candidateFor(frontier, frontier.fixedWrite);
        }
        FrontierCandidate<KeyType, ValueType> selected = null;
        for (var candidate : frontier.candidates) {
            if (!modelValue(candidate.visible)) {
                continue;
            }
            if (selected == null
                    || modelValue(beforeWrite(
                            selected.write, candidate.write))) {
                selected = candidate;
            }
        }
        return selected;
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

    private void appendNegatedSelection(
            KeyFrontier<KeyType, ValueType> frontier,
            FrontierCandidate<KeyType, ValueType> selected,
            List<Lit> blockingClause) {
        if (frontier.fixedWrite != null) {
            return;
        }
        if (selected == null) {
            for (var candidate : frontier.candidates) {
                if (!candidate.visible.isConstFalse()) {
                    blockingClause.add(candidate.visible);
                }
            }
            return;
        }

        blockingClause.add(Logic.not(selected.visible));
        for (var other : frontier.candidates) {
            if (other == selected) {
                continue;
            }
            var laterVisible = and(other.visible,
                    beforeWrite(selected.write, other.write));
            if (laterVisible != Lit.False && !laterVisible.isConstFalse()) {
                blockingClause.add(laterVisible);
            }
        }
    }

    private static boolean modelValue(Lit literal) {
        if (literal.isConstTrue()) {
            return true;
        }
        if (literal.isConstFalse()) {
            return false;
        }
        return literal.value();
    }

    private boolean predicateSnapshotMatches(
            Event<KeyType, ValueType> predicateRead,
            Map<KeyType, ValueType> snapshot,
            RelationResolver<KeyType> relationResolver) {
        final QueryEvaluation<KeyType, ValueType> evaluation;
        try {
            evaluation = predicateRead.getPredicate().evaluate(
                    new MapVisibleState<>(snapshot, relationResolver));
        } catch (QueryException exception) {
            return false;
        }

        var recorded = predicateRead.getRecordedPredicateResult();
        if (recorded != null) {
            return evaluation.canonicalEquals(recorded);
        }
        var expectedInputs = new LinkedHashMap<KeyType, ValueType>();
        for (var result : predicateRead.getPredResults()) {
            if (expectedInputs.putIfAbsent(
                    result.getKey(), result.getValue()) != null) {
                return false;
            }
        }
        return evaluation.inputs().equals(expectedInputs);
    }

    private RelationResolver<KeyType> relationResolverFor(
            Event<KeyType, ValueType> predicateRead) {
        var relations = predicateRead.getPredicate().scope().relations();
        if (relations.size() == 1) {
            return RelationResolver.fixed(relations.iterator().next());
        }
        @SuppressWarnings("unchecked")
        var resolver = (RelationResolver<KeyType>) RelationResolver.canonicalStringKeys();
        return resolver;
    }

    private Lit visibleToPredicateRead(
            KnownGraph.WriteRef<KeyType, ValueType> write,
            KnownGraph.PredicateObservation<KeyType, ValueType> observation) {
        if (isBottomTxn(write.getTxn())) {
            return Lit.True;
        }
        if (write.getTxn().equals(observation.getTxn())) {
            return write.getIndex() < observation.getEventIndex() ? Lit.True : Lit.False;
        }
        return depGraph.reaches(depNodes.get(write.getTxn()), depNodes.get(observation.getTxn()));
    }

    private Lit beforeWrite(
            KnownGraph.WriteRef<KeyType, ValueType> left,
            KnownGraph.WriteRef<KeyType, ValueType> right) {
        predicateEncodingMetrics.beforeWriteCalls++;
        if (left == right) {
            return Lit.False;
        }
        if (left.getTxn().equals(right.getTxn())) {
            return left.getIndex() < right.getIndex() ? Lit.True : Lit.False;
        }
        if (isBottomTxn(left.getTxn())) {
            return Lit.True;
        }
        if (isBottomTxn(right.getTxn())) {
            return Lit.False;
        }

        var key = left.getEvent().getKey();
        return wwOrderLiteral(left.getTxn(), right.getTxn(), key);
    }

    private Lit wwOrderLiteral(
            Transaction<KeyType, ValueType> from,
            Transaction<KeyType, ValueType> to,
            KeyType key) {
        if (from.equals(to)) {
            return Lit.False;
        }
        if (isBottomTxn(from)) {
            return Lit.True;
        }
        if (isBottomTxn(to)) {
            return Lit.False;
        }

        var direct = wwOrder.get(Triple.of(from, to, key));
        if (direct != null) {
            return direct;
        }
        var reverse = wwOrder.get(Triple.of(to, from, key));
        if (reverse != null) {
            return Logic.not(reverse);
        }
        if (hasKnownWw(from, to, key)) {
            return Lit.True;
        }
        if (hasKnownWw(to, from, key)) {
            return Lit.False;
        }

        solver.assertTrue(Lit.False);
        return Lit.False;
    }

    private boolean hasKnownWw(
            Transaction<KeyType, ValueType> from,
            Transaction<KeyType, ValueType> to,
            KeyType key) {
        return graph.getKnownGraphA().edgeValue(from, to).orElse(List.of()).stream()
                .anyMatch(edge -> edge.getType() == EdgeType.WW && Objects.equals(edge.getKey(), key));
    }

    /**
     * Routes every typed dependency through the SI A/B partition and updates
     * the actual MonoSAT induced graph used for the verdict.
     */
    private boolean addDependencyEdge(
            SIEdge<KeyType, ValueType> edge, Lit guard) {
        if (!guardCanHold(edge.getFrom(), edge.getTo(), guard)) {
            predicateEncodingMetrics.dependencyEdgesSkipped++;
            return false;
        }
        predicateEncodingMetrics.dependencyEdgeAttempts++;
        if (edge.getType() == EdgeType.PR_WR || edge.getType() == EdgeType.PR_RW) {
            predicateDependencyCandidates.add(
                    new GuardedDependencyEdge<>(edge, guard));
            return true;
        }
        return materializeDependencyEdge(edge, guard);
    }

    private boolean materializeDependencyEdge(
            SIEdge<KeyType, ValueType> edge, Lit guard) {
        if (!guardedEdgesByGuard.computeIfAbsent(
                guard, ignored -> new HashSet<>()).add(edge)) {
            predicateEncodingMetrics.dependencyEdgeDuplicates++;
            return false;
        }

        var guarded = new GuardedDependencyEdge<>(edge, guard);
        if (isDependencyEdgeA(edge.getType())) {
            dependencyEdgesA.add(guarded);
            bindGraphEdge(depGraph,
                    physicalDepEdges, physicalDepEdgeGuards,
                    depNodes.get(edge.getFrom()), depNodes.get(edge.getTo()), guard);
            bindGraphEdge(inducedGraph,
                    physicalInducedEdges, physicalInducedEdgeGuards,
                    inducedNodes.get(edge.getFrom()), inducedNodes.get(edge.getTo()), guard);
            for (var dependencyB : dependencyEdgesB) {
                if (edge.getTo().equals(dependencyB.edge.getFrom())) {
                    addInducedEdge(edge.getFrom(), dependencyB.edge.getTo(),
                            and(guard, dependencyB.guard));
                }
            }
        } else if (isDependencyEdgeB(edge.getType())) {
            dependencyEdgesB.add(guarded);
            for (var dependencyA : dependencyEdgesA) {
                if (dependencyA.edge.getTo().equals(edge.getFrom())) {
                    addInducedEdge(dependencyA.edge.getFrom(), edge.getTo(),
                            and(dependencyA.guard, guard));
                }
            }
        } else {
            throw new IllegalArgumentException(
                    "Unsupported SI dependency edge type: " + edge.getType());
        }

        predicateEncodingMetrics.dependencyEdgesQueued++;
        return true;
    }

    private void addInducedEdge(
            Transaction<KeyType, ValueType> from,
            Transaction<KeyType, ValueType> to,
            Lit guard) {
        if (guard == Lit.False) {
            return;
        }
        if (from.equals(to)) {
            solver.assertTrue(Logic.not(guard));
            return;
        }
        var edge = Pair.of(from, to);
        if (!inducedEdgesByGuard.computeIfAbsent(
                guard, ignored -> new HashSet<>()).add(edge)) {
            predicateEncodingMetrics.inducedEdgeDuplicates++;
            return;
        }
        predicateEncodingMetrics.inducedEdgesQueued++;
        bindGraphEdge(inducedGraph, physicalInducedEdges,
                physicalInducedEdgeGuards,
                inducedNodes.get(from), inducedNodes.get(to), guard);
    }

    private boolean guardCanHold(
            Transaction<KeyType, ValueType> from,
            Transaction<KeyType, ValueType> to,
            Lit guard) {
        if (guard == Lit.False) {
            return false;
        }
        if (isBottomTxn(from)) {
            return false;
        }
        if (from.equals(to) || isBottomTxn(to)) {
            solver.assertTrue(Logic.not(guard));
            return false;
        }
        return true;
    }

    private void bindGraphEdge(Graph targetGraph,
            Map<Pair<Integer, Integer>, Lit> internedEdges,
            Map<Pair<Integer, Integer>, List<Lit>> guardsByEdge,
            int from, int to, Lit guard) {
        if (guard == Lit.False) {
            return;
        }
        Lit edge;
        if (graphEdgeInterning) {
            var endpoints = Pair.of(from, to);
            edge = internedEdges.computeIfAbsent(
                    endpoints, ignored -> targetGraph.addEdge(from, to));
            guardsByEdge.computeIfAbsent(
                    endpoints, ignored -> new ArrayList<>()).add(guard);
        } else {
            edge = targetGraph.addEdge(from, to);
        }
        if (guard == Lit.True) {
            solver.assertTrue(edge);
            return;
        }
        solver.assertTrue(Logic.implies(guard, edge));
        if (!graphEdgeInterning) {
            solver.assertTrue(Logic.implies(edge, guard));
        }
    }

    /**
     * SI uses depGraph reachability as a semantic visibility predicate, so an
     * interned physical edge must be equivalent to the disjunction of all
     * logical guards that support it. A one-way guard-to-edge implication would
     * allow unsupported reachability in a SAT model.
     */
    private void sealInternedGraphEdges() {
        if (!graphEdgeInterning) {
            return;
        }
        sealInternedGraphEdges(physicalDepEdges, physicalDepEdgeGuards);
        sealInternedGraphEdges(physicalInducedEdges, physicalInducedEdgeGuards);
    }

    private void sealInternedGraphEdges(
            Map<Pair<Integer, Integer>, Lit> edges,
            Map<Pair<Integer, Integer>, List<Lit>> guardsByEdge) {
        for (var entry : edges.entrySet()) {
            var support = or(guardsByEdge.getOrDefault(
                    entry.getKey(), Collections.emptyList()));
            solver.assertTrue(Logic.implies(entry.getValue(), support));
        }
    }

    private void publishResidualSatStats() {
        var profiler = Profiler.getInstance();
        profiler.addCount("SI_PROP_WW_CHOICE_VARIABLES_COUNT",
                residualWwChoiceVariables);
        profiler.addCount("SI_PROP_WW_CHOICE_CONSTRAINTS_COUNT",
                residualWwChoiceConstraints);
        profiler.addCount("SI_PROP_RESIDUAL_SAT_VARIABLES_COUNT", solver.nVars());
        profiler.addCount("SI_PROP_RESIDUAL_SAT_CONSTRAINTS_COUNT", solver.nClauses());
        profiler.addCount("SI_DEP_PHYSICAL_EDGES_COUNT",
                graphEdgeInterning ? physicalDepEdges.size() : dependencyEdgesA.size());
        profiler.addCount("SI_INDUCED_PHYSICAL_EDGES_COUNT",
                graphEdgeInterning ? physicalInducedEdges.size()
                        : predicateEncodingMetrics.inducedEdgesQueued
                                + dependencyEdgesA.size());
    }

    private Map<KeyType, List<KnownGraph.WriteRef<KeyType, ValueType>>> buildWritesByKey(
            KnownGraph<KeyType, ValueType> graph) {
        var result = new HashMap<KeyType, List<KnownGraph.WriteRef<KeyType, ValueType>>>();
        for (var write : graph.getAllWrites()) {
            result.computeIfAbsent(write.getEvent().getKey(), ignored -> new ArrayList<>()).add(write);
        }
        for (var writes : result.values()) {
            writes.sort(Comparator
                    .comparing((KnownGraph.WriteRef<KeyType, ValueType> write) -> !isBottomTxn(write.getTxn()))
                    .thenComparing(write -> write.getTxn().getId())
                    .thenComparingInt(KnownGraph.WriteRef::getIndex));
        }
        return result;
    }

    private static boolean isDependencyEdgeA(EdgeType type) {
        return type == EdgeType.SO
                || type == EdgeType.WR
                || type == EdgeType.WW
                || type == EdgeType.PR_WR;
    }

    private static boolean isDependencyEdgeB(EdgeType type) {
        return type == EdgeType.RW
                || type == EdgeType.PR_RW;
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

    private static Lit or(Collection<Lit> terms) {
        var filtered = new ArrayList<Lit>(terms.size());
        for (var term : terms) {
            if (term == Lit.True) {
                return Lit.True;
            }
            if (term != Lit.False) {
                filtered.add(term);
            }
        }
        if (filtered.isEmpty()) {
            return Lit.False;
        }
        if (filtered.size() == 1) {
            return filtered.get(0);
        }
        return Logic.or(filtered);
    }

    /** Shrinks the unresolved WW choices while preserving SI UNSAT. */
    private void extractConflicts() {
        if (!isSatisfiable(List.of())) {
            conflictEdges = SIVerifier.InducedGraph.extractCycleEdges(graph);
            conflictConstraints = Collections.emptyList();
            return;
        }

        var coreConstraints = new ArrayList<>(constraints);
        for (int i = 0; i < coreConstraints.size();) {
            var candidate = new ArrayList<>(coreConstraints);
            candidate.remove(i);
            if (!isSatisfiable(candidate)) {
                coreConstraints = candidate;
            } else {
                i++;
            }
        }

        conflictConstraints = coreConstraints;
        conflictEdges = supportingKnownEdges(coreConstraints);
    }

    private boolean isSatisfiable(
            Collection<SIConstraint<KeyType, ValueType>> activeConstraints) {
        return new SISolverInduced<>(
                history, graph, activeConstraints, false, false,
                conflictExtractionSettings()).solveStatus() == SolveStatus.SAT;
    }

    private SIVerifier.SolverSettings conflictExtractionSettings() {
        var copy = SIVerifier.SolverSettings.defaults(settings.pruningMode);
        copy.predicateWitnessCoalescing = predicateWitnessCoalescing;
        copy.graphEdgeInterning = graphEdgeInterning;
        copy.solverTimeoutSeconds = 0;
        copy.detailedPredicateMetrics = false;
        copy.satSolveBackend = settings.satSolveBackend;
        return copy;
    }

    private Collection<Pair<EndpointPair<Transaction<KeyType, ValueType>>, Collection<Edge<KeyType>>>>
            supportingKnownEdges(
                    Collection<SIConstraint<KeyType, ValueType>> coreConstraints) {
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
            if (!txnsInCore.contains(ep.source())
                    || !txnsInCore.contains(ep.target())) {
                continue;
            }
            var edges = known.edgeValue(ep).orElse(List.of()).stream()
                    .filter(edge -> isEncodedKnownEdge(edge.getType()))
                    .collect(Collectors.toList());
            if (!edges.isEmpty()) {
                out.add(Pair.of(
                        EndpointPair.ordered(ep.source(), ep.target()), edges));
            }
        }
    }

    private static boolean isEncodedKnownEdge(EdgeType type) {
        return type == EdgeType.SO || type == EdgeType.WR
                || type == EdgeType.WW || type == EdgeType.RW
                || type == EdgeType.PR_WR || type == EdgeType.PR_RW;
    }

    private static boolean isBottomTxn(Transaction<?, ?> txn) {
        return txn.getId() == -1L
                && txn.getSession() != null
                && txn.getSession().getId() == -1L;
    }

    private static final class CompactRowMatchCache {
        private final BitSet computed = new BitSet();
        private final BitSet matched = new BitSet();
        private final BitSet invalid = new BitSet();
    }

    private static final class CachedRowContribution<KeyType> {
        private final boolean valid;
        private final RowContribution<KeyType> contribution;

        private CachedRowContribution(
                boolean valid, RowContribution<KeyType> contribution) {
            this.valid = valid;
            this.contribution = contribution;
        }

        private static <KeyType> CachedRowContribution<KeyType> valid(
                RowContribution<KeyType> contribution) {
            return new CachedRowContribution<>(true, contribution);
        }

        private static <KeyType> CachedRowContribution<KeyType> invalid() {
            return new CachedRowContribution<>(false, null);
        }
    }

    private static final class PredicateEncodingMetrics {
        private long sourceIndexNanos;
        private long scopeLookupNanos;
        private long snapshotValidationNanos;
        private long rowLocalKeyScanNanos;
        private long generalKeyScanNanos;
        private long observations;
        private long nullPredicates;
        private long resultSources;
        private long duplicateResultSources;
        private long scopedKeys;
        private long rowLocalAttempts;
        private long rowLocalEncoded;
        private long rowLocalFallbacks;
        private long generalObservations;
        private long generalExternalKeys;
        private long rowLocalKeyVisits;
        private long internalKeys;
        private long externalKeys;
        private long recordedSourceKeys;
        private long latestWriterLookups;
        private long latestWriterInputWrites;
        private long latestWriterResults;
        private long frontiers;
        private long frontierCandidates;
        private long badWrites;
        private long beforeWriteCalls;
        private long compactCacheHits;
        private long compactCacheMisses;
        private long rowContributionCacheHits;
        private long rowContributionCacheMisses;
        private long dependencyEdgeAttempts;
        private long dependencyEdgeDuplicates;
        private long dependencyEdgesSkipped;
        private long dependencyEdgesQueued;
        private long dependencyEdgeCandidates;
        private long dependencyPhysicalEdges;
        private long dependencyEdgesCoalesced;
        private long inducedEdgeDuplicates;
        private long inducedEdgesQueued;
        private long blockingClauses;
        private long blockingClauseLiterals;

        private void publish(Profiler profiler, boolean includeCounts) {
            profiler.addDurationNanos("SI_PRED_SOURCE_INDEX", sourceIndexNanos);
            profiler.addDurationNanos("SI_PRED_SCOPE_LOOKUP", scopeLookupNanos);
            profiler.addDurationNanos(
                    "SI_PRED_SNAPSHOT_VALIDATE", snapshotValidationNanos);
            profiler.addDurationNanos(
                    "SI_PRED_ROW_LOCAL_KEY_SCAN", rowLocalKeyScanNanos);
            profiler.addDurationNanos(
                    "SI_PRED_GENERAL_KEY_SCAN", generalKeyScanNanos);
            if (!includeCounts) {
                return;
            }
            profiler.addCount("SI_PRED_OBSERVATIONS_COUNT", observations);
            profiler.addCount("SI_PRED_NULL_COUNT", nullPredicates);
            profiler.addCount("SI_PRED_RESULT_SOURCES_COUNT", resultSources);
            profiler.addCount("SI_PRED_DUPLICATE_SOURCES_COUNT", duplicateResultSources);
            profiler.addCount("SI_PRED_SCOPED_KEYS_COUNT", scopedKeys);
            profiler.addCount("SI_PRED_ROW_LOCAL_ATTEMPTS_COUNT", rowLocalAttempts);
            profiler.addCount("SI_PRED_ROW_LOCAL_ENCODED_COUNT", rowLocalEncoded);
            profiler.addCount("SI_PRED_ROW_LOCAL_FALLBACKS_COUNT", rowLocalFallbacks);
            profiler.addCount("SI_PRED_GENERAL_COUNT", generalObservations);
            profiler.addCount("SI_PRED_GENERAL_EXTERNAL_KEYS_COUNT", generalExternalKeys);
            profiler.addCount("SI_PRED_ROW_LOCAL_KEY_VISITS_COUNT", rowLocalKeyVisits);
            profiler.addCount("SI_PRED_INTERNAL_KEYS_COUNT", internalKeys);
            profiler.addCount("SI_PRED_EXTERNAL_KEYS_COUNT", externalKeys);
            profiler.addCount("SI_PRED_RECORDED_SOURCE_KEYS_COUNT", recordedSourceKeys);
            profiler.addCount("SI_PRED_LATEST_WRITER_LOOKUPS_COUNT", latestWriterLookups);
            profiler.addCount("SI_PRED_LATEST_WRITER_INPUT_WRITES_COUNT",
                    latestWriterInputWrites);
            profiler.addCount("SI_PRED_LATEST_WRITER_RESULTS_COUNT", latestWriterResults);
            profiler.addCount("SI_PRED_FRONTIERS_COUNT", frontiers);
            profiler.addCount("SI_PRED_FRONTIER_CANDIDATES_COUNT", frontierCandidates);
            profiler.addCount("SI_PRED_BAD_WRITES_COUNT", badWrites);
            profiler.addCount("SI_PRED_BEFORE_WRITE_CALLS_COUNT", beforeWriteCalls);
            profiler.addCount("SI_PRED_COMPACT_CACHE_HITS_COUNT", compactCacheHits);
            profiler.addCount("SI_PRED_COMPACT_CACHE_MISSES_COUNT", compactCacheMisses);
            profiler.addCount("SI_PRED_ROW_CACHE_HITS_COUNT", rowContributionCacheHits);
            profiler.addCount("SI_PRED_ROW_CACHE_MISSES_COUNT", rowContributionCacheMisses);
            profiler.addCount("SI_PRED_DEPENDENCY_ATTEMPTS_COUNT", dependencyEdgeAttempts);
            profiler.addCount("SI_PRED_DEPENDENCY_DUPLICATES_COUNT", dependencyEdgeDuplicates);
            profiler.addCount("SI_PRED_DEPENDENCY_SKIPPED_COUNT", dependencyEdgesSkipped);
            profiler.addCount("SI_PRED_DEPENDENCY_QUEUED_COUNT", dependencyEdgesQueued);
            profiler.addCount("SI_PRED_DEPENDENCY_CANDIDATES_COUNT",
                    dependencyEdgeCandidates);
            profiler.addCount("SI_PRED_DEPENDENCY_PHYSICAL_COUNT",
                    dependencyPhysicalEdges);
            profiler.addCount("SI_PRED_DEPENDENCY_COALESCED_COUNT",
                    dependencyEdgesCoalesced);
            profiler.addCount("SI_PRED_INDUCED_DUPLICATES_COUNT", inducedEdgeDuplicates);
            profiler.addCount("SI_PRED_INDUCED_QUEUED_COUNT", inducedEdgesQueued);
            profiler.addCount("SI_PRED_BLOCKING_CLAUSES_COUNT", blockingClauses);
            profiler.addCount("SI_PRED_BLOCKING_LITERALS_COUNT", blockingClauseLiterals);
        }
    }

    private static final class KeyFrontier<KeyType, ValueType> {
        private final KeyType key;
        private final Transaction<KeyType, ValueType> reader;
        private final List<FrontierCandidate<KeyType, ValueType>> candidates;
        private final KnownGraph.WriteRef<KeyType, ValueType> fixedWrite;

        private KeyFrontier(
                KeyType key,
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

        private FrontierCandidate(
                KnownGraph.WriteRef<KeyType, ValueType> write, Lit visible) {
            this.write = write;
            this.visible = visible;
        }
    }

    private static final class PredicateCheck<KeyType, ValueType> {
        private final Event<KeyType, ValueType> predicateRead;
        private final List<KeyFrontier<KeyType, ValueType>> frontiers;
        private final Map<KeyType, ValueType> fixedSnapshot;
        private final RelationResolver<KeyType> relationResolver;

        private PredicateCheck(
                Event<KeyType, ValueType> predicateRead,
                List<KeyFrontier<KeyType, ValueType>> frontiers,
                Map<KeyType, ValueType> fixedSnapshot,
                RelationResolver<KeyType> relationResolver) {
            this.predicateRead = predicateRead;
            this.frontiers = List.copyOf(frontiers);
            this.fixedSnapshot = Collections.unmodifiableMap(
                    new LinkedHashMap<>(fixedSnapshot));
            this.relationResolver = relationResolver;
        }
    }

    private static class GuardedDependencyEdge<KeyType, ValueType> {
        private final SIEdge<KeyType, ValueType> edge;
        private final Lit guard;

        private GuardedDependencyEdge(
                SIEdge<KeyType, ValueType> edge, Lit guard) {
            this.edge = edge;
            this.guard = guard;
        }
    }

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
        private final SIEdge<KeyType, ValueType> edge;
        private Lit guard;

        private CoalescedPredicateDependency(
                SIEdge<KeyType, ValueType> edge, Lit guard) {
            this.edge = edge;
            this.guard = guard;
        }

        private void merge(SIEdge<KeyType, ValueType> witness, Lit witnessGuard) {
            for (var key : witness.getKeys()) {
                edge.addKey(key);
            }
            guard = or(guard, witnessGuard);
        }
    }
}
