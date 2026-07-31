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
import history.query.RelationResolver;
import monosat.Graph;
import monosat.Lit;
import monosat.Logic;
import monosat.Solver;
import com.google.common.graph.EndpointPair;
import org.apache.commons.lang3.tuple.Pair;

import java.util.*;
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
    private final History<KeyType, ValueType> history;
    private final KnownGraph<KeyType, ValueType> graph;
    private final Collection<SERConstraint<KeyType, ValueType>> constraints;
    private final Solver solver = new Solver();
    private final boolean collectConflicts;

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
    // Predicate constraints are refined lazily from concrete SAT models.  This
    // avoids eagerly enumerating the Cartesian product of every key frontier.
    private final List<PredicateCheck<KeyType, ValueType>> predicateChecks = new ArrayList<>();
    // Writer comparability is key-local and independent of the predicate read.
    // Initialize each key's writer pairs once, then reuse them across reads.
    private final Set<KeyType> initializedPredicateWriteOrders = new HashSet<>();
    // Dependency edges are created with their type/key metadata before their
    // guards are encoded into AR. This keeps edge construction separate from
    // constraint encoding and preserves RW/PR_RW as B-side dependencies.
    private final List<GuardedDependencyEdge> dependencyEdgesA = new ArrayList<>();
    private final List<GuardedDependencyEdge> dependencyEdgesB = new ArrayList<>();
    private final Map<Lit, Set<SEREdge<KeyType, ValueType>>> dependencyEdgesByGuard =
            new IdentityHashMap<>();
    private Collection<Pair<EndpointPair<Transaction<KeyType, ValueType>>, Collection<Edge<KeyType>>>> conflictEdges =
            Collections.emptyList();
    private Collection<SERConstraint<KeyType, ValueType>> conflictConstraints = Collections.emptyList();

    SERSolverAR(History<KeyType, ValueType> history,
                KnownGraph<KeyType, ValueType> graph,
                Collection<SERConstraint<KeyType, ValueType>> constraints) {
        this(history, graph, constraints, true);
    }

    private SERSolverAR(History<KeyType, ValueType> history,
                        KnownGraph<KeyType, ValueType> graph,
                        Collection<SERConstraint<KeyType, ValueType>> constraints,
                        boolean collectConflicts) {
        this.history = history;
        this.graph = graph;
        this.constraints = constraints;
        this.collectConflicts = collectConflicts;
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
        encodeKnownEdges();
        encodeRemainingWwChoices();
        encodeRwFromWrAndWw();
        encodePredicateConstraints();
        encodeDependencyEdges();
        encodeStrictTotalOrder();
    }

    /**
     * Solves the AR encoding.  On UNSAT, the outer solver instance can collect
     * a reduced explanation; recursive satisfiability checks disable that work.
     */
    boolean solve() {
        while (solver.solve()) {
            if (refinePredicateConstraints()) {
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

        extractConflicts();
        return false;
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
        /*
         * A false guard is already a tautology. Checking it before resolving
         * the target AR edge avoids creating unused graph variables for the
         * many RW alternatives ruled out by pruning.
         */
        if (guard == Lit.False) {
            return;
        }
        if (!dependencyEdgesByGuard
                .computeIfAbsent(guard, ignored -> new HashSet<>())
                .add(edge)) {
            return;
        }

        var target = ar(edge.getFrom(), edge.getTo());
        if (target == Lit.True || guard == target) {
            return;
        }

        var guarded = new GuardedDependencyEdge(guard, target);
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

    private void encodeDependencyEdge(GuardedDependencyEdge guarded) {
        if (guarded.guard == Lit.True) {
            solver.assertTrue(guarded.target);
        } else {
            solver.assertTrue(Logic.implies(guarded.guard, guarded.target));
        }
    }

    private void addKnownPredicateEdge(SEREdge<KeyType, ValueType> edge) {
        var existing = graph.getKnownGraphA()
                .edgeValue(edge.getFrom(), edge.getTo())
                .orElse(Collections.emptyList());
        var graphEdge = new Edge<KeyType>(edge.getType(), edge.getKey());
        if (!existing.contains(graphEdge)) {
            graph.putEdge(edge.getFrom(), edge.getTo(), graphEdge);
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
        for (var observation : graph.getPredicateObservations()) {
            var predicateRead = observation.getPredicateReadEvent();
            var predicate = predicateRead.getPredicate();
            if (predicate == null) {
                continue;
            }

            var resultSourcesByKey = new LinkedHashMap<KeyType,
                    KnownGraph.WriteRef<KeyType, ValueType>>();
            for (var source : observation.getTupleSources()) {
                if (resultSourcesByKey.putIfAbsent(source.getKey(), source.getSourceWrite()) != null) {
                    solver.assertTrue(Lit.False);
                }
            }

            var scopedEntries = writesByKey.entrySet().stream()
                    .filter(entry -> predicate.scope().covers(entry.getKey()))
                    .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .collect(Collectors.toList());

            if (predicate instanceof QueryPlan
                    && ((QueryPlan<?, ?>) predicate).isRowLocal()
                    && encodeRowLocalPredicate(observation, scopedEntries,
                            resultSourcesByKey)) {
                continue;
            }

            var frontierEntries = scopedEntries.stream()
                    .filter(entry -> observation.getPredicateReadType(entry.getKey())
                            == KnownGraph.PredicateReadType.EXTERNAL)
                    .collect(Collectors.toList());
            if (frontierEntries.isEmpty()) {
                // Internal predicate keys are checked by the same evaluator in
                // Utils before solver construction.
                continue;
            }

            var frontiers = new ArrayList<KeyFrontier<KeyType, ValueType>>(frontierEntries.size());
            for (var entry : frontierEntries) {
                frontiers.add(createKeyFrontier(observation, entry.getKey(), entry.getValue(),
                        resultSourcesByKey.get(entry.getKey())));
            }

            for (var resultKey : resultSourcesByKey.keySet()) {
                if (!predicate.scope().covers(resultKey)) {
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
                        .filter(write -> write.getTxn().equals(observation.getTxn())
                                && write.getIndex() < observation.getEventIndex())
                        .max(Comparator.comparingInt(KnownGraph.WriteRef::getIndex))
                        .orElse(resultSourcesByKey.get(entry.getKey()));
                if (latestSelf != null) {
                    snapshot.put(entry.getKey(), latestSelf.getEvent().getValue());
                }
            }
            predicateChecks.add(new PredicateCheck<>(predicateRead, frontiers,
                    snapshot, relationResolverFor(predicateRead)));
        }
    }

    /**
     * A single scan/filter without DISTINCT is the bag union of independent
     * single-row evaluations. Encode each key directly, avoiding a whole-table
     * lazy snapshot with one frontier per key.
     */
    private boolean encodeRowLocalPredicate(
            KnownGraph.PredicateObservation<KeyType, ValueType> observation,
            List<Map.Entry<KeyType, List<KnownGraph.WriteRef<KeyType, ValueType>>>> scopedEntries,
            Map<KeyType, KnownGraph.WriteRef<KeyType, ValueType>> resultSourcesByKey) {
        var predicateRead = observation.getPredicateReadEvent();
        var relationResolver = relationResolverFor(predicateRead);
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

        for (var entry : scopedEntries) {
            var key = entry.getKey();
            var writes = entry.getValue();
            var recordedSource = resultSourcesByKey.get(key);

            if (observation.getPredicateReadType(key)
                    == KnownGraph.PredicateReadType.INTERNAL) {
                var latestSelf = writes.stream()
                        .filter(write -> write.getTxn().equals(observation.getTxn())
                                && write.getIndex() < observation.getEventIndex())
                        .max(Comparator.comparingInt(KnownGraph.WriteRef::getIndex))
                        .orElse(recordedSource);
                if (recordedSource != null) {
                    if (latestSelf != recordedSource) {
                        solver.assertTrue(Lit.False);
                    }
                } else if (latestSelf != null
                        && !hasEmptyPredicateContribution(predicateRead, relationResolver,
                                key, latestSelf.getEvent().getValue())) {
                    solver.assertTrue(Lit.False);
                }
                continue;
            }

            if (recordedSource != null) {
                assertRecordedSourceLatest(
                        observation, key, writes, recordedSource);
                continue;
            }

            var badWrites = latestExternalWrites(writes, observation.getTxn()).stream()
                    .filter(write -> !hasEmptyPredicateContribution(
                            predicateRead, relationResolver, key,
                            write.getEvent().getValue()))
                    .collect(Collectors.toList());
            if (badWrites.isEmpty()) {
                continue;
            }

            var frontier = createKeyFrontier(
                    observation, key, writes, null, false);
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

        for (var resultKey : resultSourcesByKey.keySet()) {
            if (!predicateRead.getPredicate().scope().covers(resultKey)
                    || !writesByKey.containsKey(resultKey)) {
                solver.assertTrue(Lit.False);
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
            KeyType key,
            ValueType value) {
        final QueryEvaluation<KeyType, ValueType> evaluation;
        try {
            evaluation = predicateRead.getPredicate().evaluate(
                    new MapVisibleState<>(Map.of(key, value), relationResolver));
        } catch (QueryException exception) {
            return false;
        }

        if (predicateRead.getRecordedPredicateResult() == null) {
            return evaluation.inputs().isEmpty();
        }
        return evaluation.inputs().isEmpty() && evaluation.values().isEmpty();
    }

    private List<KnownGraph.WriteRef<KeyType, ValueType>> latestExternalWrites(
            List<KnownGraph.WriteRef<KeyType, ValueType>> writes,
            Transaction<KeyType, ValueType> reader) {
        var latestByWriter = new LinkedHashMap<Transaction<KeyType, ValueType>,
                KnownGraph.WriteRef<KeyType, ValueType>>();
        for (var write : writes) {
            latestByWriter.put(write.getTxn(), write);
        }
        latestByWriter.remove(reader);
        return new ArrayList<>(latestByWriter.values());
    }

    private void assertRecordedSourceLatest(
            KnownGraph.PredicateObservation<KeyType, ValueType> observation,
            KeyType key,
            List<KnownGraph.WriteRef<KeyType, ValueType>> writes,
            KnownGraph.WriteRef<KeyType, ValueType> recordedSource) {
        var latestSelf = writes.stream()
                .filter(write -> write.getTxn().equals(observation.getTxn())
                        && write.getIndex() < observation.getEventIndex())
                .max(Comparator.comparingInt(KnownGraph.WriteRef::getIndex))
                .orElse(null);
        if (latestSelf != null) {
            if (latestSelf != recordedSource) {
                solver.assertTrue(Lit.False);
            }
            return;
        }

        var candidates = latestExternalWrites(writes, observation.getTxn());
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
            KeyType key,
            List<KnownGraph.WriteRef<KeyType, ValueType>> writes,
            KnownGraph.WriteRef<KeyType, ValueType> recordedSource) {
        return createKeyFrontier(observation, key, writes, recordedSource, true);
    }

    private KeyFrontier<KeyType, ValueType> createKeyFrontier(
            KnownGraph.PredicateObservation<KeyType, ValueType> observation,
            KeyType key,
            List<KnownGraph.WriteRef<KeyType, ValueType>> writes,
            KnownGraph.WriteRef<KeyType, ValueType> recordedSource,
            boolean initializeAllWriterPairs) {
        var latestSelf = writes.stream()
                .filter(write -> write.getTxn().equals(observation.getTxn())
                        && write.getIndex() < observation.getEventIndex())
                .max(Comparator.comparingInt(KnownGraph.WriteRef::getIndex))
                .orElse(null);
        if (latestSelf != null) {
            if (recordedSource != null && recordedSource != latestSelf) {
                solver.assertTrue(Lit.False);
            }
            return new KeyFrontier<>(key,
                    observation.getTxn(),
                    List.of(new FrontierCandidate<>(latestSelf, Lit.True)), latestSelf);
        }

        // Only the final write to a key in one transaction can be externally
        // visible.  Earlier writes in the same transaction can never be a
        // latest-visible frontier.
        var latestByWriter = new LinkedHashMap<Transaction<KeyType, ValueType>,
                KnownGraph.WriteRef<KeyType, ValueType>>();
        for (var write : writes) {
            latestByWriter.put(write.getTxn(), write);
        }

        // The latest candidate is selected from a strict total order. Writer
        // comparability depends only on the key's complete writer set, so
        // repeated predicate reads can reuse the same primitive AR literals.
        if (initializeAllWriterPairs && initializedPredicateWriteOrders.add(key)) {
            var comparableWrites = new ArrayList<>(latestByWriter.values());
            for (int i = 0; i < comparableWrites.size(); i++) {
                for (int j = i + 1; j < comparableWrites.size(); j++) {
                    beforeWrite(comparableWrites.get(i), comparableWrites.get(j));
                }
            }
        }

        latestByWriter.remove(observation.getTxn());
        var candidates = latestByWriter.values().stream()
                .map(write -> new FrontierCandidate<>(write,
                        ar(write.getTxn(), observation.getTxn())))
                .filter(candidate -> candidate.visible != Lit.False)
                .collect(Collectors.toList());

        var frontier = new KeyFrontier<KeyType, ValueType>(
                key, observation.getTxn(), candidates, recordedSource);

        if (recordedSource == null) {
            return frontier;
        }

        var source = candidateFor(frontier, recordedSource);
        if (source == null) {
            solver.assertTrue(Lit.False);
            return frontier;
        }
        assertLatestVisible(
                frontier, source, observation.getPredicateReadEvent());
        return frontier;
    }

    /**
     * Validates all predicate reads against the current SAT model and adds one
     * direct no-good clause for every mismatching visible snapshot.
     */
    private boolean refinePredicateConstraints() {
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
            Event<KeyType, ValueType> predicateRead) {
        if (!source.write.getTxn().equals(frontier.reader)) {
            var edge = new SEREdge<KeyType, ValueType>(
                    source.write.getTxn(), frontier.reader, EdgeType.PR_WR, frontier.key);
            addKnownPredicateEdge(edge);
            addDependencyEdge(edge, Lit.True);
        } else {
            solver.assertTrue(source.visible);
        }
        for (var other : frontier.candidates) {
            if (other == source) {
                continue;
            }
            if (!writeChangesPredicateResult(
                    source.write, other.write, predicateRead)) {
                continue;
            }
            addDependencyEdge(
                    new SEREdge<>(frontier.reader, other.write.getTxn(),
                            EdgeType.PR_RW, frontier.key),
                    beforeWrite(source.write, other.write));
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
        return sourceMatches
                && (!Objects.equals(source.getEvent().getKey(), later.getEvent().getKey())
                || !Objects.equals(source.getEvent().getValue(), later.getEvent().getValue()));
    }

    private boolean writeMatchesPredicate(
            KnownGraph.WriteRef<KeyType, ValueType> write,
            Event<KeyType, ValueType> predicateRead) {
        var event = write.getEvent();
        try {
            var evaluation = predicateRead.getPredicate().evaluate(
                    new MapVisibleState<>(
                            Map.of(event.getKey(), event.getValue()),
                            relationResolverFor(predicateRead)));
            return evaluation.inputs().containsKey(event.getKey());
        } catch (QueryException exception) {
            return false;
        }
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

    private static final class GuardedDependencyEdge {
        private final Lit guard;
        private final Lit target;

        private GuardedDependencyEdge(Lit guard, Lit target) {
            this.guard = guard;
            this.target = target;
        }
    }

    /**
     * Compares two writes by program order inside one transaction, or by AR when
     * they come from different transactions.
     */
    private Lit beforeWrite(KnownGraph.WriteRef<KeyType, ValueType> left,
                            KnownGraph.WriteRef<KeyType, ValueType> right) {
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
            solver.assertTrue(Logic.xor(directArEdge(first, second), directArEdge(second, first)));
        }
    }

    private Lit directArEdge(Transaction<KeyType, ValueType> from, Transaction<KeyType, ValueType> to) {
        return arCache.computeIfAbsent(Pair.of(from, to), ignored ->
                arGraph.addEdge(arNodes[txnIndex.get(from)], arNodes[txnIndex.get(to)]));
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
        return new SERSolverAR<>(history, graph, activeConstraints, false).solve();
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
