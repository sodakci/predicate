package verifier;

import history.History;
import history.loaders.PredicateHistoryLoader;
import history.loaders.PredicateHistoryLoader.PredicateValue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 独立开始/提交执行模型与生产 SI 三种谓词模式的语义差分。 */
class SIPredicateSemanticsRegressionTest {
    private static final long RANDOM_SEED = 0x51E7A11L;
    private static final int RANDOM_CASES = 24;

    @TempDir
    Path temporaryDirectory;

    @Test
    void joinCannotForgetVersionsCommittedBeforeItsSnapshot() throws Exception {
        // W 同时写 a/b；X 读到 a=1、c=0，迫使 W 提交早于 X 开始且 C 尚未提交。
        // R 读到 c=1，迫使 C 提交早于 R 开始。因此 R 的固定快照必含 a=1、b=1，
        // 记录为空的 INNER JOIN 没有任何合法 SI 执行。
        var history = writeHistory(
                "join-prefix-counterexample",
                "[{\"key\":\"a:0\",\"value\":0},"
                        + "{\"key\":\"b:0\",\"value\":2},"
                        + "{\"key\":\"control:c\",\"value\":0}]",
                transaction(0, 1, 1,
                        write("a:0", "1"), write("b:0", "1")),
                transaction(1, 1, 2,
                        read("a:0", "1"), read("control:c", "0")),
                transaction(2, 1, 3, write("control:c", "1")),
                transaction(3, 1, 4,
                        read("control:c", "1"), emptyJoinRead()));

        assertFalse(SIExecutionOracle.accepts(load(history)));
        assertProductionModes(history, false);
    }

    @Test
    void readersCannotChooseCrossedEmptyJoinSnapshots() throws Exception {
        var history = writeHistory("crossed-join-snapshots",
                initial("a:0", 0, "b:0", 2, "c:0", 0, "d:0", 2,
                        "control:p", 0, "control:q", 0),
                transaction(0, 1, 1, write("a:0", "1"), write("b:0", "1"),
                        write("control:p", "1")),
                transaction(1, 1, 2, write("c:0", "1"), write("d:0", "1"),
                        write("control:q", "1")),
                transaction(2, 1, 3, read("control:p", "1"), emptyJoinRead("c", "d")),
                transaction(3, 1, 4, read("control:q", "1"), emptyJoinRead("a", "b")));
        assertFalse(SIExecutionOracle.accepts(load(history)));
        assertProductionModes(history, false);
    }

    @Test
    void handCalculatedSiHistoriesMatchEveryProductionMode() throws Exception {
        var cases = List.of(
                new Case("write-skew", true, writeHistory(
                        "write-skew",
                        initial("kv:x", 0, "kv:y", 0),
                        transaction(1, 1, 11,
                                read("kv:y", "0"), write("kv:x", "1")),
                        transaction(2, 1, 12,
                                read("kv:x", "0"), write("kv:y", "1")))),
                new Case("same-key-first-committer-wins", false, writeHistory(
                        "same-key-first-committer-wins",
                        initial("kv:x", 0),
                        transaction(1, 1, 21,
                                read("kv:x", "0"), write("kv:x", "1")),
                        transaction(2, 1, 22,
                                read("kv:x", "0"), write("kv:x", "2")))),
                new Case("commit-precedence-is-not-visibility", true, writeHistory(
                        "commit-precedence-is-not-visibility",
                        initial("kv:x", 0, "control:y", 0),
                        transaction(1, 1, 71, write("kv:x", "7")),
                        transaction(2, 1, 72, read("kv:x", "7"), read("control:y", "0")),
                        transaction(3, 1, 73, read("kv:x", "0"),
                                predicateRead("value > 0", "[]", "[]"), write("control:y", "1")))),
                new Case("self-overlay-hides-initial-match", true, writeHistory(
                        "self-overlay-hides-initial-match", initial("kv:k0", 7),
                        transaction(1, 1, 81, write("kv:k0", "4"),
                                predicateRead("value % 4 = 3", "[]", "[]")))),
                new Case("bottom-predicate", true, writeHistory(
                        "bottom-predicate",
                        initial("kv:k0", 7),
                        transaction(1, 1, 31,
                                predicateRead("value % 4 = 3",
                                        inputs("kv:k0", 7), values(7))))),
                new Case("absent-multiple-good-writers", true, writeHistory(
                        "absent-multiple-good-writers",
                        initial("kv:k0", 4),
                        transaction(1, 1, 41, write("kv:k0", "7")),
                        transaction(1, 2, 42, write("kv:k0", "8")),
                        transaction(1, 3, 43, write("kv:k0", "12")),
                        transaction(1, 4, 44,
                                predicateRead("value % 4 = 3", "[]", "[]")))),
                new Case("self-write-and-repeated-predicate", true, writeHistory(
                        "self-write-and-repeated-predicate",
                        initial("kv:k0", 4),
                        transaction(1, 1, 51,
                                predicateRead("value % 4 = 3", "[]", "[]"),
                                write("kv:k0", "7"),
                                predicateRead("value % 4 = 3",
                                        inputs("kv:k0", 7), values(7))))),
                new Case("multi-key-result-bag", true, writeHistory(
                        "multi-key-result-bag",
                        initial("kv:k0", 7, "kv:k1", 11),
                        transaction(1, 1, 61,
                                predicateRead("value % 4 = 3",
                                        inputs("kv:k0", 7, "kv:k1", 11),
                                        values(7, 11))))));

        for (var testCase : cases) {
            var oracle = SIExecutionOracle.accepts(load(testCase.path));
            assertEquals(testCase.expected, oracle, testCase.name + " oracle");
            assertProductionModes(testCase.path, testCase.expected);
        }
    }

