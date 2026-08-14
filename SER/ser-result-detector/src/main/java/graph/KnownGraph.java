package graph;

import static history.Event.EventType.PREDICATE_READ;
import static history.Event.EventType.READ;
import static history.Event.EventType.WRITE;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import com.google.common.graph.Graph;
import com.google.common.graph.GraphBuilder;
import com.google.common.graph.MutableGraph;
import com.google.common.graph.MutableValueGraph;
import com.google.common.graph.ValueGraph;
import com.google.common.graph.ValueGraphBuilder;

import org.apache.commons.lang3.tuple.Pair;

import history.Event;
import history.History;
import history.Transaction;
import lombok.Data;
import lombok.Getter;

@SuppressWarnings("UnstableApiUsage")
@Getter
public class KnownGraph<KeyType, ValueType> {
    public enum PredicateReadType {
        EXTERNAL, INTERNAL
    }

    @Data
    public static class WriteRef<KeyType, ValueType> {
        private final Transaction<KeyType, ValueType> txn;
        private final Event<KeyType, ValueType> event;
        private final int index;
        private final Long writeId;
    }

    @Data
    public static class PredicateTupleSource<KeyType, ValueType> {
        private final KeyType key;
        private final ValueType value;
        private final WriteRef<KeyType, ValueType> sourceWrite;
    }

    public static class PredicateObservation<KeyType, ValueType> {
        @Getter
        private final Transaction<KeyType, ValueType> txn;
        @Getter
        private final Event<KeyType, ValueType> predicateReadEvent;
        @Getter
        private final int eventIndex;
        @Getter
        private final List<PredicateTupleSource<KeyType, ValueType>> tupleSources;
        private final List<KeyType> predicateKeysById;
        private final Map<KeyType, Integer> predicateKeyIds;
        private final BitSet coveredKeyIds;
        @Getter
        private final int coverageEpoch;
        private final PredicateReadType defaultPredicateReadType;
        private final BitSet exceptionKeyIds;

        private PredicateObservation(Transaction<KeyType, ValueType> txn,
                Event<KeyType, ValueType> predicateReadEvent, int eventIndex,
                List<PredicateTupleSource<KeyType, ValueType>> tupleSources,
                List<KeyType> predicateKeysById, Map<KeyType, Integer> predicateKeyIds,
                BitSet coveredKeyIds, BitSet internalKeyIds, int coverageEpoch) {
            this.txn = txn;
            this.predicateReadEvent = predicateReadEvent;
            this.eventIndex = eventIndex;
            this.tupleSources = tupleSources;
            this.predicateKeysById = predicateKeysById;
            this.predicateKeyIds = predicateKeyIds;
            this.coveredKeyIds = (BitSet) coveredKeyIds.clone();
            this.coverageEpoch = coverageEpoch;
            if (coverageEpoch > 0) {
                this.defaultPredicateReadType = PredicateReadType.INTERNAL;
                this.exceptionKeyIds = new BitSet();
                return;
            }
            var internalCount = internalKeyIds.cardinality();
            var externalCount = coveredKeyIds.cardinality() - internalCount;
            this.defaultPredicateReadType = internalCount > externalCount
                    ? PredicateReadType.INTERNAL
                    : PredicateReadType.EXTERNAL;

            var exceptionBits = defaultPredicateReadType == PredicateReadType.INTERNAL
                    ? (BitSet) coveredKeyIds.clone()
                    : (BitSet) internalKeyIds.clone();
            if (defaultPredicateReadType == PredicateReadType.INTERNAL) {
                exceptionBits.andNot(internalKeyIds);
            }
            this.exceptionKeyIds = exceptionBits;
        }

        public PredicateReadType getPredicateReadType(KeyType key) {
            var keyId = predicateKeyIds.get(key);
            if (keyId == null || !coveredKeyIds.get(keyId)) {
                return null;
            }
            return exceptionKeyIds.get(keyId)
                    ? opposite(defaultPredicateReadType)
                    : defaultPredicateReadType;
        }

        public Map<KeyType, PredicateReadType> getPredicateReadTypes() {
            return new PredicateReadTypeMap<>(this);
        }

        private static PredicateReadType opposite(PredicateReadType type) {
            return type == PredicateReadType.INTERNAL
                    ? PredicateReadType.EXTERNAL
                    : PredicateReadType.INTERNAL;
        }
    }

