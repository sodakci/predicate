import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import graph.KnownGraph;
import history.InvalidHistoryError;
import history.Transaction;
import history.loaders.PredicateHistoryLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

public class TestPredicateHistoryLoader {
    @Test
    void loadsCompactKvRelationalPredicateHistory() throws Exception {
        var historyDir = dataset("[{\"key\":\"kv:0\",\"value\":0},{\"key\":\"kv:1\",\"value\":1}]", List.of(
                "{\"session\":0,\"txn\":0,\"status\":\"commit\",\"ops\":["
                        + predicateOp("value = 0", "[{\"key\":\"kv:0\",\"value\":0}]") + ","
                        + "{\"type\":\"w\",\"key\":\"kv:1\",\"value\":2}]}",
                "{\"session\":0,\"txn\":1,\"status\":\"commit\",\"ops\":["
                        + predicateOp("TRUE", "[{\"key\":\"kv:0\",\"value\":0},{\"key\":\"kv:1\",\"value\":2}]") + ","
                        + "{\"type\":\"r\",\"key\":\"kv:1\",\"value\":2}]}",
                "{\"session\":0,\"txn\":2,\"status\":\"commit\",\"ops\":["
                        + predicateOp("value % 2 = 0", "[{\"key\":\"kv:1\",\"value\":2}]") + "]}",
                "{\"session\":0,\"txn\":3,\"status\":\"commit\",\"ops\":["
                        + "{\"type\":\"w\",\"key\":\"kv:0\",\"value\":5},"
                        + predicateOp("value > 3", "[{\"key\":\"kv:0\",\"value\":5}]") + "]}",
                "{\"session\":0,\"txn\":4,\"status\":\"commit\",\"ops\":["
                        + predicateOp("value < 1", "[{\"key\":\"kv:0\",\"value\":0}]") + "]}"));

        var history = new PredicateHistoryLoader(historyDir).loadHistory();
        assertEquals(6, history.getTransactions().size());

        var initTxn = history.getTransaction(-1);
        assertNotNull(initTxn);
        assertEquals(Transaction.TransactionStatus.COMMIT, initTxn.getStatus());
        assertNull(initTxn.getEvents().get(0).getWriteId());
        assertNull(initTxn.getEvents().get(1).getWriteId());

        var value0 = new PredicateHistoryLoader.PredicateValue(0L);
        var value1 = new PredicateHistoryLoader.PredicateValue(1L);
        var value2 = new PredicateHistoryLoader.PredicateValue(2L);
        var value5 = new PredicateHistoryLoader.PredicateValue(5L);

        var equalsPredicate = history.getTransaction(0).getEvents().get(0).getPredicate();
        assertTrue(equalsPredicate.test("kv:0", value0));
        assertFalse(equalsPredicate.test("kv:1", value1));

        var truePredicate = history.getTransaction(1).getEvents().get(0).getPredicate();
        assertTrue(truePredicate.test("kv:0", value0));

        var modPredicate = history.getTransaction(2).getEvents().get(0).getPredicate();
        assertTrue(modPredicate.test("kv:1", value2));
        assertFalse(modPredicate.test("kv:0", value5));

        var gtPredicate = history.getTransaction(3).getEvents().get(1).getPredicate();
        assertTrue(gtPredicate.test("kv:0", value5));
        assertFalse(gtPredicate.test("kv:1", value2));

        var ltPredicate = history.getTransaction(4).getEvents().get(0).getPredicate();
        assertTrue(ltPredicate.test("kv:0", value0));
        assertFalse(ltPredicate.test("kv:1", value1));

        var graph = new KnownGraph<>(history);
        assertEquals(5, graph.getPredicateObservations().size());
        assertNull(graph.getPredicateObservations().get(0).getTupleSources().get(0)
                .getSourceWrite().getWriteId());
        assertEquals(history.getTransaction(0L), graph.getPredicateObservations().get(1)
                .getTupleSources().get(1).getSourceWrite().getTxn());
        assertTrue(graph.getReadFrom().hasEdgeConnecting(history.getTransaction(0L), history.getTransaction(1L)));
    }

