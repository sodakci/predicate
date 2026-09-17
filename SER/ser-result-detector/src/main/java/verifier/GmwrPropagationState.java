package verifier;

import graph.Edge;
import graph.EdgeType;
import graph.KnownGraph;
import history.History;
import history.Transaction;
import org.apache.commons.lang3.tuple.Pair;
import util.Profiler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * GMWR reduction worklist backed by the shared precedence oracle.
 *
 * <p>Ordinary WW constraints are deliberately absent. They remain owned by
 * {@link Pruning}; this state consumes the baseline graph and publishes only
 * definite GMWR-derived precedence facts for the residual encoding.</p>
 */
final class GmwrPropagationState<KeyType, ValueType> {
    enum ReductionReason {
        CYCLE,
        REACHABLE,
        SATISFIED,
        SINGLETON,
        OUTSIDE_IMPOSSIBLE,
        FORCE_OUTSIDE
    }

    enum FactKind {
        TYPED_DEPENDENCY,
        DERIVED_ORDER
    }

    private final KnownGraph<KeyType, ValueType> graph;
    private final PrecedenceOracle<Transaction<KeyType, ValueType>> precedence;
    private final Set<FactKey<KeyType, ValueType>> knownFacts = new HashSet<>();
    private final List<DependencyFact<KeyType, ValueType>> definiteFacts = new ArrayList<>();

    private final Map<Pair<Transaction<KeyType, ValueType>, Transaction<KeyType, ValueType>>,
            GmwrObligation<KeyType, ValueType>> gmwrByPair = new LinkedHashMap<>();
    private final Map<Transaction<KeyType, ValueType>,
            Set<GmwrObligation<KeyType, ValueType>>> gmwrByTxn = new HashMap<>();
    private final ArrayDeque<GmwrObligation<KeyType, ValueType>> workQueue =
            new ArrayDeque<>();
    private final Set<GmwrObligation<KeyType, ValueType>> queuedGmwr = new HashSet<>();
    private boolean suppressDirtyNotifications;
    private boolean conflict;

    final PropagationStats stats = new PropagationStats();

    GmwrPropagationState(History<KeyType, ValueType> history,
                         KnownGraph<KeyType, ValueType> graph,
                         PrecedenceOracle<Transaction<KeyType, ValueType>> precedence) {
        this.graph = graph;
        this.precedence = Objects.requireNonNull(precedence, "precedence");
        for (var from : history.getTransactions()) {
            if (!isBottomTxn(from)) {
                continue;
            }
            for (var to : history.getTransactions()) {
                if (!isBottomTxn(to)) {
                    precedence.add(from, to);
                }
            }
        }
    }

    void seedKnownDependencies() {
        boolean previousSuppression = suppressDirtyNotifications;
        suppressDirtyNotifications = true;
        try {
            syncGraph(graph.getKnownGraphA());
            syncGraph(graph.getKnownGraphB());
            seedRecordedPredicateSources();
        } finally {
            suppressDirtyNotifications = previousSuppression;
        }
    }

    boolean propagate() {
        var profiler = Profiler.getInstance();
        profiler.startTick("GMWR_REDUCTION_MS");
        try {
            for (var gmwr : gmwrObligations()) {
                enqueueGmwr(gmwr);
            }
            while (!workQueue.isEmpty() && !conflict) {
                stats.reductionSteps++;
                var gmwr = workQueue.removeFirst();
                queuedGmwr.remove(gmwr);
                reduceGmwr(gmwr);
            }
            stats.residualConstraints = residualGmwrCount();
            return conflict;
        } finally {
            profiler.endTick("GMWR_REDUCTION_MS");
        }
    }

    boolean isConflict() {
        return conflict;
    }

    long definiteFactCount() {
        return definiteFacts.size();
    }

    Collection<DependencyFact<KeyType, ValueType>> definiteFacts() {
        return Collections.unmodifiableList(definiteFacts);
    }

    Collection<GmwrObligation<KeyType, ValueType>> gmwrObligations() {
        return gmwrByPair.values();
    }

    PrecedenceOracle<Transaction<KeyType, ValueType>> precedenceOracle() {
        return precedence;
    }

