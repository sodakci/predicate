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
public final class Prun {
    private Prun() {
    }

    static <KeyType, ValueType> Result prune(
            History<KeyType, ValueType> history,
            KnownGraph<KeyType, ValueType> graph,
            Collection<SERConstraint<KeyType, ValueType>> constraints) {
        return prune(history, graph, constraints, true, "PRUN");
    }

    static <KeyType, ValueType> Result pruneSnapshotOnly(
            History<KeyType, ValueType> history,
            KnownGraph<KeyType, ValueType> graph,
            Collection<SERConstraint<KeyType, ValueType>> constraints) {
        return prune(history, graph, constraints, false, "SNAPSHOT");
    }

    private static <KeyType, ValueType> Result prune(
            History<KeyType, ValueType> history,
            KnownGraph<KeyType, ValueType> graph,
            Collection<SERConstraint<KeyType, ValueType>> constraints,
            boolean includeReachabilityPruning,
            String modeLabel) {
        var txns = new ArrayList<>(history.getTransactions());
        txns.sort(Comparator
                .comparingLong((Transaction<KeyType, ValueType> txn) -> txn.getSession().getId())
                .thenComparingLong(Transaction::getId));
        var txnIds = new IdentityHashMap<Transaction<KeyType, ValueType>, Integer>();
        for (int i = 0; i < txns.size(); i++) {
            txnIds.put(txns.get(i), i);
        }

        var direct = emptyRows(txns.size());
        addGraphEdges(graph.getKnownGraphA().edges(), graph.getKnownGraphA(), txnIds, direct);
        addGraphEdges(graph.getKnownGraphB().edges(), graph.getKnownGraphB(), txnIds, direct);
        int initialDependencyEdges = cardinality(direct);
        var initialReachability = transitiveClosure(direct);
        int existingGraphDerivedOrders = cardinality(initialReachability)
                - initialDependencyEdges;

        var writesByKey = buildWritersByKey(graph, txnIds);
        var observations = buildFixedObservations(graph, txnIds);
        var observationsByReader = groupObservationsByReader(observations);

        var forcedPairs = new HashSet<Long>();
        int crossKeyForcedOrders = 0;
        int crossSnapshotDerivedOrders = 0;
        int rounds = 0;
        int pruningPasses = 0;
        boolean inconsistent = false;
        BitSet[] reachability;

        while (true) {
            reachability = transitiveClosure(direct);
            if (hasCycle(reachability)) {
                inconsistent = true;
                break;
            }

            pruningPasses++;
            System.err.printf("%s pruning round %d%n", modeLabel, pruningPasses);
            if (includeReachabilityPruning && resolveReachableConstraints(
                    graph, constraints, reachability, txnIds, direct) > 0) {
                continue;
            }

            var sharedLowerBounds = buildSharedLowerBounds(
                    txns.size(), observationsByReader, reachability);
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
                    if (reachability[competitor].get(source)
                            || reachability[reader].get(competitor)) {
                        continue;
                    }

                    boolean inside = sharedLowerBounds.get(reader).get(competitor);
                    if (inside) {
                        boolean crossKey = !isSingleKeyLowerBound(
                                competitor, source, reachability)
                                && hasOtherKeyVisibilityWitness(competitor, reader,
                                        observation.key, observationsByReader, reachability);
                        putForced(additions, competitor, source, crossKey);
                        snapshotWriterOrders.add(pairId(competitor, source));
                    } else if (reachability[source].get(competitor)) {
                        // C cannot be before the fixed source, so C must be after R.
                        putForced(additions, reader, competitor, false);
                        snapshotWriterOrders.add(pairId(source, competitor));
                    }
                }
            }

