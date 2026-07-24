package graph;

import static history.Event.EventType.PREDICATE_READ;
import static history.Event.EventType.READ;
import static history.Event.EventType.WRITE;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    @Data
    public static class PredicateObservation<KeyType, ValueType> {
        private final Transaction<KeyType, ValueType> txn;
        private final Event<KeyType, ValueType> predicateReadEvent;
        private final int eventIndex;
        private final List<PredicateTupleSource<KeyType, ValueType>> tupleSources;
        private final Map<KeyType, PredicateReadType> predicateReadTypes;

        public PredicateReadType getPredicateReadType(KeyType key) {
            return predicateReadTypes.get(key);
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
    private final Map<Long, WriteRef<KeyType, ValueType>> writesById = new HashMap<>();
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
                if (ev.getWriteId() != null && writesById.put(ev.getWriteId(), writeRef) != null) {
                    throw new IllegalStateException(String.format("Duplicate write_id %s", ev.getWriteId()));
                }
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
        var predicateKeyUniverse = new HashSet<KeyType>();
        for (var write : allWrites) {
            predicateKeyUniverse.add(write.getEvent().getKey());
        }

        // Collect predicate-read observations and classify each covered key.
        // A key is internal when any earlier predicate read in this transaction
        // covered it, regardless of predicate identity, or when this transaction
        // wrote the key before this read.
        history.getTransactions().forEach(txn -> {
            var txnEvents = txn.getEvents();
            var writtenKeys = new HashSet<KeyType>();
            var predicateObservedKeys = new HashSet<KeyType>();
            for (int i = 0; i < txnEvents.size(); i++) {
                var ev = txnEvents.get(i);
                if (ev.getType() == WRITE) {
                    writtenKeys.add(ev.getKey());
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
                var predicateReadTypes = new HashMap<KeyType, PredicateReadType>();
                var predicate = ev.getPredicate();
                for (var key : predicateKeyUniverse) {
                    if (predicate != null && !predicate.scope().covers(key)) {
                        continue;
                    }
                    var type = predicateObservedKeys.contains(key) || writtenKeys.contains(key)
                            ? PredicateReadType.INTERNAL
                            : PredicateReadType.EXTERNAL;
                    predicateReadTypes.put(key, type);
                }
                predicateObservations.add(new PredicateObservation<>(txn, ev, i, tupleSources,
                        Map.copyOf(predicateReadTypes)));
                predicateObservedKeys.addAll(predicateReadTypes.keySet());
            }
        });
    }

    private WriteRef<KeyType, ValueType> resolveReadSource(Event<KeyType, ValueType> read) {
        if (read.getSourceWriteId() != null) {
            var source = writesById.get(read.getSourceWriteId());
            if (source == null) {
                throw new IllegalStateException(String.format(
                        "No source write_id %s for read (%s,%s)",
                        read.getSourceWriteId(), read.getKey(), read.getValue()));
            }
            validateSourceMatches("read", read.getSourceWriteId(), read.getKey(), read.getValue(), source);
            return source;
        }
        return resolveLegacySource(read.getKey(), read.getValue(), "read");
    }

    private WriteRef<KeyType, ValueType> resolvePredicateResultSource(
            Event.PredResult<KeyType, ValueType> result) {
        if (result.getSourceWriteId() != null) {
            var source = writesById.get(result.getSourceWriteId());
            if (source == null) {
                throw new IllegalStateException(String.format(
                        "No source write_id %s for predicate result (%s,%s)",
                        result.getSourceWriteId(), result.getKey(), result.getValue()));
            }
            validateSourceMatches("predicate result", result.getSourceWriteId(),
                    result.getKey(), result.getValue(), source);
            return source;
        }
        return resolveLegacySource(result.getKey(), result.getValue(), "predicate result");
    }

    private WriteRef<KeyType, ValueType> resolveLegacySource(
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

    private void validateSourceMatches(String context, Long sourceWriteId,
            KeyType key, ValueType value, WriteRef<KeyType, ValueType> source) {
        if (!Objects.equals(source.getEvent().getKey(), key)
                || !Objects.equals(source.getEvent().getValue(), value)) {
            throw new IllegalStateException(String.format(
                    "%s source_write_id %s points to (%s,%s), expected (%s,%s)",
                    context, sourceWriteId,
                    source.getEvent().getKey(), source.getEvent().getValue(),
                    key, value));
        }
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
