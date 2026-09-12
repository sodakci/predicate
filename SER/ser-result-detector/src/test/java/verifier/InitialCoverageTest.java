package verifier;

import history.History;
import history.Transaction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InitialCoverageTest {
    @Test
    void missingKeysOnBottomTxnAreSynthesizedAsAbsent() {
        var history = new History<String, Integer>();
        var init = history.addTransaction(history.addSession(-1L), -1L);
        init.setStatus(Transaction.TransactionStatus.COMMIT);
        var txn = history.addTransaction(history.addSession(1L), 1L);
        history.addWriteEvent(txn, "x", 10, 1L);
        txn.setStatus(Transaction.TransactionStatus.COMMIT);

        assertTrue(history.missingInitialKeys().contains("x"));
        var synthesized = history.ensureInitialVersions();
        assertTrue(synthesized.contains("x"));
        assertTrue(history.missingInitialKeys().isEmpty());

        var bottomWrite = init.getEvents().stream()
                .filter(event -> "x".equals(event.getKey()))
                .findFirst()
                .orElseThrow();
        assertEquals(null, bottomWrite.getValue());
    }
}
