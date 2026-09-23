package verifier;

import com.google.common.graph.EndpointPair;
import graph.Edge;
import graph.EdgeType;
import graph.KnownGraph;
import history.Event;
import history.History;
import history.Transaction;
import history.query.QueryException;
import history.query.QueryPlan;
import history.query.RelationResolver;
import history.query.RowVersion;
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
    private final History<KeyType, ValueType> history;
    private final KnownGraph<KeyType, ValueType> graph;
    private final Collection<SIConstraint<KeyType, ValueType>> constraints;
    private final SIVerifier.SolverSettings settings;
    private final boolean collectConflicts;
    private final boolean predicateWitnessCoalescing;
    private final boolean graphEdgeInterning;
    private final Solver solver;
    private final PredicateAnalysis<KeyType, ValueType> analysis;
    private final PredicatePruning.Result<KeyType, ValueType> prepared;
    private final List<Lit> assumptionLiterals = new ArrayList<>();
    private final Map<Lit, AssumptionReason<KeyType, ValueType>> assumptionReasons = new HashMap<>();
    private List<AssumptionReason<KeyType, ValueType>> conflictReasons = List.of();
    private Lit activePredicateGuard = Lit.True;
    private Lit propagationGuard = Lit.True;
    private final Graph inducedGraph;
    private final LatestVisibleChecker<KeyType, ValueType> latestVisibleChecker =
            new LatestVisibleChecker<>();
    private final Map<Transaction<KeyType, ValueType>, Integer> inducedNodes = new HashMap<>();
    private final Map<Pair<Transaction<KeyType, ValueType>, Transaction<KeyType, ValueType>>, Lit>
            visibilityChoices = new HashMap<>();
    private final List<GuardedDependencyEdge<KeyType, ValueType>> dependencyEdgesA =
            new ArrayList<>();
    private final List<GuardedDependencyEdge<KeyType, ValueType>> dependencyEdgesB =
            new ArrayList<>();
    private final Map<Transaction<KeyType, ValueType>, List<OrderingSupport<KeyType, ValueType>>>
            incomingDependencyA = new HashMap<>();
    private final Map<Transaction<KeyType, ValueType>, List<OrderingSupport<KeyType, ValueType>>>
            outgoingDependencyB = new HashMap<>();
    private final Map<Lit, Set<SIEdge<KeyType, ValueType>>> guardedEdgesByGuard =
            new IdentityHashMap<>();
    private final Map<Lit, Set<DirectedEdgeKey>> inducedEdgesByGuard =
            new IdentityHashMap<>();
    private final List<GuardedDependencyEdge<KeyType, ValueType>>
            predicateDependencyCandidates = new ArrayList<>();
    private final Map<DirectedEdgeKey, List<Lit>> inducedEdgeSupports =
            new HashMap<>();
    private final Map<Triple<Transaction<KeyType, ValueType>, Transaction<KeyType, ValueType>, KeyType>, Lit> wwOrder =
            new HashMap<>();
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
            Collection<SIConstraint<KeyType, ValueType>> constraints,
            boolean collectConflicts,
            boolean collectPredicateMetrics,
            SIVerifier.SolverSettings solverSettings,
            SIReachabilityOracle<KeyType, ValueType> vis,
            PredicatePruning.Result<KeyType, ValueType> prepared) {
        this.prepared = Objects.requireNonNull(prepared, "prepared");
        if (prepared.oracle() != vis || prepared.analysis().graph() != graph) {
            throw new IllegalArgumentException("SI 编码与谓词剪枝必须共享同一图和 Oracle");
        }
        if (prepared.hasConflict()) {
            throw new IllegalArgumentException("有确定性谓词冲突的结果不能创建 SAT 求解器");
        }
        this.analysis = prepared.analysis();
        this.history = history;
        this.graph = graph;
        this.constraints = constraints;
        this.settings = solverSettings == null
                ? SIVerifier.SolverSettings.defaults()
                : solverSettings;
        this.predicateWitnessCoalescing = this.settings.predicateWitnessCoalescing;
        this.graphEdgeInterning = this.settings.graphEdgeInterning;
        this.collectConflicts = collectConflicts;
        this.collectPredicateMetrics = collectPredicateMetrics;
        this.solver = new Solver();
        Profiler.getInstance().addCount("SI_NATIVE_SOLVER_CREATIONS_COUNT", 1);
        this.inducedGraph = new Graph(solver);
        var profiler = Profiler.getInstance();
        profileVoid(profiler, "SI_GRAPH_ENCODE_SETUP", this::createNodes);
        profileVoid(profiler, "SI_GRAPH_ENCODE_KNOWN_EDGES", this::encodeKnownEdges);
        profileVoid(profiler, "SI_GRAPH_ENCODE_WW", this::encodeWwChoices);
        profileVoid(profiler, "SI_GRAPH_ENCODE_RW", this::encodeRwFromWrAndWw);
        encodePropagationFacts();
        profileVoid(profiler, "SI_GRAPH_ENCODE_PREDICATE", this::encodePredicateConstraints);
        profileVoid(profiler, "SI_GRAPH_ENCODE_DEPENDENCIES",
                () -> {
                    encodePredicateDependencies();
                    encodeFactorizedInducedGraph();
                    materializeReducedInducedGraph();
                });
        profileVoid(profiler, "SI_GRAPH_ENCODE_ACYCLIC",
                () -> solver.assertTrue(inducedGraph.acyclic()));
        publishResidualSatStats();
        predicateEncodingMetrics.publish(profiler, collectPredicateMetrics);
        analysis.publishMetrics(profiler, collectPredicateMetrics);
    }

    boolean solve() {
        return solveStatus() == SolveStatus.SAT;
    }

    SolveStatus solveStatus() {
        conflictEdges = Collections.emptyList();
        conflictConstraints = Collections.emptyList();
        conflictReasons = List.of();
        var profiler = Profiler.getInstance();
        boolean satisfiable = profileBoolean(profiler, "SI_MONOSAT_SOLVE", this::solveOnce);
        profiler.addCount("SI_PROP_MONOSAT_PROPAGATIONS_COUNT", solver.nPropagations());
        profiler.addCount("SI_PROP_MONOSAT_CONFLICTS_COUNT", solver.nConflicts());
        if (satisfiable) {
            return SolveStatus.SAT;
        }
        if (!collectConflicts) {
            conflictEdges = Collections.emptyList();
            conflictConstraints = Collections.emptyList();
            return SolveStatus.UNSAT;
        }
        profileVoid(profiler, "SI_GRAPH_CONFLICT_EXTRACTION", this::extractConflicts);
        return SolveStatus.UNSAT;
    }

    private boolean solveOnce() {
        return settings.satSolveBackend == null
                ? solver.solve(assumptionLiterals)
                : settings.satSolveBackend.solve(solver, assumptionLiterals);
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
            inducedNodes.put(txn, inducedGraph.addNode());
        }
    }

    private void encodePropagationFacts() {
        for (var fact : prepared.definiteFacts()) {
            var kind = fact.kind == SiGmwrPropagationState.FactKind.PR_WR
                    ? AssumptionKind.PREDICATE_OBLIGATION : AssumptionKind.GMWR_RULE;
            var reason = newAssumption(kind, fact.toString(), null);
            propagationGuard = and(propagationGuard, reason.literal);
            if (fact.kind == SiGmwrPropagationState.FactKind.PR_WR) {
                addDependencyEdge(new SIEdge<>(fact.from, fact.to, EdgeType.PR_WR, fact.key),
                        reason.literal);
                continue;
            }
            Lit required;
            if (fact.kind == SiGmwrPropagationState.FactKind.WW) {
                required = wwOrderLiteral(fact.from, fact.to, fact.key);
            } else {
                required = visibilityLiteral(fact.from, fact.to);
                if (fact.kind == SiGmwrPropagationState.FactKind.NOT_VIS) {
                    required = Logic.not(required);
                }
            }
            solver.assertImplies(reason.literal, required);
        }
    }

    private Lit visibilityLiteral(Transaction<KeyType, ValueType> writer,
            Transaction<KeyType, ValueType> reader) {
        if (isBottomTxn(writer)) {
            return Lit.True;
        }
        if (writer.equals(reader) || isBottomTxn(reader)) {
            return Lit.False;
        }
        return visibilityChoices.computeIfAbsent(Pair.of(writer, reader), ignored -> {
            var visible = new Lit(solver);
            // 事务对共用的结构约束不依赖首次访问它的查询 assumption。
            addOrderingSupport(writer, reader, visible, true);
            addOrderingSupport(reader, writer, Logic.not(visible), false);
            return visible;
        });
    }

    private void assertPredicate(Lit condition) {
        solver.assertImplies(activePredicateGuard, condition);
    }

    private AssumptionReason<KeyType, ValueType> newAssumption(
            AssumptionKind kind, String reason, SIConstraint<KeyType, ValueType> wwConstraint) {
        var literal = new Lit(solver);
        var result = new AssumptionReason<KeyType, ValueType>(
                assumptionLiterals.size() + 1, kind, reason, literal, wwConstraint);
        assumptionLiterals.add(literal);
        assumptionReasons.put(literal, result);
        return result;
    }

    enum AssumptionKind { WW_CHOICE, PREDICATE_OBLIGATION, GMWR_RULE }

    @lombok.AllArgsConstructor
    static final class AssumptionReason<K, V> {
        private final long id;
        private final AssumptionKind kind;
        private final String reason;
        private final Lit literal;
        private final SIConstraint<K, V> wwConstraint;

        String assumptionId() { return "A" + id; }
        AssumptionKind getKind() { return kind; }
        String getReason() { return reason; }
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
            var assumption = newAssumption(AssumptionKind.WW_CHOICE,
                    "WW choice between " + constraint.getWriteTransaction1()
                            + " and " + constraint.getWriteTransaction2(), constraint);
            addConstraintSide(constraint.getEdges1(), and(assumption.literal, forward));
            addConstraintSide(constraint.getEdges2(), and(assumption.literal, Logic.not(forward)));
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
                for (var writer : analysis.getWritesByKey().getOrDefault(key, Collections.emptyList())) {
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
        for (var observation : prepared.observations()) {
            var predicateRead = observation.observation.getPredicateReadEvent();
            predicateEncodingMetrics.observations++;
            var assumption = newAssumption(AssumptionKind.PREDICATE_OBLIGATION,
                    "predicate at " + observation.observation.getTxn()
                            + " event=" + observation.observation.getEventIndex(), null);
            activePredicateGuard = and(assumption.literal, propagationGuard);
            if (predicateRead.getPredicate().isRowLocal()) {
                encodeRowLocalPredicate(observation);
                predicateEncodingMetrics.rowLocalEncoded++;
            } else {
                @SuppressWarnings("unchecked")
                var plan = (QueryPlan<KeyType, ValueType>) predicateRead.getPredicate();
                encodeExplicitMultiRelationPredicate(observation.observation,
                        observation.scopedEntries, observation.sources, plan);
            }
        }
        activePredicateGuard = Lit.True;
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
            for (var guard : dependency.guards) {
                materializeDependencyEdge(dependency.edge, guard);
            }
        }
        predicateEncodingMetrics.dependencyEdgeCandidates += candidateCount;
        predicateEncodingMetrics.dependencyPhysicalEdges += coalesced.size();
        predicateEncodingMetrics.dependencyEdgesCoalesced +=
                candidateCount - coalesced.size();
        if (collectPredicateMetrics) System.err.printf(
                "Predicate dependency coalescing: %d -> %d typed edges, coalesced=%d%n",
                candidateCount, coalesced.size(), candidateCount - coalesced.size());
        predicateDependencyCandidates.clear();
    }

    /** 行贡献、INTERNAL 与来源域已由前置阶段校验，此处只建立字面量。 */
    private void encodeRowLocalPredicate(
            PredicatePruning.PreparedObservation<KeyType, ValueType> observation) {
        var raw = observation.observation;
        var predicateRead = raw.getPredicateReadEvent();
        for (var key : observation.keys) {
            predicateEncodingMetrics.rowLocalKeyVisits++;
            if (key.internal) {
                predicateEncodingMetrics.internalKeys++;
                continue;
            }
            predicateEncodingMetrics.externalKeys++;
            var fixedSource = key.recordedSource != null ? key.recordedSource
                    : key.sourceCandidates.size() == 1 && !key.implicitBottomPossible
                            ? key.sourceCandidates.get(0) : null;
            if (fixedSource != null) {
                predicateSourceConstraintCount++;
                predicateEncodingMetrics.frontiers++;
                predicateEncodingMetrics.frontierCandidates++;
                if (key.recordedSource != null) {
                    predicateEncodingMetrics.recordedSourceKeys++;
                }
                assertFixedSourceLatest(raw, fixedSource, key.allExternalWrites);
                encodeFixedSourceDependencies(raw.getTxn(), key.key, fixedSource,
                        key.allExternalWrites, predicateRead);
                continue;
            }
            var candidateDomain = settings.predicateMode == SIVerifier.PredicateMode.EAGER
                    ? key.allExternalWrites : key.sourceCandidates;
            var allCandidates = latestCandidates(raw, key.key, candidateDomain, key.allExternalWrites);
            var candidates = allCandidates.stream()
                    .filter(candidate -> key.sourceCandidates.contains(candidate.write))
                    .collect(Collectors.toList());
            predicateSourceConstraintCount++;
            predicateEncodingMetrics.frontiers++;
            predicateEncodingMetrics.frontierCandidates +=
                    settings.predicateMode == SIVerifier.PredicateMode.EAGER
                            ? allCandidates.size() : candidates.size();
            var frontier = new KeyFrontier<>(key.key, raw.getTxn(), candidates, key.recordedSource);
            var choices = candidates.stream().map(candidate -> candidate.latest)
                    .collect(Collectors.toCollection(ArrayList::new));
            if (key.implicitBottomPossible) {
                choices.add(and(key.allExternalWrites.stream()
                        .map(write -> Logic.not(visibleToPredicateRead(write, raw)))
                        .collect(Collectors.toList())));
            }
            solver.assertImpliesOr(activePredicateGuard, choices);
            encodeSelectedPredicateDependencies(frontier, key.allExternalWrites, predicateRead);
            predicateEncodingMetrics.badWrites += key.badWrites.size();
            if (settings.predicateMode == SIVerifier.PredicateMode.EAGER) {
                for (var candidate : allCandidates) {
                    if (key.badWrites.contains(candidate.write)) {
                        assertPredicate(Logic.not(candidate.latest));
                        predicateEncodingMetrics.resultExclusionClauses++;
                        predicateEncodingMetrics.resultExclusionLiterals++;
                    }
                }
            } else {
                predicateEncodingMetrics.gmwrKeys++;
                predicateEncodingMetrics.gmwrSourceCandidates += candidates.size();
            }
        }
        if (settings.predicateMode == SIVerifier.PredicateMode.GMWR) {
            for (var item : prepared.residualItems()) {
                if (item.observation == raw) {
                    encodeGmwrBadWriterObligation(item);
                }
            }
        }
    }

    private void encodeGmwrBadWriterObligation(
            PredicatePruning.ResidualItem<KeyType, ValueType> item) {
        var rule = newAssumption(AssumptionKind.GMWR_RULE,
                "repair obligation reader=" + item.observation.getTxn()
                        + " bad=" + item.bad.getTxn() + " key=" + item.key, null);
        var alternatives = new ArrayList<Lit>(item.repairs.size() + 1);
        if (item.outsidePossible) {
            alternatives.add(Logic.not(visibleToPredicateRead(item.bad, item.observation)));
        }
        int repairs = 0;
        for (var repair : item.repairs) {
            var literal = and(visibleToPredicateRead(repair, item.observation),
                    beforeWrite(item.bad, repair));
            if (literal != Lit.False) {
                alternatives.add(literal);
                repairs++;
            }
        }
        alternatives.add(rule.literal.not());
        solver.assertImpliesOr(activePredicateGuard, alternatives);
        predicateEncodingMetrics.gmwrObligations++;
        predicateEncodingMetrics.gmwrRepairAlternatives += repairs;
    }

    /** 受支持单调 JOIN 的完整绑定及结果约束；所有 key 共用事务对可见性。 */
    private void encodeExplicitMultiRelationPredicate(
            KnownGraph.PredicateObservation<KeyType, ValueType> observation,
            List<Map.Entry<KeyType, List<KnownGraph.WriteRef<KeyType, ValueType>>>>
                    scopedEntries,
            Map<KeyType, KnownGraph.WriteRef<KeyType, ValueType>> resultSourcesByKey,
            QueryPlan<KeyType, ValueType> plan) {
        var reader = observation.getTxn();
        var resolver = plan.scope().relationResolver();
        var recordedInputs = prepared.observation(observation).recordedInputs;
        predicateEncodingMetrics.joinObservations++;
        predicateEncodingMetrics.joinScopedKeys += scopedEntries.size();

        var frontiersByKey = new LinkedHashMap<KeyType,
                KeyFrontier<KeyType, ValueType>>();
        var externalWritesByKey = new LinkedHashMap<KeyType,
                List<KnownGraph.WriteRef<KeyType, ValueType>>>();
        var versionsByKey = new LinkedHashMap<KeyType,
                List<QueryVersionWitness<KeyType, ValueType>>>();
        var versionPool = new ArrayList<RowVersion<KeyType, ValueType>>();

        for (var entry : scopedEntries) {
            var key = entry.getKey();
            var recordedSource = resultSourcesByKey.get(key);
            var keyState = prepared.observation(observation).key(key);
            var latestSelf = keyState.latestSelf;
            var externalWrites = keyState.allExternalWrites;
            externalWritesByKey.put(key, externalWrites);

            if (latestSelf != null) {
                var frontier = new KeyFrontier<KeyType, ValueType>(
                        key, reader,
                        List.of(new FrontierCandidate<>(latestSelf, Lit.True, Lit.True)),
                        latestSelf);
                frontiersByKey.put(key, frontier);
                addQueryVersionWitness(
                        versionsByKey, versionPool, resolver, latestSelf, Lit.True);
                continue;
            }

            var frontier = createExplicitQueryFrontier(
                    observation, key, recordedSource);
            frontiersByKey.put(key, frontier);

            // Current source candidates carry a latest-visible selection guard.
            // Writes excluded from the current snapshot remain in the version
            // pool as hypothetical same-key PR_RW replacement targets.
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
        }

        final List<Map<KeyType, ValueType>> bindings;
        try {
            bindings = plan.candidateInputBindings(versionPool);
        } catch (QueryException exception) {
            assertPredicate(Lit.False);
            return;
        }

        var contributionContexts = new IdentityHashMap<
                KnownGraph.WriteRef<KeyType, ValueType>, Lit>();
        for (var binding : bindings) {
            var resolved = resolveQueryBinding(binding, versionsByKey);
            if (resolved == null) {
                assertPredicate(Lit.False);
                return;
            }

            encodeAdditionalBindingExclusion(binding, recordedInputs, resolved);
            accumulateContributionContexts(resolved, contributionContexts);
        }

        // PR_RW is anchored at the selected PR_WR source for a key.  Other
        // relations only appear in the guard that says the replacement changes
        // this query's result contribution.
        for (var entry : scopedEntries) {
            var frontier = frontiersByKey.get(entry.getKey());
            if (frontier == null) {
                continue;
            }
            if (frontier.fixedWrite != null
                    && frontier.fixedWrite.getTxn().equals(frontier.reader)) {
                // A true query-before self-write source is internal.  An
                // external recorded source is also stored in fixedWrite and
                // must still anchor contextual PR_RW edges.
                continue;
            }
            encodeExplicitPrRw(
                    frontier,
                    externalWritesByKey.getOrDefault(
                            entry.getKey(), Collections.emptyList()),
                    contributionContexts);
        }
    }

    /**
     * Builds a latest-visible SI frontier without invoking the row-local
     * predicate-change shortcut.
     */
    private KeyFrontier<KeyType, ValueType> createExplicitQueryFrontier(
            KnownGraph.PredicateObservation<KeyType, ValueType> observation,
            KeyType key,
            KnownGraph.WriteRef<KeyType, ValueType> recordedSource) {
        predicateSourceConstraintCount++;
        predicateEncodingMetrics.frontiers++;
        var keyState = prepared.observation(observation).key(key);
        if (recordedSource != null) {
            assertFixedSourceLatest(observation, recordedSource, keyState.allExternalWrites);
            predicateEncodingMetrics.frontierCandidates++;
            return new KeyFrontier<>(key, observation.getTxn(),
                    List.of(new FrontierCandidate<>(recordedSource, Lit.True, Lit.True)), recordedSource);
        }
        var candidates = latestCandidates(observation, key, keyState.sourceCandidates, keyState.allExternalWrites).stream()
                .filter(candidate -> keyState.sourceCandidates.contains(candidate.write))
                .collect(Collectors.toList());
        predicateEncodingMetrics.frontierCandidates += candidates.size();
        var frontier = new KeyFrontier<>(key, observation.getTxn(), candidates, recordedSource);
        return frontier;
    }

    /** latest 始终比较完整外部最终写集合，之后才筛选可作为来源的版本。 */
    private List<FrontierCandidate<KeyType, ValueType>> latestCandidates(
            KnownGraph.PredicateObservation<KeyType, ValueType> observation,
            KeyType key, List<KnownGraph.WriteRef<KeyType, ValueType>> sourceCandidates,
            List<KnownGraph.WriteRef<KeyType, ValueType>> allExternalWrites) {
        return latestVisibleChecker.check(observation.getTxn(), key, sourceCandidates, allExternalWrites,
                new LatestVisibleChecker.SnapshotOrder<KeyType, ValueType>() {
                    @Override
                    public Lit visibleToReader(KeyType ignored,
                            KnownGraph.WriteRef<KeyType, ValueType> writer,
                            Transaction<KeyType, ValueType> reader) {
                        return visibleToPredicateRead(writer, observation);
                    }

                    @Override
                    public Lit beforeWriter(KeyType ignored,
                            KnownGraph.WriteRef<KeyType, ValueType> left,
                            KnownGraph.WriteRef<KeyType, ValueType> right) {
                        return beforeWrite(left, right);
                    }
                }).stream().map(validity -> new FrontierCandidate<>(
                        validity.writer, validity.visible, validity.latest))
                .collect(Collectors.toList());
    }

    private void encodeExplicitPrWr(
            KeyFrontier<KeyType, ValueType> frontier,
            KnownGraph.WriteRef<KeyType, ValueType> recordedSource) {
        for (var source : frontier.candidates) {
            if (isBottomTxn(source.write.getTxn())
                    || source.write.getTxn().equals(frontier.reader)) {
                continue;
            }
            var guard = recordedSource == source.write ? Lit.True : source.latest;
            addDependencyEdge(new SIEdge<>(
                    source.write.getTxn(), frontier.reader,
                    EdgeType.PR_WR, frontier.key), guard);
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
                        new SIEdge<>(frontier.reader, later.getTxn(),
                                EdgeType.PR_RW, frontier.key),
                        and(List.of(
                                source.latest,
                                beforeWrite(source.write, later),
                                changeContext)));
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
            if (source.selection == null || source.selection == Lit.False) {
                // Target-only or impossible-current versions cannot make this
                // binding part of the current visible snapshot.
                return;
            }
            if (source.selection != Lit.True) {
                clause.add(Logic.not(source.selection));
            }
        }
        predicateEncodingMetrics.resultExclusionClauses++;
        predicateEncodingMetrics.resultExclusionLiterals +=
                Math.max(1, clause.size());
        if (clause.isEmpty()) {
            assertPredicate(Lit.False);
        } else {
            solver.assertImpliesOr(activePredicateGuard, clause);
        }
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
            contexts.merge(target.write, context, SISolverInduced::or);
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

    private void encodeSelectedPredicateDependencies(
            KeyFrontier<KeyType, ValueType> frontier,
            List<KnownGraph.WriteRef<KeyType, ValueType>> externalWrites,
            Event<KeyType, ValueType> predicateRead) {
        for (var source : frontier.candidates) {
            var selectedGuard = source.latest;
            if (!isBottomTxn(source.write.getTxn())) {
                addDependencyEdge(new SIEdge<>(
                        source.write.getTxn(), frontier.reader,
                        EdgeType.PR_WR, frontier.key), selectedGuard);
            }
            for (var later : externalWrites) {
                if (later == source.write
                        || !analysis.writeChangesPredicateResult(
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

    /** 固定来源仍比较全部竞争写；所有子句保留当前 observation 的 assumption。 */
    private void assertFixedSourceLatest(
            KnownGraph.PredicateObservation<KeyType, ValueType> observation,
            KnownGraph.WriteRef<KeyType, ValueType> source,
            List<KnownGraph.WriteRef<KeyType, ValueType>> externalWrites) {
        if (!externalWrites.contains(source)) {
            throw new IllegalStateException("固定来源不在完整外部最终写域中");
        }
        assertPredicate(visibleToPredicateRead(source, observation));
        for (var other : externalWrites) {
            if (other == source) {
                continue;
            }
            var afterSource = beforeWrite(source, other);
            if (afterSource != Lit.False) {
                solver.assertOr(activePredicateGuard.not(), afterSource.not(),
                        visibleToPredicateRead(other, observation).not());
            }
        }
    }

    private void encodeFixedSourceDependencies(
            Transaction<KeyType, ValueType> reader, KeyType key,
            KnownGraph.WriteRef<KeyType, ValueType> source,
            List<KnownGraph.WriteRef<KeyType, ValueType>> externalWrites,
            Event<KeyType, ValueType> predicateRead) {
        if (!isBottomTxn(source.getTxn()) && !source.getTxn().equals(reader)) {
            addDependencyEdge(new SIEdge<>(source.getTxn(), reader,
                    EdgeType.PR_WR, key), Lit.True);
        }
        for (var other : externalWrites) {
            if (other == source) {
                continue;
            }
            if (!analysis.writeChangesPredicateResult(
                    source, other, predicateRead)) {
                continue;
            }
            var afterSource = beforeWrite(source, other);
            if (afterSource == Lit.False) {
                continue;
            }
            addDependencyEdge(new SIEdge<>(
                    reader, other.getTxn(),
                    EdgeType.PR_RW, key), afterSource);
        }
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
        return visibilityLiteral(write.getTxn(), observation.getTxn());
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

        assertPredicate(Lit.False);
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
        boolean predicateEdge = edge.getType() == EdgeType.PR_WR || edge.getType() == EdgeType.PR_RW;
        boolean fixedPredicate = predicateEdge && activePredicateGuard == Lit.True && guard == Lit.True;
        if (predicateEdge && !fixedPredicate) {
            predicateEncodingMetrics.predicateAttempts++;
        }
        guard = and(guard, activePredicateGuard);
        if (!guardCanHold(edge.getFrom(), edge.getTo(), guard)) {
            if (predicateEdge) {
                predicateEncodingMetrics.predicateSkipped++;
            }
            predicateEncodingMetrics.dependencyEdgesSkipped++;
            return false;
        }
        predicateEncodingMetrics.dependencyEdgeAttempts++;
        if (predicateEdge) {
            if (fixedPredicate) {
                predicateEncodingMetrics.fixedPredicateCandidates++;
            }
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
            addOrderingSupport(edge.getFrom(), edge.getTo(), guard, true);
        } else if (isDependencyEdgeB(edge.getType())) {
            dependencyEdgesB.add(guarded);
            addOrderingSupport(edge.getFrom(), edge.getTo(), guard, false);
        } else {
            throw new IllegalArgumentException(
                    "Unsupported SI dependency edge type: " + edge.getType());
        }

        predicateEncodingMetrics.dependencyEdgesQueued++;
        return true;
    }

    private void addOrderingSupport(Transaction<KeyType, ValueType> from,
            Transaction<KeyType, ValueType> to, Lit guard, boolean inA) {
        var support = new OrderingSupport<>(from, to, guard);
        if (inA) {
            incomingDependencyA.computeIfAbsent(to, ignored -> new ArrayList<>()).add(support);
        } else {
            outgoingDependencyB.computeIfAbsent(from, ignored -> new ArrayList<>()).add(support);
        }
    }

    /**
     * 用 U→V、U→V* 表示 A(U,V)，用 U*→V 表示 B(U,V)。
     * 辅助节点只连接一段 A 和一段 B；H 无环当且仅当 A∪(A;B) 无环。
     * 保留原始 guard，不枚举 A/B 支持的笛卡尔积。
     */
    private void encodeFactorizedInducedGraph() {
        var compositionNodes = new HashMap<Transaction<KeyType, ValueType>, Integer>();
        for (var middle : incomingDependencyA.keySet()) {
            if (!outgoingDependencyB.getOrDefault(middle, List.of()).isEmpty()) {
                compositionNodes.put(middle, inducedGraph.addNode());
            }
        }
        for (var entry : incomingDependencyA.entrySet()) {
            var auxiliary = compositionNodes.get(entry.getKey());
            for (var support : entry.getValue()) {
                int from = inducedNodes.get(support.from);
                addEncodingSupport(from, inducedNodes.get(support.to), support.guard);
                if (auxiliary != null) {
                    addEncodingSupport(from, auxiliary, support.guard);
                }
            }
        }
        for (var entry : outgoingDependencyB.entrySet()) {
            var auxiliary = compositionNodes.get(entry.getKey());
            if (auxiliary == null) {
                continue;
            }
            for (var support : entry.getValue()) {
                addEncodingSupport(auxiliary, inducedNodes.get(support.to), support.guard);
            }
        }
    }

    private void addEncodingSupport(int from, int to, Lit guard) {
        if (guard == Lit.False) {
            return;
        }
        if (from == to) {
            solver.assertTrue(Logic.not(guard));
            return;
        }
        var edge = new DirectedEdgeKey(from, to);
        if (!inducedEdgesByGuard.computeIfAbsent(
                guard, ignored -> new HashSet<>()).add(edge)) {
            predicateEncodingMetrics.inducedEdgeDuplicates++;
            return;
        }
        predicateEncodingMetrics.inducedEdgesQueued++;
        if (graphEdgeInterning) {
            inducedEdgeSupports.computeIfAbsent(edge,
                    ignored -> new ArrayList<>()).add(guard);
        } else {
            materializeInducedEdge(from, to, List.of(guard));
        }
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

    /** 辅助图构造后，只用无条件支持约简 H；assumption 不参与约简依据。 */
    private void materializeReducedInducedGraph() {
        if (!graphEdgeInterning) {
            return;
        }
        int size = inducedGraph.nNodes();
        var direct = new BitSet[size];
        var reach = new BitSet[size];
        var indegree = new int[size];
        for (int node = 0; node < size; node++) {
            direct[node] = new BitSet(size);
            reach[node] = new BitSet(size);
        }
        for (var entry : inducedEdgeSupports.entrySet()) {
            if (entry.getValue().contains(Lit.True)) {
                var edge = entry.getKey();
                direct[edge.from].set(edge.to);
                indegree[edge.to]++;
            }
        }
        var ready = new ArrayDeque<Integer>();
        for (int node = 0; node < size; node++) {
            if (indegree[node] == 0) {
                ready.add(node);
            }
        }
        var order = new int[size];
        int count = 0;
        while (!ready.isEmpty()) {
            int from = ready.remove();
            order[count++] = from;
            for (int to = direct[from].nextSetBit(0); to >= 0;
                    to = direct[from].nextSetBit(to + 1)) {
                if (--indegree[to] == 0) {
                    ready.add(to);
                }
            }
        }
        if (count != size) {
            solver.assertTrue(Lit.False);
            return;
        }
        for (int pos = size - 1; pos >= 0; pos--) {
            int from = order[pos];
            for (int to = direct[from].nextSetBit(0); to >= 0;
                    to = direct[from].nextSetBit(to + 1)) {
                reach[from].set(to);
                reach[from].or(reach[to]);
            }
        }
        for (int pos = 0; pos < size; pos++) {
            int from = order[pos];
            var covered = new BitSet(size);
            for (int next = pos + 1; next < size; next++) {
                int to = order[next];
                if (!direct[from].get(to) || covered.get(to)) {
                    continue;
                }
                materializeInducedEdge(from, to, List.of(Lit.True));
                covered.set(to);
                covered.or(reach[to]);
            }
        }
        for (var entry : inducedEdgeSupports.entrySet()) {
            var edge = entry.getKey();
            if (reach[edge.from].get(edge.to)) {
                // 已有确定路径，任意 guard 取值都不会改变可达关系。
                continue;
            }
            if (reach[edge.to].get(edge.from)) {
                // 反向条件支持一旦成立必成环，保留 guard 的否定约束。
                for (var guard : entry.getValue()) {
                    solver.assertTrue(Logic.not(guard));
                }
                continue;
            }
            materializeInducedEdge(edge.from, edge.to, entry.getValue());
        }
    }

    /** 剩余物理边仍精确等价于其全部逻辑支持的析取。 */
    private void materializeInducedEdge(int from, int to, List<Lit> guards) {
        var edge = inducedGraph.addEdge(from, to);
        if (guards.contains(Lit.True)) {
            solver.assertTrue(edge);
            return;
        }
        for (var guard : guards) {
            solver.assertImplies(guard, edge);
        }
        solver.assertImpliesOr(edge, guards);
    }

    private void publishResidualSatStats() {
        var profiler = Profiler.getInstance();
        profiler.addCount("SI_PROP_WW_CHOICE_VARIABLES_COUNT",
                residualWwChoiceVariables);
        profiler.addCount("SI_PROP_WW_CHOICE_CONSTRAINTS_COUNT",
                residualWwChoiceConstraints);
        profiler.addCount("SI_PROP_RESIDUAL_SAT_VARIABLES_COUNT", solver.nVars());
        profiler.addCount("SI_PROP_RESIDUAL_SAT_CONSTRAINTS_COUNT", solver.nClauses());
        profiler.addCount("SI_PROP_MONOSAT_GRAPH_NODES_COUNT", inducedGraph.nNodes());
        profiler.addCount("SI_PROP_MONOSAT_AUXILIARY_NODES_COUNT",
                inducedGraph.nNodes() - inducedNodes.size());
        profiler.addCount("SI_PROP_MONOSAT_GRAPH_EDGES_COUNT", inducedGraph.nEdges());
        profiler.addCount("SI_VISIBILITY_VARIABLES_COUNT", visibilityChoices.size());
        profiler.addCount("SI_INDUCED_PHYSICAL_EDGES_COUNT",
                inducedGraph.nEdges());
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

    /** Maps the original solve's assumption conflict directly; never re-solves. */
    private void extractConflicts() {
        var reasons = new ArrayList<AssumptionReason<KeyType, ValueType>>();
        for (var literal : solver.getConflictClause()) {
            var reason = assumptionReasons.get(Logic.not(literal));
            if (reason != null && !reasons.contains(reason)) {
                reasons.add(reason);
            }
        }
        conflictReasons = List.copyOf(reasons);
        conflictConstraints = reasons.stream().map(reason -> reason.wwConstraint)
                .filter(Objects::nonNull).collect(Collectors.toList());
        conflictEdges = reasons.isEmpty()
                ? SIVerifier.InducedGraph.extractCycleEdges(graph)
                : supportingKnownEdges(conflictConstraints);
    }

    List<AssumptionReason<KeyType, ValueType>> getConflictReasons() {
        return conflictReasons;
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

    private static final class PredicateEncodingMetrics {
        private long joinObservations;
        private long joinScopedKeys;
        private long observations;
        private long rowLocalEncoded;
        private long rowLocalKeyVisits;
        private long internalKeys;
        private long externalKeys;
        private long recordedSourceKeys;
        private long frontiers;
        private long frontierCandidates;
        private long badWrites;
        private long beforeWriteCalls;
        private long dependencyEdgeAttempts;
        private long dependencyEdgeDuplicates;
        private long dependencyEdgesSkipped;
        private long dependencyEdgesQueued;
        private long dependencyEdgeCandidates;
        private long dependencyPhysicalEdges;
        private long dependencyEdgesCoalesced;
        private long inducedEdgeDuplicates;
        private long inducedEdgesQueued;
        private long resultExclusionClauses;
        private long resultExclusionLiterals;
        private long gmwrKeys;
        private long gmwrObligations;
        private long gmwrRepairAlternatives;
        private long gmwrSourceCandidates;

        private long predicateAttempts;
        private long predicateSkipped;
        private long fixedPredicateCandidates;

        private void publish(Profiler profiler, boolean includeCounts) {
            profiler.addCount("SI_PRED_DEPENDENCY_ATTEMPTS_COUNT", predicateAttempts);
            profiler.addCount("SI_PRED_DEPENDENCY_SKIPPED_COUNT", predicateSkipped);
            profiler.addCount("SI_PRED_DEPENDENCY_FIXED_CANDIDATES_COUNT", fixedPredicateCandidates);
            profiler.addCount("SI_PRED_DEPENDENCY_CANDIDATES_COUNT", dependencyEdgeCandidates);
            profiler.addCount("SI_PRED_DEPENDENCY_PHYSICAL_EDGES_COUNT", dependencyPhysicalEdges);
            if (!includeCounts) {
                return;
            }
            profiler.addCount("SI_PRED_JOIN_COUNT", joinObservations);
            profiler.addCount("SI_PRED_JOIN_SCOPED_KEYS_COUNT", joinScopedKeys);
            profiler.addCount("SI_PRED_OBSERVATIONS_COUNT", observations);
            profiler.addCount("SI_PRED_ROW_LOCAL_ENCODED_COUNT", rowLocalEncoded);
            profiler.addCount("SI_PRED_ROW_LOCAL_KEY_VISITS_COUNT", rowLocalKeyVisits);
            profiler.addCount("SI_PRED_INTERNAL_KEYS_COUNT", internalKeys);
            profiler.addCount("SI_PRED_EXTERNAL_KEYS_COUNT", externalKeys);
            profiler.addCount("SI_PRED_RECORDED_SOURCE_KEYS_COUNT", recordedSourceKeys);
            profiler.addCount("SI_PRED_FRONTIERS_COUNT", frontiers);
            profiler.addCount("SI_PRED_FRONTIER_CANDIDATES_COUNT", frontierCandidates);
            profiler.addCount("SI_PRED_BAD_WRITES_COUNT", badWrites);
            profiler.addCount("SI_PRED_BEFORE_WRITE_CALLS_COUNT", beforeWriteCalls);
            profiler.addCount("SI_TYPED_DEPENDENCY_ATTEMPTS_COUNT", dependencyEdgeAttempts);
            profiler.addCount("SI_PRED_DEPENDENCY_DUPLICATES_COUNT", dependencyEdgeDuplicates);
            profiler.addCount("SI_TYPED_DEPENDENCY_SKIPPED_COUNT", dependencyEdgesSkipped);
            profiler.addCount("SI_PRED_DEPENDENCY_QUEUED_COUNT", dependencyEdgesQueued);
            profiler.addCount("SI_PRED_DEPENDENCY_COALESCED_COUNT",
                    dependencyEdgesCoalesced);
            profiler.addCount("SI_PRED_INDUCED_DUPLICATES_COUNT", inducedEdgeDuplicates);
            profiler.addCount("SI_PRED_INDUCED_QUEUED_COUNT", inducedEdgesQueued);
            profiler.addCount("SI_PRED_RESULT_EXCLUSION_CLAUSES_COUNT", resultExclusionClauses);
            profiler.addCount("SI_PRED_RESULT_EXCLUSION_LITERALS_COUNT", resultExclusionLiterals);
            profiler.addCount("SI_GMWR_KEYS_COUNT", gmwrKeys);
            profiler.addCount("SI_GMWR_OBLIGATIONS_COUNT", gmwrObligations);
            profiler.addCount("SI_GMWR_REPAIR_ALTERNATIVES_COUNT", gmwrRepairAlternatives);
            profiler.addCount("SI_GMWR_SOURCE_CANDIDATES_COUNT", gmwrSourceCandidates);
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
        /**
         * Null means target-only: usable for PR_RW replacement analysis but not
         * selectable in the current predicate snapshot.
         */
        private final Lit selection;

        private QueryVersionWitness(
                KnownGraph.WriteRef<KeyType, ValueType> write, Lit selection) {
            this.write = write;
            this.selection = selection;
        }
    }

    /** 有向端点按值判等；混合连续节点编号，避免异或哈希导致大量边集中碰撞。 */
    private static final class DirectedEdgeKey {
        private final int from;
        private final int to;

        private DirectedEdgeKey(int from, int to) {
            this.from = from;
            this.to = to;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof DirectedEdgeKey)) {
                return false;
            }
            var other = (DirectedEdgeKey) object;
            return from == other.from && to == other.to;
        }

        @Override
        public int hashCode() {
            int hash = from * 0x9e3779b9 + to;
            hash ^= hash >>> 16;
            hash *= 0x85ebca6b;
            hash ^= hash >>> 13;
            hash *= 0xc2b2ae35;
            return hash ^ (hash >>> 16);
        }
    }

    private static final class OrderingSupport<K, V> {
        private final Transaction<K, V> from;
        private final Transaction<K, V> to;
        private final Lit guard;

        private OrderingSupport(Transaction<K, V> from, Transaction<K, V> to, Lit guard) {
            this.from = from;
            this.to = to;
            this.guard = guard;
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
        private final Set<Lit> guards = new LinkedHashSet<>();

        private CoalescedPredicateDependency(
                SIEdge<KeyType, ValueType> edge, Lit guard) {
            this.edge = edge;
            this.guards.add(guard);
        }

        private void merge(SIEdge<KeyType, ValueType> witness, Lit witnessGuard) {
            for (var key : witness.getKeys()) {
                edge.addKey(key);
            }
            guards.add(witnessGuard);
        }
    }
}
