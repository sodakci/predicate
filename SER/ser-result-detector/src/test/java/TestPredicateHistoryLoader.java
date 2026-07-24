import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import graph.KnownGraph;
import history.Event;
import history.History;
import history.InvalidHistoryError;
import history.Transaction;
import history.loaders.PredicateHistoryLoader;
import verifier.PredicateFixtures;
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
        assertTrue(PredicateFixtures.matches(equalsPredicate, "kv:0", value0));
        assertFalse(PredicateFixtures.matches(equalsPredicate, "kv:1", value1));

        var truePredicate = history.getTransaction(1).getEvents().get(0).getPredicate();
        assertTrue(PredicateFixtures.matches(truePredicate, "kv:0", value0));

        var modPredicate = history.getTransaction(2).getEvents().get(0).getPredicate();
        assertTrue(PredicateFixtures.matches(modPredicate, "kv:1", value2));
        assertFalse(PredicateFixtures.matches(modPredicate, "kv:0", value5));

        var gtPredicate = history.getTransaction(3).getEvents().get(1).getPredicate();
        assertTrue(PredicateFixtures.matches(gtPredicate, "kv:0", value5));
        assertFalse(PredicateFixtures.matches(gtPredicate, "kv:1", value2));

        var ltPredicate = history.getTransaction(4).getEvents().get(0).getPredicate();
        assertTrue(PredicateFixtures.matches(ltPredicate, "kv:0", value0));
        assertFalse(PredicateFixtures.matches(ltPredicate, "kv:1", value1));

        var graph = new KnownGraph<>(history);
        assertEquals(5, graph.getPredicateObservations().size());
        assertNull(graph.getPredicateObservations().get(0).getTupleSources().get(0)
                .getSourceWrite().getWriteId());
        assertEquals(history.getTransaction(0L), graph.getPredicateObservations().get(1)
                .getTupleSources().get(1).getSourceWrite().getTxn());
        assertTrue(graph.getReadFrom().hasEdgeConnecting(history.getTransaction(0L), history.getTransaction(1L)));
    }

    @Test
    void classifiesPredicateReadsPerCoveredKey() throws Exception {
        var historyDir = dataset("[{\"key\":\"kv:0\",\"value\":0},{\"key\":\"kv:1\",\"value\":1}]", List.of(
                "{\"session\":0,\"txn\":0,\"status\":\"commit\",\"ops\":["
                        + "{\"type\":\"w\",\"key\":\"kv:0\",\"value\":2},"
                        + predicateOp("TRUE", "[{\"key\":\"kv:0\",\"value\":2},{\"key\":\"kv:1\",\"value\":1}]") + ","
                        + predicateOp("TRUE", "[{\"key\":\"kv:0\",\"value\":2},{\"key\":\"kv:1\",\"value\":1}]") + "]}"));

        var graph = new KnownGraph<>(new PredicateHistoryLoader(historyDir).loadHistory());
        var first = graph.getPredicateObservations().stream()
                .filter(observation -> observation.getTxn().getId() == 0L && observation.getEventIndex() == 1)
                .findFirst().orElseThrow();
        var second = graph.getPredicateObservations().stream()
                .filter(observation -> observation.getTxn().getId() == 0L && observation.getEventIndex() == 2)
                .findFirst().orElseThrow();

        assertEquals(KnownGraph.PredicateReadType.INTERNAL, first.getPredicateReadType("kv:0"));
        assertEquals(KnownGraph.PredicateReadType.EXTERNAL, first.getPredicateReadType("kv:1"));
        assertEquals(KnownGraph.PredicateReadType.INTERNAL, second.getPredicateReadType("kv:0"));
        assertEquals(KnownGraph.PredicateReadType.INTERNAL, second.getPredicateReadType("kv:1"));
        assertEquals(first.getPredicateReadEvent().getPredicate().identity(),
                second.getPredicateReadEvent().getPredicate().identity());
    }

    @Test
    void differentPredicateStillMakesCoveredKeysInternal() throws Exception {
        var historyDir = dataset("[{\"key\":\"kv:0\",\"value\":0},{\"key\":\"kv:1\",\"value\":1}]", List.of(
                "{\"session\":0,\"txn\":0,\"status\":\"commit\",\"ops\":["
                        + predicateOp("value > 100", "[]") + ","
                        + predicateOp("TRUE", "[{\"key\":\"kv:0\",\"value\":0},{\"key\":\"kv:1\",\"value\":1}]") + "]}"));

        var graph = new KnownGraph<>(new PredicateHistoryLoader(historyDir).loadHistory());
        var first = graph.getPredicateObservations().stream()
                .filter(observation -> observation.getEventIndex() == 0)
                .findFirst().orElseThrow();
        var second = graph.getPredicateObservations().stream()
                .filter(observation -> observation.getEventIndex() == 1)
                .findFirst().orElseThrow();

        assertEquals(KnownGraph.PredicateReadType.EXTERNAL, first.getPredicateReadType("kv:0"));
        assertEquals(KnownGraph.PredicateReadType.EXTERNAL, first.getPredicateReadType("kv:1"));
        assertEquals(KnownGraph.PredicateReadType.INTERNAL, second.getPredicateReadType("kv:0"));
        assertEquals(KnownGraph.PredicateReadType.INTERNAL, second.getPredicateReadType("kv:1"));
        assertNotEquals(first.getPredicateReadEvent().getPredicate().identity(),
                second.getPredicateReadEvent().getPredicate().identity());
    }

    @Test
    void classifiesOnlyKeysCoveredByEachPredicate() {
        var history = new History<String, Integer>();
        var initSession = history.addSession(-1L);
        var initTxn = history.addTransaction(initSession, -1L);
        history.addWriteEvent(initTxn, "kv:0", 0, null);
        history.addWriteEvent(initTxn, "kv:1", 1, null);
        initTxn.setStatus(Transaction.TransactionStatus.COMMIT);

        var session = history.addSession(0L);
        var txn = history.addTransaction(session, 0L);
        history.addPredicateReadEvent(txn, scopedPredicate("kv:0"),
                List.of(new Event.PredResult<>("kv:0", 0)));
        history.addPredicateReadEvent(txn, scopedPredicate("kv:1"),
                List.of(new Event.PredResult<>("kv:1", 1)));
        txn.setStatus(Transaction.TransactionStatus.COMMIT);

        var observations = new KnownGraph<>(history).getPredicateObservations();
        var first = observations.stream().filter(observation -> observation.getEventIndex() == 0)
                .findFirst().orElseThrow();
        var second = observations.stream().filter(observation -> observation.getEventIndex() == 1)
                .findFirst().orElseThrow();

        assertEquals(KnownGraph.PredicateReadType.EXTERNAL, first.getPredicateReadType("kv:0"));
        assertNull(first.getPredicateReadType("kv:1"));
        assertNull(second.getPredicateReadType("kv:0"));
        assertEquals(KnownGraph.PredicateReadType.EXTERNAL, second.getPredicateReadType("kv:1"));
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

    private static PredicateFixtures.RowPredicate<String, Integer> scopedPredicate(String coveredKey) {
        return new PredicateFixtures.RowPredicate<>() {
            @Override
            public boolean test(String key, Integer value) {
                return coveredKey.equals(key);
            }

            @Override
            public boolean covers(String key) {
                return coveredKey.equals(key);
            }
        };
    }

    private Path dataset(String initialStateJson, List<String> transactionLines) throws Exception {
        var historyDir = Files.createTempDirectory("prhist-loader");
        Files.writeString(historyDir.resolve("initial_state.json"), initialStateJson);
        Files.writeString(historyDir.resolve("history.prhist.jsonl"), String.join("\n", transactionLines));
        Files.writeString(historyDir.resolve("manifest.json"), "{}");
        return historyDir;
    }
}
