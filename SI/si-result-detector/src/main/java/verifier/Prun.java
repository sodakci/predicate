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

/** Shared-snapshot fixed-point pruning for SI WW/RW constraints. */
public final class Prun {
    private Prun() {
    }

    static <KeyType, ValueType> Result prune(
            History<KeyType, ValueType> history,
            KnownGraph<KeyType, ValueType> graph,
            Collection<SIConstraint<KeyType, ValueType>> constraints) {
        return prune(history, graph, constraints, true, "PRUN");
    }

    static <KeyType, ValueType> Result pruneSnapshotOnly(
            History<KeyType, ValueType> history,
            KnownGraph<KeyType, ValueType> graph,
            Collection<SIConstraint<KeyType, ValueType>> constraints) {
        return prune(history, graph, constraints, false, "SNAPSHOT");
    }

    private static <KeyType, ValueType> Result prune(
            History<KeyType, ValueType> history,
            KnownGraph<KeyType, ValueType> graph,
            Collection<SIConstraint<KeyType, ValueType>> constraints,
            boolean includeInducedPruning,
            String modeLabel) {
        if (constraints.isEmpty()) {
            return new Result(0, 0, 0, 0, 0, 0, 0,
                    new SIVerifier.InducedGraph.Oracle<KeyType, ValueType>(graph)
                            .hasCycle());
        }

        var txns = new ArrayList<>(history.getTransactions());
        txns.sort(Comparator
                .comparingLong((Transaction<KeyType, ValueType> txn) -> txn.getSession().getId())
                .thenComparingLong(Transaction::getId));
        var txnIds = new IdentityHashMap<Transaction<KeyType, ValueType>, Integer>();
        for (int i = 0; i < txns.size(); i++) {
            txnIds.put(txns.get(i), i);
        }

        // Snapshot visibility is defined by A reachability. B must not be folded
        // into this closure; it only participates through A composition B.
        var directA = emptyRows(txns.size());
        addGraphAEdges(graph, txnIds, directA);
        int initialDependencyEdges = cardinality(directA);
        var orderA = new IncrementalOrder(directA);
        var initialReachability = cloneRows(orderA.reachability());
        int existingGraphDerivedOrders = cardinality(initialReachability)
                - initialDependencyEdges;

        var writesByKey = buildWritersByKey(graph, txnIds);
        var observations = buildFixedObservations(graph, txnIds);
        var observationsByReader = groupObservationsByReader(observations);

        var forcedPairs = new HashSet<Long>();
        var forcedAPairs = new HashSet<Long>();
        int crossKeyForcedOrders = 0;
        int crossSnapshotDerivedOrders = 0;
        int rounds = 0;
        int pruningPasses = 0;
        boolean inconsistent = false;

        while (true) {
            var inducedOracle =
                    new SIVerifier.InducedGraph.Oracle<KeyType, ValueType>(graph);
            if (inducedOracle.hasCycle()) {
                inconsistent = true;
                break;
            }

            pruningPasses++;
            System.err.printf("%s pruning round %d%n", modeLabel, pruningPasses);
            if (includeInducedPruning) {
                var resolution = resolveInducedConstraints(
                        graph, constraints, txnIds, orderA, inducedOracle);
                if (resolution.inconsistent) {
                    inconsistent = true;
                    break;
                }
                if (resolution.resolved > 0) {
                    continue;
                }
            }

            var reachability = orderA.reachability();
            var sharedLowerBounds = buildSharedLowerBounds(
                    observationsByReader, orderA.predecessors());
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
                    if (reachability[competitor].get(source)
                            || reachability[reader].get(competitor)
                            || hasTypedEdge(graph, txns.get(reader),
                                    txns.get(competitor), EdgeType.RW,
                                    observation.key)) {
                        continue;
                    }

                    boolean inside = sharedLowerBounds.get(reader).get(competitor);
                    if (inside) {
                        boolean crossKey = !isSingleKeyLowerBound(
                                competitor, source, reachability)
                                && hasOtherKeyVisibilityWitness(
                                        competitor, reader, observation.key,
                                        observationsByReader, reachability);
                        putForced(additions, forcedPairs, competitor, source,
                                crossKey, true);
                        snapshotWriterOrders.add(pairId(competitor, source));
                    } else if (reachability[source].get(competitor)) {
                        // The selected source precedes C in A, so R --RW--> C
                        // is a B edge. It must not be inserted into the A closure.
                        putForced(additions, forcedPairs, reader, competitor,
                                false, false);
                        snapshotWriterOrders.add(pairId(source, competitor));
                    }
                }
            }

            if (additions.isEmpty()) {
                if (resolveSnapshotConstraints(
                        graph, constraints, snapshotWriterOrders, txnIds, orderA) > 0) {
                    continue;
                }
                break;
            }
            rounds++;
            for (var addition : additions.values()) {
                long pair = pairId(addition.from, addition.to);
                if (!forcedPairs.add(pair)) {
                    continue;
                }
                if (addition.crossKey) {
                    crossKeyForcedOrders++;
                }
                if (addition.inA && orderA.add(addition.from, addition.to)) {
                    forcedAPairs.add(pair);
                }
                if (rounds > 1) {
                    crossSnapshotDerivedOrders++;
                }
            }

