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
 * {@link Pruning}; this state only consumes the graph produced by that
 * baseline and publishes definite GMWR facts for {@link GmwrWwBridge}.</p>
 */
final class GmwrPropagationState<KeyType, ValueType> {
    enum ReductionReason {
        CYCLE,
        REACHABLE,
        DOMINATED,
        SATISFIED,
        SINGLETON,
        OUTSIDE_IMPOSSIBLE,
        FORCE_OUTSIDE,
        ABSENCE
    }

    enum FactKind {
        TYPED_DEPENDENCY,
        DERIVED_ORDER,
        CONDITIONAL_ORDER
    }

    private final KnownGraph<KeyType, ValueType> graph;
    private final PrecedenceOracle<Transaction<KeyType, ValueType>> precedence;
    private final Set<FactKey<KeyType, ValueType>> knownFacts = new HashSet<>();
    private final List<DependencyFact<KeyType, ValueType>> definiteFacts = new ArrayList<>();

    private final Map<Pair<Transaction<KeyType, ValueType>, Transaction<KeyType, ValueType>>,
            LogicalRelation<KeyType, ValueType>> sharedLogicalRelations = new LinkedHashMap<>();
    private final Map<Pair<Transaction<KeyType, ValueType>, Transaction<KeyType, ValueType>>,
            GmwrObligation<KeyType, ValueType>> gmwrByPair = new LinkedHashMap<>();
    private final Map<Transaction<KeyType, ValueType>,
            Set<GmwrObligation<KeyType, ValueType>>> gmwrByTxn = new HashMap<>();
    private final Map<FrontierKey<KeyType, ValueType>, FrontierDomain<KeyType, ValueType>>
            frontiers = new LinkedHashMap<>();
    private final Map<Transaction<KeyType, ValueType>,
            Set<FrontierDomain<KeyType, ValueType>>> frontiersByReader = new HashMap<>();
    private final Map<Transaction<KeyType, ValueType>,
            Set<FrontierDomain<KeyType, ValueType>>> frontiersByWriter = new HashMap<>();

    private final ArrayDeque<WorkItem<KeyType, ValueType>> workQueue = new ArrayDeque<>();
    private final Set<GmwrObligation<KeyType, ValueType>> queuedGmwr = new HashSet<>();
    private final Set<FrontierDomain<KeyType, ValueType>> queuedFrontiers = new HashSet<>();
    private final Set<Transaction<KeyType, ValueType>> lastReachabilityTouched =
            new HashSet<>();
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

    /** Synchronizes newly committed baseline WW/RW facts into the overlay. */
    long syncKnownDependencies() {
        long before = knownFacts.size();
        syncGraph(graph.getKnownGraphA());
        syncGraph(graph.getKnownGraphB());
        return knownFacts.size() - before;
    }

    boolean propagate() {
        var profiler = Profiler.getInstance();
        profiler.startTick("GMWR_REDUCTION_MS");
        try {
            for (var gmwr : gmwrByPair.values()) {
                enqueueGmwr(gmwr);
            }
            for (var frontier : frontiers.values()) {
                enqueueFrontier(frontier);
            }
            while (!workQueue.isEmpty() && !conflict) {
                stats.reductionSteps++;
                var item = workQueue.removeFirst();
                if (item.kind == WorkItem.Kind.GMWR) {
                    queuedGmwr.remove(item.gmwr);
                    reduceGmwr(item.gmwr);
                } else {
                    queuedFrontiers.remove(item.frontier);
                    reduceFrontier(item.frontier);
                }
            }
            stats.residualConstraints = residualGmwrCount() + residualFrontierCount();
            return conflict;
        } finally {
            profiler.endTick("GMWR_REDUCTION_MS");
        }
    }

    Collection<Transaction<KeyType, ValueType>> lastReachabilityTouched() {
        return Collections.unmodifiableSet(lastReachabilityTouched);
    }