    @Test
    void fixedSeedSmallHistoriesMatchEveryProductionMode() throws Exception {
        var random = new Random(RANDOM_SEED);
        for (int caseId = 0; caseId < RANDOM_CASES; caseId++) {
            var path = randomHistory(caseId, random);
            boolean expected = SIExecutionOracle.accepts(load(path));
            assertProductionModes(path, expected);
        }
    }

    private void assertProductionModes(Path path, boolean expected) {
        for (boolean optimization : List.of(false, true)) {
            assertAll(
                    () -> assertProduction(path, expected,
                            SIVerifier.PredicateMode.EAGER, false, optimization),
                    () -> assertProduction(path, expected,
                            SIVerifier.PredicateMode.GMWR, false, optimization),
                    () -> assertProduction(path, expected,
                            SIVerifier.PredicateMode.GMWR, true, optimization));
        }
    }

    private void assertProduction(Path path, boolean expected,
            SIVerifier.PredicateMode mode, boolean prepropagation, boolean optimization) {
        var settings = SIVerifier.SolverSettings.defaults();
        settings.predicateMode = mode;
        settings.gmwrPrepropagation = prepropagation;
        settings.wwReachabilityPruning = optimization;
        settings.predicateWitnessCoalescing = optimization;
        settings.graphEdgeInterning = optimization;
        var actual = new SIVerifier<String, PredicateValue>(
                new PredicateHistoryLoader(path), settings, false).auditResult();
        var expectedResult = expected ? SIVerifier.AuditResult.ACCEPT : SIVerifier.AuditResult.REJECT;
        if (actual != expectedResult) {
            preserveFailure(path, mode, prepropagation, optimization, expectedResult, actual);
        }
        assertEquals(expectedResult,
                actual, () -> "history=" + path + " mode=" + mode
                        + " prepropagation=" + prepropagation + " optimization=" + optimization);
    }

