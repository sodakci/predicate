package verifier;

import graph.Edge;
import graph.EdgeType;
import graph.KnownGraph;
import history.History;
import history.Transaction;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Shared-snapshot fixed-point pruning for SER WW/RW constraints. */
public final class Prun<KeyType, ValueType> {
    private final PrecedenceOracle<Transaction<KeyType, ValueType>> precedence;

    Prun(PrecedenceOracle<Transaction<KeyType, ValueType>> precedence) {
        this.precedence = Objects.requireNonNull(precedence, "precedence");
    }

    PrecedenceOracle<Transaction<KeyType, ValueType>> precedenceOracle() {
        return precedence;
    }

    Result prune(
            History<KeyType, ValueType> history,
            KnownGraph<KeyType, ValueType> graph,
            Collection<SERConstraint<KeyType, ValueType>> constraints) {
        return prune(history, graph, constraints, true, "PRUN");
    }

    Result pruneSnapshotOnly(
            History<KeyType, ValueType> history,
            KnownGraph<KeyType, ValueType> graph,
            Collection<SERConstraint<KeyType, ValueType>> constraints) {
        return prune(history, graph, constraints, false, "SNAPSHOT");
    }

    private Result prune(
            History<KeyType, ValueType> history,
            KnownGraph<KeyType, ValueType> graph,
            Collection<SERConstraint<KeyType, ValueType>> constraints,
            boolean includeReachabilityPruning,
            String modeLabel) {
        if (constraints.isEmpty()) {
            return new Result(0, 0, 0, 0, 0, 0, 0, false);
        }

        var txns = new ArrayList<>(history.getTransactions());
        txns.sort(Comparator
                .comparingLong((Transaction<KeyType, ValueType> txn) -> txn.getSession().getId())
                .thenComparingLong(Transaction::getId));
        var txnIds = new IdentityHashMap<Transaction<KeyType, ValueType>, Integer>();
        for (int i = 0; i < txns.size(); i++) {
            txnIds.put(txns.get(i), i);
        }

        var order = precedence;
        var initialEdges = new HashSet<Long>();
        boolean inconsistent = addGraphEdges(
                graph.getKnownGraphA().edges(), txnIds, order, initialEdges);
        inconsistent |= addGraphEdges(
                graph.getKnownGraphB().edges(), txnIds, order, initialEdges);
        int initialDependencyEdges = initialEdges.size();
        int initialRelationCount = order.relationCount();
        int existingGraphDerivedOrders = initialRelationCount - initialDependencyEdges;

        var writesByKey = buildWritersByKey(graph, txnIds);
        var observations = buildFixedObservations(graph, txnIds);
        var observationsByReader = groupObservationsByReader(observations);

        var forcedPairs = new HashSet<Long>();
        int crossKeyForcedOrders = 0;
        int crossSnapshotDerivedOrders = 0;
        int rounds = 0;
        int pruningPasses = 0;

        while (!inconsistent) {

            pruningPasses++;
            System.err.printf("%s pruning round %d%n", modeLabel, pruningPasses);
            if (includeReachabilityPruning) {
                int resolved = resolveReachableConstraints(
                        graph, constraints, txns, txnIds, order);
                if (resolved < 0) {
                    inconsistent = true;
                    break;
                }
                if (resolved > 0) {
                    continue;
                }
            }

            var sharedLowerBounds = buildSharedLowerBounds(
                    observationsByReader, txns, txnIds, order);
            var additions = new LinkedHashMap<Long, ForcedOrder>();
            var snapshotWriterOrders = new LinkedHashSet<Long>();
            for (var observation : observations) {
                int reader = observation.reader;
                int source = observation.source;
                for (int competitor : writesByKey.getOrDefault(
                        observation.key, Collections.emptySet())) {
                    if (competitor == source || competitor == reader) {
                        continue;
                    }

                    // The latest-visible disjunction is already settled.
                    if (before(order, txns, competitor, source)
                            || before(order, txns, reader, competitor)) {
                        continue;
                    }

                    boolean inside = sharedLowerBounds.get(reader).get(competitor);
                    if (inside) {
                        boolean crossKey = !isSingleKeyLowerBound(
                                competitor, source, txns, order)
                                && hasOtherKeyVisibilityWitness(competitor, reader,
                                        observation.key, observationsByReader, txns, order);
                        putForced(additions, competitor, source, crossKey);
                        snapshotWriterOrders.add(pairId(competitor, source));
                    } else if (before(order, txns, source, competitor)) {
                        // C cannot be before the fixed source, so C must be after R.
                        putForced(additions, reader, competitor, false);
                        snapshotWriterOrders.add(pairId(source, competitor));
                    }
                }
            }

            if (additions.isEmpty()) {
                if (!includeReachabilityPruning) {
                    inconsistent = resolveSnapshotConstraints(
                            graph, constraints, snapshotWriterOrders,
                            txns, txnIds, order) < 0;
                }
                break;
            }
            rounds++;
            for (var addition : additions.values()) {
                var from = txns.get(addition.from);
                var to = txns.get(addition.to);
                if (order.wouldCycle(from, to)) {
                    inconsistent = true;
                    break;
                }
                boolean newOrder = !order.before(from, to);
                order.add(from, to);
                if (!newOrder) {
                    continue;
                }
                long pair = pairId(addition.from, addition.to);
                if (forcedPairs.add(pair) && addition.crossKey) {
                    crossKeyForcedOrders++;
                }
                if (rounds > 1) {
                    crossSnapshotDerivedOrders++;
                }
            }
            if (!includeReachabilityPruning) {
                if (resolveSnapshotConstraints(graph, constraints,
                        snapshotWriterOrders, txns, txnIds, order) < 0) {
                    inconsistent = true;
                    break;
                }
            }
        }

        int allOrdersCreatedBeyondInitialTc = Math.max(0,
                order.relationCount() - initialRelationCount);
        int reachabilityDerivedOrders = Math.max(0,
                allOrdersCreatedBeyondInitialTc - forcedPairs.size());
        return new Result(
                forcedPairs.size(),
                crossKeyForcedOrders,
                reachabilityDerivedOrders,
                crossSnapshotDerivedOrders,
                existingGraphDerivedOrders,
                allOrdersCreatedBeyondInitialTc,
                rounds,
                inconsistent);
    }

