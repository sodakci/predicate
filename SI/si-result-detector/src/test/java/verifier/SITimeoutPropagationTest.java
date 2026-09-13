package verifier;

import graph.KnownGraph;
import history.History;
import history.Transaction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SITimeoutPropagationTest {
    @Test
    void mockSolverTimeoutIsNotReportedAsUnsat() {
        var history = committedTxn();
        var graph = new KnownGraph<>(history);
        var settings = SIVerifier.SolverSettings.defaults(
                SIVerifier.PruningMode.NONE);
        settings.solverTimeoutSeconds = 1;
        var observedRemaining = new int[1];
        settings.satSolveBackend = (solver, remaining) -> {
            observedRemaining[0] = remaining;
            return Optional.empty();
        };

        var si = new SISolverInduced<>(
                history, graph, List.of(), true, false, settings);
        assertEquals(SolveStatus.TIMEOUT, si.solveStatus());
        assertEquals(1, observedRemaining[0]);
        assertTrue(si.timedOut());
        assertTrue(si.getConflicts().getLeft().isEmpty());
        assertTrue(si.getConflicts().getRight().isEmpty());
    }

    @Test
    void verifierAndCliResultKeepTimeoutDistinct() {
        var settings = SIVerifier.SolverSettings.defaults(
                SIVerifier.PruningMode.NONE);
        settings.solverTimeoutSeconds = 1;
        settings.satSolveBackend = (solver, remaining) -> Optional.empty();

        var result = new SIVerifier<String, Integer>(
                () -> committedTxn(), settings, false).auditResult();
        assertEquals(SIVerifier.AuditResult.TIMEOUT, result);
        assertEquals(124, result.exitCode);
        assertEquals("[[[[ TIMEOUT ]]]]", result.marker);
        assertFalse(result == SIVerifier.AuditResult.ACCEPT);
        assertFalse(result == SIVerifier.AuditResult.REJECT);
    }

    private static History<String, Integer> committedTxn() {
        var history = new History<String, Integer>();
        history.addTransaction(history.addSession(1L), 1L)
                .setStatus(Transaction.TransactionStatus.COMMIT);
        return history;
    }
}
