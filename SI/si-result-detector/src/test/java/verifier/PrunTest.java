package verifier;

import graph.Edge;
import graph.EdgeType;
import graph.KnownGraph;
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
    void snapshotMaterializesCrossKeyForcedConstraintSide() {
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
                SIVerifier.generateConstraintsSI(history, graph));
        assertEquals(1, constraints.size());

        var result = Prun.pruneSnapshotOnly(history, graph, constraints);

        assertEquals(1, result.crossKeyForcedOrders);
        assertTrue(constraints.isEmpty());
        assertTrue(hasEdge(graph, visibleCompetitor, source, EdgeType.WW, "x"));
        assertFalse(result.inconsistent);
    }

    @Test
    void snapshotOnlyDoesNotApplyInducedBranchPruning() {
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
                SIVerifier.generateConstraintsSI(history, snapshotGraph));
        Prun.pruneSnapshotOnly(history, snapshotGraph, snapshotConstraints);
        assertEquals(1, snapshotConstraints.size());

        var combinedGraph = new KnownGraph<>(history);
        var combinedConstraints = new ArrayList<>(
                SIVerifier.generateConstraintsSI(history, combinedGraph));
        Prun.prune(history, combinedGraph, combinedConstraints);
        assertTrue(combinedConstraints.isEmpty());
    }

    @Test
    void laterCompetingWriterMaterializesBEdgeWithoutAddingItToAClosure() {
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
                SIVerifier.generateConstraintsSI(history, graph));
        var result = Prun.prune(history, graph, constraints);

        assertTrue(constraints.isEmpty());
        assertTrue(hasEdge(graph, reader, competitor, EdgeType.RW, "x"));
        assertFalse(graph.getKnownGraphA().hasEdgeConnecting(reader, competitor));
        assertFalse(result.inconsistent);
    }

    @Test
    void prunRejectsOnlyWhenBothSidesViolateTheSiInducedGraph() {
        var history = new History<String, Integer>();
        var first = addTransaction(history, 1L);
        var second = addTransaction(history, 2L);
        history.addEvent(first, WRITE, "x", 1);
        history.addEvent(second, WRITE, "x", 2);
        commitAll(history);

        var graph = new KnownGraph<>(history);
        graph.putEdge(first, second, new Edge<>(EdgeType.RW, "left"));
        graph.putEdge(second, first, new Edge<>(EdgeType.RW, "right"));
        assertFalse(SIVerifier.InducedGraph.hasCycle(graph),
                "a B-only cycle is not an SI induced-graph cycle");

        var constraints = new ArrayList<>(
                SIVerifier.generateConstraintsSI(history, graph));
        var result = Prun.prune(history, graph, constraints);

        assertTrue(result.inconsistent,
                "either WW direction composes with the reverse B edge into a self-cycle");
    }

    private static Transaction<String, Integer> addTransaction(
            History<String, Integer> history, long id) {
        return history.addTransaction(history.addSession(id), id);
    }

    private static void commitAll(History<?, ?> history) {
        history.getTransactions().forEach(
                txn -> txn.setStatus(Transaction.TransactionStatus.COMMIT));
    }

    private static boolean hasEdge(
            KnownGraph<String, Integer> graph,
            Transaction<String, Integer> from,
            Transaction<String, Integer> to,
            EdgeType type,
            String key) {
        var target = type == EdgeType.RW || type == EdgeType.PR_RW
                ? graph.getKnownGraphB()
                : graph.getKnownGraphA();
        return target.edgeValue(from, to).orElse(java.util.List.of()).stream()
                .anyMatch(edge -> edge.getType() == type
                        && key.equals(edge.getKey()));
    }
}