            if (additions.isEmpty()) {
                if (!includeReachabilityPruning) {
                    resolveSnapshotConstraints(graph, constraints,
                            snapshotWriterOrders, txnIds, direct);
                }
                break;
            }
            rounds++;
            for (var addition : additions.values()) {
                if (!direct[addition.from].get(addition.to)) {
                    direct[addition.from].set(addition.to);
                    long pair = pairId(addition.from, addition.to);
                    if (forcedPairs.add(pair) && addition.crossKey) {
                        crossKeyForcedOrders++;
                    }
                    if (rounds > 1) {
                        crossSnapshotDerivedOrders++;
                    }
                }
            }
            if (!includeReachabilityPruning) {
                resolveSnapshotConstraints(graph, constraints,
                        snapshotWriterOrders, txnIds, direct);
            }
        }

        reachability = transitiveClosure(direct);
        int allOrdersCreatedBeyondInitialTc = countDifference(
                reachability, initialReachability);
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
            IdentityHashMap<Transaction<KeyType, ValueType>, Integer> txnIds,
            BitSet[] direct) {
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
                        direct[from].set(to);
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
            BitSet[] reachability,
            IdentityHashMap<Transaction<KeyType, ValueType>, Integer> txnIds,
            BitSet[] direct) {
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
            boolean forward = reachability[first].get(second);
            boolean backward = reachability[second].get(first);
            if (forward != backward) {
                var selected = forward
                        ? constraint.getEdges1()
                        : constraint.getEdges2();
                for (var edge : selected) {
                    putEdgeIfAbsent(graph, edge);
                    Integer from = txnIds.get(edge.getFrom());
                    Integer to = txnIds.get(edge.getTo());
                    if (from != null && to != null && !from.equals(to)) {
                        direct[from].set(to);
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
        private static final int BAR_WIDTH = 30;

        private final String label;
        private final int total;
        private final boolean interactive;
        private final int nonInteractiveStep;

        private ConstraintProgress(String label, int total) {
            this.label = label;
            this.total = total;
            this.interactive = System.console() != null;
            this.nonInteractiveStep = Math.max(1,
                    Math.min(100, Math.max(1, total / 100)));
        }

        private void refresh(int checked, int solved, boolean done) {
            if (!interactive && !done && checked != 0
                    && checked % nonInteractiveStep != 0) {
                return;
            }

            var line = format(checked, solved);
            if (interactive) {
                System.err.print("\r" + line);
                if (done) {
                    System.err.println();
                }
            } else {
                System.err.println(line);
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
                    "%s post-check [%s] %3d%% checked %d/%d, solved %d",
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

    private static <KeyType, ValueType> void addGraphEdges(
            Set<com.google.common.graph.EndpointPair<Transaction<KeyType, ValueType>>> edges,
            com.google.common.graph.ValueGraph<Transaction<KeyType, ValueType>,
                    Collection<graph.Edge<KeyType>>> graph,
            IdentityHashMap<Transaction<KeyType, ValueType>, Integer> txnIds,
            BitSet[] direct) {
        for (var edge : edges) {
            var from = txnIds.get(edge.source());
            var to = txnIds.get(edge.target());
            if (from != null && to != null && !from.equals(to)) {
                direct[from].set(to);
            }
        }
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

    private static boolean isSingleKeyLowerBound(
            int transaction, int source, BitSet[] reachability) {
        return transaction == source || reachability[transaction].get(source);
    }

    private static <KeyType> Map<Integer, BitSet> buildSharedLowerBounds(
            int transactionCount,
            Map<Integer, List<FixedObservation<KeyType>>> observationsByReader,
            BitSet[] reachability) {
        var predecessors = emptyRows(transactionCount);
        for (int from = 0; from < transactionCount; from++) {
            for (int to = reachability[from].nextSetBit(0); to >= 0;
                    to = reachability[from].nextSetBit(to + 1)) {
                predecessors[to].set(from);
            }
        }

        var result = new LinkedHashMap<Integer, BitSet>();
        for (var readerEntry : observationsByReader.entrySet()) {
            var lowerBound = (BitSet) predecessors[readerEntry.getKey()].clone();
            for (var observation : readerEntry.getValue()) {
                lowerBound.set(observation.source);
                lowerBound.or(predecessors[observation.source]);
            }
            result.put(readerEntry.getKey(), lowerBound);
        }
        return result;
    }

    private static <KeyType> boolean hasOtherKeyVisibilityWitness(
            int transaction,
            int reader,
            KeyType targetKey,
            Map<Integer, List<FixedObservation<KeyType>>> observationsByReader,
            BitSet[] reachability) {
        for (var observation : observationsByReader.getOrDefault(reader, Collections.emptyList())) {
            if (!Objects.equals(observation.key, targetKey)
                    && isSingleKeyLowerBound(transaction, observation.source, reachability)) {
                return true;
            }
        }
        return false;
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

    private static BitSet[] transitiveClosure(BitSet[] direct) {
        var reachability = new BitSet[direct.length];
        for (int i = 0; i < direct.length; i++) {
            reachability[i] = (BitSet) direct[i].clone();
        }
        for (int intermediate = 0; intermediate < reachability.length; intermediate++) {
            for (int from = 0; from < reachability.length; from++) {
                if (reachability[from].get(intermediate)) {
                    reachability[from].or(reachability[intermediate]);
                }
            }
        }
        return reachability;
    }

    private static boolean hasCycle(BitSet[] reachability) {
        for (int i = 0; i < reachability.length; i++) {
            if (reachability[i].get(i)) {
                return true;
            }
        }
        return false;
    }

    private static BitSet[] emptyRows(int size) {
        var result = new BitSet[size];
        for (int i = 0; i < size; i++) {
            result[i] = new BitSet(size);
        }
        return result;
    }

    private static int cardinality(BitSet[] rows) {
        int result = 0;
        for (var row : rows) {
            result += row.cardinality();
        }
        return result;
    }

    private static int countDifference(BitSet[] minuend, BitSet[] subtrahend) {
        int result = 0;
        for (int i = 0; i < minuend.length; i++) {
            var difference = (BitSet) minuend[i].clone();
            difference.andNot(subtrahend[i]);
            result += difference.cardinality();
        }
        return result;
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
