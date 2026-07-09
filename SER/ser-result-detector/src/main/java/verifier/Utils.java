package verifier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.google.common.collect.Streams;
import com.google.common.graph.EndpointPair;
import com.google.common.graph.ValueGraph;

import org.apache.commons.lang3.tuple.Pair;
import graph.Edge;
import graph.EdgeType;
import graph.MatrixGraph;
import history.Event;
import history.History;
import history.Transaction;
import history.Event.EventType;

class Utils {
    @lombok.Data
    private static class WriteRef<KeyType, ValueType> {
        private final Transaction<KeyType, ValueType> transaction;
        private final Event<KeyType, ValueType> event;
        private final int index;
        private final Long writeId;
    }

    static <KeyType, ValueType> boolean verifyInternalConsistency(History<KeyType, ValueType> history) {
        var writesById = new HashMap<Long, WriteRef<KeyType, ValueType>>();
        var writesByKeyValue = new HashMap<Pair<KeyType, ValueType>, List<WriteRef<KeyType, ValueType>>>();
        var txnWrites = new HashMap<Pair<Transaction<KeyType, ValueType>, KeyType>, ArrayList<Integer>>();
        var getEvents = ((Function<Event.EventType, Stream<Pair<Integer, Event<KeyType, ValueType>>>>) type -> history
                .getTransactions().stream().flatMap(txn -> {
                    var events = txn.getEvents();
                    return IntStream.range(0, events.size()).mapToObj(i -> Pair.of(i, events.get(i)))
                            .filter(p -> p.getRight().getType() == type);
                }));

        getEvents.apply(Event.EventType.WRITE).forEach(p -> {
            var i = p.getLeft();
            var ev = p.getRight();
            var writeRef = new WriteRef<>(ev.getTransaction(), ev, i, ev.getWriteId());
            if (ev.getWriteId() != null) {
                writesById.put(ev.getWriteId(), writeRef);
            }
            writesByKeyValue.computeIfAbsent(Pair.of(ev.getKey(), ev.getValue()), k -> new ArrayList<>()).add(writeRef);
            txnWrites.computeIfAbsent(Pair.of(ev.getTransaction(), ev.getKey()), k -> new ArrayList()).add(i);
        });

        for (var p : getEvents.apply(Event.EventType.READ).collect(Collectors.toList())) {
            var i = p.getLeft();
            var ev = p.getRight();
            if (!checkItemRead(ev, i, writesById, writesByKeyValue, txnWrites)) {
                return false;
            }
        }

        for (var p : getEvents.apply(Event.EventType.PREDICATE_READ).collect(Collectors.toList())) {
            var i = p.getLeft();
            var ev = p.getRight();
            if (!checkPredicateRead(ev, i, writesById, writesByKeyValue, txnWrites)) {
                return false;
            }
        }
        return true;
    }

    private static <KeyType, ValueType> boolean checkItemRead(Event<KeyType, ValueType> ev, int i,
            Map<Long, WriteRef<KeyType, ValueType>> writesById,
            Map<Pair<KeyType, ValueType>, List<WriteRef<KeyType, ValueType>>> writesByKeyValue,
            Map<Pair<Transaction<KeyType, ValueType>, KeyType>, ArrayList<Integer>> txnWrites) {
        var writeEv = resolveReadSource(ev, writesById, writesByKeyValue);
        if (writeEv == null) {
            return false;
        }

        var myWriteIndices = txnWrites.getOrDefault(Pair.of(ev.getTransaction(), ev.getKey()), new ArrayList<>());
        var writeIndices = txnWrites.get(Pair.of(writeEv.getTransaction(), writeEv.getEvent().getKey()));
        var j = Collections.binarySearch(writeIndices, writeEv.getIndex());

        if (writeEv.getTransaction() == ev.getTransaction()) {
            if (j != writeIndices.size() - 1 && writeIndices.get(j + 1) < i) {
                System.err.printf("%s not reading from latest write: %s\n", ev, writeEv.getEvent());
                return false;
            } else if (writeEv.getIndex() > i) {
                System.err.printf("%s reads from a write after it: %s\n", ev, writeEv.getEvent());
                return false;
            }
        } else if (j != writeIndices.size() - 1 || (!myWriteIndices.isEmpty() && myWriteIndices.get(0) < i)) {
            System.err.printf("%s not reading from latest write: %s\n", ev, writeEv.getEvent());
            return false;
        }
        return true;
    }

