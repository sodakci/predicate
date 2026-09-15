import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import verifier.Pruning;
import verifier.SERVerifier;

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

class BlackBoxSERAuditTest {
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
        assertTrue(result.stderr.contains("SER audit result: ACCEPT"),
                () -> "expected ACCEPT marker, stderr was:\n" + result.stderr);
        assertTrue(result.stderr.contains(
                "History\nTransactions: 2 | Events: 2 | Predicates: 0"),
                () -> "stderr was:\n" + result.stderr);
        assertTrue(result.stderr.contains("WW\n1 -> 1"));
        assertTrue(result.stderr.contains("Predicate\nGenerated PR_WR/PR_RW:"));
        assertTrue(result.stderr.contains(" attempts -> "));
        assertTrue(result.stderr.contains(" remaining\nFixed PR_WR/PR_RW:"));
        assertTrue(result.stderr.contains("\nAll logical PR edges:"));
        assertTrue(result.stderr.contains(" physical edges\nSkipped:"));
        assertTrue(result.stderr.contains("SAT\nVariables:"));
        assertTrue(result.stderr.contains("Timing\nWW:"));
        assertTrue(result.stderr.contains("Peak memory:"));
        assertTrue(result.stderr.contains("\nGMWR\n"));
        assertTrue(result.stderr.contains("GMWR-WW reduced:"));
        assertFalse(result.stderr.contains("ENTIRE_EXPERIMENT:"));
        assertFalse(result.stderr.contains("Pruning round"));
        assertFalse(result.stderr.contains("post-check"));
        assertTrue(result.stderr.stripTrailing().endsWith("SER audit result: ACCEPT"));
    }

    @Test
    void auditCli_exposesOnlyPublicAuditOptionsInHelp() throws Exception {
        var result = runAuditCommand("audit", "--help");
        var help = result.stdout + result.stderr;

        assertEquals(0, result.exitCode);
        assertTrue(help.contains("--predicate-encoding"));
        assertTrue(help.contains("--solver-timeout-seconds"));
        assertTrue(help.contains("--solver-stats"));
        assertFalse(help.contains("--predicate-mode"));
        assertFalse(help.contains("--ser-propagation-mode"));
        assertFalse(help.contains("--gmwr-prepropagation"));
        assertFalse(help.contains("--predicate-witness-coalescing"));
        assertFalse(help.contains("--graph-edge-interning"));
        assertFalse(help.contains("--ww-pruning"));
        assertFalse(help.contains("--no-coalescing"));
        assertFalse(help.contains("--dot-output"));
        assertFalse(help.contains("--solver="));
    }

    @Test
    void auditCli_rejectsNonSerializableHistory() throws Exception {
        var result = runAudit(List.of(
                "r(1,0,1,1)",
                "r(2,0,1,1)",
                "w(1,1,1,1)",
                "r(1,0,2,2)",
                "r(2,0,2,2)",
                "w(2,1,2,2)"
        ));

        assertEquals(-1, result.exitCode);
        assertTrue(result.stderr.contains("SER audit result: REJECT"),
                () -> "expected REJECT marker, stderr was:\n" + result.stderr);
        assertTrue(result.stderr.contains("[SER] Reject reason:"),
                () -> "expected rejection reason, stderr was:\n" + result.stderr);
        assertTrue(result.stdout.isEmpty(),
                () -> "reject diagnostics should stay on stderr, stdout was:\n" + result.stdout);
        assertTrue(result.stderr.stripTrailing().endsWith("SER audit result: REJECT"));
    }

    @Test
    void auditCli_reportsErrorsWithFinalVerdict() throws Exception {
        var missing = tempDir.resolve("missing-history");

        var result = runAuditCommand("audit", missing.toString());

        assertEquals(1, result.exitCode);
        assertTrue(result.stderr.contains("[SER] Error:"));
        assertTrue(result.stderr.stripTrailing().endsWith("SER audit result: ERROR"));
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

        var result = runAuditCommand("audit", historyDir.toString());

        assertEquals(-1, result.exitCode);
        assertTrue(result.stderr.contains("[SER] Conflict clause:"),
                () -> "expected direct MonoSAT assumption conflict, stderr was:\n" + result.stderr);
        assertTrue(result.stderr.contains("[PREDICATE_OBLIGATION]")
                        || result.stderr.contains("[GMWR_RULE]"),
                () -> "expected predicate/GMWR assumption reason, stderr was:\n"
                        + result.stderr);
        assertFalse(result.stderr.contains("Conditional AR implications:"),
                () -> "legacy AR implication count should not be printed, stderr was:\n" + result.stderr);
        assertTrue(result.stdout.isEmpty());
    }

    @Test
    void auditCli_enablesPredicateDependencyPruningOnlyWhenRequested() throws Exception {
        var historyDir = writePrhist("predicate-pruning-mode", "[]", List.of(
                "{\"session\":0,\"txn\":0,\"kind\":\"writer\",\"status\":\"commit\",\"ops\":["
                        + "{\"type\":\"w\",\"key\":\"inventory_onhand_x\",\"value\":400000001,\"semantic\":40,\"write_id\":1},"
                        + "{\"type\":\"w\",\"key\":\"inventory_onhand_y\",\"value\":400000002,\"semantic\":40,\"write_id\":2}]}",
                "{\"session\":1,\"txn\":1,\"kind\":\"reader\",\"status\":\"commit\",\"ops\":["
                        + "{\"type\":\"pr\",\"predicate\":{\"kind\":\"inventory_threshold\",\"key_prefix\":\"inventory_onhand_\",\"comparator\":\"ge\",\"threshold\":100},\"results\":[]}]}"));

        var disabled = runAuditCommand("audit", "--solver-stats",
                "--predicate-encoding=eager", "--no-predicate-witness-coalescing",
                historyDir.toString());
        var enabled = runAuditCommand("audit", "--solver-stats",
                "--predicate-encoding=GMWR", historyDir.toString());

        assertEquals(disabled.exitCode, enabled.exitCode);
        assertTrue(disabled.stderr.contains("predicate-encoding=eager"));
        assertTrue(disabled.stderr.contains("ser-propagation-mode=ww-only"));
        assertTrue(disabled.stderr.contains("gmwr-prepropagation=false"));
        assertTrue(disabled.stderr.contains("graph-edge-interning=true"));
        assertFalse(disabled.stderr.contains("Predicate dependency prune:"));
        assertTrue(enabled.stderr.contains("predicate-encoding=gmwr"));
        assertTrue(enabled.stderr.contains("\nGMWR\n"));
        assertTrue(enabled.stderr.contains("GMWR-WW reduced:"));
        assertTrue(enabled.stderr.indexOf("Timing\n")
                        < enabled.stderr.indexOf("ENTIRE_EXPERIMENT:"));
        assertTrue(enabled.stderr.stripTrailing().endsWith(
                "SER audit result: " + (enabled.exitCode == 0 ? "ACCEPT" : "REJECT")));
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
        assertTrue(result.stderr.contains("SER audit result: ACCEPT"),
                () -> "expected ACCEPT marker, stderr was:\n" + result.stderr);
    }

    @Test
    void auditCli_defaultSolverIsMonosat() throws Exception {
        var historyDir = writeTextHistoryAsPrhist("default-monosat-history", List.of(
                "w(1,1,1,1)",
                "r(1,1,2,2)"));

        var result = runAuditCommand("audit", "--solver-stats",
                historyDir.toString());

        assertEquals(0, result.exitCode);
        assertTrue(result.stderr.contains("[[[[ ACCEPT ]]]]"));
        assertTrue(result.stderr.contains("backend=monosat"), () -> "stderr was:\n" + result.stderr);
        assertTrue(result.stderr.contains("predicate-encoding=gmwr"));
        assertTrue(result.stderr.contains("ser-propagation-mode=ww-gmwr"));
        assertTrue(result.stderr.contains("gmwr-prepropagation=true"));
        assertTrue(result.stderr.contains("predicate-witness-coalescing=true"));
        assertTrue(result.stderr.contains("graph-edge-interning=true"));
    }

    @Test
    void auditCliSupportsNoneAndReachabilityWwPruning() throws Exception {
        var historyDir = writeTextHistoryAsPrhist("prun-pruning-history", List.of(
                "w(1,1,1,1)",
                "w(1,2,2,2)",
                "w(2,2,2,2)",
                "r(1,1,3,3)",
                "r(2,2,3,3)"));

        var reachability = runAuditCommand("audit", "--ww-pruning=REACHABILITY",
                historyDir.toString());
        var none = runAuditCommand("audit", "--ww-pruning=NONE",
                historyDir.toString());

        assertEquals(reachability.exitCode, none.exitCode);
        assertEquals(0, reachability.exitCode);
        assertEquals(0, none.exitCode);
        assertFalse(reachability.stderr.contains("Pruning round"),
                () -> "stderr was:\n" + reachability.stderr);
        assertFalse(none.stderr.contains("Pruning round"),
                () -> "stderr was:\n" + none.stderr);
    }

    @Test
    void auditCli_skipsPruningWhenThereAreNoWwConstraints() throws Exception {
        var historyDir = writeTextHistoryAsPrhist("no-ww-constraints", List.of(
                "r(1,0,1,1)"));

        for (var mode : List.of("REACHABILITY", "NONE")) {
            var result = runAuditCommand("audit", "--ww-pruning=" + mode,
                    historyDir.toString());

            assertEquals(0, result.exitCode);
            assertTrue(result.stderr.contains("WW\n0 -> 0"),
                    () -> "stderr was:\n" + result.stderr);
            assertFalse(result.stderr.contains("pruning round"),
                    () -> "stderr was:\n" + result.stderr);
            assertFalse(result.stderr.contains("post-check"),
                    () -> "stderr was:\n" + result.stderr);
        }
    }

    @Test
    void auditCli_monosatRejectsSmallRejectCase() throws Exception {
        var historyDir = writeTextHistoryAsPrhist("reject-history", List.of(
                "r(1,0,1,1)",
                "r(2,0,1,1)",
                "w(1,1,1,1)",
                "r(1,0,2,2)",
                "r(2,0,2,2)",
                "w(2,1,2,2)"));

        var monosat = runAuditCommand("audit", historyDir.toString());
        var withoutWwPruning = runAuditCommand("audit",
                "--ww-pruning=NONE", historyDir.toString());

        assertEquals(-1, monosat.exitCode);
        assertEquals(monosat.exitCode, withoutWwPruning.exitCode);
        assertTrue(monosat.stderr.contains("SER audit result: REJECT"));
        assertTrue(withoutWwPruning.stderr.contains("SER audit result: REJECT"));
    }

    @Test
    void auditCli_basicPrhistOptionMatrixPreservesAcceptRejectResults() throws Exception {
        var acceptHistory = writeTextHistoryAsPrhist("matrix-accept", List.of(
                "w(1,1,1,1)",
                "r(1,1,2,2)"));

        var rejectHistory = writeTextHistoryAsPrhist("matrix-reject", List.of(
                "r(1,0,1,1)",
                "r(2,0,1,1)",
                "w(1,1,1,1)",
                "r(1,0,2,2)",
                "r(2,0,2,2)",
                "w(2,1,2,2)"));

        assertOptionMatrix(acceptHistory, 0);
        assertOptionMatrix(rejectHistory, -1);
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

        assertOptionMatrix(acceptHistory, 0);
        assertOptionMatrix(rejectHistory, -1);
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
        assertTrue(result.stderr.contains("[SER] Reject reason:"));
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
        assertTrue(result.stderr.contains("SER audit result: REJECT"),
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
        assertTrue(result.stderr.contains("[SER] Reject reason:"));
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

        // Theory: T_bottom supplies matching initial x/y/z. Each real txn reads
        // one initial matching tuple and writes another key to a non-matching
        // value, producing PR_RW T0->T1, T1->T2, T2->T0.
        assertEquals(-1, result.exitCode);
        assertTrue(result.stderr.contains("[SER] Reject reason:"));
    }

    @Test
    void auditCli_rejectsInitialReadWriteSkewCycle() throws Exception {
        var result = runAudit(List.of(
                "r(1,0,1,1)",
                "r(2,0,1,1)",
                "w(1,1,1,1)",
                "r(1,0,2,2)",
                "r(2,0,2,2)",
                "w(2,1,2,2)"
        ));

        // Theory: T_bottom -> T1/T2 by WW on x/y. RW T1->T2 on y and
        // RW T2->T1 on x form a classic SER cycle.
        assertEquals(-1, result.exitCode);
        assertTrue(result.stderr.contains("[SER] Reject reason:"));
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
        assertTrue(result.stderr.contains("SER audit result: ACCEPT"),
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

        // Theory: the manual reject history contains a four-transaction
        // predicate anti-dependency cycle.
        assertEquals(-1, result.exitCode);
        assertTrue(result.stderr.contains("SER audit result: REJECT"),
                () -> "expected REJECT marker, stderr was:\n" + result.stderr);
        assertTrue(result.stderr.contains("[SER] Reject reason:"));
    }

    private CliResult runAudit(List<String> historyLines) throws Exception {
        var historyFile = tempDir.resolve("history.txt");
        var historyDir = writeTextHistoryAsPrhist(historyFile.getFileName().toString(), historyLines);

        return runAuditCommand("audit", historyDir.toString());
    }

    private CliResult runPrhistAudit(String name, List<String> lines) throws Exception {
        return runPrhistAudit(name, "[]", lines);
    }

    private CliResult runPrhistAudit(String name, String initialStateJson, List<String> lines) throws Exception {
        var historyDir = writePrhist(name, initialStateJson, lines);
        return runAuditCommand("audit", historyDir.toString());
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
        var lastSessionSeq = new LinkedHashMap<Long, Long>();
        for (var line : lines) {
            var txn = requiredObject(MAPPER.readTree(line), "transaction");
            var compactTxn = MAPPER.createObjectNode();
            copyIfPresent(txn, compactTxn, "session");
            long session = txn.path("session").asLong();
            long sessionSeq = txn.has("session_seq")
                    ? txn.path("session_seq").asLong()
                    : lastSessionSeq.getOrDefault(session, 0L) + 1L;
            compactTxn.put("session_seq", sessionSeq);
            lastSessionSeq.merge(session, sessionSeq, Math::max);
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
        var values = MAPPER.createArrayNode();
        var sourceInputs = op.has("result")
                ? requiredArray(op.path("result").path("inputs"), "result.inputs")
                : requiredArray(op.path("results"), "results");
        for (var input : sourceInputs) {
            var compactInput = compactTuple(input);
            inputs.add(compactInput);
            var projected = MAPPER.createObjectNode();
            var key = compactInput.path("key").asText();
            var separator = key.indexOf(':');
            projected.put("k", separator < 0 ? key : key.substring(separator + 1));
            projected.set("value", compactInput.path("value"));
            values.add(projected);
        }
        result.set("inputs", inputs);
        result.set("values", values);
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

    private void assertOptionMatrix(Path historyPath, int expectedExitCode) throws Exception {
        var optionSets = List.of(
                List.<String>of(),
                List.of("--ww-pruning=none"));

        for (var options : optionSets) {
            var args = new java.util.ArrayList<String>();
            args.add("audit");
            args.addAll(options);
            args.add(historyPath.toString());

            var result = runAuditCommand(args);
            assertEquals(expectedExitCode, result.exitCode,
                    () -> String.format("options=%s stderr:%n%s%nstdout:%n%s",
                            options, result.stderr, result.stdout));
            assertTrue(result.stderr.contains(expectedExitCode == 0
                            ? "SER audit result: ACCEPT"
                            : "SER audit result: REJECT"),
                    () -> String.format("options=%s stderr:%n%s", options, result.stderr));
        }
    }

    private CliResult runAuditCommand(List<String> args) throws Exception {
        return runAuditCommand(args.toArray(new String[0]));
    }

    private CliResult runAuditCommand(String... args) throws Exception {
        Pruning.setEnablePruning(true);
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
