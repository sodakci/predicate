package verifier;

import graph.Edge;
import graph.EdgeType;
import graph.KnownGraph;
import history.History;
import history.Transaction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SIBatchedPruningTest {
    @Test
    void newlyForcedEdgesAreVisibleToPruningOnlyInNextRound() {
        var history = new History<String, Integer>();
        var a = txn(history, 1);
        var b = txn(history, 2);
        var c = txn(history, 3);
        var graph = new KnownGraph<>(history, false);
        graph.putEdge(a, b, new Edge<>(EdgeType.WR, "x"));
        var first = new SIConstraint<>(List.of(edge(a, b), edge(b, c)),
                List.of(edge(b, a)), a, b, 0);
        var second = new SIConstraint<>(List.of(edge(a, c)),
                List.of(edge(c, a)), a, c, 1);
        var constraints = new ArrayList<>(List.of(first, second));
        var oracle = new SIReachabilityOracle<>(graph);

        var round = SIReachabilityPruner.reduceOnce(graph, constraints, oracle);
        assertEquals(1, round.forced);
        assertEquals(List.of(second), constraints);
        assertTrue(oracle.reachesA(a, c), "committed batch must be available to GMWR");
        assertEquals(1, SIReachabilityPruner.reduceOnce(graph, constraints, oracle).forced);
        assertTrue(constraints.isEmpty());
    }

    @Test
    void jointlyForcedSidesAreRejectedAtRoundEnd() {
        var history = new History<String, Integer>();
        var a = txn(history, 1);
        var b = txn(history, 2);
        var c = txn(history, 3);
        var d = txn(history, 4);
        var graph = new KnownGraph<>(history, false);
        graph.putEdge(b, c, new Edge<>(EdgeType.WR, "x"));
        graph.putEdge(d, a, new Edge<>(EdgeType.WR, "y"));
        var first = new SIConstraint<>(List.of(edge(a, b)),
                List.of(edge(c, b)), a, b, 0);
        var second = new SIConstraint<>(List.of(edge(c, d)),
                List.of(edge(a, d)), c, d, 1);
        var constraints = new ArrayList<>(List.of(first, second));
        var oracle = new SIReachabilityOracle<>(graph);

        var result = SIReachabilityPruner.reduceOnce(graph, constraints, oracle);
        assertEquals(2, result.forced);
        assertTrue(result.rejected);
        assertNull(result.conflict, "the conflict belongs to the batch, not one choice");
        assertFalse(oracle.isAcyclic());
    }

    @Test
    void knownCycleIsRejectedWithoutResidualChoices() {
        var history = new History<String, Integer>();
        var a = txn(history, 1);
        var b = txn(history, 2);
        var graph = new KnownGraph<>(history, false);
        graph.putEdge(a, b, new Edge<>(EdgeType.WR, "x"));
        graph.putEdge(b, a, new Edge<>(EdgeType.WR, "y"));
        var result = SIReachabilityPruner.reduceOnce(graph, new ArrayList<>(),
                new SIReachabilityOracle<>(graph));
        assertTrue(result.rejected);
        assertEquals(0, result.forced);
    }

    @Test
    void unresolvedMultiEdgeCycleIsLeftForSolver() {
        var history = new History<String, Integer>();
        var a = txn(history, 1);
        var b = txn(history, 2);
        var c = txn(history, 3);
        var d = txn(history, 4);
        var graph = new KnownGraph<>(history, false);
        graph.putEdge(b, c, new Edge<>(EdgeType.WR, "x"));
        graph.putEdge(d, a, new Edge<>(EdgeType.WR, "y"));
        var choice = new SIConstraint<>(List.of(edge(a, b), edge(c, d)),
                List.of(edge(b, a)), a, b, 0);
        var constraints = new ArrayList<>(List.of(choice));
        var oracle = new SIReachabilityOracle<>(graph);
        var result = SIReachabilityPruner.reduceOnce(graph, constraints, oracle);
        assertEquals(0, result.forced);
        assertEquals(List.of(choice), constraints);
        assertTrue(SISolverTestSupport.create(history, graph, constraints, false, false,
                SIVerifier.SolverSettings.defaults(), oracle).solve());
    }

    private static Transaction<String, Integer> txn(History<String, Integer> history, long id) {
        var txn = history.addTransaction(history.addSession(id), id);
        txn.setStatus(Transaction.TransactionStatus.COMMIT);
        return txn;
    }

    private static SIEdge<String, Integer> edge(Transaction<String, Integer> from,
            Transaction<String, Integer> to) {
        return new SIEdge<>(from, to, EdgeType.WW, "x");
    }
}