    static int latestWriteBefore(List<Integer> writeIndices, int pos) {
        if (writeIndices == null || writeIndices.isEmpty()) {
            return -1;
        }
        var k = Collections.binarySearch(writeIndices, pos);
        if (k >= 0) {
            k--;
        } else {
            k = -k - 2;
        }
        return k >= 0 ? writeIndices.get(k) : -1;
    }

    private static <KeyType, ValueType> boolean isCommitted(Transaction<KeyType, ValueType> transaction) {
        // Transactions without COMMIT status are treated as aborted/non-visible.
        return transaction.getStatus() == Transaction.TransactionStatus.COMMIT;
    }

    private static <KeyType, ValueType> boolean checkPredicateRead(Event<KeyType, ValueType> ev, int pos,
            Map<Long, WriteRef<KeyType, ValueType>> writesById,
            Map<Pair<KeyType, ValueType>, List<WriteRef<KeyType, ValueType>>> writesByKeyValue,
            Map<Pair<Transaction<KeyType, ValueType>, KeyType>, ArrayList<Integer>> txnWrites) {
        var predicate = ev.getPredicate();
        var results = ev.getPredResults();
        if (predicate == null || results == null) {
            System.err.printf("%s has null predicate or results\n", ev);
            return false;
        }

        var resultByKey = new HashMap<KeyType, ValueType>();
        for (var result : results) {
            var key = result.getKey();
            var value = result.getValue();

            if (resultByKey.containsKey(key)) {
                System.err.printf("%s has duplicate key %s in predicate result\n", ev, key);
                return false;
            }
            resultByKey.put(key, value);

            var ref = resolvePredicateResultSource(ev, result, writesById, writesByKeyValue);
            if (ref == null) {
                return false;
            }
            if (!isCommitted(ref.getTransaction())) {
                System.err.printf("%s result (%s,%s) comes from non-committed transaction %s\n", ev, key, value,
                        ref.getTransaction());
                return false;
            }
            if (!predicate.test(key, value)) {
                System.err.printf("%s result (%s,%s) does not satisfy predicate\n", ev, key, value);
                return false;
            }

            var selfWrites = txnWrites.getOrDefault(Pair.of(ev.getTransaction(), key), new ArrayList<>());
            var latestSelf = latestWriteBefore(selfWrites, pos);
            if (latestSelf >= 0) {
                var selfEvent = ev.getTransaction().getEvents().get(latestSelf);
                if (!Objects.equals(selfEvent.getValue(), value)) {
                    System.err.printf("%s should return own latest write for key %s\n", ev, key);
                    return false;
                }
                continue;
            }

            if (ref.getTransaction() != ev.getTransaction()) {
                var writerIndices = txnWrites.get(Pair.of(ref.getTransaction(), key));
                if (writerIndices == null) {
                    System.err.printf("%s writer indices missing for (%s,%s)\n", ev, key, value);
                    return false;
                }
                var j = Collections.binarySearch(writerIndices, ref.getIndex());
                if (j != writerIndices.size() - 1) {
                    System.err.printf("%s result (%s,%s) reads from intermediate write\n", ev, key, value);
                    return false;
                }
            } else if (ref.getIndex() >= pos) {
                System.err.printf("%s result (%s,%s) reads from future self write\n", ev, key, value);
                return false;
            }
        }

        for (var p : txnWrites.entrySet()) {
            var txnAndKey = p.getKey();
            if (txnAndKey.getLeft() != ev.getTransaction()) {
                continue;
            }
            var key = txnAndKey.getRight();
            var latestSelf = latestWriteBefore(p.getValue(), pos);
            if (latestSelf < 0) {
                continue;
            }
            var selfValue = ev.getTransaction().getEvents().get(latestSelf).getValue();
            var inResult = resultByKey.containsKey(key);
            var predTrue = predicate.test(key, selfValue);
            if (predTrue && !inResult) {
                System.err.printf("%s misses own visible tuple (%s,%s)\n", ev, key, selfValue);
                return false;
            }
            if (!predTrue && inResult) {
                System.err.printf("%s should not include key %s because own latest write fails predicate\n", ev, key);
                return false;
            }
            if (predTrue && inResult && !Objects.equals(resultByKey.get(key), selfValue)) {
                System.err.printf("%s returns wrong own value for key %s\n", ev, key);
                return false;
            }
        }

        return true;
    }

