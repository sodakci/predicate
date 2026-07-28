package verifier;

import graph.Edge;
import graph.EdgeType;
import graph.KnownGraph;
import history.History;
import history.Transaction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PruningReachabilityTest {

    @Test
    void commonTargetEdgesDetectCycleThroughBasePath() {
        var history = new History<String, Integer>();
        var transactions = new ArrayList<Transaction<String, Integer>>();
        for (long id = 1; id <= 4; id++) {
            transactions.add(history.addTransaction(history.addSession(id), id));
        }

        var target = transactions.get(0);
        var middle = transactions.get(1);
        var writer = transactions.get(2);
        var reader = transactions.get(3);

        var graph = new KnownGraph<>(history);
        graph.putEdge(target, middle, new Edge<>(EdgeType.SO, null));
        graph.putEdge(middle, writer, new Edge<>(EdgeType.SO, null));

        var constraint = new SERConstraint<>(
                List.of(
                        new SEREdge<>(writer, target, EdgeType.WW, "k"),
                        new SEREdge<>(reader, target, EdgeType.RW, "k")),
                List.of(new SEREdge<>(target, writer, EdgeType.WW, "k")),
                writer, target, 0);
        var constraints = new ArrayList<>(List.of(constraint));

        boolean hasLoop = Pruning.pruneConstraints(graph, constraints, history);

        assertFalse(hasLoop);
        assertTrue(constraints.isEmpty());
        assertTrue(graph.getKnownGraphA().hasEdgeConnecting(target, writer));
        assertFalse(graph.getKnownGraphA().hasEdgeConnecting(writer, target));
    }

    @Test
    void candidateEdgesDetectCycleThroughBasePathsWithNonEndpointTransactions() {
        var history = new History<String, Integer>();
        var transactions = new ArrayList<Transaction<String, Integer>>();
        for (long id = 1; id <= 6; id++) {
            transactions.add(history.addTransaction(history.addSession(id), id));
        }

        var a = transactions.get(0);
        var x = transactions.get(1);
        var b = transactions.get(2);
        var c = transactions.get(3);
        var y = transactions.get(4);
        var d = transactions.get(5);

        var graph = new KnownGraph<>(history);
        graph.putEdge(a, x, new Edge<>(EdgeType.SO, null));
        graph.putEdge(x, b, new Edge<>(EdgeType.SO, null));
        graph.putEdge(c, y, new Edge<>(EdgeType.SO, null));
        graph.putEdge(y, d, new Edge<>(EdgeType.SO, null));

        /*
         * B -> C and D -> A are individually acyclic, but together close
         * A -> X -> B -> C -> Y -> D -> A. The alternative C -> B is safe.
         */
        var constraint = new SERConstraint<>(
                List.of(
                        new SEREdge<>(b, c, EdgeType.WW, "k"),
                        new SEREdge<>(d, a, EdgeType.RW, "r")),
                List.of(new SEREdge<>(c, b, EdgeType.WW, "k")),
                b, c, 0);
        var constraints = new ArrayList<>(List.of(constraint));

        boolean hasLoop = Pruning.pruneConstraints(graph, constraints, history);

        assertFalse(hasLoop);
        assertTrue(constraints.isEmpty());
        assertTrue(graph.getKnownGraphA().hasEdgeConnecting(c, b));
        assertFalse(graph.getKnownGraphA().hasEdgeConnecting(b, c));
    }
}
