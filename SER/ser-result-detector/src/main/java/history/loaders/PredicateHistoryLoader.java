package history.loaders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import history.Event;
import history.History;
import history.InvalidHistoryError;
import history.Transaction;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.SneakyThrows;

public class PredicateHistoryLoader implements history.HistoryLoader<String, PredicateHistoryLoader.PredicateValue> {
    private static final String HISTORY_FILE = "history.prhist.jsonl";
    private static final String INITIAL_STATE_FILE = "initial_state.json";
    private static final long INIT_SESSION_ID = -1L;
    private static final long INIT_TXN_ID = -1L;
    private static final List<String> SOURCE_METADATA_FIELDS =
            List.of("write_id", "source_write_id", "source_txn", "source_op_index");
    private static final Pattern VALUE_EQ = Pattern.compile("^value\\s*=\\s*(-?\\d+)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VALUE_MOD = Pattern.compile("^value\\s*%\\s*(-?\\d+)\\s*=\\s*(-?\\d+)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VALUE_GT = Pattern.compile("^value\\s*>\\s*(-?\\d+)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VALUE_LT = Pattern.compile("^value\\s*<\\s*(-?\\d+)$",
            Pattern.CASE_INSENSITIVE);

    private final File historyFile;
    private final File initialStateFile;
    private final ObjectMapper objectMapper = new ObjectMapper();

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
            history.addPredicateReadEvent(transaction,
                    parseQueryPredicate(requiredObject(opNode, "query")),
                    parseQueryPredicateResults(requiredObject(opNode, "result")));
            break;
        default:
            throw new InvalidHistoryError();
        }
    }

    private List<Event.PredResult<String, PredicateValue>> parseQueryPredicateResults(
            JsonNode resultNode) {
        var inputs = requiredArray(resultNode, "inputs");
        var results = new ArrayList<Event.PredResult<String, PredicateValue>>();
        for (var inputNode : inputs) {
            var key = requiredText(inputNode, "key");
            var value = parseValue(inputNode);
            results.add(new Event.PredResult<>(key, value));
        }
        return results;
    }

    private Event.PredEval<String, PredicateValue> parseQueryPredicate(JsonNode queryNode) {
        var where = requiredArray(queryNode, "where");
        if (where.size() != 1 || !where.get(0).isTextual()) {
            throw new InvalidHistoryError();
        }

        var clause = where.get(0).asText().trim();
        if ("TRUE".equalsIgnoreCase(clause)) {
            return (key, value) -> key != null && value != null;
        }

        var eq = VALUE_EQ.matcher(clause);
        if (eq.matches()) {
            var target = parseLongLiteral(eq.group(1));
            return (key, value) -> key != null
                    && value != null
                    && value.getEncoded() == target;
        }

        var mod = VALUE_MOD.matcher(clause);
        if (mod.matches()) {
            var modulus = parseLongLiteral(mod.group(1));
            var target = parseLongLiteral(mod.group(2));
            if (modulus == 0) {
                throw new InvalidHistoryError();
            }
            return (key, value) -> key != null
                    && value != null
                    && Math.floorMod(value.getEncoded(), modulus) == target;
        }

        var gt = VALUE_GT.matcher(clause);
        if (gt.matches()) {
            var target = parseLongLiteral(gt.group(1));
            return (key, value) -> key != null
                    && value != null
                    && value.getEncoded() > target;
        }

        var lt = VALUE_LT.matcher(clause);
        if (lt.matches()) {
            var target = parseLongLiteral(lt.group(1));
            return (key, value) -> key != null
                    && value != null
                    && value.getEncoded() < target;
        }

        throw new InvalidHistoryError();
    }

    private PredicateValue parseValue(JsonNode node) {
        rejectSourceMetadata(node);
        return new PredicateValue(requiredLong(node, "value"));
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

    private long parseLongLiteral(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exc) {
            throw new InvalidHistoryError();
        }
    }

    private void rejectSourceMetadata(JsonNode node) {
        for (var field : SOURCE_METADATA_FIELDS) {
            if (node.has(field)) {
                throw new InvalidHistoryError();
            }
        }
    }

    @Data
    @EqualsAndHashCode(of = "encoded")
    public static class PredicateValue {
        private final long encoded;

        public PredicateValue(long encoded) {
            this.encoded = encoded;
        }

        public PredicateValue(long encoded, int ignoredSemantic) {
            this(encoded);
        }

        @Override
        public String toString() {
            return Long.toString(encoded);
        }
    }
}