    private static <KeyType, ValueType> WriteRef<KeyType, ValueType> resolveReadSource(
            Event<KeyType, ValueType> ev,
            Map<Long, WriteRef<KeyType, ValueType>> writesById,
            Map<Pair<KeyType, ValueType>, List<WriteRef<KeyType, ValueType>>> writesByKeyValue) {
        if (ev.getSourceWriteId() != null) {
            var ref = writesById.get(ev.getSourceWriteId());
            if (ref == null) {
                System.err.printf("%s source_write_id %s has no corresponding write\n",
                        ev, ev.getSourceWriteId());
                return null;
            }
            if (!sourceMatches(ref, ev.getKey(), ev.getValue())) {
                System.err.printf("%s source_write_id %s points to (%s,%s)\n",
                        ev, ev.getSourceWriteId(), ref.getEvent().getKey(), ref.getEvent().getValue());
                return null;
            }
            return ref;
        }
        return resolveLegacySource(ev.getKey(), ev.getValue(), String.format("%s", ev), writesByKeyValue);
    }

    private static <KeyType, ValueType> WriteRef<KeyType, ValueType> resolvePredicateResultSource(
            Event<KeyType, ValueType> ev,
            Event.PredResult<KeyType, ValueType> result,
            Map<Long, WriteRef<KeyType, ValueType>> writesById,
            Map<Pair<KeyType, ValueType>, List<WriteRef<KeyType, ValueType>>> writesByKeyValue) {
        if (result.getSourceWriteId() != null) {
            var ref = writesById.get(result.getSourceWriteId());
            if (ref == null) {
                System.err.printf("%s result (%s,%s) source_write_id %s has no corresponding write\n",
                        ev, result.getKey(), result.getValue(), result.getSourceWriteId());
                return null;
            }
            if (!sourceMatches(ref, result.getKey(), result.getValue())) {
                System.err.printf("%s result (%s,%s) source_write_id %s points to (%s,%s)\n",
                        ev, result.getKey(), result.getValue(), result.getSourceWriteId(),
                        ref.getEvent().getKey(), ref.getEvent().getValue());
                return null;
            }
            return ref;
        }
        return resolveLegacySource(result.getKey(), result.getValue(),
                String.format("%s result (%s,%s)", ev, result.getKey(), result.getValue()),
                writesByKeyValue);
    }

    private static <KeyType, ValueType> WriteRef<KeyType, ValueType> resolveLegacySource(
            KeyType key,
            ValueType value,
            String context,
            Map<Pair<KeyType, ValueType>, List<WriteRef<KeyType, ValueType>>> writesByKeyValue) {
        var refs = writesByKeyValue.get(Pair.of(key, value));
        if (refs == null || refs.isEmpty()) {
            System.err.printf("%s has no corresponding write\n", context);
            return null;
        }
        if (refs.size() > 1) {
            System.err.printf("%s has ambiguous source for (%s,%s); compact histories require unique (key,value) writes\n",
                    context, key, value);
            return null;
        }
        return refs.get(0);
    }

    private static <KeyType, ValueType> boolean sourceMatches(
            WriteRef<KeyType, ValueType> ref, KeyType key, ValueType value) {
        return Objects.equals(ref.getEvent().getKey(), key)
                && Objects.equals(ref.getEvent().getValue(), value);
    }

    static <KeyType, ValueType> Map<Transaction<KeyType, ValueType>, Integer> getOrderInSession(
            History<KeyType, ValueType> history) {
        // @formatter:off
        return history.getSessions().stream()
                .flatMap(s -> Streams.zip(
                    s.getTransactions().stream(),
                    IntStream.range(0, s.getTransactions().size()).boxed(),
                    Pair::of))
                .collect(Collectors.toMap(Pair::getKey, Pair::getValue));
        // @formatter:on
    }

