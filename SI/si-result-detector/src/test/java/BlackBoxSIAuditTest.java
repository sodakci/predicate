import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import verifier.Pruning;
import verifier.SIVerifier;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlackBoxSIAuditTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void auditCli_acceptsSerializableHistory() throws Exception {
        var result = runAudit(List.of(
                "w(1,1,1,1)",
                "r(1,1,2,2)"
        ));

        assertEquals(0, result.exitCode);
        assertTrue(result.stderr.contains("[[[[ ACCEPT ]]]]"),
                () -> "expected ACCEPT marker, stderr was:\n" + result.stderr);
    }

    @Test
    void auditCli_acceptsWriteSkewHistory() throws Exception {
        var result = runAudit(List.of(
                "r(1,0,1,1)",
                "r(2,0,1,1)",
                "w(1,1,1,1)",
                "r(1,0,2,2)",
                "r(2,0,2,2)",
                "w(2,1,2,2)"
        ));

        assertEquals(0, result.exitCode);
        assertTrue(result.stderr.contains("[[[[ ACCEPT ]]]]"),
                () -> "expected ACCEPT marker, stderr was:\n" + result.stderr);
    }

    @Test
    void auditCli_reportsPurePredicateRwCycle() throws Exception {
        var historyDir = writePrhist("pure-prrw", "["
                + "{\"key\":\"inventory_onhand_x\",\"value\":130000001,\"semantic\":130,\"source_write_id\":1},"
                + "{\"key\":\"inventory_onhand_y\",\"value\":130000002,\"semantic\":130,\"source_write_id\":2},"
                + "{\"key\":\"inventory_onhand_z\",\"value\":130000003,\"semantic\":130,\"source_write_id\":3}"
                + "]", List.of(
                "{\"session\":0,\"txn\":0,\"kind\":\"inventory.t0\",\"status\":\"commit\",\"ops\":["
                        + "{\"type\":\"pr\",\"predicate\":{\"kind\":\"inventory_threshold\",\"key_prefix\":\"inventory_onhand_x\",\"comparator\":\"ge\",\"threshold\":100},\"results\":["
                        + "{\"key\":\"inventory_onhand_x\",\"value\":130000001,\"semantic\":130,\"source_write_id\":1,\"source_txn\":-1}]},"
                        + "{\"type\":\"w\",\"key\":\"inventory_onhand_z\",\"value\":400000003,\"semantic\":40,\"write_id\":10}]}",
                "{\"session\":1,\"txn\":1,\"kind\":\"inventory.t1\",\"status\":\"commit\",\"ops\":["
                        + "{\"type\":\"pr\",\"predicate\":{\"kind\":\"inventory_threshold\",\"key_prefix\":\"inventory_onhand_y\",\"comparator\":\"ge\",\"threshold\":100},\"results\":["
                        + "{\"key\":\"inventory_onhand_y\",\"value\":130000002,\"semantic\":130,\"source_write_id\":2,\"source_txn\":-1}]},"
                        + "{\"type\":\"w\",\"key\":\"inventory_onhand_x\",\"value\":400000001,\"semantic\":40,\"write_id\":11}]}",
                "{\"session\":2,\"txn\":2,\"kind\":\"inventory.t2\",\"status\":\"commit\",\"ops\":["
                        + "{\"type\":\"pr\",\"predicate\":{\"kind\":\"inventory_threshold\",\"key_prefix\":\"inventory_onhand_z\",\"comparator\":\"ge\",\"threshold\":100},\"results\":["
                        + "{\"key\":\"inventory_onhand_z\",\"value\":130000003,\"semantic\":130,\"source_write_id\":3,\"source_txn\":-1}]},"
                        + "{\"type\":\"w\",\"key\":\"inventory_onhand_y\",\"value\":400000002,\"semantic\":40,\"write_id\":12}]}"));

        var result = runAuditCommand("audit", "-t", "PRHIST", historyDir.toString());

        assertEquals(-1, result.exitCode);
        assertTrue(result.stderr.contains("[[[[ REJECT ]]]]"),
                () -> "expected REJECT marker, stderr was:\n" + result.stderr);
    }

    @Test
    void auditCli_acceptsSerializableWrWwRwChain() throws Exception {
        var result = runAudit(List.of(
                "w(1,1,1,1)",
                "r(1,1,2,2)",
                "w(2,1,2,2)",
                "r(2,1,3,3)",
                "w(1,2,3,3)"
        ));

        // Theory: serial order T1 -> T2 -> T3. WR: T1->T2 on x and
        // T2->T3 on y. WW: T1->T3 on x. No RW edge points backward.
        assertEquals(0, result.exitCode);
        assertTrue(result.stderr.contains("[[[[ ACCEPT ]]]]"),
                () -> "expected ACCEPT marker, stderr was:\n" + result.stderr);
    }

    @Test
    void auditCli_supportsMonosatSolverBackend() throws Exception {
        var historyDir = writeTextHistoryAsPrhist("monosat-history", List.of(
                "w(1,1,1,1)",
                "r(1,1,2,2)"));

        var result = runAuditCommand("audit", "-t", "PRHIST", "--solver", "monosat", "--solver-stats",
                historyDir.toString());

        assertEquals(0, result.exitCode);
        assertTrue(result.stderr.contains("[[[[ ACCEPT ]]]]"));
        assertTrue(result.stderr.contains("backend=monosat"), () -> "stderr was:\n" + result.stderr);
    }

    @Test
    void auditCli_defaultSolverIsMonosat() throws Exception {
        var historyDir = writeTextHistoryAsPrhist("default-monosat-history", List.of(
                "w(1,1,1,1)",
                "r(1,1,2,2)"));

        var result = runAuditCommand("audit", "-t", "PRHIST", "--solver-stats",
                historyDir.toString());

        assertEquals(0, result.exitCode);
        assertTrue(result.stderr.contains("[[[[ ACCEPT ]]]]"));
        assertTrue(result.stderr.contains("backend=monosat"), () -> "stderr was:\n" + result.stderr);
    }

    @Test
    void auditCli_monosatAcceptsWriteSkewCase() throws Exception {
        var historyDir = writeTextHistoryAsPrhist("reject-history", List.of(
                "r(1,0,1,1)",
                "r(2,0,1,1)",
                "w(1,1,1,1)",
                "r(1,0,2,2)",
                "r(2,0,2,2)",
                "w(2,1,2,2)"));

        var monosat = runAuditCommand("audit", "-t", "PRHIST", "--solver", "monosat", historyDir.toString());

        assertEquals(0, monosat.exitCode);
        assertTrue(monosat.stderr.contains("[[[[ ACCEPT ]]]]"));
    }

    @Test
    void auditCli_basicPrhistOptionMatrixPreservesAcceptRejectResults() throws Exception {
        var acceptHistory = writeTextHistoryAsPrhist("matrix-accept", List.of(
                "w(1,1,1,1)",
                "r(1,1,2,2)"));

        var writeSkewHistory = writeTextHistoryAsPrhist("matrix-write-skew", List.of(
                "r(1,0,1,1)",
                "r(2,0,1,1)",
                "w(1,1,1,1)",
                "r(1,0,2,2)",
                "r(2,0,2,2)",
                "w(2,1,2,2)"));

        assertOptionMatrix(acceptHistory, "PRHIST", 0);
        assertOptionMatrix(writeSkewHistory, "PRHIST", 0);
    }

    @Test
    void auditCli_prhistPredicateOptionMatrixPreservesAcceptRejectResults() throws Exception {
        var acceptHistory = writePrhist("matrix-prhist-accept", "[]", List.of(
                "{\"session\":0,\"txn\":0,\"kind\":\"writer\",\"status\":\"commit\",\"ops\":["
                        + "{\"type\":\"w\",\"key\":\"inventory_onhand_x\",\"value\":101,\"semantic\":101,\"write_id\":1}]}",
                "{\"session\":1,\"txn\":1,\"kind\":\"reader\",\"status\":\"commit\",\"ops\":["
                        + "{\"type\":\"pr\",\"predicate\":{\"kind\":\"inventory_threshold\",\"key_prefix\":\"inventory_onhand_x\",\"comparator\":\"ge\",\"threshold\":100},\"results\":["
                        + "{\"key\":\"inventory_onhand_x\",\"value\":101,\"semantic\":101,\"source_write_id\":1,\"source_txn\":0}]}]}"));

        var rejectHistory = writePrhist("matrix-prhist-reject", "[]", List.of(
                "{\"session\":0,\"txn\":0,\"kind\":\"reader\",\"status\":\"commit\",\"ops\":["
                        + "{\"type\":\"w\",\"key\":\"dep_y\",\"value\":1,\"semantic\":1,\"write_id\":20},"
                        + "{\"type\":\"pr\",\"predicate\":{\"kind\":\"inventory_threshold\",\"key_prefix\":\"inventory_onhand_x\",\"comparator\":\"ge\",\"threshold\":100},\"results\":["
                        + "{\"key\":\"inventory_onhand_x\",\"value\":101,\"semantic\":101,\"source_write_id\":21,\"source_txn\":1}]}]}",
                "{\"session\":1,\"txn\":1,\"kind\":\"writer\",\"status\":\"commit\",\"ops\":["
                        + "{\"type\":\"r\",\"key\":\"dep_y\",\"value\":1,\"semantic\":1,\"source_write_id\":20,\"source_txn\":0},"
                        + "{\"type\":\"w\",\"key\":\"inventory_onhand_x\",\"value\":101,\"semantic\":101,\"write_id\":21}]}"));

        assertOptionMatrix(acceptHistory, "PRHIST", 0);
        assertOptionMatrix(rejectHistory, "PRHIST", -1);
    }

    @Test
    void auditCli_rejectsInvalidSolverArgument() throws Exception {
        var historyDir = writeTextHistoryAsPrhist("invalid-solver-history", List.of("w(1,1,1,1)"));

        var result = runAuditCommand("audit", "-t", "PRHIST", "--solver", "xxx", historyDir.toString());

        assertEquals(2, result.exitCode);
        assertTrue(result.stderr.contains("Invalid value") || result.stderr.contains("xxx"),
                () -> "stderr was:\n" + result.stderr);
    }

    @Test
    void auditCli_rejectsOnlyMergedABGraphCycle() throws Exception {
        var result = runAudit(List.of(
                "w(1,1,1,1)",
                "w(2,1,1,1)",
                "r(1,1,2,2)",
                "r(2,0,2,2)"
        ));

        // Theory: A alone has WR T1->T2 on x. B alone has RW T2->T1 on y
        // because T2 read the initial y=0 and T1 overwrote y. Each partition
        // is acyclic by itself, but A union B has T1 -> T2 -> T1.
        assertEquals(-1, result.exitCode);
        assertTrue(result.stdout.contains("known WR key=1"),
                () -> "expected WR edge in cycle witness, stdout was:\n" + result.stdout);
        assertTrue(result.stdout.contains("RW key=2"),
                () -> "expected RW edge in cycle witness, stdout was:\n" + result.stdout);
    }

    @Test
    void auditCli_rejectsPredicateWrCycle() throws Exception {
        var result = runPrhistAudit("prwr-cycle", List.of(
                "{\"session\":0,\"txn\":0,\"kind\":\"reader\",\"status\":\"commit\",\"ops\":["
                        + "{\"type\":\"w\",\"key\":\"dep_y\",\"value\":1,\"semantic\":1,\"write_id\":20},"
                        + "{\"type\":\"pr\",\"predicate\":{\"kind\":\"inventory_threshold\",\"key_prefix\":\"inventory_onhand_x\",\"comparator\":\"ge\",\"threshold\":100},\"results\":["
                        + "{\"key\":\"inventory_onhand_x\",\"value\":101,\"semantic\":101,\"source_write_id\":21,\"source_txn\":1}]}]}",
                "{\"session\":1,\"txn\":1,\"kind\":\"writer\",\"status\":\"commit\",\"ops\":["
                        + "{\"type\":\"r\",\"key\":\"dep_y\",\"value\":1,\"semantic\":1,\"source_write_id\":20,\"source_txn\":0},"
                        + "{\"type\":\"w\",\"key\":\"inventory_onhand_x\",\"value\":101,\"semantic\":101,\"write_id\":21}]}"));

        // Theory: WR T0->T1 on dep_y, and predicate result visibility requires
        // PR_WR/source edge T1->T0 for inventory_onhand_x. The SER graph has
        // the cycle T0 -> T1 -> T0.
        assertEquals(-1, result.exitCode);
        assertTrue(result.stderr.contains("[[[[ REJECT ]]]]"),
                () -> "expected REJECT marker, stderr was:\n" + result.stderr);
    }

    @Test
    void auditCli_rejectsEmptyPredicateReadThenMatchingWriterCycle() throws Exception {
        var result = runPrhistAudit("empty-prrw-cycle", List.of(
                "{\"session\":1,\"txn\":1,\"kind\":\"writer\",\"status\":\"commit\",\"ops\":["
                        + "{\"type\":\"w\",\"key\":\"dep_y\",\"value\":1,\"semantic\":1,\"write_id\":30},"
                        + "{\"type\":\"w\",\"key\":\"inventory_onhand_x\",\"value\":101,\"semantic\":101,\"write_id\":31}]}",
                "{\"session\":0,\"txn\":0,\"kind\":\"reader\",\"status\":\"commit\",\"ops\":["
                        + "{\"type\":\"r\",\"key\":\"dep_y\",\"value\":1,\"semantic\":1,\"source_write_id\":30,\"source_txn\":1},"
                        + "{\"type\":\"pr\",\"predicate\":{\"kind\":\"inventory_threshold\",\"key_prefix\":\"inventory_onhand_x\",\"comparator\":\"ge\",\"threshold\":100},\"results\":[]}]}"));

        // Theory: WR T1->T0 on dep_y, and empty PR on x requires PR_RW
        // T0->T1 because T1 writes a matching x. The cycle is T1 -> T0 -> T1.
        assertEquals(-1, result.exitCode);
        assertTrue(result.stdout.contains("PR_RW key=inventory_onhand_x"),
                () -> "expected PR_RW edge in cycle witness, stdout was:\n" + result.stdout);
    }

    @Test
    void auditCli_rejectsPrhistInitialSourcePredicateRwCycle() throws Exception {
        var result = runPrhistAudit("init-prrw-cycle", "["
                + "{\"key\":\"inventory_onhand_x\",\"value\":130000001,\"semantic\":130,\"source_write_id\":41},"
                + "{\"key\":\"inventory_onhand_y\",\"value\":130000002,\"semantic\":130,\"source_write_id\":42},"
                + "{\"key\":\"inventory_onhand_z\",\"value\":130000003,\"semantic\":130,\"source_write_id\":43}"
                + "]", List.of(
                "{\"session\":0,\"txn\":0,\"kind\":\"inventory.t0\",\"status\":\"commit\",\"ops\":["
                        + "{\"type\":\"pr\",\"predicate\":{\"kind\":\"inventory_threshold\",\"key_prefix\":\"inventory_onhand_x\",\"comparator\":\"ge\",\"threshold\":100},\"results\":["
                        + "{\"key\":\"inventory_onhand_x\",\"value\":130000001,\"semantic\":130,\"source_write_id\":41,\"source_txn\":-1}]},"
                        + "{\"type\":\"w\",\"key\":\"inventory_onhand_z\",\"value\":400000003,\"semantic\":40,\"write_id\":44}]}",
                "{\"session\":1,\"txn\":1,\"kind\":\"inventory.t1\",\"status\":\"commit\",\"ops\":["
                        + "{\"type\":\"pr\",\"predicate\":{\"kind\":\"inventory_threshold\",\"key_prefix\":\"inventory_onhand_y\",\"comparator\":\"ge\",\"threshold\":100},\"results\":["
                        + "{\"key\":\"inventory_onhand_y\",\"value\":130000002,\"semantic\":130,\"source_write_id\":42,\"source_txn\":-1}]},"
                        + "{\"type\":\"w\",\"key\":\"inventory_onhand_x\",\"value\":400000001,\"semantic\":40,\"write_id\":45}]}",
                "{\"session\":2,\"txn\":2,\"kind\":\"inventory.t2\",\"status\":\"commit\",\"ops\":["
                        + "{\"type\":\"pr\",\"predicate\":{\"kind\":\"inventory_threshold\",\"key_prefix\":\"inventory_onhand_z\",\"comparator\":\"ge\",\"threshold\":100},\"results\":["
                        + "{\"key\":\"inventory_onhand_z\",\"value\":130000003,\"semantic\":130,\"source_write_id\":43,\"source_txn\":-1}]},"
                        + "{\"type\":\"w\",\"key\":\"inventory_onhand_y\",\"value\":400000002,\"semantic\":40,\"write_id\":46}]}"));

        assertEquals(-1, result.exitCode);
        assertTrue(result.stderr.contains("[[[[ REJECT ]]]]"));
    }

    @Test
    void auditCli_acceptsInitialReadWriteSkewCycle() throws Exception {
        var result = runAudit(List.of(
                "r(1,0,1,1)",
                "r(2,0,1,1)",
                "w(1,1,1,1)",
                "r(1,0,2,2)",
                "r(2,0,2,2)",
                "w(2,1,2,2)"
        ));

        assertEquals(0, result.exitCode);
        assertTrue(result.stderr.contains("[[[[ ACCEPT ]]]]"));
    }

    @Test
    void auditCli_acceptsManualPredicateFixtureUnderLatestVisibleSemantics() throws Exception {
        var result = runPrhistAudit("inline-manual-accept", "["
                + "{\"key\":\"inventory_onhand_A_0000\",\"value\":130000001,\"semantic\":130,\"source_write_id\":1},"
                + "{\"key\":\"inventory_onhand_A_0001\",\"value\":120000002,\"semantic\":120,\"source_write_id\":2}"
                + "]", List.of(
                "{\"session\":0,\"txn\":0,\"kind\":\"observe.before\",\"status\":\"commit\",\"ops\":["
                        + "{\"type\":\"pr\",\"predicate\":{\"kind\":\"inventory_threshold\",\"key_prefix\":\"inventory_onhand_A_\",\"comparator\":\"ge\",\"threshold\":100},\"results\":["
                        + "{\"key\":\"inventory_onhand_A_0000\",\"value\":130000001,\"semantic\":130,\"source_write_id\":1,\"source_txn\":-1},"
                        + "{\"key\":\"inventory_onhand_A_0001\",\"value\":120000002,\"semantic\":120,\"source_write_id\":2,\"source_txn\":-1}]}]}",
                "{\"session\":0,\"txn\":1,\"kind\":\"flip.a0\",\"status\":\"commit\",\"ops\":["
                        + "{\"type\":\"w\",\"key\":\"inventory_onhand_A_0000\",\"value\":400000003,\"semantic\":40,\"write_id\":3}]}",
                "{\"session\":0,\"txn\":2,\"kind\":\"observe.after\",\"status\":\"commit\",\"ops\":["
                        + "{\"type\":\"pr\",\"predicate\":{\"kind\":\"inventory_threshold\",\"key_prefix\":\"inventory_onhand_A_\",\"comparator\":\"ge\",\"threshold\":100},\"results\":["
                        + "{\"key\":\"inventory_onhand_A_0001\",\"value\":120000002,\"semantic\":120,\"source_write_id\":2,\"source_txn\":-1}]}]}"));

        // Latest-visible frontier semantics accepts this serial manual fixture.
        assertEquals(0, result.exitCode);
        assertTrue(result.stderr.contains("[[[[ ACCEPT ]]]]"),
                () -> "expected ACCEPT marker, stderr was:\n" + result.stderr);
    }

    @Test
    void auditCli_rejectsManualPredicatePrRwFixture() throws Exception {
        var result = runPrhistAudit("inline-manual-prrw-4cycle", "["
                + "{\"key\":\"inventory_onhand_A_0000\",\"value\":130000001,\"semantic\":130,\"source_write_id\":1},"
                + "{\"key\":\"inventory_onhand_B_0000\",\"value\":130000002,\"semantic\":130,\"source_write_id\":2},"
                + "{\"key\":\"inventory_onhand_C_0000\",\"value\":130000003,\"semantic\":130,\"source_write_id\":3},"
                + "{\"key\":\"inventory_onhand_D_0000\",\"value\":130000004,\"semantic\":130,\"source_write_id\":4}"
                + "]", List.of(
                "{\"session\":0,\"txn\":0,\"kind\":\"cycle.t0\",\"status\":\"commit\",\"ops\":["
                        + "{\"type\":\"pr\",\"predicate\":{\"kind\":\"inventory_threshold\",\"key_prefix\":\"inventory_onhand_A_\",\"comparator\":\"ge\",\"threshold\":100},\"results\":["
                        + "{\"key\":\"inventory_onhand_A_0000\",\"value\":130000001,\"semantic\":130,\"source_write_id\":1,\"source_txn\":-1}]},"
                        + "{\"type\":\"w\",\"key\":\"inventory_onhand_D_0000\",\"value\":400000005,\"semantic\":40,\"write_id\":5}]}",
                "{\"session\":1,\"txn\":1,\"kind\":\"cycle.t1\",\"status\":\"commit\",\"ops\":["
                        + "{\"type\":\"pr\",\"predicate\":{\"kind\":\"inventory_threshold\",\"key_prefix\":\"inventory_onhand_B_\",\"comparator\":\"ge\",\"threshold\":100},\"results\":["
                        + "{\"key\":\"inventory_onhand_B_0000\",\"value\":130000002,\"semantic\":130,\"source_write_id\":2,\"source_txn\":-1}]},"
                        + "{\"type\":\"w\",\"key\":\"inventory_onhand_A_0000\",\"value\":410000006,\"semantic\":41,\"write_id\":6}]}",
                "{\"session\":2,\"txn\":2,\"kind\":\"cycle.t2\",\"status\":\"commit\",\"ops\":["
                        + "{\"type\":\"pr\",\"predicate\":{\"kind\":\"inventory_threshold\",\"key_prefix\":\"inventory_onhand_C_\",\"comparator\":\"ge\",\"threshold\":100},\"results\":["
                        + "{\"key\":\"inventory_onhand_C_0000\",\"value\":130000003,\"semantic\":130,\"source_write_id\":3,\"source_txn\":-1}]},"
                        + "{\"type\":\"w\",\"key\":\"inventory_onhand_B_0000\",\"value\":420000007,\"semantic\":42,\"write_id\":7}]}",
                "{\"session\":3,\"txn\":3,\"kind\":\"cycle.t3\",\"status\":\"commit\",\"ops\":["
                        + "{\"type\":\"pr\",\"predicate\":{\"kind\":\"inventory_threshold\",\"key_prefix\":\"inventory_onhand_D_\",\"comparator\":\"ge\",\"threshold\":100},\"results\":["
                        + "{\"key\":\"inventory_onhand_D_0000\",\"value\":130000004,\"semantic\":130,\"source_write_id\":4,\"source_txn\":-1}]},"
                        + "{\"type\":\"w\",\"key\":\"inventory_onhand_C_0000\",\"value\":430000008,\"semantic\":43,\"write_id\":8}]}"));

        assertEquals(-1, result.exitCode);
        assertTrue(result.stderr.contains("[[[[ REJECT ]]]]"),
                () -> "expected REJECT marker, stderr was:\n" + result.stderr);
    }

    private CliResult runAudit(List<String> historyLines) throws Exception {
        var historyFile = tempDir.resolve("history.txt");
        var historyDir = writeTextHistoryAsPrhist(historyFile.getFileName().toString(), historyLines);

        return runAuditCommand("audit", "-t", "PRHIST", historyDir.toString());
    }

    private CliResult runPrhistAudit(String name, List<String> lines) throws Exception {
        return runPrhistAudit(name, "[]", lines);
    }

    private CliResult runPrhistAudit(String name, String initialStateJson, List<String> lines) throws Exception {
        var historyDir = writePrhist(name, initialStateJson, lines);
        return runAuditCommand("audit", "-t", "PRHIST", historyDir.toString());
    }

    private Path writePrhist(String name, String initialStateJson, List<String> lines) throws Exception {
        var historyDir = tempDir.resolve(name);
        Files.createDirectories(historyDir);
        Files.writeString(historyDir.resolve("initial_state.json"), compactInitialState(initialStateJson));
        Files.write(historyDir.resolve("history.prhist.jsonl"), compactTransactions(lines));
        return historyDir;
    }

    private Path writeTextHistoryAsPrhist(String name, List<String> lines) throws Exception {
        var pattern = Pattern.compile("(r|w)\\((\\d+),(\\d+),(\\d+),(\\d+)\\)");
        var keys = new LinkedHashSet<String>();
        var txns = new LinkedHashMap<Long, List<String>>();
        var txnSessions = new LinkedHashMap<Long, Long>();
        var initialWriteIds = new LinkedHashMap<String, Long>();
        var latestWriteIdByKeyValue = new LinkedHashMap<String, Long>();
        long nextWriteId = 1L;

        for (var line : lines) {
            var match = pattern.matcher(line);
            if (!match.matches()) {
                throw new IllegalArgumentException("Invalid inline history line: " + line);
            }
            var op = match.group(1);
            var key = match.group(2);
            var value = Long.parseLong(match.group(3));
            var session = Long.parseLong(match.group(4));
            var txn = Long.parseLong(match.group(5));

            if (keys.add(key)) {
                var writeId = nextWriteId++;
                initialWriteIds.put(key, writeId);
                latestWriteIdByKeyValue.put(keyValue(key, 0L), writeId);
            }

            txnSessions.putIfAbsent(txn, session);
            var txnOps = txns.computeIfAbsent(txn, ignored -> new ArrayList<>());
            if ("w".equals(op)) {
                var writeId = nextWriteId++;
                latestWriteIdByKeyValue.put(keyValue(key, value), writeId);
                txnOps.add(String.format(
                        "{\"type\":\"w\",\"key\":\"%s\",\"value\":%d}",
                        key, value));
            } else {
                var sourceWriteId = latestWriteIdByKeyValue.get(keyValue(key, value));
                if (sourceWriteId == null) {
                    txnOps.add(String.format(
                            "{\"type\":\"r\",\"key\":\"%s\",\"value\":%d}",
                            key, value));
                } else {
                    txnOps.add(String.format(
                            "{\"type\":\"r\",\"key\":\"%s\",\"value\":%d}",
                            key, value));
                }
            }
        }

        var initialRows = new ArrayList<String>();
        for (var entry : initialWriteIds.entrySet()) {
            initialRows.add(String.format(
                    "{\"key\":\"%s\",\"value\":0}",
                    entry.getKey()));
        }

        var txnLines = new ArrayList<String>();
        for (var entry : txns.entrySet()) {
            var txn = entry.getKey();
            txnLines.add(String.format(
                    "{\"session\":%d,\"txn\":%d,\"kind\":\"inline.basic\",\"status\":\"commit\",\"ops\":[%s]}",
                    txnSessions.get(txn), txn, String.join(",", entry.getValue())));
        }

        return writePrhist(name, "[" + String.join(",", initialRows) + "]", txnLines);
    }

    private static String compactInitialState(String initialStateJson) throws Exception {
        var rows = requiredArray(MAPPER.readTree(initialStateJson), "initial_state.json");
        var compactRows = MAPPER.createArrayNode();
        for (var row : rows) {
            compactRows.add(compactTuple(row));
        }
        return MAPPER.writeValueAsString(compactRows);
    }

    private static List<String> compactTransactions(List<String> lines) throws Exception {
        var compactLines = new ArrayList<String>();
        for (var line : lines) {
            var txn = requiredObject(MAPPER.readTree(line), "transaction");
            var compactTxn = MAPPER.createObjectNode();
            copyIfPresent(txn, compactTxn, "session");
            copyIfPresent(txn, compactTxn, "session_seq");
            copyIfPresent(txn, compactTxn, "txn");
            copyIfPresent(txn, compactTxn, "kind");
            copyIfPresent(txn, compactTxn, "status");

            var compactOps = MAPPER.createArrayNode();
            for (var op : requiredArray(txn.path("ops"), "ops")) {
                compactOps.add(compactOperation(op));
            }
            compactTxn.set("ops", compactOps);
            compactLines.add(MAPPER.writeValueAsString(compactTxn));
        }
        return compactLines;
    }

    private static ObjectNode compactOperation(JsonNode op) {
        var type = text(op, "type");
        var compact = MAPPER.createObjectNode();
        compact.put("type", type);
        switch (type) {
        case "w":
        case "r":
            compact.put("key", text(op, "key"));
            compact.put("value", compactValue(op));
            return compact;
        case "pr":
            compact.set("query", op.has("query") ? op.path("query").deepCopy()
                    : predicateToQuery(requiredObject(op.path("predicate"), "predicate")));
            compact.set("result", predicateResult(op));
            return compact;
        default:
            throw new IllegalArgumentException("unknown op type: " + type);
        }
    }

    private static ObjectNode predicateToQuery(JsonNode predicate) {
        var query = MAPPER.createObjectNode();
        var from = MAPPER.createObjectNode();
        from.put("relation", "kv");
        query.set("from", from);
        var select = MAPPER.createObjectNode();
        var columns = MAPPER.createArrayNode();
        columns.add("k");
        columns.add("value");
        select.set("columns", columns);
        select.put("distinct", false);
        query.set("select", select);

        var where = MAPPER.createArrayNode();
        where.add(predicateClause(predicate));
        query.set("where", where);
        return query;
    }

    private static String predicateClause(JsonNode predicate) {
        var kind = text(predicate, "kind");
        if ("inventory_threshold".equals(kind)) {
            var comparator = text(predicate, "comparator");
            var threshold = predicate.path("threshold").asLong();
            switch (comparator) {
            case "ge":
                return "value > " + (threshold - 1);
            case "gt":
                return "value > " + threshold;
            case "le":
                return "value < " + (threshold + 1);
            case "lt":
                return "value < " + threshold;
            case "eq":
                return "value = " + threshold;
            default:
                throw new IllegalArgumentException("unsupported comparator: " + comparator);
            }
        }
        throw new IllegalArgumentException("unsupported predicate kind: " + kind);
    }

    private static ObjectNode predicateResult(JsonNode op) {
        var result = MAPPER.createObjectNode();
        var inputs = MAPPER.createArrayNode();
        if (op.has("result")) {
            for (var input : requiredArray(op.path("result").path("inputs"), "result.inputs")) {
                inputs.add(compactTuple(input));
            }
        } else {
            for (var input : requiredArray(op.path("results"), "results")) {
                inputs.add(compactTuple(input));
            }
        }
        result.set("inputs", inputs);
        result.set("values", MAPPER.createArrayNode());
        return result;
    }

    private static ObjectNode compactTuple(JsonNode tuple) {
        var compact = MAPPER.createObjectNode();
        compact.put("key", text(tuple, "key"));
        compact.put("value", compactValue(tuple));
        return compact;
    }

    private static long compactValue(JsonNode node) {
        var semantic = node.get("semantic");
        if (semantic != null && semantic.canConvertToLong()) {
            return semantic.asLong();
        }
        return node.path("value").asLong();
    }

    private static ArrayNode requiredArray(JsonNode node, String context) {
        if (node == null || !node.isArray()) {
            throw new IllegalArgumentException(context + " must be an array");
        }
        return (ArrayNode) node;
    }

    private static ObjectNode requiredObject(JsonNode node, String context) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(context + " must be an object");
        }
        return (ObjectNode) node;
    }

    private static String text(JsonNode node, String fieldName) {
        var value = node.get(fieldName);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("missing text field: " + fieldName);
        }
        return value.asText();
    }

    private static void copyIfPresent(ObjectNode source, ObjectNode target, String fieldName) {
        if (source.has(fieldName)) {
            target.set(fieldName, source.get(fieldName));
        }
    }

    private static String keyValue(String key, long value) {
        return key + "\u0000" + value;
    }

    private void assertOptionMatrix(Path historyPath, String type, int expectedExitCode) throws Exception {
        var solvers = List.of("monosat");
        var optionSets = List.of(
                List.<String>of(),
                List.of("--no-pruning"),
                List.of("--no-coalescing"),
                List.of("--no-pruning", "--no-coalescing"));

        for (var solver : solvers) {
            for (var options : optionSets) {
                var args = new java.util.ArrayList<String>();
                args.add("audit");
                args.add("-t");
                args.add(type);
                args.add("--solver");
                args.add(solver);
                args.addAll(options);
                args.add(historyPath.toString());

                var result = runAuditCommand(args);
                assertEquals(expectedExitCode, result.exitCode,
                        () -> String.format("solver=%s options=%s stderr:%n%s%nstdout:%n%s",
                                solver, options, result.stderr, result.stdout));
                assertTrue(result.stderr.contains(expectedExitCode == 0 ? "[[[[ ACCEPT ]]]]" : "[[[[ REJECT ]]]]"),
                        () -> String.format("solver=%s options=%s stderr:%n%s", solver, options, result.stderr));
            }
        }
    }

    private CliResult runAuditCommand(List<String> args) throws Exception {
        return runAuditCommand(args.toArray(new String[0]));
    }

    private CliResult runAuditCommand(String... args) throws Exception {
        Pruning.setEnablePruning(true);
        SIVerifier.setCoalesceConstraints(true);
        SIVerifier.setDotOutput(false);
        SIVerifier.setCompareDerivedPredicateEdges(false);

        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        var oldOut = System.out;
        var oldErr = System.err;

        try {
            System.setOut(new PrintStream(stdout, true));
            System.setErr(new PrintStream(stderr, true));

            var cmd = new CommandLine(new Main());
            cmd.setCaseInsensitiveEnumValuesAllowed(true);
            int exitCode = cmd.execute(args);
            return new CliResult(exitCode, stdout.toString(), stderr.toString());
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }

    private static class CliResult {
        private final int exitCode;
        private final String stdout;
        private final String stderr;

        private CliResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }
}
