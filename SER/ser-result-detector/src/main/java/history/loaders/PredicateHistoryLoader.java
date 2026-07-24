package history.loaders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import history.Event;
import history.History;
import history.InvalidHistoryError;
import history.Transaction;
import history.query.PredicateEvaluator;
import history.query.QueryException;
import history.query.QueryValue;
import history.query.RecordedQueryResult;
import history.query.RelationResolver;
import history.query.StructuredQueryParser;
import history.query.ValueAdapter;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.SneakyThrows;

public class PredicateHistoryLoader implements history.HistoryLoader<String, PredicateHistoryLoader.PredicateValue> {
    private static final String HISTORY_FILE = "history.prhist.jsonl";
    private static final String INITIAL_STATE_FILE = "initial_state.json";
    private static final long INIT_SESSION_ID = -1L;
    private static final long INIT_TXN_ID = -1L;
    private static final List<String> SOURCE_METADATA_FIELDS =
            List.of("write_id", "source_write_id", "source_txn", "source_op_index");

    private final File historyFile;
    private final File initialStateFile;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ValueAdapter<PredicateValue> valueAdapter =
            ValueAdapter.of(PredicateValue::queryValue);
    private final RelationResolver<String> relationResolver =
            RelationResolver.canonicalStringKeys();
    private final StructuredQueryParser<String, PredicateValue> queryParser =
            new StructuredQueryParser<>(valueAdapter, relationResolver);

    public PredicateHistoryLoader(Path path) {
        var file = path.toFile();
        var root = file.isDirectory() ? file : file.getParentFile();
        historyFile = file.isDirectory() ? path.resolve(HISTORY_FILE).toFile() : file;
        initialStateFile = root == null ? new File(INITIAL_STATE_FILE) : root.toPath().resolve(INITIAL_STATE_FILE).toFile();

        if (!historyFile.isFile()) {
            throw new Error(String.format("%s is not a predicate history file", historyFile));
        }
    }

    @Override
    @SneakyThrows
    public History<String, PredicateValue> loadHistory() {
        var history = new History<String, PredicateValue>();
        loadInitialState(history);

        try (var in = new BufferedReader(new FileReader(historyFile))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                parseTransaction(history, objectMapper.readTree(line));
            }
        }