    private static <KeyType, ValueType> int resolveSnapshotConstraints(
            KnownGraph<KeyType, ValueType> graph,
            Collection<SERConstraint<KeyType, ValueType>> constraints,
            Set<Long> snapshotWriterOrders,
            List<Transaction<KeyType, ValueType>> txns,
            IdentityHashMap<Transaction<KeyType, ValueType>, Integer> txnIds,
            PrecedenceOracle<Transaction<KeyType, ValueType>> order) {
        int resolved = 0;
        int checked = 0;
        int total = constraints.size();
        var progress = new ConstraintProgress("SNAPSHOT", total);
        progress.refresh(checked, resolved, false);
        if (total == 0) {
            progress.refresh(checked, resolved, true);
        }
        var iterator = constraints.iterator();
        while (iterator.hasNext()) {
            var constraint = iterator.next();
            int first = txnIds.get(constraint.getWriteTransaction1());
            int second = txnIds.get(constraint.getWriteTransaction2());
            boolean forward = snapshotWriterOrders.contains(pairId(first, second));
            boolean backward = snapshotWriterOrders.contains(pairId(second, first));
            if (forward != backward) {
                var selected = forward
                        ? constraint.getEdges1()
                        : constraint.getEdges2();
                for (var edge : selected) {
                    putEdgeIfAbsent(graph, edge);
                    Integer from = txnIds.get(edge.getFrom());
                    Integer to = txnIds.get(edge.getTo());
                    if (from != null && to != null && !from.equals(to)) {
                        if (order.wouldCycle(txns.get(from), txns.get(to))) {
                            return -1;
                        }
                        order.add(txns.get(from), txns.get(to));
                    }
                }
                iterator.remove();
                resolved++;
            }
            checked++;
            progress.refresh(checked, resolved, checked == total);
        }
        return resolved;
    }