    @Test
    void acceptsDirectHistoryFilePathWithSiblingInitialState() throws Exception {
        var historyDir = Files.createTempDirectory("single-prhist");
        Files.writeString(historyDir.resolve("initial_state.json"),
                "[{\"key\":\"kv:0\",\"value\":0}]");
        var file = historyDir.resolve("history.prhist.jsonl");
        Files.writeString(file,
                "{\"session\":0,\"txn\":0,\"status\":\"commit\",\"ops\":["
                        + "{\"type\":\"r\",\"key\":\"kv:0\",\"value\":0},"
                        + "{\"type\":\"w\",\"key\":\"kv:0\",\"value\":1}]}");

        var history = new PredicateHistoryLoader(file).loadHistory();
        assertEquals(2, history.getTransactions().size());
        assertEquals(Transaction.TransactionStatus.COMMIT, history.getTransaction(0).getStatus());
        assertNotNull(history.getTransaction(-1));
    }

    @Test
    void missingInitialStateFailsByDefault() throws Exception {
        var historyDir = Files.createTempDirectory("prhist-no-init");
        Files.writeString(historyDir.resolve("history.prhist.jsonl"),
                "{\"session\":0,\"txn\":0,\"status\":\"commit\",\"ops\":[]}");

        assertThrows(InvalidHistoryError.class, () -> new PredicateHistoryLoader(historyDir).loadHistory());
    }

    @Test
    void legacyPredicateResultsFormIsRejected() throws Exception {
        var historyDir = dataset("[{\"key\":\"kv:0\",\"value\":0}]", List.of(
                "{\"session\":0,\"txn\":0,\"status\":\"commit\",\"ops\":["
                        + "{\"type\":\"pr\",\"predicate\":{\"kind\":\"inventory_threshold\"},\"results\":[]}]}"));

        assertThrows(InvalidHistoryError.class, () -> new PredicateHistoryLoader(historyDir).loadHistory());
    }

    @Test
    void sourceMetadataFieldsAreRejected() throws Exception {
        var historyDir = dataset("[{\"key\":\"kv:0\",\"value\":0,\"source_write_id\":1}]", List.of(
                "{\"session\":0,\"txn\":0,\"status\":\"commit\",\"ops\":[]}"));

        assertThrows(InvalidHistoryError.class, () -> new PredicateHistoryLoader(historyDir).loadHistory());
    }

    @Test
    void writeIdFieldsAreRejected() throws Exception {
        var historyDir = dataset("[{\"key\":\"kv:0\",\"value\":0}]", List.of(
                "{\"session\":0,\"txn\":0,\"status\":\"commit\",\"ops\":["
                        + "{\"type\":\"w\",\"key\":\"kv:0\",\"value\":1,\"write_id\":10}]}"));

        assertThrows(InvalidHistoryError.class, () -> new PredicateHistoryLoader(historyDir).loadHistory());
    }

    @Test
    void duplicateKeyValueWritesAreRejected() throws Exception {
        var historyDir = dataset("[{\"key\":\"kv:0\",\"value\":0}]", List.of(
                "{\"session\":0,\"txn\":0,\"status\":\"commit\",\"ops\":["
                        + "{\"type\":\"w\",\"key\":\"kv:0\",\"value\":0}]}"));

        assertThrows(InvalidHistoryError.class, () -> new PredicateHistoryLoader(historyDir).loadHistory());
    }

    private static String predicateOp(String whereClause, String inputsJson) {
        return "{\"type\":\"pr\",\"query\":{\"from\":{\"relation\":\"kv\"},"
                + "\"select\":{\"columns\":[\"k\",\"value\"],\"distinct\":false},"
                + "\"where\":[\"" + whereClause + "\"]},"
                + "\"result\":{\"inputs\":" + inputsJson + ",\"values\":[]}}";
    }

    private Path dataset(String initialStateJson, List<String> transactionLines) throws Exception {
        var historyDir = Files.createTempDirectory("prhist-loader");
        Files.writeString(historyDir.resolve("initial_state.json"), initialStateJson);
        Files.writeString(historyDir.resolve("history.prhist.jsonl"), String.join("\n", transactionLines));
        Files.writeString(historyDir.resolve("manifest.json"), "{}");
        return historyDir;
    }
}