            resolveSnapshotConstraints(
                    graph, constraints, snapshotWriterOrders, txnIds, orderA);
        }

        var reachability = orderA.reachability();
        int allOrdersCreatedBeyondInitialTc = countDifference(
                reachability, initialReachability);
        int reachabilityDerivedOrders = Math.max(0,
                allOrdersCreatedBeyondInitialTc - forcedAPairs.size());
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

    private static <KeyType, ValueType> Resolution resolveInducedConstraints(
            KnownGraph<KeyType, ValueType> graph,
            Collection<SIConstraint<KeyType, ValueType>> constraints,
            IdentityHashMap<Transaction<KeyType, ValueType>, Integer> txnIds,
            IncrementalOrder orderA,
            SIVerifier.InducedGraph.Oracle<KeyType, ValueType> oracle) {
        int resolved = 0;
        var iterator = constraints.iterator();
        while (iterator.hasNext()) {
            var constraint = iterator.next();
            boolean forward = oracle.canAddAll(constraint.getEdges1());
            boolean backward = oracle.canAddAll(constraint.getEdges2());
            if (!forward && !backward) {
                return new Resolution(resolved, true);
            }
            if (forward == backward) {
                continue;
            }
            var selected = forward
                    ? constraint.getEdges1()
                    : constraint.getEdges2();
            oracle.addAll(selected);
            addConstraintSide(graph,
                    selected, txnIds, orderA);
            iterator.remove();
            resolved++;
        }
        return new Resolution(resolved, false);
    }

    private static <KeyType, ValueType> int resolveSnapshotConstraints(
            KnownGraph<KeyType, ValueType> graph,
            Collection<SIConstraint<KeyType, ValueType>> constraints,
            Set<Long> snapshotWriterOrders,
            IdentityHashMap<Transaction<KeyType, ValueType>, Integer> txnIds,
            IncrementalOrder orderA) {
        int resolved = 0;
        var iterator = constraints.iterator();
        while (iterator.hasNext()) {
            var constraint = iterator.next();
            int first = txnIds.get(constraint.getWriteTransaction1());
            int second = txnIds.get(constraint.getWriteTransaction2());
            boolean forward = snapshotWriterOrders.contains(pairId(first, second));
            boolean backward = snapshotWriterOrders.contains(pairId(second, first));
            if (forward == backward) {
                continue;
            }
            addConstraintSide(graph,
                    forward ? constraint.getEdges1() : constraint.getEdges2(),
                    txnIds, orderA);
            iterator.remove();
            resolved++;
        }
        return resolved;
    }

    private static <KeyType, ValueType> void addConstraintSide(
            KnownGraph<KeyType, ValueType> graph,
            Collection<SIEdge<KeyType, ValueType>> edges,
            IdentityHashMap<Transaction<KeyType, ValueType>, Integer> txnIds,
            IncrementalOrder orderA) {
        for (var edge : edges) {
            putEdgeIfAbsent(graph, edge);
            if (!isEdgeA(edge.getType())) {
                continue;
            }
            Integer from = txnIds.get(edge.getFrom());
            Integer to = txnIds.get(edge.getTo());
            if (from != null && to != null && !from.equals(to)) {
                orderA.add(from, to);
            }
        }
    }

    private static <KeyType, ValueType> void putEdgeIfAbsent(
            KnownGraph<KeyType, ValueType> graph,
            SIEdge<KeyType, ValueType> edge) {
        var target = isEdgeB(edge.getType())
                ? graph.getKnownGraphB()
                : graph.getKnownGraphA();
        boolean present = target.edgeValue(edge.getFrom(), edge.getTo())
                .orElse(Collections.emptyList()).stream()
                .anyMatch(existing -> existing.getType() == edge.getType()
                        && Objects.equals(existing.getKey(), edge.getKey()));
        if (!present) {
            graph.putEdge(edge.getFrom(), edge.getTo(),
                    new Edge<>(edge.getType(), edge.getKey()));
        }
    }

    private static <KeyType, ValueType> boolean hasTypedEdge(
            KnownGraph<KeyType, ValueType> graph,
            Transaction<KeyType, ValueType> from,
            Transaction<KeyType, ValueType> to,
            EdgeType type,
            KeyType key) {
        var target = isEdgeB(type)
                ? graph.getKnownGraphB()
                : graph.getKnownGraphA();
        return target.edgeValue(from, to).orElse(Collections.emptyList()).stream()
                .anyMatch(edge -> edge.getType() == type
                        && Objects.equals(edge.getKey(), key));
    }

    private static boolean isEdgeA(EdgeType type) {
        return type == EdgeType.SO || type == EdgeType.WR
                || type == EdgeType.WW || type == EdgeType.PR_WR;
    }

    private static boolean isEdgeB(EdgeType type) {
        return type == EdgeType.RW || type == EdgeType.PR_RW;
    }

    private static <KeyType, ValueType> void addGraphAEdges(
            KnownGraph<KeyType, ValueType> graph,
            IdentityHashMap<Transaction<KeyType, ValueType>, Integer> txnIds,
            BitSet[] direct) {
        for (var edge : graph.getKnownGraphA().edges()) {
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
            result.add(new FixedObservation<>(entry.getKey().getLeft(),
                    entry.getKey().getRight(), entry.getValue()));
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

    private static <KeyType> Map<Integer, BitSet> buildSharedLowerBounds(
            Map<Integer, List<FixedObservation<KeyType>>> observationsByReader,
            BitSet[] predecessors) {
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

    private static boolean isSingleKeyLowerBound(
            int transaction, int source, BitSet[] reachability) {
        return transaction == source || reachability[transaction].get(source);
    }

    private static <KeyType> boolean hasOtherKeyVisibilityWitness(
            int transaction,
            int reader,
            KeyType targetKey,
            Map<Integer, List<FixedObservation<KeyType>>> observationsByReader,
            BitSet[] reachability) {
        for (var observation : observationsByReader.getOrDefault(
                reader, Collections.emptyList())) {
            if (!Objects.equals(observation.key, targetKey)
                    && isSingleKeyLowerBound(
                            transaction, observation.source, reachability)) {
                return true;
            }
        }
        return false;
    }

    private static void putForced(
            Map<Long, ForcedOrder> additions,
            Set<Long> alreadyForced,
            int from,
            int to,
            boolean crossKey,
            boolean inA) {
        if (from == to || alreadyForced.contains(pairId(from, to))) {
            return;
        }
        long pair = pairId(from, to);
        var previous = additions.get(pair);
        if (previous == null || crossKey && !previous.crossKey) {
            additions.put(pair, new ForcedOrder(from, to, crossKey, inA));
        }
    }

    private static BitSet[] transitiveClosure(BitSet[] direct) {
        var reachability = cloneRows(direct);
        for (int intermediate = 0; intermediate < reachability.length; intermediate++) {
            for (int from = 0; from < reachability.length; from++) {
                if (reachability[from].get(intermediate)) {
                    reachability[from].or(reachability[intermediate]);
                }
            }
        }
        return reachability;
    }

    private static BitSet[] cloneRows(BitSet[] rows) {
        var result = new BitSet[rows.length];
        for (int i = 0; i < rows.length; i++) {
            result[i] = (BitSet) rows[i].clone();
        }
        return result;
    }

    private static final class IncrementalOrder {
        private final BitSet[] direct;
        private final BitSet[] reachability;
        private final BitSet[] predecessors;

        private IncrementalOrder(BitSet[] direct) {
            this.direct = direct;
            this.reachability = transitiveClosure(direct);
            this.predecessors = emptyRows(direct.length);
            for (int from = 0; from < reachability.length; from++) {
                for (int to = reachability[from].nextSetBit(0); to >= 0;
                        to = reachability[from].nextSetBit(to + 1)) {
                    predecessors[to].set(from);
                }
            }
        }

        private BitSet[] reachability() {
            return reachability;
        }

        private BitSet[] predecessors() {
            return predecessors;
        }

        private boolean add(int from, int to) {
            if (direct[from].get(to)) {
                return false;
            }
            direct[from].set(to);
            if (reachability[from].get(to)) {
                return true;
            }
            var affectedPredecessors = (BitSet) predecessors[from].clone();
            affectedPredecessors.set(from);
            var affectedSuccessors = (BitSet) reachability[to].clone();
            affectedSuccessors.set(to);
            for (int predecessor = affectedPredecessors.nextSetBit(0);
                    predecessor >= 0;
                    predecessor = affectedPredecessors.nextSetBit(predecessor + 1)) {
                reachability[predecessor].or(affectedSuccessors);
            }
            for (int successor = affectedSuccessors.nextSetBit(0);
                    successor >= 0;
                    successor = affectedSuccessors.nextSetBit(successor + 1)) {
                predecessors[successor].or(affectedPredecessors);
            }
            return true;
        }
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

    private static final class Resolution {
        private final int resolved;
        private final boolean inconsistent;

        private Resolution(int resolved, boolean inconsistent) {
            this.resolved = resolved;
            this.inconsistent = inconsistent;
        }
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
        private final boolean inA;

        private ForcedOrder(int from, int to, boolean crossKey, boolean inA) {
            this.from = from;
            this.to = to;
            this.crossKey = crossKey;
            this.inA = inA;
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
                int crossKeyForcedOrders,
                int reachabilityDerivedOrders,
                int crossSnapshotDerivedOrders,
                int existingGraphDerivedOrders,
                int ordersCreatedBeyondInitialTc,
                int propagationRounds,
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
