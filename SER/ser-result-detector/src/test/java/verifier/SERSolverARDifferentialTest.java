package verifier;

import graph.KnownGraph;
import history.Event;
import history.History;
import history.Transaction;
import history.loaders.PredicateHistoryLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.tuple.Pair;

import static history.Event.EventType.READ;
import static history.Event.EventType.WRITE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Small-history SER differential tests.
 *
 * Design rule:
 *  - small histories are correctness oracle cases;
 *  - large/dense histories are stress/performance cases, not correctness oracle cases.
 *
 * This test intentionally uses exhaustive AR enumeration for tiny histories and then compares
 * histories covered by the currently implemented external-predicate path:
 *  - direct SERSolverAR encoding;
 *  - SERVerifier with pruning on/off;
 *  - SERVerifier with coalescing on/off;
 *  - the production MonoSAT solver path.
 *
 * Important: the oracle is source_write_id-first when source ids are present.
 * Compact PRHIST cases omit source ids and use unique (key,value) as identity.
 */
class SERSolverARDifferentialTest {
    private static final long INIT_SESSION_ID = -1L;
    private static final long INIT_TXN_ID = -1L;

    private static final int IN_MEMORY_CASES = 160;
    private static final int PRHIST_CASES = 60;
    private static final int MAX_ORACLE_CLIENT_TXNS = 8;
    private static final int TXNS = 4;
    private static final List<String> KEYS = List.of("k0", "k1", "k2");

    @TempDir
    Path tempDir;

    @Test
    void handCraftedRegressionHistoriesMatchExhaustiveArOracle() {
        var cases = List.of(
                bottomOldVersionSatisfiedButCurrentDoesNot(),
                ordinaryOldVersionSatisfiedButCurrentDoesNot(),
                currentVersionSatisfiedButPredicateOmits(),
                predicateContainsNewVersionOldBottomDoesNotConflict(),
                sameValueDifferentWriteIdPointRead(),
                widePredicateMissingOneKey(),
                selfWriteVisibleInPredicate()
        );

        for (int i = 0; i < cases.size(); i++) {
            var testCase = cases.get(i);
            boolean oracle = exhaustiveOracle(testCase.history);
            assertEquals(testCase.expected, oracle,
                    () -> "test case expectation is wrong: " + testCase.name + "\n" + describe(testCase.history));

            if (usesOnlyExternalPredicateKeys(testCase.history)) {
                assertDirectSolverMatchesOracle(testCase.history, oracle, "hand:" + testCase.name);
                assertVerifierMatchesOracle(testCase.history, oracle, -10_000 - i, "hand:" + testCase.name);
            }
        }
    }

    @Test
    void randomSmallHistoriesMatchExhaustiveArOracle() {
        int comparedPredicateCases = 0;
        for (int seed = 0; seed < IN_MEMORY_CASES; seed++) {
            int caseSeed = seed;
            var history = randomHistory(seed);
            boolean expected = exhaustiveOracle(history);
            if (!usesOnlyExternalPredicateKeys(history)) {
                continue;
            }

            assertDirectSolverMatchesOracle(history, expected, "random:" + caseSeed);
            assertVerifierMatchesOracle(history, expected, caseSeed, "random:" + caseSeed);
            if (hasPredicateKeys(history)) {
                comparedPredicateCases++;
            }
        }
        assertTrue(comparedPredicateCases > 0, "external-predicate differential cases must not be empty");
    }

    @Test
    void randomPrhistJsonHistoriesMatchExhaustiveArOracle() throws Exception {
        int comparedPredicateCases = 0;
        for (int seed = 0; seed < PRHIST_CASES; seed++) {
            int caseSeed = seed;
            var historyDir = writeRandomPrhist(seed);
            var history = new PredicateHistoryLoader(historyDir).loadHistory();
            boolean expected = exhaustiveOracle(history);
            if (!usesOnlyExternalPredicateKeys(history)) {
                continue;
            }

            assertDirectSolverMatchesOracle(history, expected, "prhist:" + caseSeed);
            assertVerifierMatchesOracle(history, expected, caseSeed, "prhist:" + caseSeed);
            if (hasPredicateKeys(history)) {
                comparedPredicateCases++;
            }
        }
        assertTrue(comparedPredicateCases > 0, "external PRHIST differential cases must not be empty");
    }

    private static boolean usesOnlyExternalPredicateKeys(History<String, ?> history) {
        var graph = new KnownGraph<>(history);
        var types = graph.getPredicateObservations().stream()
                .flatMap(observation -> observation.getPredicateReadTypes().values().stream())
                .collect(Collectors.toList());
        return types.stream().allMatch(type -> type == KnownGraph.PredicateReadType.EXTERNAL);
    }

    private static boolean hasPredicateKeys(History<String, ?> history) {
        return new KnownGraph<>(history).getPredicateObservations().stream()
                .anyMatch(observation -> !observation.getPredicateReadTypes().isEmpty());
    }

