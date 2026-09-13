package verifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.apache.commons.lang3.tuple.Pair;
import history.Event;
import history.History;
import history.Transaction;
import history.Event.EventType;
import history.query.MapVisibleState;
import history.query.QueryException;
import history.query.QueryPlan;
import history.query.RelationResolver;

class Utils {
    @lombok.Data
    private static class WriteRef<KeyType, ValueType> {
        private final Transaction<KeyType, ValueType> transaction;
        private final Event<KeyType, ValueType> event;
        private final int index;
    }

    @lombok.Data
    private static class PredicateReadState<KeyType, ValueType> {
        private final int eventIndex;
        private final Map<KeyType, ValueType> resultByKey;
    }

    static <KeyType, ValueType> boolean verifyInternalConsistency(History<KeyType, ValueType> history) {
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
            var writeRef = new WriteRef<>(ev.getTransaction(), ev, i);
            writesByKeyValue.computeIfAbsent(Pair.of(ev.getKey(), ev.getValue()), k -> new ArrayList<>()).add(writeRef);
            txnWrites.computeIfAbsent(Pair.of(ev.getTransaction(), ev.getKey()), k -> new ArrayList()).add(i);
        });

        for (var p : getEvents.apply(Event.EventType.READ).collect(Collectors.toList())) {
            var i = p.getLeft();
            var ev = p.getRight();
            if (!checkItemRead(ev, i, writesByKeyValue, txnWrites)) {
                return false;
            }
        }

        for (var txn : history.getTransactions()) {
            var previousPredicateReads = new HashMap<Object,
                    PredicateReadState<KeyType, ValueType>>();
            var events = txn.getEvents();
            for (int i = 0; i < events.size(); i++) {
                var ev = events.get(i);
                if (ev.getType() != Event.EventType.PREDICATE_READ) {
                    continue;
                }
                var predicate = ev.getPredicate();
                var identity = predicate == null ? null : predicate.identity();
                var current = checkPredicateRead(ev, i, writesByKeyValue,
                        txnWrites, previousPredicateReads.get(identity));
                if (current == null) {
                    return false;
                }
                previousPredicateReads.put(identity, current);
            }
        }
        return true;
    }

    private static <KeyType, ValueType> boolean checkItemRead(Event<KeyType, ValueType> ev, int i,
            Map<Pair<KeyType, ValueType>, List<WriteRef<KeyType, ValueType>>> writesByKeyValue,
            Map<Pair<Transaction<KeyType, ValueType>, KeyType>, ArrayList<Integer>> txnWrites) {
        var writeEv = resolveUniqueSource(
                ev.getKey(), ev.getValue(), () -> String.format("%s", ev), writesByKeyValue);
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

    private static <KeyType, ValueType> PredicateReadState<KeyType, ValueType> checkPredicateRead(
            Event<KeyType, ValueType> ev, int pos,
            Map<Pair<KeyType, ValueType>, List<WriteRef<KeyType, ValueType>>> writesByKeyValue,
            Map<Pair<Transaction<KeyType, ValueType>, KeyType>, ArrayList<Integer>> txnWrites,
            PredicateReadState<KeyType, ValueType> previous) {
        var predicate = ev.getPredicate();
        var results = ev.getPredResults();
        if (predicate == null || results == null) {
            System.err.printf("%s has null predicate or results\n", ev);
            return null;
        }

        var resultByKey = new HashMap<KeyType, ValueType>();
        for (var result : results) {
            var key = result.getKey();
            var value = result.getValue();

            if (resultByKey.containsKey(key)) {
                System.err.printf("%s has duplicate key %s in predicate result\n", ev, key);
                return null;
            }
            resultByKey.put(key, value);

            var ref = resolveUniqueSource(
                    result.getKey(), result.getValue(),
                    () -> String.format("%s result (%s,%s)",
                            ev, result.getKey(), result.getValue()),
                    writesByKeyValue);
            if (ref == null) {
                return null;
            }
            if (!isCommitted(ref.getTransaction())) {
                System.err.printf("%s result (%s,%s) comes from non-committed transaction %s\n", ev, key, value,
                        ref.getTransaction());
                return null;
            }
            if (!predicate.scope().covers(key)) {
                System.err.printf("%s result (%s,%s) is outside query scope\n", ev, key, value);
                return null;
            }

            if (ref.getTransaction() != ev.getTransaction()) {
                var writerIndices = txnWrites.get(Pair.of(ref.getTransaction(), key));
                if (writerIndices == null) {
                    System.err.printf("%s writer indices missing for (%s,%s)\n", ev, key, value);
                    return null;
                }
                var j = Collections.binarySearch(writerIndices, ref.getIndex());
                if (j != writerIndices.size() - 1) {
                    System.err.printf("%s result (%s,%s) reads from intermediate write\n", ev, key, value);
                    return null;
                }
                var selfWrites = txnWrites.get(Pair.of(ev.getTransaction(), key));
                if (latestWriteBefore(selfWrites, pos) >= 0) {
                    System.err.printf("%s result (%s,%s) ignores an earlier self write\n", ev, key, value);
                    return null;
                }
            } else {
                var selfWrites = txnWrites.get(Pair.of(ev.getTransaction(), key));
                var latestSelf = latestWriteBefore(selfWrites, pos);
                if (ref.getIndex() >= pos || latestSelf != ref.getIndex()) {
                    System.err.printf("%s result (%s,%s) does not use its latest earlier self write\n",
                            ev, key, value);
                    return null;
                }
            }
        }

        var recorded = ev.getRecordedPredicateResult();
        if (recorded != null && !recorded.inputs().equals(resultByKey)) {
            System.err.printf("%s result.inputs disagree with resolved predicate inputs\n", ev);
            return null;
        }

        var coveredKeys = new HashSet<KeyType>();
        for (var keyValue : writesByKeyValue.keySet()) {
            if (predicate.scope().covers(keyValue.getLeft())) {
                coveredKeys.add(keyValue.getLeft());
            }
        }

        if (predicate instanceof QueryPlan
                && !((QueryPlan<?, ?>) predicate).isRowLocal()) {
            var snapshot = new HashMap<KeyType, ValueType>();
            boolean allLocal = true;
            for (var key : coveredKeys) {
                var selfWrites = txnWrites.get(Pair.of(ev.getTransaction(), key));
                var latestSelf = latestWriteBefore(selfWrites, pos);
                if (latestSelf < 0) {
                    allLocal = false;
                    break;
                }
                snapshot.put(key, ev.getTransaction().getEvents().get(latestSelf).getValue());
            }
            if (allLocal && !predicateSnapshotMatches(ev, snapshot)) {
                return null;
            }
            return new PredicateReadState<>(pos, new HashMap<>(resultByKey));
        }

        int previousIndex = previous == null ? -1 : previous.getEventIndex();

        for (var key : coveredKeys) {
            var selfWrites = txnWrites.get(Pair.of(ev.getTransaction(), key));
            var latestSelf = latestWriteBefore(selfWrites, pos);

            if (previous != null && latestSelf <= previousIndex) {
                if (!Objects.equals(resultByKey.get(key), previous.getResultByKey().get(key))
                        || resultByKey.containsKey(key) != previous.getResultByKey().containsKey(key)) {
                    System.err.printf("%s does not inherit key %s from its previous identical predicate read\n",
                            ev, key);
                    return null;
                }
                continue;
            }

            if (latestSelf < 0) {
                // No transaction-local basis for this key. Its observation is
                // external and is left to VIS/AR solving.
                continue;
            }

            var latestWrite = ev.getTransaction().getEvents().get(latestSelf);
            var expectedValue = latestWrite.getValue();
            var expectedPresent = predicateMatchesRow(ev, key, expectedValue);
            var actualPresent = resultByKey.containsKey(key);
            if (expectedPresent != actualPresent
                    || expectedPresent && !Objects.equals(expectedValue, resultByKey.get(key))) {
                System.err.printf("%s result for key %s is not determined by latest local write %s\n",
                        ev, key, latestWrite);
                return null;
            }
        }

        return new PredicateReadState<>(pos, new HashMap<>(resultByKey));
    }

    private static <KeyType, ValueType> boolean predicateSnapshotMatches(
            Event<KeyType, ValueType> event, Map<KeyType, ValueType> snapshot) {
        try {
            var evaluation = event.getPredicate().evaluate(
                    new MapVisibleState<>(snapshot, relationResolverFor(event)));
            var recorded = event.getRecordedPredicateResult();
            if (recorded != null) {
                if (!evaluation.canonicalEquals(recorded)) {
                    System.err.printf("%s whole-snapshot query does not match recorded result\n", event);
                    return false;
                }
                return true;
            }
            var expectedInputs = new HashMap<KeyType, ValueType>();
            for (var result : event.getPredResults()) {
                if (expectedInputs.putIfAbsent(result.getKey(), result.getValue()) != null) {
                    System.err.printf("%s has duplicate key in predicate result\n", event);
                    return false;
                }
            }
            if (!evaluation.inputs().equals(expectedInputs)) {
                System.err.printf("%s whole-snapshot query does not match recorded inputs\n", event);
                return false;
            }
            return true;
        } catch (QueryException exception) {
            System.err.printf("%s whole-snapshot query evaluation failed: %s\n",
                    event, exception.getMessage());
            return false;
        }
    }

    private static <KeyType, ValueType> RelationResolver<KeyType> relationResolverFor(
            Event<KeyType, ValueType> event) {
        var relations = event.getPredicate().scope().relations();
        return resolvedKey -> {
            var canonical = String.valueOf(resolvedKey);
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

    private static <KeyType, ValueType> boolean predicateMatchesRow(
            Event<KeyType, ValueType> event, KeyType key, ValueType value) {
        try {
            var evaluation = event.getPredicate().evaluate(
                    new MapVisibleState<>(Map.of(key, value), relationResolverFor(event)));
            return evaluation.inputs().containsKey(key)
                    && Objects.equals(evaluation.inputs().get(key), value);
        } catch (QueryException exception) {
            return false;
        }
    }

    private static <KeyType, ValueType> WriteRef<KeyType, ValueType> resolveUniqueSource(
            KeyType key,
            ValueType value,
            Supplier<String> context,
            Map<Pair<KeyType, ValueType>, List<WriteRef<KeyType, ValueType>>> writesByKeyValue) {
        var refs = writesByKeyValue.get(Pair.of(key, value));
        if (refs == null || refs.isEmpty()) {
            System.err.printf("%s has no corresponding write\n", context.get());
            return null;
        }
        if (refs.size() > 1) {
            System.err.printf("%s has ambiguous source for (%s,%s); compact histories require unique (key,value) writes\n",
                    context.get(), key, value);
            return null;
        }
        return refs.get(0);
    }

}
