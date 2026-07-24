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
