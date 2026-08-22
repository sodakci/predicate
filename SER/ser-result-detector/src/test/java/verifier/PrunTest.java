package verifier;

import graph.KnownGraph;
import graph.EdgeType;
import history.History;
import history.Transaction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static history.Event.EventType.READ;
import static history.Event.EventType.WRITE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrunTest {
    @Test
    void prunPruningMaterializesForcedConstraintSide() {
        var history = new History<String, Integer>();
        var source = addTransaction(history, 1L);
        var visibleCompetitor = addTransaction(history, 2L);
        var reader = addTransaction(history, 3L);
        history.addEvent(source, WRITE, "x", 1);
        history.addEvent(visibleCompetitor, WRITE, "x", 2);
        history.addEvent(visibleCompetitor, WRITE, "y", 2);
        history.addEvent(reader, READ, "x", 1);
        history.addEvent(reader, READ, "y", 2);
        commitAll(history);

        var graph = new KnownGraph<>(history);
        var constraints = new ArrayList<>(
                SERVerifier.generateConstraintsSER(history, graph));
        assertEquals(1, constraints.size());

        var result = Prun.prune(
                history, graph, constraints);

        assertEquals(1, result.crossKeyForcedOrders);
        assertTrue(constraints.isEmpty());
        assertTrue(hasWw(graph, visibleCompetitor, source, "x"));
    }

    @Test
    void sharedSnapshotProducesCrossKeyOrder() {
        var history = new History<String, Integer>();
        var t1 = addTransaction(history, 1L);
        var t2 = addTransaction(history, 2L);
        var reader = addTransaction(history, 3L);
        history.addEvent(t1, WRITE, "x", 1);
        history.addEvent(t2, WRITE, "x", 2);
        history.addEvent(t2, WRITE, "y", 2);
        history.addEvent(reader, READ, "x", 1);
        history.addEvent(reader, READ, "y", 2);
        commitAll(history);

        var graph = new KnownGraph<>(history);
        var constraints = new ArrayList<>(
                SERVerifier.generateConstraintsSER(history, graph));
        var result = Prun.pruneSnapshotOnly(history, graph, constraints);

        assertEquals(1, result.newForcedTransactionOrders);
        assertEquals(1, result.crossKeyForcedOrders);
        assertEquals(1, result.ordersCreatedBeyondInitialTc);
        assertEquals(0, result.reachabilityDerivedOrders);
        assertTrue(constraints.isEmpty());
        assertFalse(result.inconsistent);
    }

    @Test
    void snapshotOnlyDoesNotApplyReachabilityPruning() {
        var history = new History<String, Integer>();
        var session = history.addSession(1L);
        var competitor = history.addTransaction(session, 1L);
        var source = history.addTransaction(session, 2L);
        var reader = addTransaction(history, 3L);
        history.addEvent(competitor, WRITE, "x", 2);
        history.addEvent(source, WRITE, "x", 1);
        history.addEvent(reader, READ, "x", 1);
        commitAll(history);

        var snapshotGraph = new KnownGraph<>(history);
        var snapshotConstraints = new ArrayList<>(
                SERVerifier.generateConstraintsSER(history, snapshotGraph));
        assertEquals(1, snapshotConstraints.size());

        Prun.pruneSnapshotOnly(history, snapshotGraph, snapshotConstraints);

        assertEquals(1, snapshotConstraints.size());

        var combinedGraph = new KnownGraph<>(history);
        var combinedConstraints = new ArrayList<>(
                SERVerifier.generateConstraintsSER(history, combinedGraph));
        Prun.prune(history, combinedGraph, combinedConstraints);

        assertTrue(combinedConstraints.isEmpty());
    }

    @Test
    void forcedOrderPropagatesAcrossAnotherSnapshotInLaterRound() {
        var history = new History<String, Integer>();
        var t1 = addTransaction(history, 1L);
        var t2 = addTransaction(history, 2L);
        var firstReader = addTransaction(history, 3L);
        var t3 = addTransaction(history, 4L);
        var secondReader = addTransaction(history, 5L);

        history.addEvent(t1, WRITE, "x", 1);
        history.addEvent(t1, WRITE, "z", 1);
        history.addEvent(t2, WRITE, "x", 2);
        history.addEvent(t2, WRITE, "y", 2);
        history.addEvent(t2, WRITE, "q", 2);
        history.addEvent(firstReader, READ, "x", 1);
        history.addEvent(firstReader, READ, "y", 2);
        history.addEvent(t3, WRITE, "q", 3);
        history.addEvent(secondReader, READ, "z", 1);
        history.addEvent(secondReader, READ, "q", 3);
        commitAll(history);

        var graph = new KnownGraph<>(history);
        var constraints = new ArrayList<>(
                SERVerifier.generateConstraintsSER(history, graph));
        var result = Prun.prune(history, graph, constraints);

        assertEquals(2, result.newForcedTransactionOrders);
        assertEquals(2, result.crossKeyForcedOrders);
        assertEquals(2, result.propagationRounds);
        assertEquals(1, result.crossSnapshotDerivedOrders);
        assertEquals(3, result.ordersCreatedBeyondInitialTc);
        assertEquals(1, result.reachabilityDerivedOrders);
        assertFalse(result.inconsistent);
    }

    @Test
    void laterCompetingWriterIsForcedAfterReader() {
        var history = new History<String, Integer>();
        var session = history.addSession(1L);
        var source = history.addTransaction(session, 1L);
        var competitor = history.addTransaction(session, 2L);
        var reader = addTransaction(history, 3L);
        history.addEvent(source, WRITE, "x", 1);
        history.addEvent(competitor, WRITE, "x", 2);
        history.addEvent(reader, READ, "x", 1);
        commitAll(history);

        var graph = new KnownGraph<>(history);
        var constraints = new ArrayList<>(
                SERVerifier.generateConstraintsSER(history, graph));
        var result = Prun.prune(history, graph, constraints);

        assertEquals(0, result.newForcedTransactionOrders);
        assertEquals(0, result.crossKeyForcedOrders);
        assertTrue(constraints.isEmpty());
        assertTrue(graph.getKnownGraphB().edgeValue(reader, competitor)
                .orElse(java.util.List.of()).stream()
                .anyMatch(edge -> edge.getType() == EdgeType.RW
                        && "x".equals(edge.getKey())));
        assertFalse(result.inconsistent);
    }

    private static Transaction<String, Integer> addTransaction(
            History<String, Integer> history, long id) {
        var session = history.addSession(id);
        return history.addTransaction(session, id);
    }

    private static void commitAll(History<?, ?> history) {
        history.getTransactions().forEach(
                txn -> txn.setStatus(Transaction.TransactionStatus.COMMIT));
    }

    private static boolean hasWw(
            KnownGraph<String, Integer> graph,
            Transaction<String, Integer> from,
            Transaction<String, Integer> to,
            String key) {
        return graph.getKnownGraphA().edgeValue(from, to)
                .orElse(java.util.List.of()).stream()
                .anyMatch(edge -> edge.getType() == EdgeType.WW
                        && key.equals(edge.getKey()));
    }
}
