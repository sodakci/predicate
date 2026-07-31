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
import history.query.RelationResolver;
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
    private final List<PredicateCheck<KeyType, ValueType>> predicateChecks =
            new ArrayList<>();

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

        while (solver.solve()) {
            if (refinePredicateConstraints()) {
                continue;
            }
            return true;
        }
        conflictEdges = SIVerifier.InducedGraph.extractCycleEdges(graph);
        conflictConstraints = constraints;
        return false;
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
            var predicate = predicateRead.getPredicate();
            if (predicate == null) {
                continue;
            }

            var resultSourcesByKey = new LinkedHashMap<KeyType,
                    KnownGraph.WriteRef<KeyType, ValueType>>();
            for (var source : observation.getTupleSources()) {
                if (resultSourcesByKey.putIfAbsent(
                        source.getKey(), source.getSourceWrite()) != null) {
                    solver.assertTrue(Lit.False);
                }
            }

            var scopedEntries = writesByKey.entrySet().stream()
                    .filter(entry -> predicate.scope().covers(entry.getKey()))
                    .sorted(Comparator.comparing(
                            entry -> String.valueOf(entry.getKey())))
                    .collect(Collectors.toList());
            var frontierEntries = scopedEntries.stream()
                    .filter(entry -> observation.getPredicateReadType(entry.getKey())
                            == KnownGraph.PredicateReadType.EXTERNAL)
                    .collect(Collectors.toList());
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

    private KeyFrontier<KeyType, ValueType> createKeyFrontier(
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
            if (recordedSource != null && recordedSource != latestSelf) {
                solver.assertTrue(Lit.False);
            }
            return new KeyFrontier<>(key, observation.getTxn(),
                    List.of(new FrontierCandidate<>(latestSelf, Lit.True)),
                    latestSelf);
        }

        var latestByWriter = new LinkedHashMap<Transaction<KeyType, ValueType>,
                KnownGraph.WriteRef<KeyType, ValueType>>();
        for (var write : writes) {
            latestByWriter.put(write.getTxn(), write);
        }
        latestByWriter.remove(observation.getTxn());
        var predicateSourceGuards =
                new IdentityHashMap<KnownGraph.WriteRef<KeyType, ValueType>, Lit>();
        if (recordedSource == null) {
            for (var write : latestByWriter.values()) {
                if (isBottomTxn(write.getTxn())) {
                    continue;
                }
                var sourceGuard = new Lit(solver);
                predicateSourceGuards.put(write, sourceGuard);
                addDepEdge(write.getTxn(), observation.getTxn(),
                        EdgeType.PR_WR, key, sourceGuard);
            }
        }
        var candidates = latestByWriter.values().stream()
                .map(write -> new FrontierCandidate<>(write,
                        visibleToPredicateRead(write, observation)))
                .filter(candidate -> candidate.visible != Lit.False)
                .collect(Collectors.toList());
        var frontier = new KeyFrontier<>(
                key, observation.getTxn(), candidates, recordedSource);
        if (recordedSource == null) {
            bindPredicateSourceGuards(frontier, predicateSourceGuards);
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

    private void bindPredicateSourceGuards(
            KeyFrontier<KeyType, ValueType> frontier,
            Map<KnownGraph.WriteRef<KeyType, ValueType>, Lit> sourceGuards) {
        for (var candidate : frontier.candidates) {
            var sourceGuard = sourceGuards.get(candidate.write);
            if (sourceGuard == null) {
                continue;
            }
            var selectedGuard = selectionGuard(frontier, candidate);
            solver.assertTrue(Logic.implies(sourceGuard, selectedGuard));
            solver.assertTrue(Logic.implies(selectedGuard, sourceGuard));
        }
    }

    private void assertLatestVisible(
            KeyFrontier<KeyType, ValueType> frontier,
            FrontierCandidate<KeyType, ValueType> source,
            Event<KeyType, ValueType> predicateRead) {
        if (!source.write.getTxn().equals(frontier.reader)) {
            addDepEdge(source.write.getTxn(), frontier.reader,
                    EdgeType.PR_WR, frontier.key, Lit.True);
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
            var afterSource = beforeWrite(source.write, other.write);
            if (afterSource == Lit.False) {
                continue;
            }
            solver.assertTrue(Logic.implies(
                    afterSource, Logic.not(other.visible)));
        }
    }

    private boolean writeChangesPredicateResult(
            KnownGraph.WriteRef<KeyType, ValueType> source,
            KnownGraph.WriteRef<KeyType, ValueType> later,
            Event<KeyType, ValueType> predicateRead) {
        var resolver = relationResolverFor(predicateRead);
        try {
            var sourceEvent = source.getEvent();
            var sourceResult = predicateRead.getPredicate().evaluate(
                    new MapVisibleState<>(
                            Map.of(sourceEvent.getKey(), sourceEvent.getValue()),
                            resolver));
            var laterEvent = later.getEvent();
            var laterResult = predicateRead.getPredicate().evaluate(
                    new MapVisibleState<>(
                            Map.of(laterEvent.getKey(), laterEvent.getValue()),
                            resolver));
            return !sourceResult.canonicalEquals(laterResult);
        } catch (QueryException exception) {
            return true;
        }
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
                refined |= encodePredicateDependencies(
                        check, snapshot, selected);
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
            refined = true;
        }
        return refined;
    }

    private boolean encodePredicateDependencies(
            PredicateCheck<KeyType, ValueType> check,
            Map<KeyType, ValueType> snapshot,
            List<FrontierCandidate<KeyType, ValueType>> selected) {
        var selectionKey =
                new ArrayList<KnownGraph.WriteRef<KeyType, ValueType>>(
                        selected.size());
        for (var candidate : selected) {
            selectionKey.add(candidate == null ? null : candidate.write);
        }
        if (!check.encodedSelections.add(selectionKey)) {
            return false;
        }

        var selectionTerms = new ArrayList<Lit>(check.frontiers.size());
        for (int i = 0; i < check.frontiers.size(); i++) {
            selectionTerms.add(selectionGuard(
                    check.frontiers.get(i), selected.get(i)));
        }
        var snapshotGuard = and(selectionTerms);
        var added = false;
        for (int i = 0; i < check.frontiers.size(); i++) {
            var frontier = check.frontiers.get(i);
            var predecessor = selected.get(i);
            for (var later : frontier.candidates) {
                if (later == predecessor
                        || later.write.getTxn().equals(frontier.reader)) {
                    continue;
                }
                var afterPredecessor = predecessor == null
                        ? Lit.True
                        : beforeWrite(predecessor.write, later.write);
                if (afterPredecessor == Lit.False
                        || !queryChangesAfterWrite(
                                check, snapshot, frontier.key,
                                later.write.getEvent().getValue())) {
                    continue;
                }
                var guard = and(snapshotGuard, afterPredecessor);
                if (!addAntiDepEdge(frontier.reader, later.write.getTxn(),
                        EdgeType.PR_RW, frontier.key, guard)) {
                    continue;
                }
                for (var dep : depEdges) {
                    if (dep.to.equals(frontier.reader)) {
                        addInducedEdge(
                                dep.from, later.write.getTxn(),
                                and(dep.guard, guard));
                    }
                }
                added = true;
            }
        }
        return added;
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

    private boolean queryChangesAfterWrite(
            PredicateCheck<KeyType, ValueType> check,
            Map<KeyType, ValueType> snapshot,
            KeyType key,
            ValueType value) {
        try {
            var before = check.predicateRead.getPredicate().evaluate(
                    new MapVisibleState<>(snapshot, check.relationResolver));
            var afterSnapshot = new LinkedHashMap<>(snapshot);
            afterSnapshot.put(key, value);
            var after = check.predicateRead.getPredicate().evaluate(
                    new MapVisibleState<>(
                            afterSnapshot, check.relationResolver));
            return !before.canonicalEquals(after);
        } catch (QueryException exception) {
            return true;
        }
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

    private boolean addAntiDepEdge(
            Transaction<KeyType, ValueType> from,
            Transaction<KeyType, ValueType> to,
            EdgeType type,
            KeyType key,
            Lit guard) {
        if (!guardCanHold(from, to, guard)) {
            return false;
        }
        antiDepEdges.add(new GuardedEdge<>(from, to, type, key, guard));
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
        private final Set<List<KnownGraph.WriteRef<KeyType, ValueType>>>
                encodedSelections = new HashSet<>();

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