    private static <KeyType, ValueType> int resolveReachableConstraints(
            KnownGraph<KeyType, ValueType> graph,
            Collection<SERConstraint<KeyType, ValueType>> constraints,
            List<Transaction<KeyType, ValueType>> txns,
            IdentityHashMap<Transaction<KeyType, ValueType>, Integer> txnIds,
            PrecedenceOracle<Transaction<KeyType, ValueType>> order) {
        int resolved = 0;
        int checked = 0;
        int total = constraints.size();
        var progress = new ConstraintProgress("PRUN", total);
        progress.refresh(checked, resolved, false);
        if (total == 0) {
            progress.refresh(checked, resolved, true);
        }
        var iterator = constraints.iterator();
        while (iterator.hasNext()) {
            var constraint = iterator.next();
            int first = txnIds.get(constraint.getWriteTransaction1());
            int second = txnIds.get(constraint.getWriteTransaction2());
            boolean forward = before(order, txns, first, second);
            boolean backward = before(order, txns, second, first);
            if (forward != backward) {
                var selected = forward
                        ? constraint.getEdges1()
                        : constraint.getEdges2();
                for (var edge : selected) {
                    putEdgeIfAbsent(graph, edge);
                    Integer from = txnIds.get(edge.getFrom());
                    Integer to = txnIds.get(edge.getTo());
                    if (from != null && to != null && !from.equals(to)) {
                        if (order.wouldCycle(txns.get(from), txns.get(to))) {
                            return -1;
                        }
                        order.add(txns.get(from), txns.get(to));
                    }
                }
                iterator.remove();
                resolved++;
            }
            checked++;
            progress.refresh(checked, resolved, checked == total);
        }
        return resolved;
    }

    private static final class ConstraintProgress {
        private static final int BAR_WIDTH = 15;

        private final String label;
        private final int total;
        private final int refreshStep;

        private ConstraintProgress(String label, int total) {
            this.label = label;
            this.total = total;
            this.refreshStep = Math.max(1,
                    Math.min(100, Math.max(1, total / 100)));
        }

        private void refresh(int checked, int solved, boolean done) {
            if (!done && checked != 0 && checked % refreshStep != 0) {
                return;
            }

            var line = format(checked, solved);
            // Carriage-return refresh is also used when stderr is redirected:
            // captured logs keep one logical progress line instead of one
            // physical line per update, while a terminal updates in place.
            System.err.print("\r" + line);
            if (done) {
                System.err.println();
            }
            System.err.flush();
        }

        private String format(int checked, int solved) {
            int percent = total == 0
                    ? 100
                    : (int) Math.floor(checked * 100.0 / total);
            int filled = Math.min(BAR_WIDTH,
                    Math.max(0, checked * BAR_WIDTH / Math.max(1, total)));
            var bar = new StringBuilder(BAR_WIDTH);
            for (int i = 0; i < BAR_WIDTH; i++) {
                bar.append(i < filled ? '=' : '-');
            }
            return String.format(
                    "%s post-check [%s] %3d%% %d/%d solved=%d",
                    label, bar, percent, checked, total, solved);
        }
    }

    private static <KeyType, ValueType> void putEdgeIfAbsent(
            KnownGraph<KeyType, ValueType> graph,
            SEREdge<KeyType, ValueType> edge) {
        var target = edge.getType() == EdgeType.RW
                || edge.getType() == EdgeType.PR_RW
                ? graph.getKnownGraphB()
                : graph.getKnownGraphA();
        boolean present = target.edgeValue(edge.getFrom(), edge.getTo())
                .orElse(Collections.emptyList()).stream()
                .anyMatch(existing -> existing.getType() == edge.getType()
                        && Objects.equals(existing.getKey(), edge.getKey()));
        if (!present && edge.getType() != EdgeType.PR_RW) {
            graph.putEdge(edge.getFrom(), edge.getTo(),
                    new Edge<>(edge.getType(), edge.getKey()));
        }
    }

