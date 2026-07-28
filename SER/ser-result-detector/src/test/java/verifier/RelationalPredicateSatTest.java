package verifier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import history.loaders.PredicateHistoryLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RelationalPredicateSatTest {
    private static final String PURCHASE =
            "{\"key\":\"purchases:p0\",\"value\":{\"purchase_id\":\"p0\",\"sku\":\"s0\",\"buyer\":\"u0\"}}";
    private static final String INVENTORY_EMPTY =
            "{\"key\":\"inventory:i0\",\"value\":{\"sku\":\"s0\",\"stock\":0}}";
    private static final String INVENTORY_AVAILABLE =
            "{\"key\":\"inventory:i0\",\"value\":{\"sku\":\"s0\",\"stock\":2}}";

    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsCorrectResultForArbitraryInnerJoin() throws Exception {
        var history = writeHistory(
                "correct-join",
                "[" + PURCHASE + "," + INVENTORY_AVAILABLE + "]",
                transaction(0, 10, joinRead(
                        "[" + PURCHASE + "," + INVENTORY_AVAILABLE + "]",
                        "[{\"purchase_id\":\"p0\",\"sku\":\"s0\",\"stock\":2}]")));

        assertTrue(audit(history));
    }

    @Test
    void rejectsIncorrectProjectedValueWithCorrectPhysicalInputs() throws Exception {
        var history = writeHistory(
                "wrong-projection",
                "[" + PURCHASE + "," + INVENTORY_AVAILABLE + "]",
                transaction(0, 10, joinRead(
                        "[" + PURCHASE + "," + INVENTORY_AVAILABLE + "]",
                        "[{\"purchase_id\":\"p0\",\"sku\":\"s0\",\"stock\":999}]")));

        assertFalse(audit(history));
    }

    @Test
    void rejectsJoinResultThatOmitsVisibleRow() throws Exception {
        var history = writeHistory(
                "omitted-row",
                "[" + PURCHASE + "," + INVENTORY_AVAILABLE + "]",
                transaction(0, 10, joinRead("[]", "[]")));

        assertFalse(audit(history));
    }

    @Test
    void rejectsEmptyJoinWhenReadFromForcesMatchingWriterBeforeReader() throws Exception {
        var initialState = "[" + PURCHASE + "," + INVENTORY_EMPTY + ","
                + "{\"key\":\"control:c0\",\"value\":{\"version\":0}}]";
        var writer = transaction(1, 11,
                "{\"type\":\"w\",\"key\":\"inventory:i0\","
                        + "\"value\":{\"sku\":\"s0\",\"stock\":2}},"
                        + "{\"type\":\"w\",\"key\":\"control:c0\","
                        + "\"value\":{\"version\":1}}");
        var reader = transaction(2, 12,
                "{\"type\":\"r\",\"key\":\"control:c0\","
                        + "\"value\":{\"version\":1}},"
                        + joinRead("[]", "[]"));
        var history = writeHistory("forced-before", initialState, writer, reader);

        assertFalse(audit(history));
    }

    @Test
    void acceptsEmptyJoinWhenMatchingWriterCanBeOrderedAfterReader() throws Exception {
        var writer = transaction(1, 11,
                "{\"type\":\"w\",\"key\":\"inventory:i0\","
                        + "\"value\":{\"sku\":\"s0\",\"stock\":2}}");
        var reader = transaction(2, 12, joinRead("[]", "[]"));
        var history = writeHistory(
                "writer-after-reader",
                "[" + PURCHASE + "," + INVENTORY_EMPTY + "]",
                writer,
                reader);

        assertTrue(audit(history));
    }

    @Test
    void rowLocalEncodingPreservesDuplicateProjectedRows() throws Exception {
        var initialState = "["
                + "{\"key\":\"kv:k0\",\"value\":7},"
                + "{\"key\":\"kv:k1\",\"value\":7}]";
        var inputs = "["
                + "{\"key\":\"kv:k0\",\"value\":7},"
                + "{\"key\":\"kv:k1\",\"value\":7}]";
        var duplicateValues = "[{\"value\":7},{\"value\":7}]";

        var correct = writeHistory(
                "row-local-duplicates",
                initialState,
                transaction(0, 10,
                        singleTableRead(false, inputs, duplicateValues)));
        var missingDuplicate = writeHistory(
                "row-local-missing-duplicate",
                initialState,
                transaction(0, 10,
                        singleTableRead(false, inputs, "[{\"value\":7}]")));

        assertTrue(audit(correct));
        assertFalse(audit(missingDuplicate));
    }

    @Test
    void distinctSingleTableQueryKeepsWholeSnapshotSemantics() throws Exception {
        var initialState = "["
                + "{\"key\":\"kv:k0\",\"value\":7},"
                + "{\"key\":\"kv:k1\",\"value\":7}]";
        var inputs = "["
                + "{\"key\":\"kv:k0\",\"value\":7},"
                + "{\"key\":\"kv:k1\",\"value\":7}]";

        var correct = writeHistory(
                "distinct-correct",
                initialState,
                transaction(0, 10,
                        singleTableRead(true, inputs, "[{\"value\":7}]")));
        var duplicate = writeHistory(
                "distinct-duplicate",
                initialState,
                transaction(0, 10,
                        singleTableRead(true, inputs,
                                "[{\"value\":7},{\"value\":7}]")));

        assertTrue(audit(correct));
        assertFalse(audit(duplicate));
    }

    @Test
    void rowLocalEmptyResultRejectsWriterForcedBeforeReader() throws Exception {
        var initialState = "["
                + "{\"key\":\"kv:k0\",\"value\":4},"
                + "{\"key\":\"control:c0\",\"value\":0}]";
        var writer = transaction(1, 11,
                "{\"type\":\"w\",\"key\":\"kv:k0\",\"value\":7},"
                        + "{\"type\":\"w\",\"key\":\"control:c0\",\"value\":1}");
        var reader = transaction(2, 12,
                "{\"type\":\"r\",\"key\":\"control:c0\",\"value\":1},"
                        + singleTableRead(false, "[]", "[]"));
        var history = writeHistory(
                "row-local-forced-before", initialState, writer, reader);

        assertFalse(audit(history));
    }

    @Test
    void rowLocalEmptyResultAcceptsLaterNonMatchingWriter() throws Exception {
        var initialState = "["
                + "{\"key\":\"kv:k0\",\"value\":4},"
                + "{\"key\":\"control:c0\",\"value\":0}]";
        var badWriter = transaction(1, 11,
                "{\"type\":\"w\",\"key\":\"kv:k0\",\"value\":7}");
        var goodWriter = transaction(1, 12,
                "{\"type\":\"w\",\"key\":\"kv:k0\",\"value\":8},"
                        + "{\"type\":\"w\",\"key\":\"control:c0\",\"value\":1}");
        var reader = transaction(2, 13,
                "{\"type\":\"r\",\"key\":\"control:c0\",\"value\":1},"
                        + singleTableRead(false, "[]", "[]"));
        var history = writeHistory(
                "row-local-later-good",
                initialState, badWriter, goodWriter, reader);

        assertTrue(audit(history));
    }

    @Test
    void rowLocalRecordedSourceRejectsCompetingWriterBeforeReader() throws Exception {
        var initialState = "["
                + "{\"key\":\"kv:k0\",\"value\":7},"
                + "{\"key\":\"control:c0\",\"value\":0}]";
        var writer = transaction(1, 11,
                "{\"type\":\"w\",\"key\":\"kv:k0\",\"value\":11},"
                        + "{\"type\":\"w\",\"key\":\"control:c0\",\"value\":1}");
        var reader = transaction(2, 12,
                "{\"type\":\"r\",\"key\":\"control:c0\",\"value\":1},"
                        + singleTableRead(
                                false,
                                "[{\"key\":\"kv:k0\",\"value\":7}]",
                                "[{\"value\":7}]"));
        var history = writeHistory(
                "row-local-competing-writer", initialState, writer, reader);

        assertFalse(audit(history));
    }

    private static String singleTableRead(
            boolean distinct, String inputs, String values) {
        return "{\"type\":\"pr\",\"query\":{"
                + "\"from\":{\"relation\":\"kv\"},"
                + "\"select\":{\"columns\":[\"value\"],\"distinct\":"
                + distinct + "},"
                + "\"where\":[\"value % 4 = 3\"]},"
                + "\"result\":{\"inputs\":" + inputs
                + ",\"values\":" + values + "}}";
    }

    private static String joinRead(String inputs, String values) {
        return "{\"type\":\"pr\",\"query\":{"
                + "\"from\":{\"alias\":\"p\",\"relation\":\"purchases\"},"
                + "\"joins\":[{\"alias\":\"i\",\"relation\":\"inventory\","
                + "\"type\":\"INNER\",\"on\":[\"p.value.sku = i.value.sku\"]}],"
                + "\"select\":{\"columns\":[\"p.value.purchase_id\",\"i.value.sku\","
                + "\"i.value.stock\"],\"distinct\":false},"
                + "\"where\":[\"i.value.stock > 0\"]},"
                + "\"result\":{\"inputs\":" + inputs + ",\"values\":" + values + "}}";
    }

    private static String transaction(long session, long transaction, String operations) {
        return "{\"session\":" + session + ",\"txn\":" + transaction
                + ",\"status\":\"commit\",\"ops\":[" + operations + "]}";
    }

    private Path writeHistory(String name, String initialState, String... transactions) throws Exception {
        var directory = Files.createDirectories(temporaryDirectory.resolve(name));
        Files.writeString(directory.resolve("initial_state.json"), initialState);
        Files.writeString(directory.resolve("history.prhist.jsonl"), String.join("\n", transactions));
        return directory;
    }

    private static boolean audit(Path historyDirectory) {
        return new SERVerifier<>(new PredicateHistoryLoader(historyDirectory)).audit();
    }
}