    void addGmwrItem(Transaction<KeyType, ValueType> reader,
                     Transaction<KeyType, ValueType> badWriter,
                     Collection<Transaction<KeyType, ValueType>> repairs,
                     KeyType key) {
        stats.initialConstraints++;
        if (reader.equals(badWriter)) {
            conflict = true;
            stats.conflicts++;
            return;
        }
        var repairSet = new LinkedHashSet<Transaction<KeyType, ValueType>>();
        for (var repair : repairs) {
            if (repair == null || repair.equals(reader) || repair.equals(badWriter)) {
                continue;
            }
            repairSet.add(repair);
        }

        var pair = Pair.of(reader, badWriter);
        var obligation = gmwrByPair.computeIfAbsent(
                pair, ignored -> new GmwrObligation<>(reader, badWriter));
        // Preserve every semantic obligation explicitly. Items sharing the
        // same pair reuse only the outside branch and propagation state.
        obligation.items.add(new GmwrItem<>(repairSet));
        if (key != null) {
            obligation.keys.add(key);
        }
        indexGmwr(reader, obligation);
        indexGmwr(badWriter, obligation);
        for (var repair : repairSet) {
            indexGmwr(repair, obligation);
        }
        enqueueGmwr(obligation);
    }

    /** Adds a known dependency fact discovered outside the GMWR worklist. */
    boolean addKnownFact(Transaction<KeyType, ValueType> from,
                         Transaction<KeyType, ValueType> to,
                         EdgeType type,
                         KeyType key) {
        return addFact(DependencyFact.typed(from, to, type, key, "KNOWN_GRAPH", true), false);
    }

    private boolean addTypedFact(Transaction<KeyType, ValueType> from,
                                 Transaction<KeyType, ValueType> to,
                                 EdgeType type,
                                 KeyType key,
                                 String rule) {
        return addFact(DependencyFact.typed(from, to, type, key, rule, true), true);
    }

    private boolean addDerivedOrder(Transaction<KeyType, ValueType> from,
                                    Transaction<KeyType, ValueType> to,
                                    KeyType key,
                                    String rule) {
        return addFact(DependencyFact.derivedOrder(from, to, key, rule), true);
    }

    private boolean addFact(DependencyFact<KeyType, ValueType> fact,
                            boolean gmwrDerived) {
        if (fact.from.equals(fact.to)) {
            conflict = true;
            return false;
        }
        if (!knownFacts.add(FactKey.of(fact))) {
            return false;
        }
        if (!addPrecedence(fact.from, fact.to)) {
            return false;
        }
        if (gmwrDerived) {
            definiteFacts.add(fact);
            stats.forcedFacts++;
        }
        return true;
    }

    private void reduceGmwr(GmwrObligation<KeyType, ValueType> gmwr) {
        if (gmwr.resolved || conflict) {
            return;
        }
        if (precedence.before(gmwr.reader, gmwr.badWriter)
                || repairAlreadySatisfied(gmwr)) {
            gmwr.resolved = true;
            gmwr.satisfied = true;
            gmwr.lastReason = ReductionReason.SATISFIED;
            stats.satisfied++;
            return;
        }

        if (precedence.before(gmwr.badWriter, gmwr.reader) && gmwr.outsidePossible) {
            gmwr.outsidePossible = false;
            gmwr.lastReason = ReductionReason.OUTSIDE_IMPOSSIBLE;
        }

        boolean emptyRepairItem = false;
        for (var item : gmwr.items) {
            var candidateIterator = item.repairs.iterator();
            while (candidateIterator.hasNext()) {
                var repair = candidateIterator.next();
                if (precedence.before(repair, gmwr.badWriter)
                        || precedence.before(gmwr.reader, repair)) {
                    candidateIterator.remove();
                    stats.removedCandidates++;
                    item.lastReason = ReductionReason.REACHABLE;
                }
            }
            if (item.repairs.isEmpty()) {
                emptyRepairItem = true;
            }
        }

        // Item obligations in one pair group are conjunctive. An unrepairable
        // item cannot be dropped because another item still has repairs.
        if (emptyRepairItem && !gmwr.outsidePossible) {
            gmwr.resolved = true;
            gmwr.lastReason = ReductionReason.CYCLE;
            stats.conflicts++;
            conflict = true;
            return;
        }

        if (emptyRepairItem && gmwr.outsidePossible) {
            forceOutside(gmwr);
            return;
        }

        if (!gmwr.outsidePossible && gmwr.items.isEmpty()) {
            gmwr.resolved = true;
            gmwr.lastReason = ReductionReason.CYCLE;
            stats.conflicts++;
            conflict = true;
            return;
        }

        if (!gmwr.outsidePossible) {
            boolean forced = false;
            for (var item : gmwr.items) {
                if (item.repairs.size() == 1) {
                    forceRepair(gmwr, item.repairs.iterator().next());
                    forced = true;
                }
            }
            if (forced) {
                gmwr.lastReason = ReductionReason.SINGLETON;
            }
        }
    }