    private static final class PredicateReadTypeMap<KeyType>
            extends AbstractMap<KeyType, PredicateReadType> {
        private final PredicateObservation<KeyType, ?> observation;

        private PredicateReadTypeMap(PredicateObservation<KeyType, ?> observation) {
            this.observation = observation;
        }

        @Override
        public PredicateReadType get(Object key) {
            @SuppressWarnings("unchecked")
            var typedKey = (KeyType) key;
            return observation.getPredicateReadType(typedKey);
        }

        @Override
        public boolean containsKey(Object key) {
            return get(key) != null;
        }

        @Override
        public int size() {
            return observation.coveredKeyIds.cardinality();
        }

        @Override
        public Set<Entry<KeyType, PredicateReadType>> entrySet() {
            return new AbstractSet<>() {
                @Override
                public int size() {
                    return PredicateReadTypeMap.this.size();
                }

                @Override
                public Iterator<Entry<KeyType, PredicateReadType>> iterator() {
                    return new Iterator<>() {
                        private int nextKeyId = observation.coveredKeyIds.nextSetBit(0);

                        @Override
                        public boolean hasNext() {
                            return nextKeyId >= 0;
                        }

                        @Override
                        public Entry<KeyType, PredicateReadType> next() {
                            if (nextKeyId < 0) {
                                throw new NoSuchElementException();
                            }
                            var keyId = nextKeyId;
                            nextKeyId = observation.coveredKeyIds.nextSetBit(keyId + 1);
                            var key = observation.predicateKeysById.get(keyId);
                            return Map.entry(key, observation.getPredicateReadType(key));
                        }
                    };
                }
            };
        }
    }

    private final MutableValueGraph<Transaction<KeyType, ValueType>, Collection<Edge<KeyType>>> readFrom = ValueGraphBuilder
            .directed().build();
    private final MutableValueGraph<Transaction<KeyType, ValueType>, Collection<Edge<KeyType>>> knownGraphA = ValueGraphBuilder
            .directed().build();
    private final MutableValueGraph<Transaction<KeyType, ValueType>, Collection<Edge<KeyType>>> knownGraphB = ValueGraphBuilder
            .directed().build();
    // Legacy unique (key,value) view retained only for old tests/helpers.
    private final Map<Pair<KeyType, ValueType>, WriteRef<KeyType, ValueType>> writes = new HashMap<>();
    private final Map<Pair<KeyType, ValueType>, List<WriteRef<KeyType, ValueType>>> writesByKeyValue = new HashMap<>();
    private final List<WriteRef<KeyType, ValueType>> allWrites = new ArrayList<>();
    private final Map<Pair<Transaction<KeyType, ValueType>, KeyType>, List<Integer>> txnWrites = new HashMap<>();
    private final List<PredicateObservation<KeyType, ValueType>> predicateObservations = new ArrayList<>();

    /**
     * Build a graph from a history
     *
     * The built graph contains SO and WR edges
     */
    public KnownGraph(History<KeyType, ValueType> history) {
        history.getTransactions().forEach(txn -> {
            knownGraphA.addNode(txn);
            knownGraphB.addNode(txn);
            readFrom.addNode(txn);
        });

        // add SO edges
        history.getSessions().forEach(session -> {
            Transaction<KeyType, ValueType> prevTxn = null;
            for (var txn : session.getTransactions()) {
                if (prevTxn != null) {
                    addEdge(knownGraphA, prevTxn, txn,
                            new Edge<>(EdgeType.SO, null));
                }
                prevTxn = txn;
            }
        });

        // build write indexes
        history.getTransactions().forEach(txn -> {
            var events = txn.getEvents();
            for (int i = 0; i < events.size(); i++) {
                var ev = events.get(i);
                if (ev.getType() != WRITE) {
                    continue;
                }
                var writeRef = new WriteRef<>(txn, ev, i, ev.getWriteId());
                allWrites.add(writeRef);
                var keyValue = Pair.of(ev.getKey(), ev.getValue());
                writes.putIfAbsent(keyValue, writeRef);
                writesByKeyValue.computeIfAbsent(keyValue, k -> new ArrayList<>()).add(writeRef);
                txnWrites.computeIfAbsent(Pair.of(txn, ev.getKey()), k -> new ArrayList<>()).add(i);
            }
        });

        // add WR edges from point reads
        var events = history.getEvents();
        events.stream().filter(e -> e.getType() == READ).forEach(ev -> {
            var writeRef = resolveReadSource(ev);
            var writeTxn = writeRef.getTxn();
            var txn = ev.getTransaction();

            if (writeTxn == txn) {
                return;
            }

            putEdge(writeTxn, txn, new Edge<KeyType>(EdgeType.WR, ev.getKey()));
        });

        // Build the finite key universe represented by the history. Each
        // Each evaluator narrows this universe through its query scope.
        var predicateKeysById = new ArrayList<KeyType>();
        var predicateKeyIds = new HashMap<KeyType, Integer>();
        for (var write : allWrites) {
            var key = write.getEvent().getKey();
            if (!predicateKeyIds.containsKey(key)) {
                predicateKeyIds.put(key, predicateKeysById.size());
                predicateKeysById.add(key);
            }
        }
        var immutablePredicateKeysById = List.copyOf(predicateKeysById);
        var immutablePredicateKeyIds = Map.copyOf(predicateKeyIds);

        // Collect predicate-read observations and classify each covered key.
        // A key is internal when any earlier predicate read in this transaction
        // covered it, regardless of predicate identity, or when this transaction
        // wrote the key before this read.
        history.getTransactions().forEach(txn -> {
            var txnEvents = txn.getEvents();
            var writtenKeyIds = new BitSet(predicateKeysById.size());
            var predicateObservedKeyIds = new BitSet(predicateKeysById.size());
            int coverageEpoch = 0;
            for (int i = 0; i < txnEvents.size(); i++) {
                var ev = txnEvents.get(i);
                if (ev.getType() == WRITE) {
                    writtenKeyIds.set(predicateKeyIds.get(ev.getKey()));
                    continue;
                }
                if (ev.getType() != PREDICATE_READ) {
                    continue;
                }
                var tupleSources = new ArrayList<PredicateTupleSource<KeyType, ValueType>>();
                for (var result : ev.getPredResults()) {
                    var sourceWrite = resolvePredicateResultSource(result);
                    tupleSources.add(new PredicateTupleSource<>(result.getKey(), result.getValue(), sourceWrite));
                }
                var coveredKeyIds = new BitSet(predicateKeysById.size());
                var internalKeyIds = new BitSet(predicateKeysById.size());
                var predicate = ev.getPredicate();
                for (int keyId = 0; keyId < predicateKeysById.size(); keyId++) {
                    var key = predicateKeysById.get(keyId);
                    if (predicate != null && !predicate.scope().covers(key)) {
                        continue;
                    }
                    coveredKeyIds.set(keyId);
                    if (coverageEpoch == 0
                            && (predicateObservedKeyIds.get(keyId) || writtenKeyIds.get(keyId))) {
                        internalKeyIds.set(keyId);
                    }
                }
                predicateObservations.add(new PredicateObservation<>(txn, ev, i, tupleSources,
                        immutablePredicateKeysById, immutablePredicateKeyIds,
                        coveredKeyIds, internalKeyIds, coverageEpoch));
                if (!predicateKeysById.isEmpty()
                        && coveredKeyIds.cardinality() == predicateKeysById.size()) {
                    coverageEpoch++;
                    predicateObservedKeyIds.clear();
                } else if (coverageEpoch == 0) {
                    predicateObservedKeyIds.or(coveredKeyIds);
                }
            }
        });
    }