        return history;
    }

    @SneakyThrows
    private void loadInitialState(History<String, PredicateValue> history) {
        if (!initialStateFile.isFile()) {
            throw new InvalidHistoryError();
        }

        var initSession = history.addSession(INIT_SESSION_ID);
        var initTxn = history.addTransaction(initSession, INIT_TXN_ID);
        var initialState = objectMapper.readTree(initialStateFile);
        if (initialState == null || !initialState.isArray()) {
            throw new InvalidHistoryError();
        }

        for (var tuple : initialState) {
            var key = requiredText(tuple, "key");
            var value = parseValue(tuple);
            history.addWriteEvent(initTxn, key, value, null);
        }
        initTxn.setStatus(Transaction.TransactionStatus.COMMIT);
    }

    private void parseTransaction(History<String, PredicateValue> history, JsonNode txnNode) {
        var status = requiredText(txnNode, "status");
        if (!"commit".equalsIgnoreCase(status)) {
            throw new InvalidHistoryError();
        }

        var sessionId = requiredLong(txnNode, "session");
        var txnId = requiredLong(txnNode, "txn");
        var session = history.getSession(sessionId);
        if (session == null) {
            session = history.addSession(sessionId);
        }

        if (history.getTransaction(txnId) != null) {
            throw new InvalidHistoryError();
        }
        var transaction = history.addTransaction(session, txnId);

        for (var opNode : requiredArray(txnNode, "ops")) {
            parseOperation(history, transaction, opNode);
        }
        transaction.setStatus(Transaction.TransactionStatus.COMMIT);
    }

    private void parseOperation(History<String, PredicateValue> history,
            Transaction<String, PredicateValue> transaction, JsonNode opNode) {
        var type = requiredText(opNode, "type");
        switch (type) {
        case "w":
            history.addWriteEvent(transaction, requiredText(opNode, "key"), parseValue(opNode),
                    null);
            break;
        case "r": {
            var key = requiredText(opNode, "key");
            var value = parseValue(opNode);
            history.addReadEvent(transaction, key, value, null, null, null);
            break;
        }
        case "pr":
            if (opNode.has("predicate") || opNode.has("results") || !opNode.has("query")) {
                throw new InvalidHistoryError();
            }
            var parsedResult = parseQueryPredicateResult(requiredObject(opNode, "result"));
            history.addPredicateReadEvent(transaction,
                    parseQueryPredicate(requiredObject(opNode, "query")),
                    parsedResult.inputs,
                    parsedResult.recorded);
            break;
        default:
            throw new InvalidHistoryError();
        }
    }

    private ParsedQueryResult parseQueryPredicateResult(
            JsonNode resultNode) {
        var inputs = requiredArray(resultNode, "inputs");
        var results = new ArrayList<Event.PredResult<String, PredicateValue>>();
        var recordedInputs = new LinkedHashMap<String, PredicateValue>();
        for (var inputNode : inputs) {
            var key = requiredText(inputNode, "key");
            var value = parseValue(inputNode);
            if (recordedInputs.putIfAbsent(key, value) != null) {
                throw new InvalidHistoryError();
            }
            results.add(new Event.PredResult<>(key, value));
        }

        var valuesNode = requiredArray(resultNode, "values");
        var values = new ArrayList<QueryValue>();
        for (var valueNode : valuesNode) {
            values.add(parseQueryValue(valueNode));
        }
        var recorded = new RecordedQueryResult<>(recordedInputs, values, valueAdapter);
        return new ParsedQueryResult(results, recorded);
    }

    private PredicateEvaluator<String, PredicateValue> parseQueryPredicate(JsonNode queryNode) {
        try {
            return queryParser.parse(queryNode);
        } catch (QueryException exception) {
            throw new InvalidHistoryError();
        }
    }

    private PredicateValue parseValue(JsonNode node) {
        rejectSourceMetadata(node);
        var value = node.get("value");
        if (value == null || value.isNull() || value.isArray()
                || value.isNumber() && !value.isIntegralNumber()) {
            throw new InvalidHistoryError();
        }
        try {
            return new PredicateValue(value);
        } catch (QueryException exception) {
            throw new InvalidHistoryError();
        }
    }

    private QueryValue parseQueryValue(JsonNode node) {
        if (node == null || node.isNull()
                || node.isNumber() && !node.isIntegralNumber()) {
            throw new InvalidHistoryError();
        }
        try {
            return QueryValue.of(node);
        } catch (QueryException exception) {
            throw new InvalidHistoryError();
        }
    }

    private JsonNode requiredObject(JsonNode node, String fieldName) {
        var child = node.get(fieldName);
        if (child == null || !child.isObject()) {
            throw new InvalidHistoryError();
        }
        return child;
    }

    private JsonNode requiredArray(JsonNode node, String fieldName) {
        var child = node.get(fieldName);
        if (child == null || !child.isArray()) {
            throw new InvalidHistoryError();
        }
        return child;
    }

    private String requiredText(JsonNode node, String fieldName) {
        var child = node.get(fieldName);
        if (child == null || !child.isTextual()) {
            throw new InvalidHistoryError();
        }
        return child.asText();
    }

    private long requiredLong(JsonNode node, String fieldName) {
        var child = node.get(fieldName);
        if (child == null || !child.canConvertToLong()) {
            throw new InvalidHistoryError();
        }
        return child.asLong();
    }

    private void rejectSourceMetadata(JsonNode node) {
        for (var field : SOURCE_METADATA_FIELDS) {
            if (node.has(field)) {
                throw new InvalidHistoryError();
            }
        }
    }

    private static final class ParsedQueryResult {
        private final List<Event.PredResult<String, PredicateValue>> inputs;
        private final RecordedQueryResult<String, PredicateValue> recorded;

        private ParsedQueryResult(
                List<Event.PredResult<String, PredicateValue>> inputs,
                RecordedQueryResult<String, PredicateValue> recorded) {
            this.inputs = inputs;
            this.recorded = recorded;
        }
    }

    @EqualsAndHashCode(of = "canonical")
    public static class PredicateValue {
        private final QueryValue canonical;

        public PredicateValue(long encoded) {
            this(QueryValue.integer(encoded));
        }

        public PredicateValue(long encoded, int ignoredSemantic) {
            this(encoded);
        }

        public PredicateValue(JsonNode value) {
            this(QueryValue.of(value));
        }

        private PredicateValue(QueryValue canonical) {
            this.canonical = canonical;
        }

        public long getEncoded() {
            return canonical.asLong();
        }

        public JsonNode getJsonValue() {
            return canonical.json();
        }

        public QueryValue queryValue() {
            return canonical;
        }

        @Override
        public String toString() {
            return canonical.toString();
        }
    }
}