    private void forceOutside(GmwrObligation<KeyType, ValueType> gmwr) {
        addDerivedOrder(gmwr.reader, gmwr.badWriter, gmwr.keys.isEmpty()
                ? null : gmwr.keys.iterator().next(), "GMWR_FORCE_OUTSIDE");
        gmwr.lastReason = ReductionReason.FORCE_OUTSIDE;
    }

    private void forceRepair(GmwrObligation<KeyType, ValueType> gmwr,
                             Transaction<KeyType, ValueType> repair) {
        stats.forcedRepairs++;
        var key = gmwr.keys.isEmpty() ? null : gmwr.keys.iterator().next();
        addDerivedOrder(gmwr.badWriter, repair, key, "GMWR_SINGLETON_REPAIR");
        addDerivedOrder(repair, gmwr.reader, key, "GMWR_SINGLETON_REPAIR");
    }

    private boolean repairAlreadySatisfied(GmwrObligation<KeyType, ValueType> gmwr) {
        for (var item : gmwr.items) {
            boolean satisfied = false;
            for (var repair : item.repairs) {
                if (precedence.before(gmwr.badWriter, repair)
                        && precedence.before(repair, gmwr.reader)) {
                    satisfied = true;
                    break;
                }
            }
            if (!satisfied) {
                return false;
            }
        }
        return !gmwr.items.isEmpty();
    }

    private void dirtyGmwrAffectedBy(Transaction<KeyType, ValueType> from,
                                     Transaction<KeyType, ValueType> to) {
        dirtyGmwrTouching(from);
        dirtyGmwrTouching(to);
    }

    private void dirtyGmwrTouching(Transaction<KeyType, ValueType> txn) {
        for (var gmwr : gmwrByTxn.getOrDefault(txn, Collections.emptySet())) {
            enqueueGmwr(gmwr);
        }
    }

    private void indexGmwr(Transaction<KeyType, ValueType> txn,
                           GmwrObligation<KeyType, ValueType> gmwr) {
        gmwrByTxn.computeIfAbsent(txn, ignored -> new HashSet<>()).add(gmwr);
    }

    private void enqueueGmwr(GmwrObligation<KeyType, ValueType> gmwr) {
        if (!gmwr.resolved && queuedGmwr.add(gmwr)) {
            workQueue.addLast(gmwr);
        }
    }

    void releasePropagationIndexes() {
        gmwrByTxn.clear();
        workQueue.clear();
        queuedGmwr.clear();
    }

    void releaseEncodedState() {
        knownFacts.clear();
        definiteFacts.clear();
        gmwrByPair.clear();
    }

    private boolean addPrecedence(Transaction<KeyType, ValueType> from,
                                  Transaction<KeyType, ValueType> to) {
        if (precedence.before(from, to)) {
            return true;
        }
        if (precedence.wouldCycle(from, to)) {
            conflict = true;
            return false;
        }
        var predecessors = new LinkedHashSet<>(precedence.predecessor(from));
        predecessors.add(from);
        var successors = new LinkedHashSet<>(precedence.successor(to));
        successors.add(to);
        var newlyReachable = new ArrayList<PrecedenceOracle.Relation<
                Transaction<KeyType, ValueType>>>();
        for (var predecessor : predecessors) {
            for (var successor : successors) {
                if (!precedence.before(predecessor, successor)) {
                    newlyReachable.add(new PrecedenceOracle.Relation<>(
                            predecessor, successor));
                }
            }
        }
        precedence.add(from, to);
        if (!suppressDirtyNotifications) {
            for (var pair : newlyReachable) {
                dirtyGmwrAffectedBy(pair.from, pair.to);
            }
        }
        return true;
    }

    private void syncGraph(com.google.common.graph.ValueGraph<
            Transaction<KeyType, ValueType>, Collection<Edge<KeyType>>> known) {
        for (var endpoint : known.edges()) {
            for (var edge : known.edgeValue(endpoint).orElse(Collections.emptyList())) {
                if (isDependencyType(edge.getType())) {
                    addKnownFact(endpoint.source(), endpoint.target(),
                            edge.getType(), edge.getKey());
                }
            }
        }
    }

    private void seedRecordedPredicateSources() {
        for (var observation : graph.getPredicateObservations()) {
            var reader = observation.getTxn();
            for (var source : observation.getTupleSources()) {
                if (observation.getPredicateReadType(source.getKey())
                        != KnownGraph.PredicateReadType.EXTERNAL) {
                    continue;
                }
                var writer = source.getSourceWrite().getTxn();
                if (!writer.equals(reader)) {
                    addTypedFact(writer, reader, EdgeType.PR_WR, source.getKey(),
                            "RECORDED_PREDICATE_SOURCE");
                }
            }
        }
    }

