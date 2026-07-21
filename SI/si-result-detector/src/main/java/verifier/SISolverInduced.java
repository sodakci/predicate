package verifier;

import com.google.common.graph.EndpointPair;
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
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;

import java.util.*;
import java.util.stream.Collectors;

class SISolverInduced<KeyType, ValueType> {
    private final History<KeyType, ValueType> history;
    private final KnownGraph<KeyType, ValueType> graph;
    private final Collection<SIConstraint<KeyType, ValueType>> constraints;
    private final Solver solver = new Solver();
    private final Graph depGraph = new Graph(solver);
    private final Graph inducedGraph = new Graph(solver);
    private final Map<Transaction<KeyType, ValueType>, Integer> depNodes = new HashMap<>();
    private final Map<Transaction<KeyType, ValueType>, Integer> inducedNodes = new HashMap<>();
    private final List<GuardedEdge<KeyType, ValueType>> depEdges = new ArrayList<>();
    private final List<GuardedEdge<KeyType, ValueType>> antiDepEdges = new ArrayList<>();
    private final Map<Triple<Transaction<KeyType, ValueType>, Transaction<KeyType, ValueType>, KeyType>, Lit> wwOrder =
            new HashMap<>();
    private final Map<KeyType, List<KnownGraph.WriteRef<KeyType, ValueType>>> writesByKey;

    private Collection<Pair<EndpointPair<Transaction<KeyType, ValueType>>, Collection<Edge<KeyType>>>> conflictEdges =
            Collections.emptyList();
    private Collection<SIConstraint<KeyType, ValueType>> conflictConstraints = Collections.emptyList();

    SISolverInduced(
            History<KeyType, ValueType> history,
            KnownGraph<KeyType, ValueType> graph,
            Collection<SIConstraint<KeyType, ValueType>> constraints) {
        this.history = history;
        this.graph = graph;
        this.constraints = constraints;
        this.writesByKey = buildWritesByKey(graph);
        createNodes();
    }

    boolean solve() {
        conflictEdges = Collections.emptyList();
        conflictConstraints = Collections.emptyList();

        encodeKnownEdges();
        encodeWwChoices();
        encodePredicateConstraints();
        encodeInducedComposition();
        solver.assertTrue(inducedGraph.acyclic());

        boolean sat = solver.solve();
        if (!sat) {
            conflictEdges = SIVerifier.InducedGraph.extractCycleEdges(graph);
            conflictConstraints = constraints;
        }
        return sat;
    }

    Pair<Collection<Pair<EndpointPair<Transaction<KeyType, ValueType>>, Collection<Edge<KeyType>>>>,
            Collection<SIConstraint<KeyType, ValueType>>> getConflicts() {
        return Pair.of(conflictEdges, conflictConstraints);
    }

    private void createNodes() {
        for (var txn : history.getTransactions()) {
            depNodes.put(txn, depGraph.addNode());
            inducedNodes.put(txn, inducedGraph.addNode());
        }
    }

    private void encodeKnownEdges() {
        for (var ep : graph.getKnownGraphA().edges()) {
            for (var edge : graph.getKnownGraphA().edgeValue(ep).orElse(List.of())) {
                if (isDepEdge(edge.getType())) {
                    addDepEdge(ep.source(), ep.target(), edge.getType(), edge.getKey(), Lit.True);
                }
            }
        }
        for (var ep : graph.getKnownGraphB().edges()) {
            for (var edge : graph.getKnownGraphB().edgeValue(ep).orElse(List.of())) {
                if (isAntiDepEdge(edge.getType())) {
                    addAntiDepEdge(ep.source(), ep.target(), edge.getType(), edge.getKey(), Lit.True);
                }
            }
        }
    }

    private void encodeWwChoices() {
        for (var constraint : constraints) {
            var forward = new Lit(solver);
            addConstraintSide(constraint.getEdges1(), forward);
            addConstraintSide(constraint.getEdges2(), Logic.not(forward));
        }
    }