    private static History<String, Integer> randomHistory(int seed) {
        var random = new Random(0x5eedL + seed);
        var history = new History<String, Integer>();
        var knownWrites = new HashMap<String, List<WriteChoice<Integer>>>();
        long[] nextWriteId = {1L};

        var initSession = history.addSession(INIT_SESSION_ID);
        var initTxn = history.addTransaction(initSession, INIT_TXN_ID);
        for (int i = 0; i < KEYS.size(); i++) {
            int value = i * 10;
            addWrite(history, initTxn, KEYS.get(i), value, nextWriteId[0]++, INIT_TXN_ID, knownWrites);
        }
        initTxn.setStatus(Transaction.TransactionStatus.COMMIT);

        var sessions = List.of(history.addSession(1L), history.addSession(2L));
        for (int txnId = 1; txnId <= TXNS; txnId++) {
            var session = sessions.get((txnId - 1) % sessions.size());
            var txn = history.addTransaction(session, txnId);
            var localWrittenKeys = new HashSet<String>();

            int operations = 2 + random.nextInt(4);
            for (int op = 0; op < operations; op++) {
                int choice = random.nextInt(3);
                if (choice == 0) {
                    addRandomRead(history, random, txn, localWrittenKeys, knownWrites);
                } else if (choice == 1) {
                    addRandomPredicateRead(history, random, txn, knownWrites);
                } else {
                    addRandomWrite(history, random, txn, nextWriteId, localWrittenKeys, knownWrites);
                }
            }
            if (txn.getEvents().stream().noneMatch(event -> event.getType() == WRITE)) {
                addRandomWrite(history, random, txn, nextWriteId, localWrittenKeys, knownWrites);
            }

            txn.setStatus(Transaction.TransactionStatus.COMMIT);
        }

        return history;
    }

    private Path writeRandomPrhist(int seed) throws Exception {
        var random = new Random(0x51_0adL + seed);
        var historyDir = tempDir.resolve("prhist-" + seed);
        Files.createDirectories(historyDir);

        var knownWrites = new HashMap<String, List<JsonWriteChoice>>();
        long[] nextWriteId = {1L};
        var initialRows = new ArrayList<String>();
        for (int i = 0; i < KEYS.size(); i++) {
            int semantic = i * 10;
            long writeId = nextWriteId[0]++;
            initialRows.add(jsonInitialTuple(KEYS.get(i), semantic, writeId));
            knownWrites.computeIfAbsent(KEYS.get(i), ignored -> new ArrayList<>())
                    .add(new JsonWriteChoice(KEYS.get(i), semantic, semantic, writeId, INIT_TXN_ID, 0));
        }
        Files.writeString(historyDir.resolve("initial_state.json"), "[" + String.join(",", initialRows) + "]");

        var lines = new ArrayList<String>();
        for (int txnId = 1; txnId <= TXNS; txnId++) {
            var ops = new ArrayList<String>();
            var localWrittenKeys = new HashSet<String>();
            int operations = 2 + random.nextInt(4);
            for (int op = 0; op < operations; op++) {
                int choice = random.nextInt(3);
                if (choice == 0) {
                    var source = randomJsonWrite(random, knownWrites, txnId, localWrittenKeys);
                    if (source == null) {
                        continue;
                    }
                    ops.add(String.format(
                            "{\"type\":\"r\",\"key\":\"%s\",\"value\":%d}",
                            source.key, source.value));
                } else if (choice == 1) {
                    ops.add(randomJsonPredicateRead(random, knownWrites));
                } else {
                    var write = nextJsonWrite(random, nextWriteId, txnId, ops.size(), localWrittenKeys, knownWrites);
                    if (write == null) {
                        continue;
                    }
                    ops.add(String.format(
                            "{\"type\":\"w\",\"key\":\"%s\",\"value\":%d}",
                            write.key, write.value));
                    knownWrites.computeIfAbsent(write.key, ignored -> new ArrayList<>()).add(write);
                }
            }
            if (ops.stream().noneMatch(op -> op.contains("\"type\":\"w\""))) {
                var write = nextJsonWrite(random, nextWriteId, txnId, ops.size(), localWrittenKeys, knownWrites);
                ops.add(String.format(
                        "{\"type\":\"w\",\"key\":\"%s\",\"value\":%d}",
                        write.key, write.value));
                knownWrites.computeIfAbsent(write.key, ignored -> new ArrayList<>()).add(write);
            }
            long session = ((txnId - 1) % 2) + 1;
            lines.add(String.format(
                    "{\"session\":%d,\"session_seq\":%d,\"txn\":%d,\"kind\":\"differential.random\",\"status\":\"commit\",\"ops\":[%s]}",
                    session, txnId, txnId, String.join(",", ops)));
        }
        Files.write(historyDir.resolve("history.prhist.jsonl"), lines);
        return historyDir;
    }

    private static <ValueType> void assertDirectSolverMatchesOracle(
            History<String, ValueType> history,
            boolean expected,
            String label) {
        for (boolean coalescing : List.of(true, false)) {
            assertEquals(expected, solveSer(history, coalescing),
                    () -> "direct SERSolverAR mismatch for " + label
                            + " coalescing=" + coalescing + "\n" + describe(history));
        }
    }