    private static <KeyType, ValueType> boolean addGraphEdges(
            Set<com.google.common.graph.EndpointPair<Transaction<KeyType, ValueType>>> edges,
            IdentityHashMap<Transaction<KeyType, ValueType>, Integer> txnIds,
            PrecedenceOracle<Transaction<KeyType, ValueType>> order,
            Set<Long> initialEdges) {
        boolean inconsistent = false;
        for (var edge : edges) {
            var from = txnIds.get(edge.source());
            var to = txnIds.get(edge.target());
            if (from != null && to != null && !from.equals(to)) {
                initialEdges.add(pairId(from, to));
                if (order.wouldCycle(edge.source(), edge.target())) {
                    inconsistent = true;
                } else {
                    order.add(edge.source(), edge.target());
                }
            }
        }
        return inconsistent;
    }

    private static <KeyType, ValueType> Map<KeyType, Set<Integer>> buildWritersByKey(
            KnownGraph<KeyType, ValueType> graph,
            IdentityHashMap<Transaction<KeyType, ValueType>, Integer> txnIds) {
        var result = new LinkedHashMap<KeyType, Set<Integer>>();
        for (var write : graph.getAllWrites()) {
            result.computeIfAbsent(write.getEvent().getKey(), ignored -> new LinkedHashSet<>())
                    .add(txnIds.get(write.getTxn()));
        }
        return result;
    }

    private static <KeyType> Map<Integer, List<FixedObservation<KeyType>>>
            groupObservationsByReader(List<FixedObservation<KeyType>> observations) {
        var result = new LinkedHashMap<Integer, List<FixedObservation<KeyType>>>();
        for (var observation : observations) {
            result.computeIfAbsent(observation.reader, ignored -> new ArrayList<>())
                    .add(observation);
        }
        return result;
    }

    private static <KeyType, ValueType> List<FixedObservation<KeyType>> buildFixedObservations(
            KnownGraph<KeyType, ValueType> graph,
            IdentityHashMap<Transaction<KeyType, ValueType>, Integer> txnIds) {
        var fixed = new LinkedHashMap<Pair<Integer, KeyType>, Integer>();
        var ambiguous = new HashSet<Pair<Integer, KeyType>>();

        for (var endpoint : graph.getReadFrom().edges()) {
            int source = txnIds.get(endpoint.source());
            int reader = txnIds.get(endpoint.target());
            for (var edge : graph.getReadFrom().edgeValue(endpoint)
                    .orElse(Collections.emptyList())) {
                recordFixed(fixed, ambiguous, reader, edge.getKey(), source);
            }
        }
        for (var observation : graph.getPredicateObservations()) {
            int reader = txnIds.get(observation.getTxn());
            for (var tuple : observation.getTupleSources()) {
                if (observation.getPredicateReadType(tuple.getKey())
                        != KnownGraph.PredicateReadType.EXTERNAL) {
                    continue;
                }
                int source = txnIds.get(tuple.getSourceWrite().getTxn());
                if (source != reader) {
                    recordFixed(fixed, ambiguous, reader, tuple.getKey(), source);
                }
            }
        }

        var result = new ArrayList<FixedObservation<KeyType>>();
        for (var entry : fixed.entrySet()) {
            result.add(new FixedObservation<>(
                    entry.getKey().getLeft(), entry.getKey().getRight(), entry.getValue()));
        }
        return result;
    }

    private static <KeyType> void recordFixed(
            Map<Pair<Integer, KeyType>, Integer> fixed,
            Set<Pair<Integer, KeyType>> ambiguous,
            int reader,
            KeyType key,
            int source) {
        var observation = Pair.of(reader, key);
        if (ambiguous.contains(observation)) {
            return;
        }
        var previous = fixed.putIfAbsent(observation, source);
        if (previous != null && previous != source) {
            fixed.remove(observation);
            ambiguous.add(observation);
        }
    }

    private static <KeyType, ValueType> boolean isSingleKeyLowerBound(
            int transaction,
            int source,
            List<Transaction<KeyType, ValueType>> txns,
            PrecedenceOracle<Transaction<KeyType, ValueType>> order) {
        return transaction == source || before(order, txns, transaction, source);
    }