    private void addConstraintSide(Collection<SIEdge<KeyType, ValueType>> edges, Lit guard) {
        if (edges == null) {
            return;
        }
        for (var edge : edges) {
            if (edge.getType() == EdgeType.WW) {
                addDepEdge(edge.getFrom(), edge.getTo(), edge.getType(), edge.getKey(), guard);
                registerWwOrder(edge.getFrom(), edge.getTo(), edge.getKey(), guard);
            } else if (edge.getType() == EdgeType.RW) {
                addAntiDepEdge(edge.getFrom(), edge.getTo(), edge.getType(), edge.getKey(), guard);
            }
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

    private void encodePredicateConstraints() {
        for (var observation : graph.getPredicateObservations()) {
            var predicateRead = observation.getPredicateReadEvent();
            if (predicateRead.getPredicate() == null) {
                continue;
            }

            var resultSourcesByKey = observation.getTupleSources().stream()
                    .collect(Collectors.toMap(
                            KnownGraph.PredicateTupleSource::getKey,
                            KnownGraph.PredicateTupleSource::getSourceWrite));

            for (var entry : writesByKey.entrySet()) {
                var key = entry.getKey();
                var writes = entry.getValue();
                if (writes.isEmpty()) {
                    continue;
                }

                boolean keyInResult = resultSourcesByKey.containsKey(key);
                if (observation.getPredicateReadType(key) != KnownGraph.PredicateReadType.EXTERNAL) {
                    continue;
                }

                if (keyInResult) {
                    encodeFixedPredicateFrontier(
                            observation, key, writes, resultSourcesByKey.get(key), predicateRead);
                } else {
                    encodeOmittedPredicateKeyConstraints(observation, key, writes, predicateRead);
                }
            }
        }
    }

    private void encodeFixedPredicateFrontier(
            KnownGraph.PredicateObservation<KeyType, ValueType> observation,
            KeyType key,
            List<KnownGraph.WriteRef<KeyType, ValueType>> writes,
            KnownGraph.WriteRef<KeyType, ValueType> frontier,
            Event<KeyType, ValueType> predicateRead) {
        if (frontier == null || !writes.contains(frontier) || !writeRowIsInPredicateResult(frontier, predicateRead)) {
            solver.assertTrue(Lit.False);
            return;
        }

        var reader = observation.getTxn();
        if (frontier.getTxn().equals(reader)) {
            solver.assertTrue(frontier.getIndex() < observation.getEventIndex() ? Lit.True : Lit.False);
        } else {
            addDepEdge(frontier.getTxn(), reader, EdgeType.PR_WR, key, Lit.True);
        }

        for (var write : writes) {
            if (write == frontier) {
                continue;
            }

            var afterFrontier = beforeWrite(frontier, write);
            if (afterFrontier == Lit.False) {
                continue;
            }

            solver.assertTrue(Logic.implies(
                    afterFrontier,
                    Logic.not(visibleToPredicateRead(write, observation))));

            var writer = write.getTxn();
            if (writer.equals(reader) || writer.equals(frontier.getTxn())) {
                continue;
            }
            if (writeChangesPredicateResultSet(write, frontier, predicateRead)) {
                addAntiDepEdge(reader, writer, EdgeType.PR_RW, key, afterFrontier);
            }
        }
    }

    private void encodeOmittedPredicateKeyConstraints(
            KnownGraph.PredicateObservation<KeyType, ValueType> observation,
            KeyType key,
            List<KnownGraph.WriteRef<KeyType, ValueType>> writes,
            Event<KeyType, ValueType> predicateRead) {
        var reader = observation.getTxn();
        var visible = new ArrayList<Lit>(writes.size());
        for (var write : writes) {
            visible.add(visibleToPredicateRead(write, observation));
        }

        var initialFrontier = noVisibleWrites(visible);
        var frontierLits = new ArrayList<Lit>(writes.size());
        for (int i = 0; i < writes.size(); i++) {
            var noLaterVisible = new ArrayList<Lit>();
            for (int j = 0; j < writes.size(); j++) {
                if (i == j) {
                    continue;
                }
                noLaterVisible.add(Logic.not(and(
                        visible.get(j),
                        beforeWrite(writes.get(i), writes.get(j)))));
            }
            var frontier = and(visible.get(i), and(noLaterVisible));
            frontierLits.add(frontier);

            var write = writes.get(i);
            if (writeRowIsInPredicateResult(write, predicateRead)) {
                solver.assertTrue(Logic.not(frontier));
            }
        }

        var candidates = new ArrayList<Lit>(frontierLits.size() + 1);
        candidates.add(initialFrontier);
        candidates.addAll(frontierLits);
        solver.assertTrue(or(candidates));
        assertAtMostOne(candidates);

        for (var later : writes) {
            var writer = later.getTxn();
            if (!writer.equals(reader) && writeChangesPredicateResultSet(later, null, predicateRead)) {
                addAntiDepEdge(reader, writer, EdgeType.PR_RW, key, initialFrontier);
            }
        }

        for (int i = 0; i < writes.size(); i++) {
            var frontier = writes.get(i);
            var frontierLit = frontierLits.get(i);
            for (var later : writes) {
                if (later == frontier) {
                    continue;
                }
                var writer = later.getTxn();
                if (writer.equals(reader) || writer.equals(frontier.getTxn())) {
                    continue;
                }
                if (!writeChangesPredicateResultSet(later, frontier, predicateRead)) {
                    continue;
                }
                addAntiDepEdge(reader, writer, EdgeType.PR_RW, key,
                        and(frontierLit, beforeWrite(frontier, later)));
            }
        }
    }

    private void encodeInducedComposition() {
        for (var dep : depEdges) {
            for (var anti : antiDepEdges) {
                if (dep.to.equals(anti.from)) {
                    addInducedEdge(dep.from, anti.to, and(dep.guard, anti.guard));
                }
            }
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
        return depGraph.reaches(depNodes.get(write.getTxn()), depNodes.get(observation.getTxn()));
    }

    private Lit beforeWrite(
            KnownGraph.WriteRef<KeyType, ValueType> left,
            KnownGraph.WriteRef<KeyType, ValueType> right) {
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
        var direct = wwOrder.get(Triple.of(left.getTxn(), right.getTxn(), key));
        if (direct != null) {
            return direct;
        }
        var reverse = wwOrder.get(Triple.of(right.getTxn(), left.getTxn(), key));
        if (reverse != null) {
            return Logic.not(reverse);
        }
        if (hasKnownWw(left.getTxn(), right.getTxn(), key)) {
            return Lit.True;
        }
        if (hasKnownWw(right.getTxn(), left.getTxn(), key)) {
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

    private void addDepEdge(
            Transaction<KeyType, ValueType> from,
            Transaction<KeyType, ValueType> to,
            EdgeType type,
            KeyType key,
            Lit guard) {
        if (!guardCanHold(from, to, guard)) {
            return;
        }
        var guarded = new GuardedEdge<>(from, to, type, key, guard);
        depEdges.add(guarded);
        bindGraphEdge(depGraph, depNodes.get(from), depNodes.get(to), guard);
        bindGraphEdge(inducedGraph, inducedNodes.get(from), inducedNodes.get(to), guard);
    }

    private void addAntiDepEdge(
            Transaction<KeyType, ValueType> from,
            Transaction<KeyType, ValueType> to,
            EdgeType type,
            KeyType key,
            Lit guard) {
        if (!guardCanHold(from, to, guard)) {
            return;
        }
        antiDepEdges.add(new GuardedEdge<>(from, to, type, key, guard));
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
        bindGraphEdge(inducedGraph, inducedNodes.get(from), inducedNodes.get(to), guard);
    }

    private boolean guardCanHold(
            Transaction<KeyType, ValueType> from,
            Transaction<KeyType, ValueType> to,
            Lit guard) {
        if (guard == Lit.False) {
            return false;
        }
        if (from.equals(to) || isBottomTxn(to)) {
            solver.assertTrue(Logic.not(guard));
            return false;
        }
        return true;
    }

    private void bindGraphEdge(Graph targetGraph, int from, int to, Lit guard) {
        if (guard == Lit.False) {
            return;
        }
        var edge = targetGraph.addEdge(from, to);
        if (guard == Lit.True) {
            solver.assertTrue(edge);
            return;
        }
        solver.assertTrue(Logic.implies(guard, edge));
        solver.assertTrue(Logic.implies(edge, guard));
    }

    private void assertAtMostOne(List<Lit> terms) {
        for (int i = 0; i < terms.size(); i++) {
            for (int j = i + 1; j < terms.size(); j++) {
                solver.assertTrue(Logic.not(and(terms.get(i), terms.get(j))));
            }
        }
    }

    private Lit noVisibleWrites(List<Lit> visible) {
        var terms = new ArrayList<Lit>(visible.size());
        for (var lit : visible) {
            terms.add(Logic.not(lit));
        }
        return and(terms);
    }

    private boolean writeRowIsInPredicateResult(
            KnownGraph.WriteRef<KeyType, ValueType> write,
            Event<KeyType, ValueType> predicateRead) {
        return predicateRead.getPredicate().test(write.getEvent().getKey(), write.getEvent().getValue());
    }

    private boolean writeChangesPredicateResultSet(
            KnownGraph.WriteRef<KeyType, ValueType> after,
            KnownGraph.WriteRef<KeyType, ValueType> before,
            Event<KeyType, ValueType> predicateRead) {
        if (before == null) {
            return writeRowIsInPredicateResult(after, predicateRead);
        }
        return !samePredicateResultSetAfterWrite(after, before, predicateRead);
    }

    private boolean samePredicateResultSetAfterWrite(
            KnownGraph.WriteRef<KeyType, ValueType> left,
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

    private static boolean isDepEdge(EdgeType type) {
        return type == EdgeType.SO
                || type == EdgeType.WR
                || type == EdgeType.WW;
    }

    private static boolean isAntiDepEdge(EdgeType type) {
        return type == EdgeType.RW;
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

    private static boolean isBottomTxn(Transaction<?, ?> txn) {
        return txn.getId() == -1L
                && txn.getSession() != null
                && txn.getSession().getId() == -1L;
    }

    private static class GuardedEdge<KeyType, ValueType> {
        private final Transaction<KeyType, ValueType> from;
        private final Transaction<KeyType, ValueType> to;
        private final EdgeType type;
        private final KeyType key;
        private final Lit guard;

        private GuardedEdge(
                Transaction<KeyType, ValueType> from,
                Transaction<KeyType, ValueType> to,
                EdgeType type,
                KeyType key,
                Lit guard) {
            this.from = from;
            this.to = to;
            this.type = type;
            this.key = key;
            this.guard = guard;
        }
    }
}
