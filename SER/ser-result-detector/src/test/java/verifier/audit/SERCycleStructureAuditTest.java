package verifier.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import history.Event;
import history.History;
import history.Transaction;
import history.query.GeneralRecordedQueryResult;
import history.query.QueryPlan;
import history.query.QueryValue;
import history.query.RecordedQueryResult;
import history.query.RelationResolver;
import history.query.StructuredQueryParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import verifier.SERVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Heavy regression for the current SER dependency model.
 *
 * <p>Enumerates every length-2/3/4 cycle signature over
 * WR/RW/WW/PR_WR/PR_RW and checks both the cyclic history and a control
 * history with the closing edge removed. DISTINCT is intentionally outside
 * the supported model and is not generated here.</p>
 */
@Tag("audit")
class SERCycleStructureAuditTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String[] TYPES = {"WR", "RW", "WW", "PR_WR", "PR_RW"};
    private static final StructuredQueryParser<String, Integer> PARSER =
            new StructuredQueryParser<>(value -> QueryValue.integer(value.longValue()),
                    RelationResolver.canonicalStringKeys());
    private static final Map<String, QueryPlan<String, Integer>> PLANS = new HashMap<>();

    @Test
    void recognizesAllLengthTwoToFourTypedCyclesAndBrokenControls() throws Exception {
        for (int length = 2; length <= 4; length++) {
            enumerate(length, 0, new String[length]);
        }
    }

    @Test
    void recognizesLongMixedCyclesAndBrokenControls() throws Exception {
        var random = new Random(0x5E7C1E5L);
        for (int length : List.of(5, 6)) {
            for (int sample = 0; sample < 200; sample++) {
                var signature = new String[length];
                for (int i = 0; i < length; i++) {
                    signature[i] = TYPES[random.nextInt(TYPES.length)];
                }
                assertSignature(signature);
            }
        }
    }

    private static void enumerate(int length, int position, String[] signature) throws Exception {
        if (position == length) {
            assertSignature(signature.clone());
            return;
        }
        for (var type : TYPES) {
            signature[position] = type;
            enumerate(length, position + 1, signature);
        }
    }

    private static void assertSignature(String[] signature) throws Exception {
        var cycle = build(signature, false);
        var control = build(signature, true);
        for (var settings : configurations()) {
            assertEquals(SERVerifier.AuditResult.REJECT,
                    audit(cycle, settings),
                    "cycle accepted: " + String.join("->", signature));
            assertEquals(SERVerifier.AuditResult.ACCEPT,
                    audit(control, settings),
                    "broken control rejected: " + String.join("->", signature));
        }
    }

    private static List<SERVerifier.SolverSettings> configurations() {
        var eagerRaw = SERVerifier.SolverSettings.forModes(
                SERVerifier.PredicateSolvingMode.EAGER,
                SERVerifier.PruningMode.NONE);
        eagerRaw.predicateWitnessCoalescing = false;
        eagerRaw.graphEdgeInterning = false;
        eagerRaw.gmwrPrepropagation = false;

        var eagerCompressed = SERVerifier.SolverSettings.forModes(
                SERVerifier.PredicateSolvingMode.EAGER,
                SERVerifier.PruningMode.NONE);
        eagerCompressed.predicateWitnessCoalescing = true;
        eagerCompressed.graphEdgeInterning = true;
        eagerCompressed.gmwrPrepropagation = false;

        var gmwrRaw = SERVerifier.SolverSettings.forModes(
                SERVerifier.PredicateSolvingMode.GMWR,
                SERVerifier.PruningMode.NONE);
        gmwrRaw.predicateWitnessCoalescing = false;
        gmwrRaw.graphEdgeInterning = false;
        gmwrRaw.gmwrPrepropagation = false;

        var gmwrOptimized = SERVerifier.SolverSettings.forModes(
                SERVerifier.PredicateSolvingMode.GMWR,
                SERVerifier.PruningMode.NONE);
        gmwrOptimized.predicateWitnessCoalescing = true;
        gmwrOptimized.graphEdgeInterning = true;
        gmwrOptimized.gmwrPrepropagation = true;
        return List.of(eagerRaw, eagerCompressed, gmwrRaw, gmwrOptimized);
    }

    private static SERVerifier.AuditResult audit(
            Built history, SERVerifier.SolverSettings settings) {
        return new SERVerifier<String, Integer>(
                () -> history.history, settings, false).audit();
    }

    private static Built build(String[] signature, boolean breakClosingEdge) throws Exception {
        var history = new History<String, Integer>();
        var initial = history.addTransaction(history.addSession(-1), -1);
        initial.setStatus(Transaction.TransactionStatus.COMMIT);

        var main = new ArrayList<Transaction<String, Integer>>();
        for (int i = 0; i < signature.length; i++) {
            var txn = history.addTransaction(history.addSession(i + 1L), i + 1L);
            txn.setStatus(Transaction.TransactionStatus.COMMIT);
            main.add(txn);
        }
        long nextId = signature.length + 1L;

        for (int i = 0; i < signature.length; i++) {
            int next = (i + 1) % signature.length;
            boolean broken = breakClosingEdge && i == signature.length - 1;
            var from = main.get(i);
            var to = main.get(next);
            String local = "e" + i;
            String key = "kv:" + local;

            switch (signature[i]) {
            case "WR":
                history.addWriteEvent(initial, key, 0, null);
                history.addWriteEvent(from, key, 100 + i, null);
                if (!broken) {
                    history.addEvent(to, Event.EventType.READ, key, 100 + i);
                }
                break;
            case "RW":
                history.addWriteEvent(initial, key, 0, null);
                if (!broken) {
                    history.addEvent(from, Event.EventType.READ, key, 0);
                }
                history.addWriteEvent(to, key, 200 + i, null);
                break;
            case "PR_WR":
                history.addWriteEvent(initial, key, 0, null);
                history.addWriteEvent(from, key, 300 + i, null);
                if (!broken) {
                    predicateRead(history, to, key, 300 + i, true);
                }
                break;
            case "PR_RW": {
                history.addWriteEvent(initial, key, 0, null);
                var source = history.addTransaction(history.addSession(nextId), nextId++);
                source.setStatus(Transaction.TransactionStatus.COMMIT);
                history.addWriteEvent(source, key, 400 + i, null);
                if (!broken) {
                    predicateRead(history, from, key, 400 + i, true);
                }
                // Force source < later writer so the latter is a genuine PR_RW candidate.
                history.addEvent(to, Event.EventType.READ, key, 400 + i);
                history.addWriteEvent(to, key, 1 + i, null);
                break;
            }
            case "WW": {
                history.addWriteEvent(initial, key, 0, null);
                String gate = "kv:g" + i;
                history.addWriteEvent(initial, gate, 0, null);
                history.addWriteEvent(from, key, 500 + i, null);
                history.addWriteEvent(from, gate, 600 + i, null);
                history.addWriteEvent(to, key, 700 + i, null);
                var reader = history.addTransaction(history.addSession(nextId), nextId++);
                reader.setStatus(Transaction.TransactionStatus.COMMIT);
                if (!broken) {
                    history.addEvent(reader, Event.EventType.READ, gate, 600 + i);
                    history.addEvent(reader, Event.EventType.READ, key, 700 + i);
                }
                break;
            }
            default:
                throw new AssertionError(signature[i]);
            }
        }
        return new Built(history);
    }

    private static void predicateRead(
            History<String, Integer> history,
            Transaction<String, Integer> txn,
            String key,
            int value,
            boolean present) throws Exception {
        String local = key.substring(key.indexOf(':') + 1);
        var plan = planFor(local);
        var inputs = new LinkedHashMap<String, Integer>();
        var values = new ArrayList<QueryValue>();
        var rows = new ArrayList<Event.PredResult<String, Integer>>();
        if (present) {
            inputs.put(key, value);
            values.add(row(local, value));
            rows.add(new Event.PredResult<>(key, value));
        }
        RecordedQueryResult<String, Integer> recorded =
                new GeneralRecordedQueryResult<>(inputs, values, item -> QueryValue.integer(item.longValue()));
        history.addPredicateReadEvent(txn, plan, rows, recorded);
    }

    private static QueryPlan<String, Integer> planFor(String local) throws Exception {
        var cached = PLANS.get(local);
        if (cached != null) {
            return cached;
        }
        var query = JSON.createObjectNode();
        query.putObject("from").put("relation", "kv");
        query.putArray("where").add("k = '" + local + "' AND value > 9");
        var columns = query.putObject("select").putArray("columns");
        columns.add("k");
        columns.add("value");
        var plan = PARSER.parse(query);
        PLANS.put(local, plan);
        return plan;
    }

    private static QueryValue row(String local, int value) {
        var fields = new LinkedHashMap<String, QueryValue>();
        fields.put("k", QueryValue.text(local));
        fields.put("value", QueryValue.integer(value));
        return QueryValue.object(fields);
    }

    private static final class Built {
        private final History<String, Integer> history;

        private Built(History<String, Integer> history) {
            this.history = history;
        }
    }
}