    private static <KeyType, ValueType> Map<Integer, BitSet> buildSharedLowerBounds(
            Map<Integer, List<FixedObservation<KeyType>>> observationsByReader,
            List<Transaction<KeyType, ValueType>> txns,
            IdentityHashMap<Transaction<KeyType, ValueType>, Integer> txnIds,
            PrecedenceOracle<Transaction<KeyType, ValueType>> order) {
        var result = new LinkedHashMap<Integer, BitSet>();
        for (var readerEntry : observationsByReader.entrySet()) {
            var lowerBound = toBitSet(
                    order.predecessor(txns.get(readerEntry.getKey())), txnIds);
            for (var observation : readerEntry.getValue()) {
                lowerBound.set(observation.source);
                lowerBound.or(toBitSet(
                        order.predecessor(txns.get(observation.source)), txnIds));
            }
            result.put(readerEntry.getKey(), lowerBound);
        }
        return result;
    }

    private static <KeyType, ValueType> BitSet toBitSet(
            Collection<Transaction<KeyType, ValueType>> transactions,
            IdentityHashMap<Transaction<KeyType, ValueType>, Integer> txnIds) {
        var result = new BitSet();
        for (var transaction : transactions) {
            var index = txnIds.get(transaction);
            if (index != null) {
                result.set(index);
            }
        }
        return result;
    }

    private static <KeyType, ValueType> boolean hasOtherKeyVisibilityWitness(
            int transaction,
            int reader,
            KeyType targetKey,
            Map<Integer, List<FixedObservation<KeyType>>> observationsByReader,
            List<Transaction<KeyType, ValueType>> txns,
            PrecedenceOracle<Transaction<KeyType, ValueType>> order) {
        for (var observation : observationsByReader.getOrDefault(reader, Collections.emptyList())) {
            if (!Objects.equals(observation.key, targetKey)
                    && isSingleKeyLowerBound(
                            transaction, observation.source, txns, order)) {
                return true;
            }
        }
        return false;
    }

    private static <KeyType, ValueType> boolean before(
            PrecedenceOracle<Transaction<KeyType, ValueType>> order,
            List<Transaction<KeyType, ValueType>> txns,
            int from,
            int to) {
        return order.before(txns.get(from), txns.get(to));
    }

    private static void putForced(
            Map<Long, ForcedOrder> additions, int from, int to, boolean crossKey) {
        if (from == to) {
            return;
        }
        long pair = pairId(from, to);
        var previous = additions.get(pair);
        if (previous == null || crossKey && !previous.crossKey) {
            additions.put(pair, new ForcedOrder(from, to, crossKey));
        }
    }

    private static long pairId(int from, int to) {
        return ((long) from << 32) | (to & 0xffffffffL);
    }

    private static final class FixedObservation<KeyType> {
        private final int reader;
        private final KeyType key;
        private final int source;

        private FixedObservation(int reader, KeyType key, int source) {
            this.reader = reader;
            this.key = key;
            this.source = source;
        }
    }

    private static final class ForcedOrder {
        private final int from;
        private final int to;
        private final boolean crossKey;

        private ForcedOrder(int from, int to, boolean crossKey) {
            this.from = from;
            this.to = to;
            this.crossKey = crossKey;
        }
    }

    public static final class Result {
        public final int newForcedTransactionOrders;
        public final int crossKeyForcedOrders;
        public final int reachabilityDerivedOrders;
        public final int crossSnapshotDerivedOrders;
        public final int existingGraphDerivedOrders;
        public final int ordersCreatedBeyondInitialTc;
        public final int propagationRounds;
        public final boolean inconsistent;

        private Result(int newForcedTransactionOrders,
                int crossKeyForcedOrders, int reachabilityDerivedOrders,
                int crossSnapshotDerivedOrders, int existingGraphDerivedOrders,
                int ordersCreatedBeyondInitialTc, int propagationRounds,
                boolean inconsistent) {
            this.newForcedTransactionOrders = newForcedTransactionOrders;
            this.crossKeyForcedOrders = crossKeyForcedOrders;
            this.reachabilityDerivedOrders = reachabilityDerivedOrders;
            this.crossSnapshotDerivedOrders = crossSnapshotDerivedOrders;
            this.existingGraphDerivedOrders = existingGraphDerivedOrders;
            this.ordersCreatedBeyondInitialTc = ordersCreatedBeyondInitialTc;
            this.propagationRounds = propagationRounds;
            this.inconsistent = inconsistent;
        }
    }
}
