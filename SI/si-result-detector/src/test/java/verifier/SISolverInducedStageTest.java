package verifier;

import graph.KnownGraph;
import history.History;
import history.Transaction;
import org.junit.jupiter.api.Test;
import util.Profiler;

import java.util.ArrayList;

import static history.Event.EventType.READ;
import static history.Event.EventType.WRITE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SISolverInducedStageTest {
    @Test
    void constructorRunsExplicitTypedEdgeEncodingStagesBeforeSolve() {
        var history = new History<String, Integer>();
        var source = addTransaction(history, 1L);
        var reader = addTransaction(history, 2L);
        var writer = addTransaction(history, 3L);
        history.addEvent(source, WRITE, "x", 1);
        history.addEvent(reader, READ, "x", 1);
        history.addEvent(writer, WRITE, "x", 2);
        history.getTransactions().forEach(
                txn -> txn.setStatus(Transaction.TransactionStatus.COMMIT));

        var graph = new KnownGraph<>(history);
        var constraints = new ArrayList<>(
                SIVerifier.generateConstraintsSI(history, graph));
        var profiler = Profiler.getInstance();
        profiler.clear();

        var solver = new SISolverInduced<>(history, graph, constraints);

        assertEquals(1, profiler.getCounter("SI_GRAPH_ENCODE_SETUP"));
        assertEquals(1, profiler.getCounter("SI_GRAPH_ENCODE_KNOWN_EDGES"));
        assertEquals(1, profiler.getCounter("SI_GRAPH_ENCODE_WW"));
        assertEquals(1, profiler.getCounter("SI_GRAPH_ENCODE_RW"));
        assertEquals(1, profiler.getCounter("SI_GRAPH_ENCODE_PREDICATE"));
        assertEquals(1, profiler.getCounter("SI_GRAPH_ENCODE_ACYCLIC"));
        assertTrue(solver.solve());
    }

    private static Transaction<String, Integer> addTransaction(
            History<String, Integer> history, long id) {
        return history.addTransaction(history.addSession(id), id);
    }
}