    private static <ValueType> boolean solveSer(History<String, ValueType> history, boolean coalesce) {
        SERVerifier.setCoalesceConstraints(coalesce);
        var graph = new KnownGraph<>(history);
        return new SERSolverAR<>(history, graph, SERVerifier.generateConstraintsSER(history, graph)).solve();
    }

    private static <ValueType> void assertVerifierMatchesOracle(
            History<String, ValueType> history,
            boolean expected,
            int seed,
            String label) {
        try {
            for (boolean pruning : List.of(true, false)) {
                for (boolean coalescing : List.of(true, false)) {
                    Pruning.setEnablePruning(pruning);
                    SERVerifier.setCoalesceConstraints(coalescing);
                    SERVerifier.setDotOutput(false);
                    SERVerifier.setCompareDerivedPredicateEdges(false);

                    boolean actual = new SERVerifier<String, ValueType>(() -> history).audit();
                    assertEquals(expected, actual,
                            () -> String.format(
                                    "label=%s seed=%d solver=monosat pruning=%s coalescing=%s%n%s",
                                    label, seed, pruning, coalescing, describe(history)));
                }
            }
        } finally {
            Pruning.setEnablePruning(true);
            SERVerifier.setCoalesceConstraints(true);
            SERVerifier.setDotOutput(false);
            SERVerifier.setCompareDerivedPredicateEdges(false);
        }
    }

    /**
     * Exhaustive SER oracle.
     *
     * It enumerates every AR order that respects session order.
     * A candidate order is valid iff every point read and predicate read observes exactly
     * the latest visible write version.
     *
     * Scope: this is deliberately a tiny-history oracle. Larger histories are
     * covered by stress and CLI-level tests, because exhaustive AR enumeration is
     * factorial in the number of client transactions.
     */
    private static <ValueType> boolean exhaustiveOracle(History<String, ValueType> history) {
        assertOracleScope(history);

        var txns = history.getTransactions().stream()
                .filter(txn -> !isBottomTxn(txn))
                .sorted(Comparator.comparingLong(Transaction::getId))
                .collect(Collectors.toCollection(ArrayList::new));

        var writes = collectWrites(history);
        var writesById = writes.stream()
                .filter(write -> write.event.getWriteId() != null)
                .collect(Collectors.toMap(write -> write.event.getWriteId(), write -> write));
        var writesByKeyValue = writes.stream()
                .collect(Collectors.groupingBy(write -> Pair.of(write.event.getKey(), write.event.getValue())));

        return anyPermutationSatisfies(txns, 0, order ->
                respectsSessionOrder(history, order)
                        && pointReadsMatchLatestVisible(history, order, writes, writesById, writesByKeyValue)
                        && predicateReadsMatchLatestVisible(history, order, writes, writesById, writesByKeyValue));
    }

    private static <ValueType> boolean anyPermutationSatisfies(
            List<Transaction<String, ValueType>> txns,
            int index,
            java.util.function.Predicate<List<Transaction<String, ValueType>>> predicate) {
        if (index == txns.size()) {
            return predicate.test(txns);
        }
        for (int i = index; i < txns.size(); i++) {
            swap(txns, index, i);
            if (anyPermutationSatisfies(txns, index + 1, predicate)) {
                swap(txns, index, i);
                return true;
            }
            swap(txns, index, i);
        }
        return false;
    }

    private static <ValueType> boolean respectsSessionOrder(
            History<String, ValueType> history,
            List<Transaction<String, ValueType>> order) {
        var rank = ranks(order);
        for (var session : history.getSessions()) {
            Transaction<String, ValueType> previous = null;
            for (var txn : session.getTransactions()) {
                if (isBottomTxn(txn)) {
                    continue;
                }
                if (previous != null && rank.get(previous) >= rank.get(txn)) {
                    return false;
                }
                previous = txn;
            }
        }
        return true;
    }

    private static <ValueType> boolean pointReadsMatchLatestVisible(
            History<String, ValueType> history,
            List<Transaction<String, ValueType>> order,
            List<WriteInstance<ValueType>> writes,
            Map<Long, WriteInstance<ValueType>> writesById,
            Map<Pair<String, ValueType>, List<WriteInstance<ValueType>>> writesByKeyValue) {
        for (var txn : history.getTransactions()) {
            var events = txn.getEvents();
            for (int i = 0; i < events.size(); i++) {
                var event = events.get(i);
                if (event.getType() != READ) {
                    continue;
                }
                var expectedSource = resolveSource(event.getKey(), event.getValue(),
                        event.getSourceWriteId(), writesById, writesByKeyValue);
                var actualSource = latestVisibleWrite(event.getKey(), txn, i, order, writes);
                if (!sameWrite(expectedSource, actualSource)) {
                    return false;
                }
                if (expectedSource == null || !Objects.equals(event.getValue(), expectedSource.event.getValue())) {
                    return false;
                }
            }
        }
        return true;
    }

