package verifier;

import graph.Edge;
import graph.EdgeType;
import graph.KnownGraph;
import history.Event;
import history.History;
import history.Transaction;
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
        this.arGraph = new Graph(solver);
        this.arNodes = createArNodes();
        this.writesByKey = buildWritesByKey(graph);
        encodeKnownEdges();
        encodeRemainingWwChoices();
        encodeRwFromWrAndWw();
        encodePredicateConstraints();
        encodeStrictTotalOrder();
    }

    /**
     * Solves the AR encoding.  On UNSAT, the outer solver instance can collect
     * a reduced explanation; recursive satisfiability checks disable that work.
     */
    boolean solve() {
        boolean sat = solver.solve();
        if (sat || !collectConflicts) {
            conflictEdges = Collections.emptyList();
            conflictConstraints = Collections.emptyList();
            return sat;
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
     * Existing precedence edges are mandatory AR edges.  The graph keeps two
     * known-edge partitions, but both must be respected by the same order.
     */
    private void encodeKnownEdges() {
        encodeKnownGraphRespectAr(graph.getKnownGraphA());
        encodeKnownGraphRespectAr(graph.getKnownGraphB());
    }

    private void encodeKnownGraphRespectAr(com.google.common.graph.ValueGraph<Transaction<KeyType, ValueType>, Collection<Edge<KeyType>>> known) {
        for (var ep : known.edges()) {
            var edges = known.edgeValue(ep).orElse(Collections.emptyList());
            if (edges.stream().anyMatch(edge -> isEncodedKnownEdge(edge.getType()))) {
                solver.assertTrue(ar(ep.source(), ep.target()));
            }
        }
    }

    /**
     * Encodes each unresolved WW pair as a binary choice.  Choosing one write
     * direction also activates the dependent edges generated for that branch.
     */
    private void encodeRemainingWwChoices() {
        for (var c : constraints) {
            var forward = ar(c.getWriteTransaction1(), c.getWriteTransaction2());
            var backward = ar(c.getWriteTransaction2(), c.getWriteTransaction1());
            solver.assertTrue(Logic.xor(forward, backward));

            for (var edge : c.getEdges1()) {
                solver.assertTrue(Logic.implies(forward, ar(edge.getFrom(), edge.getTo())));
            }
            for (var edge : c.getEdges2()) {
                solver.assertTrue(Logic.implies(backward, ar(edge.getFrom(), edge.getTo())));
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
                    solver.assertTrue(Logic.implies(ar(ep.source(), u), ar(ep.target(), u)));
                }
            }
        }
    }

    /**
     * Encodes predicate-read consistency without pre-materializing PR_* edges.
     *
     * <p>For each observed predicate read and each key, the encoding identifies
     * the latest visible write frontier. That frontier writer implies PR_WR.
     * PR_RW then follows a later WW writer when Delta(frontier, later, key)
     * holds under the canonical predicate result.</p>
     */
    private void encodePredicateConstraints() {
        for (var observation : graph.getPredicateObservations()) {
            var predicateRead = observation.getPredicateReadEvent();
            if (predicateRead.getPredicate() == null) {
                continue;
            }

            var predWrGuardsBySource = new HashMap<Transaction<KeyType, ValueType>, Lit>();
            var predRwGuardsByTarget = new HashMap<Transaction<KeyType, ValueType>, Lit>();
            var resultSourcesByKey = observation.getTupleSources().stream()
                    .collect(Collectors.toMap(KnownGraph.PredicateTupleSource::getKey, KnownGraph.PredicateTupleSource::getSourceWrite));

            for (var entry : writesByKey.entrySet()) {
                var key = entry.getKey();
                var writes = entry.getValue();
                if (writes.isEmpty()) {
                    continue;
                }

                boolean keyInResult = resultSourcesByKey.containsKey(key);
                if (!keyInResult && writes.stream().noneMatch(write -> writeRowIsInPredicateResult(write, predicateRead))) {
                    continue;
                }

                var visibleWriters = createVisibleWriterLits(observation, key, writes, resultSourcesByKey);
                var observationFrontier = keyInResult
                        ? fixedObservationFrontier(resultSourcesByKey.get(key), writes, predicateRead)
                        : createObservationFrontierLits(visibleWriters, writes);
                if (!keyInResult) {
                    assertObservationFrontierMatchesResult(false, observationFrontier, writes, predicateRead);
                }
                for (int i = 0; i < writes.size(); i++) {
                    var t = writes.get(i).getTxn();
                    if (!t.equals(observation.getTxn())) {
                        addGuard(predWrGuardsBySource, t, observationFrontier.get(i));
                    }

                    for (int j = 0; j < writes.size(); j++) {
                        if (i == j) {
                            continue;
                        }
                        var u = writes.get(j).getTxn();
                        if (u.equals(observation.getTxn()) || u.equals(t)) {
                            continue;
                        }

                        var before = beforeWrite(writes.get(i), writes.get(j));
                        if (before == Lit.False
                                || observationFrontier.get(i) == Lit.False
                                || !writeChangesPredicateResultSet(writes.get(j), writes.get(i), predicateRead)) {
                            continue;
                        }
                        var predRw = and(
                                observationFrontier.get(i),
                                before);
                        addGuard(predRwGuardsByTarget, u, predRw);
                    }
                }
            }

            for (var entry : predWrGuardsBySource.entrySet()) {
                solver.assertTrue(Logic.implies(entry.getValue(), ar(entry.getKey(), observation.getTxn())));
            }
            for (var entry : predRwGuardsByTarget.entrySet()) {
                solver.assertTrue(Logic.implies(entry.getValue(), ar(observation.getTxn(), entry.getKey())));
            }
        }
    }

    private void addGuard(Map<Transaction<KeyType, ValueType>, Lit> guards,
                          Transaction<KeyType, ValueType> txn,
                          Lit guard) {
        if (guard == Lit.False) {
            return;
        }
        guards.merge(txn, guard, SERSolverAR::or);
    }

    /**
     * For one predicate observation and key, computes whether each write can be
     * visible to the predicate read under the current AR assignment.
     */
    private List<Lit> createVisibleWriterLits(KnownGraph.PredicateObservation<KeyType, ValueType> observation,
                                              KeyType key,
                                              List<KnownGraph.WriteRef<KeyType, ValueType>> writes,
                                              Map<KeyType, KnownGraph.WriteRef<KeyType, ValueType>> resultSourcesByKey) {
        var resultSource = resultSourcesByKey.get(key);
        var result = new ArrayList<Lit>(writes.size());
        int predicateIndex = observation.getEventIndex();
        if (resultSource != null) {
            if (resultSource.getTxn().equals(observation.getTxn())) {
                solver.assertTrue(resultSource.getIndex() < predicateIndex ? Lit.True : Lit.False);
            } else {
                solver.assertTrue(ar(resultSource.getTxn(), observation.getTxn()));
            }
        }
        for (var write : writes) {
            if (resultSource != null) {
                solver.assertTrue(Logic.implies(
                        beforeWrite(resultSource, write),
                        Logic.not(visibleToPredicateRead(write, observation))));
                result.add(beforeOrEqualWrite(write, resultSource));
                continue;
            }
            if (write.getTxn().equals(observation.getTxn())) {
                result.add(write.getIndex() < predicateIndex ? Lit.True : Lit.False);
                continue;
            }
            result.add(ar(write.getTxn(), observation.getTxn()));
        }
        return result;
    }

    private Lit visibleToPredicateRead(KnownGraph.WriteRef<KeyType, ValueType> write,
                                       KnownGraph.PredicateObservation<KeyType, ValueType> observation) {
        if (write.getTxn().equals(observation.getTxn())) {
            return write.getIndex() < observation.getEventIndex() ? Lit.True : Lit.False;
        }
        return ar(write.getTxn(), observation.getTxn());
    }

    /**
     * The selected latest visible write must agree with the observed
     * PredicateResult presence for this key.
     */
    private void assertObservationFrontierMatchesResult(
            boolean keyInResult,
            List<Lit> observationFrontier,
            List<KnownGraph.WriteRef<KeyType, ValueType>> writes,
            Event<KeyType, ValueType> predicateRead) {
        for (int i = 0; i < writes.size(); i++) {
            if (writeRowIsInPredicateResult(writes.get(i), predicateRead) != keyInResult) {
                solver.assertTrue(Logic.not(observationFrontier.get(i)));
            }
        }
    }

    private List<Lit> fixedObservationFrontier(
            KnownGraph.WriteRef<KeyType, ValueType> resultSource,
            List<KnownGraph.WriteRef<KeyType, ValueType>> writes,
            Event<KeyType, ValueType> predicateRead) {
        var result = new ArrayList<Lit>(writes.size());
        for (var write : writes) {
            result.add(write == resultSource ? Lit.True : Lit.False);
        }
        if (resultSource != null && !writeRowIsInPredicateResult(resultSource, predicateRead)) {
            solver.assertTrue(Lit.False);
        }
        return result;
    }

    /**
     * Selects the latest visible write for the observed key.  At most one write
     * can be on this frontier because AR is a total order.
     */
    private List<Lit> createObservationFrontierLits(
            List<Lit> visibleWriters,
            List<KnownGraph.WriteRef<KeyType, ValueType>> writes) {
        var result = new ArrayList<Lit>(writes.size());
        for (int i = 0; i < writes.size(); i++) {
            var noLaterVisibleWriterTerms = new ArrayList<Lit>();
            for (int j = 0; j < writes.size(); j++) {
                if (i == j) {
                    continue;
                }
                noLaterVisibleWriterTerms.add(Logic.not(and(
                        visibleWriters.get(j),
                        beforeWrite(writes.get(i), writes.get(j)))));
            }
            result.add(and(visibleWriters.get(i), and(noLaterVisibleWriterTerms)));
        }
        for (int i = 0; i < writes.size(); i++) {
            for (int j = i + 1; j < writes.size(); j++) {
                solver.assertTrue(Logic.not(and(result.get(i), result.get(j))));
            }
        }
        return result;
    }

    /** Compares two writes in the local write order, allowing equality. */
    private Lit beforeOrEqualWrite(KnownGraph.WriteRef<KeyType, ValueType> left,
                                   KnownGraph.WriteRef<KeyType, ValueType> right) {
        if (left == right) {
            return Lit.True;
        }
        return beforeWrite(left, right);
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

    /** Returns true when this write's row appears in the predicate result set. */
    private boolean writeRowIsInPredicateResult(KnownGraph.WriteRef<KeyType, ValueType> write,
                                                Event<KeyType, ValueType> predicateRead) {
        return predicateRead.getPredicate().test(write.getEvent().getKey(), write.getEvent().getValue());
    }

    /**
     * Compare the PredicateResult set before and after a write to this key.
     * Source write identity is deliberately ignored: PR_* is triggered only by
     * a row entering the result, leaving the result, or changing its key/value.
     */
    private boolean samePredicateResultSetAfterWrite(KnownGraph.WriteRef<KeyType, ValueType> left,
                                                     KnownGraph.WriteRef<KeyType, ValueType> right,
                                                     Event<KeyType, ValueType> predicateRead) {
        boolean leftInResult = writeRowIsInPredicateResult(left, predicateRead);
        boolean rightInResult = writeRowIsInPredicateResult(right, predicateRead);
        if (!leftInResult && !rightInResult) {
            return true;
        }
        if (leftInResult != rightInResult) {
            return false;
        }
        return Objects.equals(left.getEvent().getKey(), right.getEvent().getKey())
                && Objects.equals(left.getEvent().getValue(), right.getEvent().getValue());
    }

    private boolean writeChangesPredicateResultSet(KnownGraph.WriteRef<KeyType, ValueType> after,
                                                   KnownGraph.WriteRef<KeyType, ValueType> before,
                                                   Event<KeyType, ValueType> predicateRead) {
        return !samePredicateResultSetAfterWrite(after, before, predicateRead);
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
