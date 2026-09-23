package verifier;

import graph.KnownGraph;
import graph.Edge;
import graph.EdgeType;
import history.Event;
import history.History;
import history.Transaction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SIOptimizationParityTest {
    @Test
    void compactOracleChecksACompositionBInsteadOfPlainUnion() {
        var history = new History<String, Integer>();
        var first = history.addTransaction(history.addSession(1L), 1L);
        var second = history.addTransaction(history.addSession(2L), 2L);
        first.setStatus(Transaction.TransactionStatus.COMMIT);
        second.setStatus(Transaction.TransactionStatus.COMMIT);
        var graph = new KnownGraph<String, Integer>(history);
        graph.putEdge(first, second, new Edge<>(EdgeType.WR, "a"));

        var oracle = new SIReachabilityOracle<String, Integer>(graph);
        assertEquals(true, oracle.hasConflict(List.of(
                new SIEdge<>(second, first, EdgeType.RW, "b"))));
        assertEquals(false, oracle.hasConflict(List.of(
                new SIEdge<>(first, second, EdgeType.RW, "b2"))));
    }

    @Test
    void witnessCoalescingAndGraphInterningPreserveSiVerdict() {
        var statuses = new ArrayList<SolveStatus>();
        for (boolean coalescing : List.of(false, true)) {
            for (boolean interning : List.of(false, true)) {
                var history = predicateCycleHistory();
                var graph = new KnownGraph<>(history);
                var settings = SIVerifier.SolverSettings.defaults();
                settings.predicateWitnessCoalescing = coalescing;
                settings.graphEdgeInterning = interning;
                statuses.add(SISolverTestSupport.create(history, graph,
                        SIVerifier.generateConstraintsSI(history, graph),
                        false, false, settings).solveStatus());
            }
        }
        assertEquals(List.of(
                SolveStatus.UNSAT, SolveStatus.UNSAT,
                SolveStatus.UNSAT, SolveStatus.UNSAT), statuses);
    }

    private static History<String, Integer> predicateCycleHistory() {
        var history = new History<String, Integer>();
        var bottom = history.addTransaction(history.addSession(-1L), -1L);
        bottom.setStatus(Transaction.TransactionStatus.COMMIT);
        var first = history.addTransaction(history.addSession(1L), 1L);
        var second = history.addTransaction(history.addSession(2L), 2L);
        history.addWriteEvent(bottom, "k1", 0, null);
        history.addWriteEvent(bottom, "k2", 0, null);
        history.addPredicateReadEvent(first,
                (PredicateFixtures.RowPredicate<String, Integer>)
                        (key, value) -> key.startsWith("k") && value == 0,
                List.of(new Event.PredResult<>("k1", 0),
                        new Event.PredResult<>("k2", 0)));
        history.addWriteEvent(first, "k1", 1, null);
        history.addWriteEvent(first, "k2", 2, null);
        history.addPredicateReadEvent(second,
                (PredicateFixtures.RowPredicate<String, Integer>)
                        (key, value) -> key.startsWith("k") && value == 0,
                List.of(new Event.PredResult<>("k1", 0),
                        new Event.PredResult<>("k2", 0)));
        history.addWriteEvent(second, "k1", 3, null);
        history.addWriteEvent(second, "k2", 4, null);
        first.setStatus(Transaction.TransactionStatus.COMMIT);
        second.setStatus(Transaction.TransactionStatus.COMMIT);
        return history;
    }
}
