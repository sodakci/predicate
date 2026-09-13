package verifier;

import history.History;
import history.Transaction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InitialCoverageTest {
    @Test
    void missingKeysOnBottomTxnAreSynthesizedAsAbsent() {
        var history = new History<String, Integer>();
        var init = history.addTransaction(history.addSession(-1L), -1L);
        init.setStatus(Transaction.TransactionStatus.COMMIT);
        var txn = history.addTransaction(history.addSession(1L), 1L);
        history.addWriteEvent(txn, "x", 10, null);
        txn.setStatus(Transaction.TransactionStatus.COMMIT);

        assertTrue(history.missingInitialKeys().contains("x"));
        assertTrue(history.ensureInitialVersions().contains("x"));
        assertTrue(history.missingInitialKeys().isEmpty());
        assertNull(init.getEvents().stream()
                .filter(event -> "x".equals(event.getKey()))
                .findFirst().orElseThrow().getValue());
    }
}
