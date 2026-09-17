package verifier;

import graph.KnownGraph;
import history.History;
import history.Transaction;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SERTimeoutPropagationTest {
    @Test
    void mockSolverTimeoutReturnsTimeoutWithoutConflictExtraction() {
        var history = committedTxn();
        var graph = new KnownGraph<>(history);
        var settings = SERVerifier.SolverSettings.forModes(
                SERVerifier.PredicateSolvingMode.EAGER,
                SERVerifier.PruningMode.NONE);
        settings.solverTimeoutSeconds = 1;
        settings.satSolveBackend = (solver, remaining, assumptions) -> Optional.empty();

        var ar = new SERSolverAR<>(history, graph, List.of(), true, false, settings);
        assertEquals(SolveStatus.TIMEOUT, ar.solve());
        assertTrue(ar.timedOut());
        var conflicts = ar.getConflicts();
        assertTrue(conflicts.getLeft().isEmpty());
        assertTrue(conflicts.getRight().isEmpty());
    }

    @Test
    void verifierTimeoutDoesNotEmitUnsatConflictExtraction() {
        var history = committedTxn();
        var settings = SERVerifier.SolverSettings.forModes(
                SERVerifier.PredicateSolvingMode.EAGER,
                SERVerifier.PruningMode.NONE);
        settings.solverTimeoutSeconds = 1;
        settings.satSolveBackend = (solver, remaining, assumptions) -> Optional.empty();

        var stderr = new ByteArrayOutputStream();
        var oldErr = System.err;
        try {
            System.setErr(new PrintStream(stderr, true));
            var result = new SERVerifier<String, Integer>(() -> history, settings, false).audit();
            assertEquals(SERVerifier.AuditResult.TIMEOUT, result);
        } finally {
            System.setErr(oldErr);
        }
        var text = stderr.toString();
        assertFalse(text.contains("[SER] Reject reason:"));
        assertFalse(text.contains("[[[[ REJECT ]]]]"));
        assertFalse(text.contains("timeout-scope=solver"));
    }

    @Test
    void mainMapsTimeoutToMarkerAndExit124() throws Exception {
        assertEquals(124, SERVerifier.AuditResult.TIMEOUT.exitCode);
        assertEquals("[[[[ TIMEOUT ]]]]", SERVerifier.AuditResult.TIMEOUT.marker);
        assertEquals(0, SERVerifier.AuditResult.ACCEPT.exitCode);
        assertEquals(-1, SERVerifier.AuditResult.REJECT.exitCode);

        var historyDir = Files.createTempDirectory("ser-timeout-cli");
        Files.writeString(historyDir.resolve("initial_state.json"), "[]");
        Files.writeString(historyDir.resolve("history.prhist.jsonl"),
                "{\"session\":1,\"session_seq\":1,\"txn\":1,"
                        + "\"status\":\"commit\",\"ops\":[{\"type\":\"w\","
                        + "\"key\":\"x\",\"value\":1}]}");

        var settingsHook = SERVerifier.SolverSettings.forModes(
                SERVerifier.PredicateSolvingMode.EAGER,
                SERVerifier.PruningMode.NONE);
        settingsHook.satSolveBackend = (solver, remaining, assumptions) -> Optional.empty();
        settingsHook.solverTimeoutSeconds = 1;

        var stderr = new ByteArrayOutputStream();
        var stdout = new ByteArrayOutputStream();
        var oldErr = System.err;
        var oldOut = System.out;
        try {
            System.setErr(new PrintStream(stderr, true));
            System.setOut(new PrintStream(stdout, true));
            var verifier = new SERVerifier<>(
                    new history.loaders.PredicateHistoryLoader(historyDir),
                    settingsHook, false);
            var result = verifier.audit();
            System.err.println(result.marker);
            assertEquals(SERVerifier.AuditResult.TIMEOUT, result);
            assertEquals(124, result.exitCode);
        } finally {
            System.setErr(oldErr);
            System.setOut(oldOut);
        }
        assertTrue(stderr.toString().contains("[[[[ TIMEOUT ]]]]"));
        assertFalse(stderr.toString().contains("[[[[ REJECT ]]]]"));
    }

    @Test
    void timeoutIsNotAcceptOrReject() {
        assertFalse(SERVerifier.AuditResult.TIMEOUT == SERVerifier.AuditResult.ACCEPT);
        assertFalse(SERVerifier.AuditResult.TIMEOUT == SERVerifier.AuditResult.REJECT);
    }

    private static History<String, Integer> committedTxn() {
        var history = new History<String, Integer>();
        history.addTransaction(history.addSession(1L), 1L)
                .setStatus(Transaction.TransactionStatus.COMMIT);
        return history;
    }
}
