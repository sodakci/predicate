package verifier;

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
import com.google.common.graph.EndpointPair;
import org.apache.commons.lang3.tuple.Pair;
import util.Profiler;

import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * Encodes serializability as a SAT problem over an arbitration order (AR).
 *
 * <p>Each non-reflexive pair of transactions gets one Boolean literal
 * {@code ar[i][j]}, meaning transaction {@code i} is ordered before transaction
 * {@code j}.  The solver constrains these literals to form a strict total
 * order, then adds the known precedence edges, unresolved WW choices, derived
 * RW edges, and predicate-read visibility constraints on top of that order.</p>
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
    private final Solver solver = new Solver();
    private final boolean collectConflicts;
    private final boolean collectPredicateMetrics;
    private final SERVerifier.PredicateSolvingMode predicateSolvingMode;

    private final List<Transaction<KeyType, ValueType>> txns;
    private final Map<Transaction<KeyType, ValueType>, Integer> txnIndex;
    private final KnownOrder knownOrder;
    // AR is encoded as SAT-selected direct precedence edges in an acyclic graph.
    // We only create literals for transaction pairs that actually appear in the
    // formula; any acyclic partial order has a total extension.
    private final Graph arGraph;
    private final int[] arNodes;
    private final Map<Pair<Transaction<KeyType, ValueType>, Transaction<KeyType, ValueType>>, Lit> arCache =
            new HashMap<>();
    private final Set<Pair<Transaction<KeyType, ValueType>, Transaction<KeyType, ValueType>>> comparablePairs =
            new HashSet<>();
    // Per-key write lists provide the local write order candidates used by WW/RW
    // and predicate-read encodings.
    private final Map<KeyType, List<KnownGraph.WriteRef<KeyType, ValueType>>> writesByKey;
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
    private boolean collectingPredicateMetrics;
    // Predicate constraints are refined lazily from concrete SAT models.  This
    // avoids eagerly enumerating the Cartesian product of every key frontier.
    private final List<PredicateCheck<KeyType, ValueType>> predicateChecks = new ArrayList<>();
    private final List<RowLocalPredicateCheck<KeyType, ValueType>> rowLocalPredicateChecks =
            new ArrayList<>();
    // Writer comparability is key-local and independent of the predicate read.
    // Initialize each key's writer pairs once, then reuse them across reads.
    private final Set<KeyType> initializedPredicateWriteOrders = new HashSet<>();
    // Dependency edges are created with their type/key metadata before their
    // guards are encoded into AR. This keeps edge construction separate from
    // constraint encoding and preserves RW/PR_RW as B-side dependencies.
    private final List<GuardedDependencyEdge<KeyType, ValueType>> dependencyEdgesA =
            new ArrayList<>();
    private final List<GuardedDependencyEdge<KeyType, ValueType>> dependencyEdgesB =
            new ArrayList<>();
    private final Map<Lit, Set<SEREdge<KeyType, ValueType>>> dependencyEdgesByGuard =
            new IdentityHashMap<>();
    private Collection<Pair<EndpointPair<Transaction<KeyType, ValueType>>, Collection<Edge<KeyType>>>> conflictEdges =
            Collections.emptyList();
    private Collection<SERConstraint<KeyType, ValueType>> conflictConstraints = Collections.emptyList();

    SERSolverAR(History<KeyType, ValueType> history,
                KnownGraph<KeyType, ValueType> graph,
                Collection<SERConstraint<KeyType, ValueType>> constraints) {
        this(history, graph, constraints, true, false,
                SERVerifier.PredicateSolvingMode.CALFE);
    }

    private SERSolverAR(History<KeyType, ValueType> history,
                        KnownGraph<KeyType, ValueType> graph,
                        Collection<SERConstraint<KeyType, ValueType>> constraints,
                        boolean collectConflicts) {
        this(history, graph, constraints, collectConflicts, false,
                SERVerifier.PredicateSolvingMode.CALFE);
    }

    SERSolverAR(History<KeyType, ValueType> history,
                KnownGraph<KeyType, ValueType> graph,
                Collection<SERConstraint<KeyType, ValueType>> constraints,
                boolean collectConflicts,
                boolean collectPredicateMetrics) {
        this(history, graph, constraints, collectConflicts, collectPredicateMetrics,
                SERVerifier.PredicateSolvingMode.CALFE);
    }

    SERSolverAR(History<KeyType, ValueType> history,
                KnownGraph<KeyType, ValueType> graph,
                Collection<SERConstraint<KeyType, ValueType>> constraints,
                boolean collectConflicts,
                boolean collectPredicateMetrics,
                SERVerifier.PredicateSolvingMode predicateSolvingMode) {
        var profiler = Profiler.getInstance();
        profiler.startTick("SER_AR_ENCODE_SETUP");
        try {
            this.history = history;
            this.graph = graph;
            this.constraints = constraints;
            this.collectConflicts = collectConflicts;
            this.collectPredicateMetrics = collectPredicateMetrics;
            this.predicateSolvingMode = Objects.requireNonNull(
                    predicateSolvingMode, "predicateSolvingMode");
            this.txns = history.getTransactions().stream()
                    .filter(txn -> !isBottomTxn(txn))
                    .collect(Collectors.toList());
            this.txnIndex = new HashMap<>();
            for (int i = 0; i < txns.size(); i++) {
                txnIndex.put(txns.get(i), i);
            }
            this.knownOrder = buildKnownOrder();
            this.arGraph = new Graph(solver);
            this.arNodes = createArNodes();
            this.writesByKey = buildWritesByKey(graph);
            for (int writeRefId = 0;
                    writeRefId < graph.getAllWrites().size(); writeRefId++) {
                writeRefIds.put(graph.getAllWrites().get(writeRefId), writeRefId);
            }
            this.sortedKeyWriteIndexes = buildKeyWriteIndexes(writesByKey);
        } finally {
            profiler.endTick("SER_AR_ENCODE_SETUP");
        }
        profileVoid(profiler, "SER_AR_ENCODE_KNOWN_EDGES", this::encodeKnownEdges);
        profileVoid(profiler, "SER_AR_ENCODE_WW", this::encodeRemainingWwChoices);
        profileVoid(profiler, "SER_AR_ENCODE_RW", this::encodeRwFromWrAndWw);
        profileVoid(profiler, "SER_AR_ENCODE_PREDICATE", this::encodePredicateConstraints);
        if (predicateSolvingMode == SERVerifier.PredicateSolvingMode.CALFE) {
            profileVoid(profiler, "SER_CALFE_PREDECLARE_AR", this::predeclareRowLocalArPairs);
        }
        profileVoid(profiler, "SER_AR_ENCODE_DEPENDENCIES", this::encodeDependencyEdges);
        profileVoid(profiler, "SER_AR_ENCODE_TOTAL_ORDER", this::encodeStrictTotalOrder);
    }

    /**
     * Solves the AR encoding.  On UNSAT, the outer solver instance can collect
     * a reduced explanation; recursive satisfiability checks disable that work.
     */
    boolean solve() {
        var profiler = Profiler.getInstance();
        while (profileBoolean(profiler, "SER_MONOSAT_SOLVE", solver::solve)) {
            if (profileBoolean(profiler, "SER_AR_PREDICATE_REFINEMENT",
                    this::refinePredicateConstraints)) {
                continue;
            }
            conflictEdges = Collections.emptyList();
            conflictConstraints = Collections.emptyList();
            return true;
        }

        if (!collectConflicts) {
            conflictEdges = Collections.emptyList();
            conflictConstraints = Collections.emptyList();
            return false;
        }

        profileVoid(profiler, "SER_AR_CONFLICT_EXTRACTION", this::extractConflicts);
        return false;
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
            Profiler profiler, String tag, BooleanSupplier action) {
        profiler.startTick(tag);
        try {
            return action.getAsBoolean();
        } finally {
            profiler.endTick(tag);
        }
    }

    Pair<Collection<Pair<EndpointPair<Transaction<KeyType, ValueType>>, Collection<Edge<KeyType>>>>, Collection<SERConstraint<KeyType, ValueType>>> getConflicts() {
        return Pair.of(conflictEdges, conflictConstraints);
    }

    int getArVariableCount() {
        return txns.size() * Math.max(0, txns.size() - 1);
    }

    /**
     * Allocates one graph node per real transaction. Pairwise AR edge
     * literals are created lazily by ar(...).
     */
    private int[] createArNodes() {
        var result = new int[txns.size()];
        for (int i = 0; i < txns.size(); i++) {
            result[i] = arGraph.addNode();
        }
        return result;
    }

    /**
     * The selected AR graph is a strict partial order. Every queried pair is
     * forced comparable in ensureComparable(...), so the partial order satisfies
     * all formula-visible total-order choices and can be extended to a strict
     * total serial order for unqueried pairs.
     */
    private void encodeStrictTotalOrder() {
        solver.assertTrue(arGraph.acyclic());
    }

    /**
     * Existing precedence edges are mandatory AR edges. Only a transitive
     * reduction is needed in the solver because it has exactly the same
     * reachability relation as the full known graph.
     */
    private void encodeKnownEdges() {
        if (knownOrder.cyclic) {
            solver.assertTrue(Lit.False);
            return;
        }
        for (var edge : knownOrder.reductionEdges) {
            solver.assertTrue(directArEdge(txns.get(edge[0]), txns.get(edge[1])));
        }
    }

    private KnownOrder buildKnownOrder() {
        var adjacency = new BitSet[txns.size()];
        for (int i = 0; i < adjacency.length; i++) {
            adjacency[i] = new BitSet(adjacency.length);
        }

        boolean invalid = addKnownOrderEdges(graph.getKnownGraphA(), adjacency);
        invalid |= addKnownOrderEdges(graph.getKnownGraphB(), adjacency);
        if (invalid) {
            return KnownOrder.cyclic(txns.size());
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
            return KnownOrder.cyclic(txns.size());
        }

        var reachable = new BitSet[txns.size()];
        for (int i = 0; i < reachable.length; i++) {
            reachable[i] = new BitSet(reachable.length);
        }
        for (int pos = topologicalOrder.length - 1; pos >= 0; pos--) {
            int from = topologicalOrder[pos];
            for (int to = adjacency[from].nextSetBit(0); to >= 0;
                    to = adjacency[from].nextSetBit(to + 1)) {
                reachable[from].set(to);
                reachable[from].or(reachable[to]);
            }
        }

        var topologicalPosition = new int[txns.size()];
        for (int pos = 0; pos < topologicalOrder.length; pos++) {
            topologicalPosition[topologicalOrder[pos]] = pos;
        }

        var reductionEdges = new ArrayList<int[]>();
        for (int from = 0; from < adjacency.length; from++) {
            var covered = new BitSet(adjacency.length);
            for (int pos = topologicalPosition[from] + 1; pos < topologicalOrder.length; pos++) {
                int to = topologicalOrder[pos];
                if (!adjacency[from].get(to) || covered.get(to)) {
                    continue;
                }
                reductionEdges.add(new int[] { from, to });
                covered.set(to);
                covered.or(reachable[to]);
            }
        }

        return new KnownOrder(reachable, reductionEdges, false);
    }

    private boolean addKnownOrderEdges(
            com.google.common.graph.ValueGraph<Transaction<KeyType, ValueType>, Collection<Edge<KeyType>>> known,
            BitSet[] adjacency) {
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
            adjacency[txnIndex.get(ep.source())].set(txnIndex.get(ep.target()));
        }
        return invalid;
    }

    /**
     * Encodes each unresolved WW pair as a binary choice.  Choosing one write
     * direction also activates the dependent edges generated for that branch.
     */
    private void encodeRemainingWwChoices() {
        for (var c : constraints) {
            var forward = ar(c.getWriteTransaction1(), c.getWriteTransaction2());
            var backward = ar(c.getWriteTransaction2(), c.getWriteTransaction1());

            for (var edge : c.getEdges1()) {
                addDependencyEdge(edge, forward);
            }
            for (var edge : c.getEdges2()) {
                addDependencyEdge(edge, backward);
            }
        }
    }

    /**
     * Derives ordinary RW edges directly in SAT from WR and WW order:
     * if T' writes a value read by T, and T' is ordered before another writer U
     * of the same key, then T must be ordered before U.
     */
    private void encodeRwFromWrAndWw() {
        for (var ep : graph.getReadFrom().edges()) {
            var readers = graph.getReadFrom().edgeValue(ep.source(), ep.target()).orElse(Collections.emptyList());
            for (var wrEdge : readers) {
                var key = wrEdge.getKey();
                for (var writer : this.writesByKey.getOrDefault(key, Collections.emptyList())) {
                    var u = writer.getTxn();
                    if (u.equals(ep.source()) || u.equals(ep.target())) {
                        continue;
                    }
                    // Algorithm 1, lines 28-30:
                    // if T' --WR(x)--> T and T' --WW(x)--> U then T --RW(x)--> U.
                    addDependencyEdge(
                            new SEREdge<>(ep.target(), u, EdgeType.RW, key),
                            ar(ep.source(), u));
                }
            }
        }
    }

    private void addDependencyEdge(SEREdge<KeyType, ValueType> edge, Lit guard) {
        if (collectingPredicateMetrics) {
            predicateEncodingMetrics.dependencyEdgeAttempts++;
        }
        /*
         * A false guard is already a tautology. Checking it before resolving
         * the target AR edge avoids creating unused graph variables for the
         * many RW alternatives ruled out by pruning.
         */
        if (guard == Lit.False) {
            if (collectingPredicateMetrics) {
                predicateEncodingMetrics.dependencyEdgesSkipped++;
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

        var target = ar(edge.getFrom(), edge.getTo());
        if (target == Lit.True || guard == target) {
            if (collectingPredicateMetrics) {
                predicateEncodingMetrics.dependencyEdgesSkipped++;
            }
            return;
        }

        if (collectingPredicateMetrics) {
            predicateEncodingMetrics.dependencyEdgesQueued++;
        }

        var guarded = new GuardedDependencyEdge<>(edge, guard);
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
        for (var guarded : dependencyEdgesA) {
            encodeDependencyEdge(guarded);
        }
        for (var guarded : dependencyEdgesB) {
            encodeDependencyEdge(guarded);
        }
        dependencyEdgesA.clear();
        dependencyEdgesB.clear();
        dependencyEdgesByGuard.clear();
    }

    private void encodeDependencyEdge(
            GuardedDependencyEdge<KeyType, ValueType> guarded) {
        var target = ar(guarded.edge.getFrom(), guarded.edge.getTo());
        if (guarded.guard == Lit.True) {
            solver.assertTrue(target);
        } else {
            solver.assertTrue(Logic.implies(guarded.guard, target));
        }
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
    }

    /**
     * Builds the latest-visible frontier for each predicate read.  Concrete
     * frontier combinations are checked lazily in solve(): when a SAT model
     * produces a result different from the recorded query result, that exact
     * combination is forbidden and the solver is resumed.  The generated
     * blocking clause is identical to the corresponding eager snapshot clause,
     * but unreachable and unnecessary combinations are never enumerated.
     */
    private void encodePredicateConstraints() {
        collectingPredicateMetrics = collectPredicateMetrics;
        try {
            for (var observation : graph.getPredicateObservations()) {
                if (collectingPredicateMetrics) {
                    predicateEncodingMetrics.observations++;
                }
                var predicateRead = observation.getPredicateReadEvent();
                var predicate = predicateRead.getPredicate();
                if (predicate == null) {
                    if (collectingPredicateMetrics) {
                        predicateEncodingMetrics.nullPredicates++;
                    }
                    continue;
                }

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
                        solver.assertTrue(Lit.False);
                    }
                }
                predicateEncodingMetrics.sourceIndexNanos += System.nanoTime() - started;

                started = System.nanoTime();
                var scopedEntries = scopedWrites(predicate.scope());
                predicateEncodingMetrics.scopeLookupNanos += System.nanoTime() - started;
                if (collectingPredicateMetrics) {
                    predicateEncodingMetrics.scopedKeys += scopedEntries.size();
                }

                if (predicate instanceof QueryPlan
                        && ((QueryPlan<?, ?>) predicate).isRowLocal()) {
                    if (collectingPredicateMetrics) {
                        predicateEncodingMetrics.rowLocalAttempts++;
                    }
                    var encoded = predicateSolvingMode ==
                            SERVerifier.PredicateSolvingMode.EAGER
                            ? encodeRowLocalPredicateEager(
                                    observation, scopedEntries, resultSourcesByKey)
                            : registerRowLocalPredicateCheck(
                                    observation, scopedEntries, resultSourcesByKey);
                    if (encoded) {
                        if (collectingPredicateMetrics) {
                            predicateEncodingMetrics.rowLocalEncoded++;
                        }
                        continue;
                    }
                    if (collectingPredicateMetrics) {
                        predicateEncodingMetrics.rowLocalFallbacks++;
                    }
                }
                if (collectingPredicateMetrics) {
                    predicateEncodingMetrics.generalObservations++;
                }

                started = System.nanoTime();
                var frontierEntries = scopedEntries.stream()
                        .filter(entry -> observation.getPredicateReadType(entry.key)
                                == KnownGraph.PredicateReadType.EXTERNAL)
                        .collect(Collectors.toList());
                predicateEncodingMetrics.generalKeyScanNanos +=
                        System.nanoTime() - started;
                if (collectingPredicateMetrics) {
                    predicateEncodingMetrics.generalExternalKeys += frontierEntries.size();
                }
                if (frontierEntries.isEmpty()) {
                    // Internal predicate keys are checked by the same evaluator in
                    // Utils before solver construction.
                    continue;
                }

                var frontiers = new ArrayList<KeyFrontier<KeyType, ValueType>>(
                        frontierEntries.size());
                for (var entry : frontierEntries) {
                    frontiers.add(createKeyFrontier(
                            observation, entry, resultSourcesByKey.get(entry.key)));
                }

                for (var resultKey : resultSourcesByKey.keySet()) {
                    if (!predicate.scope().covers(resultKey)) {
                        solver.assertTrue(Lit.False);
                    }
                }

                var snapshot = new LinkedHashMap<KeyType, ValueType>();
                var frontierKeys = frontierEntries.stream().map(entry -> entry.key)
                        .collect(Collectors.toSet());
                for (var entry : scopedEntries) {
                    if (frontierKeys.contains(entry.key)) {
                        continue;
                    }
                    var latestSelf = entry.latestSelfBefore(
                            observation.getTxn(), observation.getEventIndex());
                    if (latestSelf == null) {
                        latestSelf = resultSourcesByKey.get(entry.key);
                    }
                    if (latestSelf != null) {
                        snapshot.put(entry.key, latestSelf.getEvent().getValue());
                    }
                }
                predicateChecks.add(new PredicateCheck<>(predicateRead, frontiers,
                        snapshot, relationResolverFor(predicateRead)));
            }
        } finally {
            collectingPredicateMetrics = false;
            predicateEncodingMetrics.publish(
                    Profiler.getInstance(), collectPredicateMetrics);
        }
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

    /** Eagerly materializes every row-local reader-key constraint before solve(). */
    private boolean encodeRowLocalPredicateEager(
            KnownGraph.PredicateObservation<KeyType, ValueType> observation,
            List<KeyWriteIndex<KeyType, ValueType>> scopedEntries,
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
                        solver.assertTrue(Lit.False);
                    }
                } else if (latestSelf != null
                        && !hasEmptyPredicateContribution(
                                predicateRead, relationResolver, latestSelf)) {
                    solver.assertTrue(Lit.False);
                }
                continue;
            }
            if (collectingPredicateMetrics) {
                predicateEncodingMetrics.externalKeys++;
            }

            if (recordedSource != null) {
                if (collectingPredicateMetrics) {
                    predicateEncodingMetrics.recordedSourceKeys++;
                }
                assertRecordedSourceLatest(observation, entry, recordedSource);
                continue;
            }

            var badWrites = latestExternalWrites(entry, observation.getTxn()).stream()
                    .filter(write -> !hasEmptyPredicateContribution(
                            predicateRead, relationResolver, write))
                    .collect(Collectors.toList());
            if (collectingPredicateMetrics) {
                predicateEncodingMetrics.badWrites += badWrites.size();
            }
            var frontier = createKeyFrontier(observation, entry, null, false);
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
                if (collectingPredicateMetrics) {
                    predicateEncodingMetrics.blockingClauses++;
                    predicateEncodingMetrics.blockingClauseLiterals +=
                            Math.max(1, blockingClause.size());
                }
                if (blockingClause.isEmpty()) {
                    solver.addClause(Lit.False);
                } else {
                    solver.assertOr(blockingClause);
                }
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

    /** Registers one row-local observation for CALFE model replay. */
    private boolean registerRowLocalPredicateCheck(
            KnownGraph.PredicateObservation<KeyType, ValueType> observation,
            List<KeyWriteIndex<KeyType, ValueType>> scopedEntries,
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

        for (var resultKey : resultSourcesByKey.keySet()) {
            if (!predicateRead.getPredicate().scope().covers(resultKey)
                    || !writesByKey.containsKey(resultKey)) {
                solver.assertTrue(Lit.False);
            }
        }
        var recordedSourcesByKeyId = new Object[sortedKeyWriteIndexes.size()];
        for (var entry : scopedEntries) {
            recordedSourcesByKeyId[entry.keyId] = resultSourcesByKey.get(entry.key);
        }
        rowLocalPredicateChecks.add(new RowLocalPredicateCheck<>(
                observation, scopedEntries, relationResolver, recordedSourcesByKeyId));
        return true;
    }

    /**
     * MonoSAT graph variables cannot be added after the first solve.  CALFE
     * therefore allocates only the graph-edge variables that a later
     * materialized reader-key constraint may reference.  It deliberately does
     * not assert pair comparability yet: the unchanged ar(...) path adds that
     * XOR exactly when the corresponding predicate formula is materialized.
     * This creates no PR_WR/PR_RW edge, frontier guard, or blocking clause.
     */
    private void predeclareRowLocalArPairs() {
        if (rowLocalPredicateChecks.isEmpty()) {
            return;
        }

        var requiredPairs = new BitSet[txns.size()];
        for (int index = 0; index < requiredPairs.length; index++) {
            requiredPairs[index] = new BitSet(txns.size());
        }
        var preparedWriterPairs = new BitSet(sortedKeyWriteIndexes.size());
        long readerKeyVisits = 0L;

        for (var check : rowLocalPredicateChecks) {
            var observation = check.observation;
            var reader = observation.getTxn();
            for (var entry : check.scopedEntries) {
                if (observation.getPredicateReadType(entry.key)
                        != KnownGraph.PredicateReadType.EXTERNAL) {
                    continue;
                }
                readerKeyVisits++;

                for (var write : entry.latestWritesByWriter) {
                    markRequiredArPair(requiredPairs, reader, write.getTxn());
                }

                if (!preparedWriterPairs.get(entry.keyId)) {
                    preparedWriterPairs.set(entry.keyId);
                    var writes = entry.latestWritesByWriter;
                    for (int left = 0; left < writes.size(); left++) {
                        for (int right = left + 1; right < writes.size(); right++) {
                            markRequiredArPair(requiredPairs,
                                    writes.get(left).getTxn(),
                                    writes.get(right).getTxn());
                        }
                    }
                }
            }
        }

        long pairCount = 0L;
        for (int left = 0; left < requiredPairs.length; left++) {
            for (int right = requiredPairs[left].nextSetBit(left + 1);
                    right >= 0;
                    right = requiredPairs[left].nextSetBit(right + 1)) {
                var leftTxn = txns.get(left);
                var rightTxn = txns.get(right);
                directArEdge(leftTxn, rightTxn);
                directArEdge(rightTxn, leftTxn);
                pairCount++;
            }
        }
        if (collectPredicateMetrics) {
            var profiler = Profiler.getInstance();
            profiler.addCount("SER_CALFE_PREDECLARE_READER_KEYS_COUNT", readerKeyVisits);
            profiler.addCount("SER_CALFE_PREDECLARE_AR_PAIRS_COUNT", pairCount);
        }
    }

    private void markRequiredArPair(
            BitSet[] requiredPairs,
            Transaction<KeyType, ValueType> left,
            Transaction<KeyType, ValueType> right) {
        if (left == right || left.equals(right)
                || isBottomTxn(left) || isBottomTxn(right)) {
            return;
        }
        int leftIndex = txnIndex.get(left);
        int rightIndex = txnIndex.get(right);
        if (!knownOrder.cyclic
                && (knownOrder.reachable[leftIndex].get(rightIndex)
                    || knownOrder.reachable[rightIndex].get(leftIndex))) {
            return;
        }
        if (leftIndex < rightIndex) {
            requiredPairs[leftIndex].set(rightIndex);
        } else {
            requiredPairs[rightIndex].set(leftIndex);
        }
    }

    /** Materializes the unchanged eager constraint for one violating reader-key. */
    private void materializeRowLocalKeyConstraint(
            RowLocalPredicateCheck<KeyType, ValueType> check,
            KeyWriteIndex<KeyType, ValueType> entry,
            KnownGraph.WriteRef<KeyType, ValueType> recordedSource) {
        var observation = check.observation;
        var predicateRead = observation.getPredicateReadEvent();
        var key = entry.key;

        if (observation.getPredicateReadType(key)
                == KnownGraph.PredicateReadType.INTERNAL) {
            var latestSelf = entry.latestSelfBefore(
                    observation.getTxn(), observation.getEventIndex());
            if (latestSelf == null) {
                latestSelf = recordedSource;
            }
            if (recordedSource != null) {
                if (latestSelf != recordedSource) {
                    solver.assertTrue(Lit.False);
                }
            } else if (latestSelf != null
                    && !hasEmptyPredicateContribution(
                            predicateRead, check.relationResolver, latestSelf)) {
                solver.assertTrue(Lit.False);
            }
            return;
        }

        if (recordedSource != null) {
            assertRecordedSourceLatest(observation, entry, recordedSource);
            return;
        }

        var badWrites = latestExternalWrites(entry, observation.getTxn()).stream()
                .filter(write -> !hasEmptyPredicateContribution(
                        predicateRead, check.relationResolver, write))
                .collect(Collectors.toList());
        var frontier = createKeyFrontier(observation, entry, null, false);
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
            if (blockingClause.isEmpty()) {
                solver.addClause(Lit.False);
            } else {
                solver.assertOr(blockingClause);
            }
        }
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

    private void assertRecordedSourceLatest(
            KnownGraph.PredicateObservation<KeyType, ValueType> observation,
            KeyWriteIndex<KeyType, ValueType> writeIndex,
            KnownGraph.WriteRef<KeyType, ValueType> recordedSource) {
        var key = writeIndex.key;
        var latestSelf = writeIndex.latestSelfBefore(
                observation.getTxn(), observation.getEventIndex());
        if (latestSelf != null) {
            if (latestSelf != recordedSource) {
                solver.assertTrue(Lit.False);
            }
            return;
        }

        var candidates = latestExternalWrites(writeIndex, observation.getTxn());
        if (candidates.stream().noneMatch(write -> write == recordedSource)) {
            solver.assertTrue(Lit.False);
            return;
        }
        if (ar(recordedSource.getTxn(), observation.getTxn()) == Lit.False) {
            solver.assertTrue(Lit.False);
            return;
        }

        var sourceEdge = new SEREdge<KeyType, ValueType>(
                recordedSource.getTxn(), observation.getTxn(),
                EdgeType.PR_WR, key);
        addKnownPredicateEdge(sourceEdge);
        addDependencyEdge(sourceEdge, Lit.True);
        for (var other : candidates) {
            if (other == recordedSource) {
                continue;
            }
            if (!writeChangesPredicateResult(
                    recordedSource, other, observation.getPredicateReadEvent())) {
                continue;
            }
            addDependencyEdge(
                    new SEREdge<>(observation.getTxn(), other.getTxn(),
                            EdgeType.PR_RW, key),
                    beforeWrite(recordedSource, other));
        }
    }

    private KeyFrontier<KeyType, ValueType> createKeyFrontier(
            KnownGraph.PredicateObservation<KeyType, ValueType> observation,
            KeyWriteIndex<KeyType, ValueType> writeIndex,
            KnownGraph.WriteRef<KeyType, ValueType> recordedSource) {
        return createKeyFrontier(observation, writeIndex, recordedSource, true);
    }

    private KeyFrontier<KeyType, ValueType> createKeyFrontier(
            KnownGraph.PredicateObservation<KeyType, ValueType> observation,
            KeyWriteIndex<KeyType, ValueType> writeIndex,
            KnownGraph.WriteRef<KeyType, ValueType> recordedSource,
            boolean initializeAllWriterPairs) {
        var key = writeIndex.key;
        if (collectingPredicateMetrics) {
            predicateEncodingMetrics.frontiers++;
        }
        var latestSelf = writeIndex.latestSelfBefore(
                observation.getTxn(), observation.getEventIndex());
        if (latestSelf != null) {
            if (recordedSource != null && recordedSource != latestSelf) {
                solver.assertTrue(Lit.False);
            }
            if (collectingPredicateMetrics) {
                predicateEncodingMetrics.frontierCandidates++;
            }
            return new KeyFrontier<>(key,
                    observation.getTxn(),
                    List.of(new FrontierCandidate<>(latestSelf, Lit.True)), latestSelf);
        }

        // Only the final write to a key in one transaction can be externally
        // visible.  Earlier writes in the same transaction can never be a
        // latest-visible frontier.
        // The latest candidate is selected from a strict total order. Writer
        // comparability depends only on the key's complete writer set, so
        // repeated predicate reads can reuse the same primitive AR literals.
        if (initializeAllWriterPairs && initializedPredicateWriteOrders.add(key)) {
            var comparableWrites = writeIndex.latestWritesByWriter;
            for (int i = 0; i < comparableWrites.size(); i++) {
                for (int j = i + 1; j < comparableWrites.size(); j++) {
                    beforeWrite(comparableWrites.get(i), comparableWrites.get(j));
                }
            }
        }

        var externalWrites = writeIndex.latestExternalWrites(observation.getTxn());
        var candidates = externalWrites.stream()
                .map(write -> new FrontierCandidate<>(write,
                        ar(write.getTxn(), observation.getTxn())))
                .filter(candidate -> candidate.visible != Lit.False)
                .collect(Collectors.toList());
        if (collectingPredicateMetrics) {
            predicateEncodingMetrics.frontierCandidates += candidates.size();
        }

        var frontier = new KeyFrontier<KeyType, ValueType>(
                key, observation.getTxn(), candidates, recordedSource);

        if (recordedSource == null) {
            encodeSelectedPredicateDependencies(
                    frontier, externalWrites, observation.getPredicateReadEvent());
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
            addDependencyEdge(new SEREdge<>(
                    source.write.getTxn(),
                    frontier.reader,
                    EdgeType.PR_WR,
                    frontier.key), selectedGuard);

            for (var later : externalWrites) {
                if (later == source.write
                        || !writeChangesPredicateResult(
                                source.write, later, predicateRead)) {
                    continue;
                }
                addDependencyEdge(new SEREdge<>(
                        frontier.reader,
                        later.getTxn(),
                        EdgeType.PR_RW,
                        frontier.key),
                        and(selectedGuard,
                                beforeWrite(source.write, later)));
            }
        }
    }

    /**
     * Validates all predicate reads against the current SAT model and adds one
     * direct no-good clause for every mismatching visible snapshot.
     */
    private boolean refinePredicateConstraints() {
        var refined = refineGeneralPredicateConstraints();
        if (rowLocalPredicateChecks.isEmpty()) {
            return refined;
        }

        var positions = candidateArPositions();
        var rowLocalRefined = refineRowLocalPredicateConstraints(positions);
        if (rowLocalRefined) {
            encodeDependencyEdges();
        }
        return refined || rowLocalRefined;
    }

    private boolean refineGeneralPredicateConstraints() {
        var refined = false;
        for (var check : predicateChecks) {
            var snapshot = new LinkedHashMap<>(check.fixedSnapshot);
            var selected = new ArrayList<FrontierCandidate<KeyType, ValueType>>(
                    check.frontiers.size());

            for (var frontier : check.frontiers) {
                var selectedCandidate = selectedCandidate(frontier);
                selected.add(selectedCandidate);
                if (selectedCandidate == null) {
                    snapshot.remove(frontier.key);
                } else {
                    snapshot.put(frontier.key,
                            selectedCandidate.write.getEvent().getValue());
                }
            }

            if (predicateSnapshotMatches(check.predicateRead, snapshot,
                    check.relationResolver)) {
                continue;
            }

            var blockingClause = new ArrayList<Lit>();
            for (int i = 0; i < check.frontiers.size(); i++) {
                appendNegatedSelection(check.frontiers.get(i), selected.get(i),
                        blockingClause);
            }
            if (blockingClause.isEmpty()) {
                solver.addClause(Lit.False);
            } else {
                solver.assertOr(blockingClause);
            }
            refined = true;
        }
        return refined;
    }

    private boolean refineRowLocalPredicateConstraints(int[] positions) {
        long started = System.nanoTime();
        long keyVisits = 0L;
        long mismatches = 0L;
        long materialized = 0L;
        boolean refined = false;

        for (var check : rowLocalPredicateChecks) {
            for (var entry : check.scopedEntries) {
                keyVisits++;
                var recordedSource = check.recordedSource(entry.keyId);
                if (!rowLocalKeyConstraintViolated(
                        check, entry, recordedSource, positions)) {
                    continue;
                }
                mismatches++;
                if (check.materializedKeyIds.get(entry.keyId)) {
                    throw new IllegalStateException(String.format(
                            "materialized row-local predicate constraint still violated: txn=%s key=%s",
                            check.observation.getTxn().getId(), entry.key));
                }
                materializeRowLocalKeyConstraint(check, entry, recordedSource);
                check.materializedKeyIds.set(entry.keyId);
                materialized++;
                refined = true;
            }
        }

        var profiler = Profiler.getInstance();
        profiler.addDurationNanos("SER_CALFE_REPLAY", System.nanoTime() - started);
        if (collectPredicateMetrics) {
            profiler.addCount("SER_CALFE_REPLAY_ROUNDS_COUNT", 1L);
            profiler.addCount("SER_CALFE_KEY_VISITS_COUNT", keyVisits);
            profiler.addCount("SER_CALFE_MISMATCHES_COUNT", mismatches);
            profiler.addCount("SER_CALFE_MATERIALIZED_KEYS_COUNT", materialized);
        }
        return refined;
    }

    /** Evaluates the unchanged eager reader-key formula in one concrete total AR. */
    private boolean rowLocalKeyConstraintViolated(
            RowLocalPredicateCheck<KeyType, ValueType> check,
            KeyWriteIndex<KeyType, ValueType> entry,
            KnownGraph.WriteRef<KeyType, ValueType> recordedSource,
            int[] positions) {
        var observation = check.observation;
        var predicateRead = observation.getPredicateReadEvent();
        var reader = observation.getTxn();

        if (observation.getPredicateReadType(entry.key)
                == KnownGraph.PredicateReadType.INTERNAL) {
            var latestSelf = entry.latestSelfBefore(reader, observation.getEventIndex());
            if (latestSelf == null) {
                latestSelf = recordedSource;
            }
            if (recordedSource != null) {
                return latestSelf != recordedSource;
            }
            return latestSelf != null
                    && !hasEmptyPredicateContribution(
                            predicateRead, check.relationResolver, latestSelf);
        }

        var latestSelf = entry.latestSelfBefore(reader, observation.getEventIndex());
        if (latestSelf != null) {
            return recordedSource != null && recordedSource != latestSelf;
        }

        var candidates = entry.latestExternalWrites(reader);
        if (recordedSource != null) {
            if (!containsIdentity(candidates, recordedSource)
                    || !beforeInCandidate(recordedSource.getTxn(), reader, positions)) {
                return true;
            }
            for (var other : candidates) {
                if (other == recordedSource
                        || !writeChangesPredicateResult(
                                recordedSource, other, predicateRead)) {
                    continue;
                }
                if (beforeInCandidate(recordedSource.getTxn(), other.getTxn(), positions)
                        && beforeInCandidate(other.getTxn(), reader, positions)) {
                    return true;
                }
            }
            return false;
        }

        var latestVisible = latestVisibleWrite(candidates, reader, positions);
        return latestVisible != null
                && !hasEmptyPredicateContribution(
                        predicateRead, check.relationResolver, latestVisible);
    }

    private KnownGraph.WriteRef<KeyType, ValueType> latestVisibleWrite(
            List<KnownGraph.WriteRef<KeyType, ValueType>> candidates,
            Transaction<KeyType, ValueType> reader,
            int[] positions) {
        KnownGraph.WriteRef<KeyType, ValueType> latest = null;
        for (var candidate : candidates) {
            if (!beforeInCandidate(candidate.getTxn(), reader, positions)) {
                continue;
            }
            if (latest == null
                    || beforeInCandidate(latest.getTxn(), candidate.getTxn(), positions)) {
                latest = candidate;
            }
        }
        return latest;
    }

    private static boolean containsIdentity(List<?> candidates, Object expected) {
        for (var candidate : candidates) {
            if (candidate == expected) {
                return true;
            }
        }
        return false;
    }

    private boolean beforeInCandidate(
            Transaction<KeyType, ValueType> from,
            Transaction<KeyType, ValueType> to,
            int[] positions) {
        if (from == to || from.equals(to)) {
            return false;
        }
        if (isBottomTxn(from)) {
            return !isBottomTxn(to);
        }
        if (isBottomTxn(to)) {
            return false;
        }
        return positions[txnIndex.get(from)] < positions[txnIndex.get(to)];
    }

    /** Builds one deterministic total extension of the current SAT-selected AR. */
    private int[] candidateArPositions() {
        var adjacency = new BitSet[txns.size()];
        var indegree = new int[txns.size()];
        for (int index = 0; index < adjacency.length; index++) {
            adjacency[index] = new BitSet(adjacency.length);
        }
        for (var entry : arCache.entrySet()) {
            if (!modelValue(entry.getValue())) {
                continue;
            }
            int from = txnIndex.get(entry.getKey().getLeft());
            int to = txnIndex.get(entry.getKey().getRight());
            if (!adjacency[from].get(to)) {
                adjacency[from].set(to);
                indegree[to]++;
            }
        }

        var ready = new PriorityQueue<Integer>();
        for (int index = 0; index < indegree.length; index++) {
            if (indegree[index] == 0) {
                ready.add(index);
            }
        }
        var positions = new int[txns.size()];
        int position = 0;
        while (!ready.isEmpty()) {
            int from = ready.remove();
            positions[from] = position++;
            for (int to = adjacency[from].nextSetBit(0); to >= 0;
                    to = adjacency[from].nextSetBit(to + 1)) {
                if (--indegree[to] == 0) {
                    ready.add(to);
                }
            }
        }
        if (position != txns.size()) {
            throw new IllegalStateException("SAT model contains a cyclic AR");
        }
        return positions;
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
            if (selected == null || modelValue(
                    beforeWrite(selected.write, candidate.write))) {
                selected = candidate;
            }
        }
        return selected;
    }

    private Lit selectionGuard(
            KeyFrontier<KeyType, ValueType> frontier,
            FrontierCandidate<KeyType, ValueType> selected) {
        var guard = selected.visible;
        for (var other : frontier.candidates) {
            if (other == selected) {
                continue;
            }
            var laterVisible = and(other.visible,
                    beforeWrite(selected.write, other.write));
            guard = and(guard, Logic.not(laterVisible));
        }
        return guard;
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

    /** Forces one recorded source to be the latest visible write for its key. */
    private void assertLatestVisible(KeyFrontier<KeyType, ValueType> frontier,
            FrontierCandidate<KeyType, ValueType> source,
            List<KnownGraph.WriteRef<KeyType, ValueType>> externalWrites,
            Event<KeyType, ValueType> predicateRead) {
        if (!source.write.getTxn().equals(frontier.reader)) {
            var edge = new SEREdge<KeyType, ValueType>(
                    source.write.getTxn(), frontier.reader, EdgeType.PR_WR, frontier.key);
            addKnownPredicateEdge(edge);
            addDependencyEdge(edge, Lit.True);
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
            addDependencyEdge(
                    new SEREdge<>(frontier.reader, other.getTxn(),
                            EdgeType.PR_RW, frontier.key),
                    beforeWrite(source.write, other));
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
        var cacheKey = ((long) planId << 32) | (writeRefId & 0xffffffffL);
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

    /**
     * Appends the CNF disjunction for the negation of one selected frontier.
     * Fixed recorded frontiers are already globally asserted and can be omitted.
     */
    private void appendNegatedSelection(
            KeyFrontier<KeyType, ValueType> frontier,
            FrontierCandidate<KeyType, ValueType> selected,
            List<Lit> blockingClause) {
        if (frontier.fixedWrite != null) {
            return;
        }
        if (selected == null) {
            // ABSENT means every candidate writer is after the reader.
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
            if (expectedInputs.putIfAbsent(result.getKey(), result.getValue()) != null) {
                return false;
            }
        }
        return evaluation.inputs().equals(expectedInputs);
    }

    private RelationResolver<KeyType> relationResolverFor(
            Event<KeyType, ValueType> predicateRead) {
        var relations = predicateRead.getPredicate().scope().relations();
        return key -> {
            var canonical = String.valueOf(key);
            var separator = canonical.indexOf(':');
            if (separator > 0) {
                return canonical.substring(0, separator);
            }
            if (relations.size() == 1) {
                return relations.iterator().next();
            }
            return "__legacy__";
        };
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

        private FrontierCandidate(KnownGraph.WriteRef<KeyType, ValueType> write, Lit visible) {
            this.write = write;
            this.visible = visible;
        }
    }

    private static final class PredicateCheck<KeyType, ValueType> {
        private final Event<KeyType, ValueType> predicateRead;
        private final List<KeyFrontier<KeyType, ValueType>> frontiers;
        private final Map<KeyType, ValueType> fixedSnapshot;
        private final RelationResolver<KeyType> relationResolver;

        private PredicateCheck(Event<KeyType, ValueType> predicateRead,
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

    private static final class RowLocalPredicateCheck<KeyType, ValueType> {
        private final KnownGraph.PredicateObservation<KeyType, ValueType> observation;
        private final List<KeyWriteIndex<KeyType, ValueType>> scopedEntries;
        private final RelationResolver<KeyType> relationResolver;
        private final Object[] recordedSourcesByKeyId;
        private final BitSet materializedKeyIds = new BitSet();

        private RowLocalPredicateCheck(
                KnownGraph.PredicateObservation<KeyType, ValueType> observation,
                List<KeyWriteIndex<KeyType, ValueType>> scopedEntries,
                RelationResolver<KeyType> relationResolver,
                Object[] recordedSourcesByKeyId) {
            this.observation = observation;
            this.scopedEntries = scopedEntries;
            this.relationResolver = relationResolver;
            this.recordedSourcesByKeyId = recordedSourcesByKeyId;
        }

        @SuppressWarnings("unchecked")
        private KnownGraph.WriteRef<KeyType, ValueType> recordedSource(int keyId) {
            return (KnownGraph.WriteRef<KeyType, ValueType>) recordedSourcesByKeyId[keyId];
        }
    }

    private static final class GuardedDependencyEdge<KeyType, ValueType> {
        private final SEREdge<KeyType, ValueType> edge;
        private final Lit guard;

        private GuardedDependencyEdge(
                SEREdge<KeyType, ValueType> edge,
                Lit guard) {
            this.edge = edge;
            this.guard = guard;
        }
    }

    /**
     * Compares two writes by program order inside one transaction, or by AR when
     * they come from different transactions.
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
        return ar(left.getTxn(), right.getTxn());
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

    private static Lit and(Lit first, Lit second, Lit third) {
        return and(and(first, second), third);
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

    private Lit ar(Transaction<KeyType, ValueType> from, Transaction<KeyType, ValueType> to) {
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
            int fromIndex = txnIndex.get(from);
            int toIndex = txnIndex.get(to);
            if (knownOrder.reachable[fromIndex].get(toIndex)) {
                return Lit.True;
            }
            if (knownOrder.reachable[toIndex].get(fromIndex)) {
                return Lit.False;
            }
        }

        ensureComparable(from, to);
        return directArEdge(from, to);
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
            solver.assertTrue(Logic.xor(directArEdge(first, second), directArEdge(second, first)));
        }
    }

    private Lit directArEdge(Transaction<KeyType, ValueType> from, Transaction<KeyType, ValueType> to) {
        return arCache.computeIfAbsent(Pair.of(from, to), ignored ->
                arGraph.addEdge(arNodes[txnIndex.get(from)], arNodes[txnIndex.get(to)]));
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
        private long comparablePairsCreated;
        private long knownPredicateEdgeAttempts;
        private long knownPredicateEdgeDuplicates;
        private long dependencyEdgeAttempts;
        private long dependencyEdgeDuplicates;
        private long dependencyEdgesSkipped;
        private long dependencyEdgesQueued;
        private long blockingClauses;
        private long blockingClauseLiterals;

        private void publish(Profiler profiler, boolean includeCounts) {
            profiler.addDurationNanos("SER_PRED_SOURCE_INDEX", sourceIndexNanos);
            profiler.addDurationNanos("SER_PRED_SCOPE_LOOKUP", scopeLookupNanos);
            profiler.addDurationNanos(
                    "SER_PRED_SNAPSHOT_VALIDATE", snapshotValidationNanos);
            profiler.addDurationNanos("SER_PRED_ROW_LOCAL_KEY_SCAN", rowLocalKeyScanNanos);
            profiler.addDurationNanos("SER_PRED_GENERAL_KEY_SCAN", generalKeyScanNanos);
            if (!includeCounts) {
                return;
            }
            profiler.addCount("SER_PRED_OBSERVATIONS_COUNT", observations);
            profiler.addCount("SER_PRED_NULL_COUNT", nullPredicates);
            profiler.addCount("SER_PRED_RESULT_SOURCES_COUNT", resultSources);
            profiler.addCount("SER_PRED_DUPLICATE_SOURCES_COUNT", duplicateResultSources);
            profiler.addCount("SER_PRED_SCOPED_KEYS_COUNT", scopedKeys);
            profiler.addCount("SER_PRED_ROW_LOCAL_ATTEMPTS_COUNT", rowLocalAttempts);
            profiler.addCount("SER_PRED_ROW_LOCAL_ENCODED_COUNT", rowLocalEncoded);
            profiler.addCount("SER_PRED_ROW_LOCAL_FALLBACKS_COUNT", rowLocalFallbacks);
            profiler.addCount("SER_PRED_GENERAL_COUNT", generalObservations);
            profiler.addCount("SER_PRED_GENERAL_EXTERNAL_KEYS_COUNT", generalExternalKeys);
            profiler.addCount("SER_PRED_ROW_LOCAL_KEY_VISITS_COUNT", rowLocalKeyVisits);
            profiler.addCount("SER_PRED_INTERNAL_KEYS_COUNT", internalKeys);
            profiler.addCount("SER_PRED_EXTERNAL_KEYS_COUNT", externalKeys);
            profiler.addCount("SER_PRED_RECORDED_SOURCE_KEYS_COUNT", recordedSourceKeys);
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
            profiler.addCount("SER_PRED_DEPENDENCY_ATTEMPTS_COUNT", dependencyEdgeAttempts);
            profiler.addCount("SER_PRED_DEPENDENCY_DUPLICATES_COUNT",
                    dependencyEdgeDuplicates);
            profiler.addCount("SER_PRED_DEPENDENCY_SKIPPED_COUNT", dependencyEdgesSkipped);
            profiler.addCount("SER_PRED_DEPENDENCY_QUEUED_COUNT", dependencyEdgesQueued);
            profiler.addCount("SER_PRED_BLOCKING_CLAUSES_COUNT", blockingClauses);
            profiler.addCount("SER_PRED_BLOCKING_LITERALS_COUNT", blockingClauseLiterals);
        }
    }

    private static boolean isBottomTxn(Transaction<?, ?> txn) {
        return txn.getId() == -1L
                && txn.getSession() != null
                && txn.getSession().getId() == -1L;
    }

    private static final class KnownOrder {
        private final BitSet[] reachable;
        private final List<int[]> reductionEdges;
        private final boolean cyclic;

        private KnownOrder(BitSet[] reachable, List<int[]> reductionEdges, boolean cyclic) {
            this.reachable = reachable;
            this.reductionEdges = reductionEdges;
            this.cyclic = cyclic;
        }

        private static KnownOrder cyclic(int size) {
            var reachable = new BitSet[size];
            for (int i = 0; i < size; i++) {
                reachable[i] = new BitSet(size);
            }
            return new KnownOrder(reachable, Collections.emptyList(), true);
        }
    }

    /**
     * Extracts a compact UNSAT explanation.  If the known graph alone is
     * inconsistent, report a known-edge cycle; otherwise greedily shrink the
     * unresolved WW constraint set while preserving UNSAT.
     */
    private void extractConflicts() {
        if (!isSatisfiable(List.of())) {
            conflictEdges = extractKnownEdgeCycle();
            conflictConstraints = Collections.emptyList();
            return;
        }

        var coreConstraints = new ArrayList<>(constraints);
        for (int i = 0; i < coreConstraints.size(); ) {
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

    private boolean isSatisfiable(Collection<SERConstraint<KeyType, ValueType>> activeConstraints) {
        return new SERSolverAR<>(history, graph, activeConstraints, false, false,
                predicateSolvingMode).solve();
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
        return type != EdgeType.PR_WR && type != EdgeType.PR_RW;
    }
}
