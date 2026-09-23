package verifier;

import graph.Edge;
import graph.EdgeType;
import graph.KnownGraph;
import history.Event;
import history.History;
import history.Transaction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static history.Event.EventType.WRITE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SISolverInducedParityTest {
    @Test
    void edgeRetainsAllCoalescedWitnessKeys() {
        var history = new History<String, Integer>();
        var first = addTransaction(history, 1L);
        var second = addTransaction(history, 2L);
        var edge = new SIEdge<>(first, second, EdgeType.PR_WR, "k1");

        assertTrue(edge.addKey("k2"));
        assertTrue(edge.addKey("k3"));
        assertFalse(edge.addKey("k1"));
        assertEquals(Set.of("k1", "k2", "k3"), edge.getKeys());
        assertEquals("k1", edge.getKey());
    }

    @Test
    void countsOneSourceConstraintForEachExternalPredicateKey() {
        var history = new History<String, Integer>();
        var bottom = history.addTransaction(history.addSession(-1L), -1L);
        var reader = addTransaction(history, 1L);
        history.addEvent(bottom, WRITE, "x", 0);
        history.addPredicateReadEvent(reader,
                (PredicateFixtures.RowPredicate<String, Integer>)
                        (key, value) -> key.equals("x") && value == 0,
                List.of(new Event.PredResult<>("x", 0)));
        commitAll(history);

        var graph = new KnownGraph<>(history);
        var solver = SISolverTestSupport.create(history, graph,
                SIVerifier.generateConstraintsSI(history, graph));

        assertEquals(1L, solver.getPredicateSourceConstraintCount());
        assertTrue(solver.solve());
    }

    @Test
    void preparationReportsKnownInducedCycleWithoutCreatingSolver() {
        var history = new History<String, Integer>();
        var first = addTransaction(history, 1L);
        var second = addTransaction(history, 2L);
        commitAll(history);
        var graph = new KnownGraph<String, Integer>(history);
        graph.putEdge(first, second, new Edge<>(EdgeType.WR, "a"));
        graph.putEdge(second, first, new Edge<>(EdgeType.RW, "b"));

        var oracle = new SIReachabilityOracle<>(graph);
        var prepared = SISolverTestSupport.prepare(history, graph, oracle,
                SIVerifier.SolverSettings.defaults());

        assertTrue(prepared.hasConflict());
        assertFalse(prepared.conflictReasons().isEmpty());
        assertFalse(SIVerifier.InducedGraph.extractCycleEdges(graph).isEmpty());
    }

    @Test
    void conflictExtractionRemovesIrrelevantChoiceConstraints() {
        var history = new History<String, Integer>();
        var first = addTransaction(history, 1L);
        var second = addTransaction(history, 2L);
        var third = addTransaction(history, 3L);
        var fourth = addTransaction(history, 4L);
        commitAll(history);

        var graph = new KnownGraph<String, Integer>(history);
        graph.putEdge(first, second, new Edge<>(EdgeType.RW, "b1"));
        graph.putEdge(second, first, new Edge<>(EdgeType.RW, "b2"));
        var required = new SIConstraint<>(
                List.of(new SIEdge<>(first, second, EdgeType.WW, "x")),
                List.of(new SIEdge<>(second, first, EdgeType.WW, "x")),
                first, second, 0);
        var irrelevant = new SIConstraint<>(
                List.of(new SIEdge<>(third, fourth, EdgeType.WW, "y")),
                List.of(new SIEdge<>(fourth, third, EdgeType.WW, "y")),
                third, fourth, 1);

        var solver = SISolverTestSupport.create(
                history, graph, List.of(required, irrelevant));

        assertFalse(solver.solve());
        assertEquals(List.of(required), List.copyOf(solver.getConflicts().getRight()));
    }

    private static Transaction<String, Integer> addTransaction(
            History<String, Integer> history, long id) {
        return history.addTransaction(history.addSession(id), id);
    }

    private static void commitAll(History<?, ?> history) {
        history.getTransactions().forEach(
                txn -> txn.setStatus(Transaction.TransactionStatus.COMMIT));
    }
}