    void clearLastReachabilityTouched() {
        lastReachabilityTouched.clear();
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

    Collection<FrontierDomain<KeyType, ValueType>> frontierDomains() {
        return frontiers.values();
    }

    PrecedenceOracle<Transaction<KeyType, ValueType>> precedenceOracle() {
        return precedence;
    }

    LogicalRelation<KeyType, ValueType> internLogicalRelation(
            Transaction<KeyType, ValueType> from,
            Transaction<KeyType, ValueType> to) {
        return sharedLogicalRelations.computeIfAbsent(
                Pair.of(from, to), ignored -> new LogicalRelation<>(from, to));
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
            internLogicalRelation(badWriter, repair);
            internLogicalRelation(repair, reader);
        }
        internLogicalRelation(reader, badWriter);

        var pair = Pair.of(reader, badWriter);
        var obligation = gmwrByPair.computeIfAbsent(
                pair, ignored -> new GmwrObligation<>(reader, badWriter));
        if (key != null) {
            obligation.keys.add(key);
        }
        mergeRepairSet(obligation, repairSet);
        indexGmwr(reader, obligation);
        indexGmwr(badWriter, obligation);
        for (var repair : repairSet) {
            indexGmwr(repair, obligation);
        }
        enqueueGmwr(obligation);
    }

    void addOrIntersectFrontier(Transaction<KeyType, ValueType> reader,
                                int eventIndex,
                                int coverageEpoch,
                                KeyType key,
                                Collection<Transaction<KeyType, ValueType>> allowed) {
        addOrIntersectFrontier(reader, eventIndex, coverageEpoch, key, allowed, false);
    }

    void addOrIntersectFrontier(Transaction<KeyType, ValueType> reader,
                                int eventIndex,
                                int coverageEpoch,
                                KeyType key,
                                Collection<Transaction<KeyType, ValueType>> allowed,
                                boolean mustExist) {
        stats.initialConstraints++;
        var frontierKey = new FrontierKey<>(reader, eventIndex, coverageEpoch, key);
        var allowedSet = new LinkedHashSet<Transaction<KeyType, ValueType>>();
        for (var writer : allowed) {
            if (writer != null && !writer.equals(reader)) {
                allowedSet.add(writer);
            }
        }
        var existing = frontiers.get(frontierKey);
        if (existing == null) {
            var domain = new FrontierDomain<>(frontierKey, allowedSet, mustExist);
            frontiers.put(frontierKey, domain);
            frontiersByReader.computeIfAbsent(reader, ignored -> new HashSet<>()).add(domain);
            for (var writer : allowedSet) {
                frontiersByWriter.computeIfAbsent(writer, ignored -> new HashSet<>()).add(domain);
            }
            enqueueFrontier(domain);
            return;
        }
        existing.allowed.retainAll(allowedSet);
        existing.mustExist |= mustExist;
        existing.absencePossible = !existing.mustExist;
        existing.lastReason = ReductionReason.DOMINATED;
        enqueueFrontier(existing);
    }

    /** Adds a known fact discovered outside GMWR, without publishing feedback. */
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

        // Key obligations in one bundle are AND. An unrepairable key cannot be
        // dropped because another key still has repairs.
        if (emptyRepairItem && !gmwr.outsidePossible) {
            gmwr.resolved = true;
            gmwr.lastReason = ReductionReason.CYCLE;
            stats.conflicts++;
            conflict = true;
            return;
        }

