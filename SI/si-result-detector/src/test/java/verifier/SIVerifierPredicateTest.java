package verifier;

import graph.Edge;
import graph.EdgeType;
import graph.KnownGraph;
import history.History;
import history.Transaction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SIVerifierPredicateTest {
    @Test
    void knownPrWrIsUsedAsAnAEdgeBySiStages() {
        var history = twoTransactionHistory();
        var first = history.getTransaction(1L);
        var second = history.getTransaction(2L);

        var withoutPredicateEdge = new KnownGraph<String, Integer>(history);
        withoutPredicateEdge.putEdge(second, first,
                new Edge<>(EdgeType.WR, "dependency"));
        assertTrue(SISolverTestSupport.solve(
                history, withoutPredicateEdge, List.of()));

        var withPredicateEdge = new KnownGraph<String, Integer>(history);
        withPredicateEdge.putEdge(second, first,
                new Edge<>(EdgeType.WR, "dependency"));
        withPredicateEdge.putEdge(first, second,
                new Edge<>(EdgeType.PR_WR, "predicate"));
        assertFalse(SISolverTestSupport.solve(
                history, withPredicateEdge, List.of()),
                "PR_WR must enter A and close the A-edge cycle");
    }

    @Test
    void knownPrRwIsUsedAsABEdgeBySiStages() {
        var history = twoTransactionHistory();
        var first = history.getTransaction(1L);
        var second = history.getTransaction(2L);

        var bOnly = new KnownGraph<String, Integer>(history);
        bOnly.putEdge(second, first,
                new Edge<>(EdgeType.PR_RW, "predicate"));
        assertTrue(SISolverTestSupport.solve(history, bOnly, List.of()),
                "a standalone B edge is not itself an induced-SI edge");

        var withComposition = new KnownGraph<String, Integer>(history);
        withComposition.putEdge(first, second,
                new Edge<>(EdgeType.WR, "dependency"));
        withComposition.putEdge(second, first,
                new Edge<>(EdgeType.PR_RW, "predicate"));
        assertFalse(SISolverTestSupport.solve(
                history, withComposition, List.of()),
                "PR_RW must enter B so A composition B creates a self-cycle");
        var conflicts = SIVerifier.InducedGraph.extractCycleEdges(withComposition);
        assertTrue(conflicts.stream()
                .flatMap(conflict -> conflict.getRight().stream())
                .anyMatch(edge -> edge.getType() == EdgeType.WR));
        assertTrue(conflicts.stream()
                .flatMap(conflict -> conflict.getRight().stream())
                .anyMatch(edge -> edge.getType() == EdgeType.PR_RW));
    }

    private static History<String, Integer> twoTransactionHistory() {
        var history = new History<String, Integer>();
        var first = history.addTransaction(history.addSession(1L), 1L);
        var second = history.addTransaction(history.addSession(2L), 2L);
        first.setStatus(Transaction.TransactionStatus.COMMIT);
        second.setStatus(Transaction.TransactionStatus.COMMIT);
        return history;
    }
}
