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
    // Dependency edges are created with their type/key metadata before their
    // guards are encoded into AR. This keeps edge construction separate from
    // constraint encoding and preserves RW/PR_RW as B-side dependencies.
    private final List<GuardedDependencyEdge<KeyType, ValueType>> dependencyEdgesA = new ArrayList<>();
    private final List<GuardedDependencyEdge<KeyType, ValueType>> dependencyEdgesB = new ArrayList<>();
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
    }

    private void encodeDependencyEdge(GuardedDependencyEdge<KeyType, ValueType> guarded) {
        var edge = guarded.edge;
        solver.assertTrue(Logic.implies(
                guarded.guard, ar(edge.getFrom(), edge.getTo())));
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
            if (!write.getTxn().equals(observation.getTxn())) {
                latestByWriter.put(write.getTxn(), write);
            }
        }

        var candidates = latestByWriter.values().stream()
                .map(write -> new FrontierCandidate<>(write,
                        ar(write.getTxn(), observation.getTxn())))
                .collect(Collectors.toList());

        // The latest candidate is selected from a strict total order.  Create
        // only the primitive pairwise AR literals here; materializing every
        // candidate's compound latest-visible guard would be quadratic per
        // predicate observation and consume most of the solver memory.
        for (int i = 0; i < candidates.size(); i++) {
            for (int j = i + 1; j < candidates.size(); j++) {
                beforeWrite(candidates.get(i).write, candidates.get(j).write);
            }
        }

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
        assertLatestVisible(frontier, source);
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
                solver.addClause(blockingClause);
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
            FrontierCandidate<KeyType, ValueType> source) {
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
            addDependencyEdge(
                    new SEREdge<>(frontier.reader, other.write.getTxn(),
                            EdgeType.PR_RW, frontier.key),
                    beforeWrite(source.write, other.write));
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

    private static final class GuardedDependencyEdge<KeyType, ValueType> {
        private final SEREdge<KeyType, ValueType> edge;
        private final Lit guard;

        private GuardedDependencyEdge(SEREdge<KeyType, ValueType> edge, Lit guard) {
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