        compactRepairAntichain(gmwr);

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
        internLogicalRelation(gmwr.reader, gmwr.badWriter).forced = true;
        addDerivedOrder(gmwr.reader, gmwr.badWriter, gmwr.keys.isEmpty()
                ? null : gmwr.keys.iterator().next(), "GMWR_FORCE_OUTSIDE");
        gmwr.lastReason = ReductionReason.FORCE_OUTSIDE;
    }

    private void forceRepair(GmwrObligation<KeyType, ValueType> gmwr,
                             Transaction<KeyType, ValueType> repair) {
        stats.forcedRepairs++;
        internLogicalRelation(gmwr.badWriter, repair).forced = true;
        internLogicalRelation(repair, gmwr.reader).forced = true;
        var key = gmwr.keys.isEmpty() ? null : gmwr.keys.iterator().next();
        addDerivedOrder(gmwr.badWriter, repair, key, "GMWR_SINGLETON_REPAIR");
        addDerivedOrder(repair, gmwr.reader, key, "GMWR_SINGLETON_REPAIR");
    }

    private void reduceFrontier(FrontierDomain<KeyType, ValueType> frontier) {
        if (frontier.resolved || conflict) {
            return;
        }
        var iterator = frontier.allowed.iterator();
        while (iterator.hasNext()) {
            if (precedence.before(frontier.key.reader, iterator.next())) {
                iterator.remove();
                frontier.lastReason = ReductionReason.REACHABLE;
            }
        }
        if (frontier.allowed.isEmpty()) {
            if (frontier.mustExist) {
                frontier.lastReason = ReductionReason.CYCLE;
                stats.conflicts++;
                conflict = true;
                return;
            }
            frontier.resolved = true;
            frontier.lastReason = ReductionReason.ABSENCE;
            return;
        }
        if (frontier.mustExist && frontier.allowed.size() == 1) {
            var source = frontier.allowed.iterator().next();
            frontier.resolved = true;
            frontier.lastReason = ReductionReason.SINGLETON;
            addTypedFact(source, frontier.key.reader, EdgeType.PR_WR,
                    frontier.key.key, "FRONTIER_UNIQUE_SOURCE");
        }
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

    private void mergeRepairSet(GmwrObligation<KeyType, ValueType> obligation,
                                Set<Transaction<KeyType, ValueType>> incoming) {
        for (var item : obligation.items) {
            if (incoming.containsAll(item.repairs)) {
                item.multiplicity++;
                stats.mergedConstraints++;
                return;
            }
        }
        var iterator = obligation.items.iterator();
        long multiplicity = 1L;
        while (iterator.hasNext()) {
            var item = iterator.next();
            if (item.repairs.containsAll(incoming)) {
                multiplicity += item.multiplicity;
                iterator.remove();
                stats.mergedConstraints++;
            }
        }
        obligation.items.add(new GmwrItem<>(incoming, multiplicity));
    }

    private void compactRepairAntichain(GmwrObligation<KeyType, ValueType> obligation) {
        var compact = new ArrayList<GmwrItem<KeyType, ValueType>>();
        for (var item : obligation.items) {
            boolean dominated = false;
            for (int index = 0; index < compact.size();) {
                var existing = compact.get(index);
                if (item.repairs.containsAll(existing.repairs)) {
                    existing.multiplicity += item.multiplicity;
                    stats.mergedConstraints++;
                    dominated = true;
                    break;
                }
                if (existing.repairs.containsAll(item.repairs)) {
                    compact.remove(index);
                    stats.mergedConstraints++;
                    continue;
                }
                index++;
            }
            if (!dominated) {
                compact.add(item);
            }
        }
        obligation.items.clear();
        obligation.items.addAll(compact);
    }

    private void dirtyGmwrAffectedBy(Transaction<KeyType, ValueType> from,
                                     Transaction<KeyType, ValueType> to) {
        dirtyGmwrTouching(from);
        dirtyGmwrTouching(to);
        for (var frontier : frontiersByReader.getOrDefault(from, Collections.emptySet())) {
            enqueueFrontier(frontier);
        }
        for (var frontier : frontiersByWriter.getOrDefault(to, Collections.emptySet())) {
            enqueueFrontier(frontier);
        }
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
            workQueue.addLast(WorkItem.gmwr(gmwr));
        }
    }

    private void enqueueFrontier(FrontierDomain<KeyType, ValueType> frontier) {
        if (!frontier.resolved && queuedFrontiers.add(frontier)) {
            workQueue.addLast(WorkItem.frontier(frontier));
        }
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
        lastReachabilityTouched.add(from);
        lastReachabilityTouched.add(to);
        if (!suppressDirtyNotifications) {
            for (var pair : newlyReachable) {
                lastReachabilityTouched.add(pair.from);
                lastReachabilityTouched.add(pair.to);
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
                    addOrIntersectFrontier(reader, observation.getEventIndex(),
                            observation.getCoverageEpoch(), source.getKey(),
                            List.of(writer), true);
                }
            }
        }
    }

    private long residualGmwrCount() {
        return gmwrByPair.values().stream()
                .filter(gmwr -> !gmwr.resolved && !gmwr.satisfied).count();
    }

    private long residualFrontierCount() {
        return frontiers.values().stream().filter(frontier -> !frontier.resolved).count();
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
        long mergedConstraints;
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
        long multiplicity;
        ReductionReason lastReason;

        GmwrItem(Set<Transaction<KeyType, ValueType>> repairs, long multiplicity) {
            this.repairs = new LinkedHashSet<>(repairs);
            this.multiplicity = multiplicity;
        }
    }

    static final class FrontierKey<KeyType, ValueType> {
        final Transaction<KeyType, ValueType> reader;
        final int eventIndex;
        final int coverageEpoch;
        final KeyType key;

        FrontierKey(Transaction<KeyType, ValueType> reader,
                    int eventIndex,
                    int coverageEpoch,
                    KeyType key) {
            this.reader = reader;
            this.eventIndex = eventIndex;
            this.coverageEpoch = coverageEpoch;
            this.key = key;
        }

        @Override
        public boolean equals(Object object) {
            if (!(object instanceof FrontierKey)) {
                return false;
            }
            var other = (FrontierKey<?, ?>) object;
            return eventIndex == other.eventIndex && coverageEpoch == other.coverageEpoch
                    && Objects.equals(reader, other.reader) && Objects.equals(key, other.key);
        }

        @Override
        public int hashCode() {
            return Objects.hash(reader, eventIndex, coverageEpoch, key);
        }
    }

    static final class FrontierDomain<KeyType, ValueType> {
        final FrontierKey<KeyType, ValueType> key;
        final LinkedHashSet<Transaction<KeyType, ValueType>> allowed;
        boolean mustExist;
        boolean absencePossible;
        boolean resolved;
        ReductionReason lastReason;

        FrontierDomain(FrontierKey<KeyType, ValueType> key,
                       Collection<Transaction<KeyType, ValueType>> allowed) {
            this(key, allowed, false);
        }

        FrontierDomain(FrontierKey<KeyType, ValueType> key,
                       Collection<Transaction<KeyType, ValueType>> allowed,
                       boolean mustExist) {
            this.key = key;
            this.allowed = new LinkedHashSet<>(allowed);
            this.mustExist = mustExist;
            this.absencePossible = !mustExist;
        }
    }

    static final class LogicalRelation<KeyType, ValueType> {
        final Transaction<KeyType, ValueType> from;
        final Transaction<KeyType, ValueType> to;
        boolean forced;

        LogicalRelation(Transaction<KeyType, ValueType> from,
                        Transaction<KeyType, ValueType> to) {
            this.from = from;
            this.to = to;
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

    private static final class WorkItem<KeyType, ValueType> {
        enum Kind { GMWR, FRONTIER }

        final Kind kind;
        final GmwrObligation<KeyType, ValueType> gmwr;
        final FrontierDomain<KeyType, ValueType> frontier;

        private WorkItem(Kind kind,
                         GmwrObligation<KeyType, ValueType> gmwr,
                         FrontierDomain<KeyType, ValueType> frontier) {
            this.kind = kind;
            this.gmwr = gmwr;
            this.frontier = frontier;
        }

        static <KeyType, ValueType> WorkItem<KeyType, ValueType> gmwr(
                GmwrObligation<KeyType, ValueType> gmwr) {
            return new WorkItem<>(Kind.GMWR, gmwr, null);
        }

        static <KeyType, ValueType> WorkItem<KeyType, ValueType> frontier(
                FrontierDomain<KeyType, ValueType> frontier) {
            return new WorkItem<>(Kind.FRONTIER, null, frontier);
        }
    }
}
