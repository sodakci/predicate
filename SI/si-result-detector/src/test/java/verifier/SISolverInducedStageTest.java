package verifier;

import graph.KnownGraph;
import graph.EdgeType;
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

        var solver = SISolverTestSupport.create(history, graph, constraints);

        assertEquals(1, profiler.getCounter("SI_GRAPH_ENCODE_SETUP"));
        assertEquals(1, profiler.getCounter("SI_GRAPH_ENCODE_KNOWN_EDGES"));
        assertEquals(1, profiler.getCounter("SI_GRAPH_ENCODE_WW"));
        assertEquals(1, profiler.getCounter("SI_GRAPH_ENCODE_RW"));
        assertEquals(1, profiler.getCounter("SI_GRAPH_ENCODE_PREDICATE"));
        assertEquals(1, profiler.getCounter("SI_GRAPH_ENCODE_ACYCLIC"));
        assertTrue(solver.solve());
    }

    @Test
    void incrementalVisibilityJoinsPredecessorsAndSuccessorsButExcludesRw() {
        var history = new History<String, Integer>();
        var a = addTransaction(history, 1L);
        var b = addTransaction(history, 2L);
        var c = addTransaction(history, 3L);
        var d = addTransaction(history, 4L);
        var e = addTransaction(history, 5L);
        var graph = new KnownGraph<>(history);
        var vis = new SIReachabilityOracle<>(graph);
        vis.commitRound(java.util.List.of(new SIEdge<>(a, b, EdgeType.SO, "x")));
        vis.commitRound(java.util.List.of(new SIEdge<>(c, d, EdgeType.WR, "x")));
        vis.commitRound(java.util.List.of(new SIEdge<>(b, c, EdgeType.WW, "x")));
        vis.commitRound(java.util.List.of(new SIEdge<>(d, e, EdgeType.PR_RW, "x")));
        assertTrue(vis.reachesA(a, d));
        assertTrue(vis.reachesA(b, d));
        org.junit.jupiter.api.Assertions.assertFalse(vis.reachesA(a, e));
        org.junit.jupiter.api.Assertions.assertFalse(vis.reachesA(d, a));
        vis.commitRound(java.util.List.of(new SIEdge<>(d, e, EdgeType.PR_WR, "x")));
        assertTrue(vis.reachesA(a, e));
        assertEquals(1, vis.buildCount());
    }

    @Test
    void recordedSourceNeedsOnlyLinearWriterComparisons() {
        for (var mode : SIVerifier.PredicateMode.values()) {
            var history = new History<String, Integer>();
            int writers = 20;
            for (int i = 1; i <= writers; i++) {
                history.addEvent(addTransaction(history, i), WRITE, "x", i);
            }
            var reader = addTransaction(history, 100L);
            history.addPredicateReadEvent(reader,
                    (PredicateFixtures.RowPredicate<String, Integer>) (key, value) -> value > 0,
                    java.util.List.of(new history.Event.PredResult<>("x", 1)));
            history.getTransactions().forEach(
                    txn -> txn.setStatus(Transaction.TransactionStatus.COMMIT));
            var graph = new KnownGraph<>(history);
            var settings = SIVerifier.SolverSettings.defaults();
            settings.predicateMode = mode;
            var profiler = Profiler.getInstance();
            profiler.clear();
            var solver = SISolverTestSupport.create(history, graph,
                    SIVerifier.generateConstraintsSI(history, graph), true, true, settings);
            assertEquals(SolveStatus.SAT, solver.solveStatus());
            long comparisons = profiler.getCount("SI_PRED_BEFORE_WRITE_CALLS_COUNT");
            assertTrue(comparisons <= 2L * writers,
                    mode + " 固定来源应只线性比较竞争写，实际 " + comparisons);
            assertEquals(1L, profiler.getCount("SI_PRED_FRONTIER_CANDIDATES_COUNT"));
        }
    }

    @Test
    void physicalEdgeUsesDirectClausesAndRequiresAtLeastOneSupport() throws Exception {
        var history = new History<String, Integer>();
        addTransaction(history, 1L);
        addTransaction(history, 2L);
        var graph = new KnownGraph<>(history);
        var encoder = SISolverTestSupport.create(history, graph, java.util.List.of());
        var solverField = SISolverInduced.class.getDeclaredField("solver");
        solverField.setAccessible(true);
        try (var nativeSolver = (monosat.Solver) solverField.get(encoder)) {
            var first = new monosat.Lit(nativeSolver);
            var second = new monosat.Lit(nativeSolver);
            int before = nativeSolver.nVars();
            var materialize = SISolverInduced.class.getDeclaredMethod(
                    "materializeInducedEdge", int.class, int.class, java.util.List.class);
            materialize.setAccessible(true);
            materialize.invoke(encoder, 0, 1, java.util.List.of(first, second));
            var graphField = SISolverInduced.class.getDeclaredField("inducedGraph");
            graphField.setAccessible(true);
            var edge = ((monosat.Graph) graphField.get(encoder)).getEdge(0, 1).l;
            assertEquals(before + 1, nativeSolver.nVars(),
                    "边绑定只需新增物理边变量，无需 OR 或 implies 辅助变量");
            for (boolean a : java.util.List.of(false, true)) {
                for (boolean b : java.util.List.of(false, true)) {
                    for (boolean e : java.util.List.of(false, true)) {
                        assertEquals(e == (a || b), nativeSolver.solve(java.util.List.of(
                                a ? first : first.not(), b ? second : second.not(),
                                e ? edge : edge.not())), "物理边必须恰好等价于全部支持的析取");
                    }
                }
            }
        }
    }

    private static Transaction<String, Integer> addTransaction(
            History<String, Integer> history, long id) {
        return history.addTransaction(history.addSession(id), id);
    }
}