    private void preserveFailure(Path source, SIVerifier.PredicateMode mode,
            boolean prepropagation, boolean optimization,
            SIVerifier.AuditResult expected, SIVerifier.AuditResult actual) {
        try {
            var target = Files.createDirectories(Path.of("build", "si-semantic-failures",
                    source.getFileName().toString(), mode + "-" + prepropagation + "-" + optimization));
            for (var name : List.of("initial_state.json", "history.prhist.jsonl")) {
                Files.copy(source.resolve(name), target.resolve(name),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(target.resolve("configuration.txt"),
                    "seed=" + RANDOM_SEED + "\nmode=" + mode + "\nprepropagation=" + prepropagation
                            + "\noptimization=" + optimization + "\nexpected=" + expected + "\nactual=" + actual);
        } catch (java.io.IOException exception) {
            throw new AssertionError("无法保存 SI 语义反例 " + source, exception);
        }
    }

    private Path randomHistory(int caseId, Random random) throws Exception {
        var keys = List.of("kv:k0", "kv:k1");
        var writerKeys = new ArrayList<String>();
        var writerValues = new ArrayList<Integer>();
        for (int txn = 0; txn < 3; txn++) {
            writerKeys.add(keys.get(random.nextInt(keys.size())));
            writerValues.add(1000 + caseId * 10 + txn);
        }

        var transactions = new ArrayList<String>();
        for (int txn = 0; txn < 3; txn++) {
            var operations = new ArrayList<String>();
            var readKey = keys.get(random.nextInt(keys.size()));
            int readValue = 0;
            if (random.nextBoolean()) {
                var sources = new ArrayList<Integer>();
                for (int source = 0; source < 3; source++) {
                    if (writerKeys.get(source).equals(readKey)) {
                        sources.add(writerValues.get(source));
                    }
                }
                if (!sources.isEmpty()) {
                    readValue = sources.get(random.nextInt(sources.size()));
                }
            }
            operations.add(read(readKey, Integer.toString(readValue)));
            if (random.nextBoolean()) {
                operations.add(predicateRead("value > 0", "[]", "[]"));
            }
            operations.add(write(writerKeys.get(txn),
                    Integer.toString(writerValues.get(txn))));
            if (random.nextBoolean()) {
                operations.add(predicateRead("value > 0",
                        inputs(writerKeys.get(txn), writerValues.get(txn)),
                        values(writerValues.get(txn))));
            }
            transactions.add(transaction(txn + 1L, 1, 100 + txn,
                    operations.toArray(String[]::new)));
        }
        return writeHistory("random-" + caseId,
                initial("kv:k0", 0, "kv:k1", 0),
                transactions.toArray(String[]::new));
    }

    private History<String, PredicateValue> load(Path path) {
        return new PredicateHistoryLoader(path).loadHistory();
    }

    private Path writeHistory(String name, String initialState,
            String... transactions) throws Exception {
        var directory = Files.createDirectories(temporaryDirectory.resolve(name));
        Files.writeString(directory.resolve("initial_state.json"), initialState);
        Files.writeString(directory.resolve("history.prhist.jsonl"),
                String.join("\n", transactions));
        return directory;
    }

    private static String transaction(long session, long sequence, long id,
            String... operations) {
        return "{\"session\":" + session + ",\"session_seq\":" + sequence
                + ",\"txn\":" + id + ",\"status\":\"commit\",\"ops\":["
                + String.join(",", operations) + "]}";
    }

    private static String read(String key, String value) {
        return "{\"type\":\"r\",\"key\":\"" + key + "\",\"value\":" + value + "}";
    }

    private static String write(String key, String value) {
        return "{\"type\":\"w\",\"key\":\"" + key + "\",\"value\":" + value + "}";
    }

    private static String predicateRead(String condition, String inputs, String values) {
        return "{\"type\":\"pr\",\"query\":{"
                + "\"from\":{\"relation\":\"kv\"},"
                + "\"select\":{\"columns\":[\"value\"],\"distinct\":false},"
                + "\"where\":[\"" + condition + "\"]},"
                + "\"result\":{\"inputs\":" + inputs + ",\"values\":" + values + "}}";
    }

    private static String emptyJoinRead() {
        return emptyJoinRead("a", "b");
    }

    private static String emptyJoinRead(String left, String right) {
        return "{\"type\":\"pr\",\"query\":{"
                + "\"from\":{\"relation\":\"" + left + "\",\"alias\":\"l\"},"
                + "\"joins\":[{\"relation\":\"" + right + "\",\"alias\":\"r\","
                + "\"type\":\"INNER\",\"on\":[\"l.value = r.value\"]}],"
                + "\"select\":{\"columns\":[\"l.value AS left_value\","
                + "\"r.value AS right_value\"],\"distinct\":false}},"
                + "\"result\":{\"inputs\":[],\"values\":[]}}";
    }

    private static String initial(Object... keyValues) {
        var rows = new ArrayList<String>();
        for (int i = 0; i < keyValues.length; i += 2) {
            rows.add("{\"key\":\"" + keyValues[i] + "\",\"value\":"
                    + keyValues[i + 1] + "}");
        }
        return "[" + String.join(",", rows) + "]";
    }

    private static String inputs(Object... keyValues) {
        return initial(keyValues);
    }

    private static String values(int... values) {
        var rows = new ArrayList<String>();
        for (var value : values) {
            rows.add("{\"value\":" + value + "}");
        }
        return "[" + String.join(",", rows) + "]";
    }

    private static final class Case {
        private final String name;
        private final boolean expected;
        private final Path path;

        private Case(String name, boolean expected, Path path) {
            this.name = name;
            this.expected = expected;
            this.path = path;
        }
    }
}