    private WriteRef<KeyType, ValueType> resolveReadSource(Event<KeyType, ValueType> read) {
        return resolveUniqueSource(read.getKey(), read.getValue(), "read");
    }

    private WriteRef<KeyType, ValueType> resolvePredicateResultSource(
            Event.PredResult<KeyType, ValueType> result) {
        return resolveUniqueSource(result.getKey(), result.getValue(), "predicate result");
    }

    private WriteRef<KeyType, ValueType> resolveUniqueSource(
            KeyType key, ValueType value, String context) {
        var sources = writesByKeyValue.get(Pair.of(key, value));
        if (sources == null || sources.isEmpty()) {
            throw new IllegalStateException(String.format(
                    "No source write for %s (%s,%s)", context, key, value));
        }
        if (sources.size() > 1) {
            throw new IllegalStateException(String.format(
                    "Ambiguous %s source for (%s,%s); compact histories require unique (key,value) writes",
                    context, key, value));
        }
        return sources.get(0);
    }

    public void putEdge(Transaction<KeyType, ValueType> u,
            Transaction<KeyType, ValueType> v, Edge<KeyType> edge) {
        switch (edge.getType()) {
        case WR:
            addEdge(readFrom, u, v, edge);
            addEdge(knownGraphA, u, v, edge);
            break;
        case WW:
        case SO:
        case PR_WR:
            addEdge(knownGraphA, u, v, edge);
            break;
        case RW:
        case PR_RW:
            addEdge(knownGraphB, u, v, edge);
            break;
        }
    }

    /**
     * Remove all derived PR_WR edges from knownGraphA and all PR_RW edges
     * from knownGraphB.  Called at the start of each refresh cycle so that
     * stale derived edges are not accumulated across rounds.
     */
    public void clearDerivedPredicateEdges() {
        clearEdgesOfType(knownGraphA, EdgeType.PR_WR);
        clearEdgesOfType(knownGraphB, EdgeType.PR_RW);
    }

    private void clearEdgesOfType(
            MutableValueGraph<Transaction<KeyType, ValueType>, Collection<Edge<KeyType>>> graph,
            EdgeType type) {
        var snapshot = new ArrayList<>(graph.edges());
        for (var ep : snapshot) {
            var edgeOpt = graph.edgeValue(ep.source(), ep.target());
            if (edgeOpt.isEmpty()) continue;
            var edges = edgeOpt.get();
            edges.removeIf(e -> e.getType() == type);
            if (edges.isEmpty()) {
                graph.removeEdge(ep.source(), ep.target());
            }
        }
    }

    private void addEdge(
            MutableValueGraph<Transaction<KeyType, ValueType>, Collection<Edge<KeyType>>> graph,
            Transaction<KeyType, ValueType> u,
            Transaction<KeyType, ValueType> v, Edge<KeyType> edge) {
        if (!graph.hasEdgeConnecting(u, v)) {
            graph.putEdgeValue(u, v, new ArrayList<>());
        }
        graph.edgeValue(u, v).get().add(edge);
    }
}