    private long residualGmwrCount() {
        return gmwrObligations().stream()
                .filter(gmwr -> !gmwr.resolved && !gmwr.satisfied).count();
    }

    static boolean isBottomTxn(Transaction<?, ?> txn) {
        return txn.getId() == -1L && txn.getSession() != null
                && txn.getSession().getId() == -1L;
    }

    private static boolean isDependencyType(EdgeType type) {
        return type == EdgeType.SO || type == EdgeType.WR || type == EdgeType.WW
                || type == EdgeType.RW || type == EdgeType.PR_WR || type == EdgeType.PR_RW;
    }

    static final class PropagationStats {
        long reductionSteps;
        long initialConstraints;
        long residualConstraints;
        long removedCandidates;
        long forcedFacts;
        long forcedRepairs;
        long satisfied;
        long conflicts;
        long residualSatVariables;
        long residualSatConstraints;
    }

    static final class DependencyFact<KeyType, ValueType> {
        final Transaction<KeyType, ValueType> from;
        final Transaction<KeyType, ValueType> to;
        final EdgeType type;
        final KeyType key;
        final FactKind kind;
        final String rule;
        final boolean unconditional;

        DependencyFact(Transaction<KeyType, ValueType> from,
                       Transaction<KeyType, ValueType> to,
                       EdgeType type,
                       KeyType key) {
            this(from, to, type, key, type == null ? FactKind.DERIVED_ORDER
                    : FactKind.TYPED_DEPENDENCY, "LEGACY", true);
        }

        private DependencyFact(Transaction<KeyType, ValueType> from,
                               Transaction<KeyType, ValueType> to,
                               EdgeType type,
                               KeyType key,
                               FactKind kind,
                               String rule,
                               boolean unconditional) {
            this.from = from;
            this.to = to;
            this.type = type;
            this.key = key;
            this.kind = kind;
            this.rule = rule;
            this.unconditional = unconditional;
        }

        static <KeyType, ValueType> DependencyFact<KeyType, ValueType> typed(
                Transaction<KeyType, ValueType> from,
                Transaction<KeyType, ValueType> to,
                EdgeType type,
                KeyType key,
                String rule,
                boolean unconditional) {
            return new DependencyFact<>(from, to, type, key,
                    FactKind.TYPED_DEPENDENCY, rule, unconditional);
        }

        static <KeyType, ValueType> DependencyFact<KeyType, ValueType> derivedOrder(
                Transaction<KeyType, ValueType> from,
                Transaction<KeyType, ValueType> to,
                KeyType key,
                String rule) {
            return new DependencyFact<>(from, to, null, key,
                    FactKind.DERIVED_ORDER, rule, true);
        }

        boolean isTypedDependency() {
            return kind == FactKind.TYPED_DEPENDENCY && type != null;
        }
    }

    static final class GmwrObligation<KeyType, ValueType> {
        final Transaction<KeyType, ValueType> reader;
        final Transaction<KeyType, ValueType> badWriter;
        final Set<KeyType> keys = new LinkedHashSet<>();
        final List<GmwrItem<KeyType, ValueType>> items = new ArrayList<>();
        boolean outsidePossible = true;
        boolean resolved;
        boolean satisfied;
        ReductionReason lastReason;

        GmwrObligation(Transaction<KeyType, ValueType> reader,
                       Transaction<KeyType, ValueType> badWriter) {
            this.reader = reader;
            this.badWriter = badWriter;
        }
    }

    static final class GmwrItem<KeyType, ValueType> {
        final LinkedHashSet<Transaction<KeyType, ValueType>> repairs;
        ReductionReason lastReason;

        GmwrItem(Set<Transaction<KeyType, ValueType>> repairs) {
            this.repairs = new LinkedHashSet<>(repairs);
        }
    }

    private static final class FactKey<KeyType, ValueType> {
        private final EdgeType type;
        private final Transaction<KeyType, ValueType> from;
        private final Transaction<KeyType, ValueType> to;
        private final KeyType key;

        private FactKey(DependencyFact<KeyType, ValueType> fact) {
            type = fact.type;
            from = fact.from;
            to = fact.to;
            key = fact.key;
        }

        static <KeyType, ValueType> FactKey<KeyType, ValueType> of(
                DependencyFact<KeyType, ValueType> fact) {
            return new FactKey<>(fact);
        }

        @Override
        public boolean equals(Object object) {
            if (!(object instanceof FactKey)) {
                return false;
            }
            var other = (FactKey<?, ?>) object;
            return type == other.type && Objects.equals(from, other.from)
                    && Objects.equals(to, other.to) && Objects.equals(key, other.key);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, from, to, key);
        }
    }

}