    private static <ValueType> boolean predicateReadsMatchLatestVisible(
            History<String, ValueType> history,
            List<Transaction<String, ValueType>> order,
            List<WriteInstance<ValueType>> writes,
            Map<Long, WriteInstance<ValueType>> writesById,
            Map<Pair<String, ValueType>, List<WriteInstance<ValueType>>> writesByKeyValue) {
        var keys = writes.stream()
                .map(write -> write.event.getKey())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        for (var txn : history.getTransactions()) {
            var events = txn.getEvents();
            for (int i = 0; i < events.size(); i++) {
                var event = events.get(i);
                if (event.getType() != Event.EventType.PREDICATE_READ) {
                    continue;
                }

                var expected = new LinkedHashMap<String, Object>();
                for (var key : keys) {
                    var latest = latestVisibleWrite(key, txn, i, order, writes);
                    if (latest != null && event.getPredicate().test(key, latest.event.getValue())) {
                        expected.put(key, writeToken(latest));
                    }
                }

                var actual = new LinkedHashMap<String, Object>();
                for (var result : event.getPredResults()) {
                    var source = resolveSource(result.getKey(), result.getValue(),
                            result.getSourceWriteId(), writesById, writesByKeyValue);
                    if (source == null) {
                        return false;
                    }
                    if (actual.put(result.getKey(), writeToken(source)) != null) {
                        return false;
                    }
                    if (!Objects.equals(result.getKey(), source.event.getKey())) {
                        return false;
                    }
                    if (!Objects.equals(result.getValue(), source.event.getValue())) {
                        return false;
                    }
                }

                if (!expected.equals(actual)) {
                    return false;
                }

                for (var result : event.getPredResults()) {
                    var actualSource = latestVisibleWrite(result.getKey(), txn, i, order, writes);
                    var expectedSource = resolveSource(result.getKey(), result.getValue(),
                            result.getSourceWriteId(), writesById, writesByKeyValue);
                    if (!sameWrite(expectedSource, actualSource)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static <ValueType> void assertOracleScope(History<String, ValueType> history) {
        var clientTxnCount = history.getTransactions().stream()
                .filter(txn -> !isBottomTxn(txn))
                .count();
        if (clientTxnCount > MAX_ORACLE_CLIENT_TXNS) {
            throw new AssertionError(String.format(
                    "exhaustive oracle is scoped to at most %d client transactions, got %d",
                    MAX_ORACLE_CLIENT_TXNS, clientTxnCount));
        }

        var writesById = new HashMap<Long, WriteInstance<ValueType>>();
        var writesByKeyValue = new HashMap<Pair<String, ValueType>, WriteInstance<ValueType>>();
        for (var write : collectWrites(history)) {
            if (write.event.getWriteId() != null
                    && writesById.put(write.event.getWriteId(), write) != null) {
                throw new AssertionError("duplicate write_id in oracle case: " + write.event.getWriteId());
            }
            var keyValue = Pair.of(write.event.getKey(), write.event.getValue());
            var previous = writesByKeyValue.putIfAbsent(keyValue, write);
            if (previous != null
                    && (previous.event.getWriteId() == null || write.event.getWriteId() == null)) {
                throw new AssertionError("duplicate compact (key,value) in oracle case: " + keyValue);
            }
        }

        for (var txn : history.getTransactions()) {
            if (txn.getStatus() != Transaction.TransactionStatus.COMMIT) {
                throw new AssertionError("oracle cases must contain only committed transactions: " + txn);
            }
            for (var event : txn.getEvents()) {
                if (event.getType() == READ) {
                    assertPointReadInOracleScope(event, writesById, writesByKeyValue);
                } else if (event.getType() == Event.EventType.PREDICATE_READ) {
                    assertPredicateReadInOracleScope(event, writesById, writesByKeyValue);
                }
            }
        }
    }

    private static <ValueType> void assertPointReadInOracleScope(
            Event<String, ValueType> event,
            Map<Long, WriteInstance<ValueType>> writesById,
            Map<Pair<String, ValueType>, WriteInstance<ValueType>> writesByKeyValue) {
        var source = resolveSource(event.getKey(), event.getValue(),
                event.getSourceWriteId(), writesById, singletonLists(writesByKeyValue));
        if (source == null) {
            throw new AssertionError("oracle read has no source write: " + event);
        }
        if (!Objects.equals(event.getKey(), source.event.getKey())
                || !Objects.equals(event.getValue(), source.event.getValue())) {
            throw new AssertionError("oracle read source points to a different row: " + event);
        }
    }

    private static <ValueType> void assertPredicateReadInOracleScope(
            Event<String, ValueType> event,
            Map<Long, WriteInstance<ValueType>> writesById,
            Map<Pair<String, ValueType>, WriteInstance<ValueType>> writesByKeyValue) {
        if (event.getPredicate() == null) {
            throw new AssertionError("oracle predicate read without predicate is not allowed: " + event);
        }
        var keys = new HashSet<String>();
        for (var result : event.getPredResults()) {
            if (!keys.add(result.getKey())) {
                throw new AssertionError("duplicate predicate result key in oracle case: " + result.getKey());
            }
            var source = resolveSource(result.getKey(), result.getValue(),
                    result.getSourceWriteId(), writesById, singletonLists(writesByKeyValue));
            if (source == null) {
                throw new AssertionError("oracle predicate result has no source write: " + result);
            }
            if (!Objects.equals(result.getKey(), source.event.getKey())
                    || !Objects.equals(result.getValue(), source.event.getValue())) {
                throw new AssertionError("oracle predicate result source points to a different row: "
                        + result);
            }
            if (!event.getPredicate().test(result.getKey(), result.getValue())) {
                throw new AssertionError("oracle predicate result row does not satisfy predicate: " + result);
            }
        }
    }

    private static <ValueType> WriteInstance<ValueType> resolveSource(
            String key,
            ValueType value,
            Long sourceWriteId,
            Map<Long, WriteInstance<ValueType>> writesById,
            Map<Pair<String, ValueType>, List<WriteInstance<ValueType>>> writesByKeyValue) {
        if (sourceWriteId != null) {
            return writesById.get(sourceWriteId);
        }
        var sources = writesByKeyValue.get(Pair.of(key, value));
        if (sources == null || sources.size() != 1) {
            return null;
        }
        return sources.get(0);
    }

    private static <ValueType> Map<Pair<String, ValueType>, List<WriteInstance<ValueType>>> singletonLists(
            Map<Pair<String, ValueType>, WriteInstance<ValueType>> writesByKeyValue) {
        var result = new HashMap<Pair<String, ValueType>, List<WriteInstance<ValueType>>>();
        for (var entry : writesByKeyValue.entrySet()) {
            result.put(entry.getKey(), List.of(entry.getValue()));
        }
        return result;
    }

    private static <ValueType> Object writeToken(WriteInstance<ValueType> write) {
        return write.event.getWriteId() != null
                ? write.event.getWriteId()
                : Pair.of(write.event.getKey(), write.event.getValue());
    }

    private static <ValueType> boolean sameWrite(
            WriteInstance<ValueType> expected,
            WriteInstance<ValueType> actual) {
        if (expected == null || actual == null) {
            return expected == actual;
        }
        if (expected.event.getWriteId() != null || actual.event.getWriteId() != null) {
            return Objects.equals(expected.event.getWriteId(), actual.event.getWriteId());
        }
        return expected.txn == actual.txn && expected.eventIndex == actual.eventIndex;
    }

    private static <ValueType> WriteInstance<ValueType> latestVisibleWrite(
            String key,
            Transaction<String, ValueType> reader,
            int readerEventIndex,
            List<Transaction<String, ValueType>> order,
            List<WriteInstance<ValueType>> writes) {
        var rank = ranks(order);
        return writes.stream()
                .filter(write -> key.equals(write.event.getKey()))
                .filter(write -> visibleTo(write, reader, readerEventIndex, rank))
                .max((left, right) -> compareWrites(left, right, rank))
                .orElse(null);
    }

    private static <ValueType> boolean visibleTo(
            WriteInstance<ValueType> write,
            Transaction<String, ValueType> reader,
            int readerEventIndex,
            Map<Transaction<String, ValueType>, Integer> rank) {
        if (write.txn.equals(reader)) {
            return write.eventIndex < readerEventIndex;
        }
        if (isBottomTxn(write.txn)) {
            return true;
        }
        if (isBottomTxn(reader)) {
            return false;
        }
        return rank.get(write.txn) < rank.get(reader);
    }

    private static <ValueType> int compareWrites(
            WriteInstance<ValueType> left,
            WriteInstance<ValueType> right,
            Map<Transaction<String, ValueType>, Integer> rank) {
        if (left.txn.equals(right.txn)) {
            return Integer.compare(left.eventIndex, right.eventIndex);
        }
        return Integer.compare(txnRank(left.txn, rank), txnRank(right.txn, rank));
    }

    private static <ValueType> int txnRank(
            Transaction<String, ValueType> txn,
            Map<Transaction<String, ValueType>, Integer> rank) {
        return isBottomTxn(txn) ? -1 : rank.get(txn);
    }

    private static <ValueType> List<WriteInstance<ValueType>> collectWrites(History<String, ValueType> history) {
        var writes = new ArrayList<WriteInstance<ValueType>>();
        for (var txn : history.getTransactions()) {
            var events = txn.getEvents();
            for (int i = 0; i < events.size(); i++) {
                if (events.get(i).getType() == WRITE) {
                    writes.add(new WriteInstance<>(txn, events.get(i), i));
                }
            }
        }
        return writes;
    }

    private static <ValueType> Map<Transaction<String, ValueType>, Integer> ranks(
            List<Transaction<String, ValueType>> order) {
        var rank = new HashMap<Transaction<String, ValueType>, Integer>();
        for (int i = 0; i < order.size(); i++) {
            rank.put(order.get(i), i);
        }
        return rank;
    }

    private static TestCase bottomOldVersionSatisfiedButCurrentDoesNot() {
        var b = new CaseBuilder("bottom-old-version-satisfied-current-not", true);
        b.initial("k0", 20).initial("k1", 0).initial("k2", 0);
        b.txn(1).w("k0", 5);
        b.txn(2).prGe(10);
        return b.build();
    }

    private static TestCase ordinaryOldVersionSatisfiedButCurrentDoesNot() {
        var b = new CaseBuilder("ordinary-old-version-satisfied-current-not", true);
        b.initial("k0", 0).initial("k1", 0).initial("k2", 0);
        b.txn(1).w("k0", 20);
        b.txn(2).w("k0", 5);
        b.txn(3).prGe(10);
        return b.build();
    }

    private static TestCase currentVersionSatisfiedButPredicateOmits() {
        var b = new CaseBuilder("current-version-satisfied-but-pr-omits", false);
        b.initial("k0", 0).initial("k1", 0).initial("k2", 0);
        b.txn(1).w("k0", 20);
        b.txn(2).prGe(10);
        return b.build();
    }

    private static TestCase predicateContainsNewVersionOldBottomDoesNotConflict() {
        var b = new CaseBuilder("pr-contains-new-version-old-bottom-no-conflict", true);
        b.initial("k0", 20).initial("k1", 0).initial("k2", 0);
        var w = b.txn(1).w("k0", 30);
        b.txn(2).prGe(10, w);
        return b.build();
    }

    private static TestCase sameValueDifferentWriteIdPointRead() {
        var b = new CaseBuilder("same-value-different-write-id-point-read", true);
        b.initial("k0", 0).initial("k1", 0).initial("k2", 0);
        b.txn(1).w("k0", 100);
        var second = b.txn(2).w("k0", 100);
        b.txn(3).r(second);
        return b.build();
    }

    private static TestCase widePredicateMissingOneKey() {
        var b = new CaseBuilder("wide-predicate-missing-one-key", false);
        b.initial("k0", 0).initial("k1", 0).initial("k2", 0);
        var a = b.txn(1).w("k0", 20);
        b.txn(2).w("k1", 20);
        b.txn(3).prGe(10, a);
        return b.build();
    }

    private static TestCase selfWriteVisibleInPredicate() {
        var b = new CaseBuilder("self-write-visible-in-predicate", true);
        b.initial("k0", 0).initial("k1", 0).initial("k2", 0);
        var txn = b.txn(1);
        var w = txn.w("k0", 20);
        txn.prGe(10, w);
        return b.build();
    }

    private static void addRandomRead(
            History<String, Integer> history,
            Random random,
            Transaction<String, Integer> txn,
            Set<String> localWrittenKeys,
            Map<String, List<WriteChoice<Integer>>> knownWrites) {
        var source = randomWrite(random, knownWrites, txn.getId(), localWrittenKeys);
        if (source == null) {
            return;
        }
        history.addReadEvent(txn, source.key, source.value, source.writeId, null, null);
    }

    private static void addRandomPredicateRead(
            History<String, Integer> history,
            Random random,
            Transaction<String, Integer> txn,
            Map<String, List<WriteChoice<Integer>>> knownWrites) {
        String prefix = random.nextDouble() < 0.25 ? "" : randomKey(random);
        int threshold = random.nextBoolean() ? 50 : 120;
        Event.PredEval<String, Integer> predicate =
                (candidateKey, value) -> candidateKey.startsWith(prefix) && value >= threshold;

        var latestKnown = latestKnownByKey(knownWrites).stream()
                .filter(write -> predicate.test(write.key, write.value))
                .collect(Collectors.toList());
        var results = new ArrayList<Event.PredResult<String, Integer>>();

        if (!latestKnown.isEmpty() && random.nextBoolean()) {
            if (prefix.isEmpty() && random.nextBoolean()) {
                for (var source : latestKnown) {
                    results.add(new Event.PredResult<>(source.key, source.value, source.writeId, null, null));
                }
            } else {
                var source = randomElement(random, latestKnown);
                results.add(new Event.PredResult<>(source.key, source.value, source.writeId, null, null));
            }
        }
        history.addPredicateReadEvent(txn, predicate, results);
    }

    private static void addRandomWrite(
            History<String, Integer> history,
            Random random,
            Transaction<String, Integer> txn,
            long[] nextWriteId,
            Set<String> localWrittenKeys,
            Map<String, List<WriteChoice<Integer>>> knownWrites) {
        var candidates = KEYS.stream()
                .filter(key -> !localWrittenKeys.contains(key))
                .collect(Collectors.toList());
        if (candidates.isEmpty()) {
            return;
        }
        var key = randomElement(random, candidates);
        int value = random.nextDouble() < 0.25 && !knownWrites.get(key).isEmpty()
                ? randomElement(random, knownWrites.get(key)).value
                : 100 + (int) nextWriteId[0];
        addWrite(history, txn, key, value, nextWriteId[0]++, txn.getId(), knownWrites);
        localWrittenKeys.add(key);
    }

    private static void addWrite(
            History<String, Integer> history,
            Transaction<String, Integer> txn,
            String key,
            Integer value,
            long writeId,
            long ownerTxnId,
            Map<String, List<WriteChoice<Integer>>> knownWrites) {
        history.addWriteEvent(txn, key, value, writeId);
        knownWrites.computeIfAbsent(key, ignored -> new ArrayList<>())
                .add(new WriteChoice<>(key, value, writeId, ownerTxnId));
    }

    private static List<WriteChoice<Integer>> latestKnownByKey(
            Map<String, List<WriteChoice<Integer>>> knownWrites) {
        var result = new ArrayList<WriteChoice<Integer>>();
        for (var writes : knownWrites.values()) {
            if (!writes.isEmpty()) {
                result.add(writes.get(writes.size() - 1));
            }
        }
        return result;
    }

    private static JsonWriteChoice nextJsonWrite(
            Random random,
            long[] nextWriteId,
            long txnId,
            int opIndex,
            Set<String> localWrittenKeys,
            Map<String, List<JsonWriteChoice>> knownWrites) {
        var candidates = KEYS.stream()
                .filter(key -> !localWrittenKeys.contains(key))
                .collect(Collectors.toList());
        if (candidates.isEmpty()) {
            return null;
        }
        var key = randomElement(random, candidates);
        localWrittenKeys.add(key);
        long writeId = nextWriteId[0]++;
        int semantic = random.nextBoolean() ? 100 + (int) writeId : 30 + (int) writeId;
        return new JsonWriteChoice(key, semantic, semantic, writeId, txnId, opIndex);
    }

    private static String randomJsonPredicateRead(
            Random random,
            Map<String, List<JsonWriteChoice>> knownWrites) {
        int threshold = random.nextBoolean() ? 50 : 120;
        var latest = new ArrayList<JsonWriteChoice>();
        for (var writes : knownWrites.values()) {
            if (!writes.isEmpty()) {
                latest.add(writes.get(writes.size() - 1));
            }
        }
        var candidates = latest.stream()
                .filter(write -> write.semantic >= threshold)
                .collect(Collectors.toList());
        var results = new ArrayList<String>();
        if (!candidates.isEmpty() && random.nextBoolean()) {
            if (random.nextBoolean()) {
                for (var source : candidates) {
                    results.add(jsonResultTuple(source));
                }
            } else {
                results.add(jsonResultTuple(randomElement(random, candidates)));
            }
        }
        return String.format(
                "{\"type\":\"pr\",\"query\":{\"from\":{\"relation\":\"kv\"},\"select\":{\"columns\":[\"k\",\"value\"],\"distinct\":false},\"where\":[\"value > %d\"]},\"result\":{\"inputs\":[%s],\"values\":[]}}",
                threshold - 1, String.join(",", results));
    }

    private static JsonWriteChoice randomJsonWrite(
            Random random,
            Map<String, List<JsonWriteChoice>> knownWrites,
            long currentTxnId,
            Set<String> localWrittenKeys) {
        var readableKeys = KEYS.stream()
                .filter(key -> !localWrittenKeys.contains(key))
                .collect(Collectors.toList());
        if (readableKeys.isEmpty()) {
            return null;
        }
        var key = randomElement(random, readableKeys);
        var candidates = knownWrites.get(key).stream()
                .filter(write -> write.ownerTxnId != currentTxnId)
                .collect(Collectors.toList());
        return randomElement(random, candidates);
    }

    private static String jsonInitialTuple(String key, int semantic, long sourceWriteId) {
        return String.format(
                "{\"key\":\"%s\",\"value\":%d}",
                key, semantic);
    }

    private static String jsonResultTuple(JsonWriteChoice source) {
        return String.format(
                "{\"key\":\"%s\",\"value\":%d}",
                source.key, source.value);
    }

    private static long value(int semantic) {
        return semantic * 1000L + 7L;
    }

    private static <ValueType> String describe(History<String, ValueType> history) {
        var lines = new StringBuilder();
        for (var txn : history.getTransactions().stream()
                .sorted(Comparator.comparingLong(Transaction::getId))
                .collect(Collectors.toList())) {
            lines.append(txn).append(' ').append(txn.getEvents()).append('\n');
        }
        return lines.toString();
    }

    private static boolean isBottomTxn(Transaction<?, ?> txn) {
        return txn.getId() == INIT_TXN_ID
                && txn.getSession() != null
                && txn.getSession().getId() == INIT_SESSION_ID;
    }

    private static String randomKey(Random random) {
        return KEYS.get(random.nextInt(KEYS.size()));
    }

    private static <T> T randomElement(Random random, List<T> values) {
        return values.get(random.nextInt(values.size()));
    }

    private static <ValueType> WriteChoice<ValueType> randomWrite(
            Random random,
            Map<String, List<WriteChoice<ValueType>>> knownWrites,
            long currentTxnId,
            Set<String> localWrittenKeys) {
        var readableKeys = KEYS.stream()
                .filter(key -> !localWrittenKeys.contains(key))
                .collect(Collectors.toList());
        if (readableKeys.isEmpty()) {
            return null;
        }
        var key = randomElement(random, readableKeys);
        var candidates = knownWrites.get(key).stream()
                .filter(write -> write.ownerTxnId != currentTxnId)
                .collect(Collectors.toList());
        return randomElement(random, candidates);
    }

    private static <T> void swap(List<T> values, int left, int right) {
        T tmp = values.get(left);
        values.set(left, values.get(right));
        values.set(right, tmp);
    }

    private static final class TestCase {
        private final String name;
        private final History<String, Integer> history;
        private final boolean expected;

        private TestCase(String name, History<String, Integer> history, boolean expected) {
            this.name = name;
            this.history = history;
            this.expected = expected;
        }
    }

    private static final class CaseBuilder {
        private final String name;
        private final boolean expected;
        private final History<String, Integer> history = new History<>();
        private final Transaction<String, Integer> initTxn;
        private final Map<Integer, Transaction<String, Integer>> txns = new HashMap<>();
        private long nextWriteId = 1L;
        private final Map<String, Version> latest = new HashMap<>();

        private CaseBuilder(String name, boolean expected) {
            this.name = name;
            this.expected = expected;
            var initSession = history.addSession(INIT_SESSION_ID);
            initTxn = history.addTransaction(initSession, INIT_TXN_ID);
        }

        private CaseBuilder initial(String key, int value) {
            var writeId = nextWriteId++;
            history.addWriteEvent(initTxn, key, value, writeId);
            latest.put(key, new Version(key, value, writeId));
            return this;
        }

        private TxnBuilder txn(int id) {
            var txn = txns.computeIfAbsent(id, ignored -> {
                var session = history.getSessions().stream()
                        .filter(existing -> existing.getId() == 1L)
                        .findFirst()
                        .orElseGet(() -> history.addSession(1L));
                return history.addTransaction(session, id);
            });
            return new TxnBuilder(this, txn);
        }

        private TestCase build() {
            initTxn.setStatus(Transaction.TransactionStatus.COMMIT);
            txns.values().forEach(txn -> txn.setStatus(Transaction.TransactionStatus.COMMIT));
            return new TestCase(name, history, expected);
        }
    }

    private static final class TxnBuilder {
        private final CaseBuilder parent;
        private final Transaction<String, Integer> txn;

        private TxnBuilder(CaseBuilder parent, Transaction<String, Integer> txn) {
            this.parent = parent;
            this.txn = txn;
        }

        private Version w(String key, int value) {
            var writeId = parent.nextWriteId++;
            parent.history.addWriteEvent(txn, key, value, writeId);
            var version = new Version(key, value, writeId);
            parent.latest.put(key, version);
            return version;
        }

        private TxnBuilder r(Version version) {
            parent.history.addReadEvent(txn, version.key, version.value, version.writeId, null, null);
            return this;
        }

        private TxnBuilder prGe(int threshold, Version... results) {
            Event.PredEval<String, Integer> predicate = (candidateKey, value) -> value >= threshold;
            var predResults = new ArrayList<Event.PredResult<String, Integer>>();
            for (var version : results) {
                predResults.add(new Event.PredResult<>(version.key, version.value, version.writeId, null, null));
            }
            parent.history.addPredicateReadEvent(txn, predicate, predResults);
            return this;
        }
    }

    private static final class Version {
        private final String key;
        private final Integer value;
        private final Long writeId;

        private Version(String key, Integer value, Long writeId) {
            this.key = key;
            this.value = value;
            this.writeId = writeId;
        }
    }

    private static final class WriteChoice<ValueType> {
        private final String key;
        private final ValueType value;
        private final Long writeId;
        private final long ownerTxnId;

        private WriteChoice(String key, ValueType value, Long writeId, long ownerTxnId) {
            this.key = key;
            this.value = value;
            this.writeId = writeId;
            this.ownerTxnId = ownerTxnId;
        }
    }

    private static final class JsonWriteChoice {
        private final String key;
        private final long value;
        private final int semantic;
        private final Long writeId;
        private final long ownerTxnId;
        private final int opIndex;

        private JsonWriteChoice(String key, long value, int semantic, Long writeId, long ownerTxnId, int opIndex) {
            this.key = key;
            this.value = value;
            this.semantic = semantic;
            this.writeId = writeId;
            this.ownerTxnId = ownerTxnId;
            this.opIndex = opIndex;
        }
    }

    private static final class WriteInstance<ValueType> {
        private final Transaction<String, ValueType> txn;
        private final Event<String, ValueType> event;
        private final int eventIndex;

        private WriteInstance(
                Transaction<String, ValueType> txn,
                Event<String, ValueType> event,
                int eventIndex) {
            this.txn = txn;
            this.event = event;
            this.eventIndex = eventIndex;
        }
    }
}