    /*
     * Delete edges in a way that preserves reachability
     */
    static <KeyType, ValueType> MatrixGraph<Transaction<KeyType, ValueType>> reduceEdges(
            MatrixGraph<Transaction<KeyType, ValueType>> graph,
            Map<Transaction<KeyType, ValueType>, Integer> orderInSession) {
        System.err.printf("Before: %d edges\n", graph.edges().size());
        var newGraph = MatrixGraph.ofNodes(graph);

        for (var n : graph.nodes()) {
            var succ = graph.successors(n);
            // @formatter:off
            var firstInSession = succ.stream()
                .collect(Collectors.toMap(
                    m -> m.getSession(),
                    Function.identity(),
                    (p, q) -> orderInSession.get(p)
                        < orderInSession.get(q) ? p : q));

            firstInSession.values().forEach(m -> newGraph.putEdge(n, m));

            succ.stream()
                .filter(m -> m.getSession() == n.getSession()
                        && orderInSession.get(m) == orderInSession.get(n) + 1)
                .forEach(m -> newGraph.putEdge(n, m));
            // @formatter:on
        }

        System.err.printf("After: %d edges\n", newGraph.edges().size());
        return newGraph;
    }

    static <KeyType, ValueType> String conflictsToDot(Collection<Transaction<KeyType, ValueType>> transactions,
            Collection<Pair<EndpointPair<Transaction<KeyType, ValueType>>, Collection<Edge<KeyType>>>> edges,
            Collection<SERConstraint<KeyType, ValueType>> constraints) {
        var builder = new StringBuilder();
        builder.append("digraph {\n");

        for (var txn : transactions) {
            builder.append(String.format("\"%s\";\n", txn));
        }

        for (var e : edges) {
            var pair = e.getLeft();
            var keys = e.getRight();
            var label = new StringBuilder();

            for (var k : keys) {
                if (k.getType() != EdgeType.SO) {
                    label.append(String.format("%s %s\\n", k.getType(), k.getKey()));
                } else {
                    label.append(String.format("%s\\n", k.getType()));
                }
            }

            builder.append(
                    String.format("\"%s\" -> \"%s\" [label=\"%s\"];\n", pair.source(), pair.target(), label));
        }

        int colorStep = 0x1000000 / (constraints.size() + 1);
        int color = 0;
        for (var c : constraints) {
            color += colorStep;
            for (var e : c.getEdges1()) {
                builder.append(String.format("\"%s\" -> \"%s\" [style=dotted,color=\"#%06x\"];\n", e.getFrom(), e.getTo(), color));
            }

            for (var e : c.getEdges2()) {
                builder.append(String.format("\"%s\" -> \"%s\" [style=dashed,color=\"#%06x\"];\n", e.getFrom(), e.getTo(), color));
            }
        }

        builder.append("}\n");
        return builder.toString();
    }

    static <KeyType, ValueType> String conflictsToLegacy(Collection<Transaction<KeyType, ValueType>> transactions,
            Collection<Pair<EndpointPair<Transaction<KeyType, ValueType>>, Collection<Edge<KeyType>>>> edges,
            Collection<SERConstraint<KeyType, ValueType>> constraints) {
        var builder = new StringBuilder();

        if (edges.isEmpty() && constraints.isEmpty()) {
            builder.append("Reject reason: serializability violation; no compact conflict core was extracted.\n");
            builder.append("The contradiction may be caused by SAT-derived RW or predicate-visibility constraints.\n");
            return builder.toString();
        }

        edges.forEach(p -> builder.append(String.format("Edge: %s\n", p)));
        constraints.forEach(c -> builder.append(String.format("Constraint: %s\n", c)));
        builder.append(String.format("Related transactions:\n"));
        transactions.forEach(t -> {
            builder.append(String.format("sessionid: %d, id: %d\n", t.getSession().getId(), t.getId()));
        });

        return builder.toString();
    }
}
