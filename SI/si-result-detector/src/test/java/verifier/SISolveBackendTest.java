package verifier;

import graph.Edge;
import graph.EdgeType;
import graph.KnownGraph;
import history.History;
import history.Transaction;
import org.junit.jupiter.api.Test;
import util.Profiler;

import java.util.List;

import static history.Event.EventType.READ;
import static history.Event.EventType.WRITE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SISolveBackendTest {
    @Test
    void satBackendIsInvokedOnce() {
        var history = committedTxn();
        var graph = new KnownGraph<>(history);
        var settings = SIVerifier.SolverSettings.defaults();
        var calls = new int[1];
        settings.satSolveBackend = (solver, assumptions) -> {
            calls[0]++;
            return solver.solve(assumptions);
        };

        var si = SISolverTestSupport.create(
                history, graph, List.of(), true, false, settings);
        assertEquals(SolveStatus.SAT, si.solveStatus());
        assertEquals(1, calls[0]);
    }

    @Test
    void unsatDiagnosticsDoNotInvokeBackendAgain() {
        var history = committedTxn();
        var first = history.getTransaction(1L);
        var second = history.addTransaction(history.addSession(2L), 2L);
        second.setStatus(Transaction.TransactionStatus.COMMIT);
        var graph = new KnownGraph<>(history);
        graph.putEdge(first, second, new Edge<>(EdgeType.RW, "x"));
        graph.putEdge(second, first, new Edge<>(EdgeType.RW, "x"));
        var choice = new SIConstraint<>(
                List.of(new SIEdge<>(first, second, EdgeType.WW, "x")),
                List.of(new SIEdge<>(second, first, EdgeType.WW, "x")),
                first, second, 0);
        var settings = SIVerifier.SolverSettings.defaults();
        var calls = new int[1];
        settings.satSolveBackend = (solver, assumptions) -> {
            calls[0]++;
            return solver.solve(assumptions);
        };
        var solver = SISolverTestSupport.create(history, graph,
                List.of(choice), true, false, settings);
        assertEquals(SolveStatus.UNSAT, solver.solveStatus());
        assertEquals(1, calls[0], "UNSAT 应直接使用本次求解的冲突子句");
    }

    @Test
    void deterministicPredicateConflictCreatesNoNativeSolverAndSkipsBackend() {
        for (var mode : SIVerifier.PredicateMode.values()) {
            var history = new History<String, Integer>();
            var bottom = history.addTransaction(history.addSession(-1L), -1L);
            var writer = history.addTransaction(history.addSession(1L), 1L);
            var reader = history.addTransaction(history.addSession(2L), 2L);
            history.addEvent(bottom, WRITE, "x", 0);
            history.addEvent(bottom, WRITE, "marker", 0);
            history.addEvent(writer, WRITE, "x", 1);
            history.addEvent(writer, WRITE, "marker", 1);
            history.addEvent(reader, READ, "marker", 1);
            history.addPredicateReadEvent(reader,
                    (PredicateFixtures.RowPredicate<String, Integer>)
                            (key, value) -> key.equals("x") && value > 0,
                    List.of());
            history.getTransactions().forEach(
                    txn -> txn.setStatus(Transaction.TransactionStatus.COMMIT));
            var settings = SIVerifier.SolverSettings.defaults();
            settings.predicateMode = mode;
            var calls = new int[1];
            settings.satSolveBackend = (solver, assumptions) -> {
                calls[0]++;
                return solver.solve(assumptions);
            };
            Profiler.getInstance().clear();

            assertEquals(SIVerifier.AuditResult.REJECT,
                    new SIVerifier<>(() -> history, settings, false).auditResult());
            assertEquals(1, Profiler.getInstance().getCounter("SI_PRED_PREPARE_MS"),
                    "本例必须到达谓词准备阶段，不能由其他预检提前拒绝");
            assertEquals(0, calls[0]);
            assertEquals(0L, Profiler.getInstance().getCount("SI_NATIVE_SOLVER_CREATIONS_COUNT"));
        }
    }

    @Test
    void productionConstructorRejectsDifferentOracleBeforeNativeAllocation() {
        var history = committedTxn();
        var graph = new KnownGraph<>(history);
        var settings = SIVerifier.SolverSettings.defaults();
        var preparedOracle = new SIReachabilityOracle<>(graph);
        var prepared = SISolverTestSupport.prepare(history, graph, preparedOracle, settings);
        assertFalse(prepared.hasConflict());
        var differentOracle = new SIReachabilityOracle<>(graph);
        Profiler.getInstance().clear();

        assertThrows(IllegalArgumentException.class, () -> new SISolverInduced<>(
                history, graph, List.of(), true, false, settings, differentOracle, prepared));

        assertEquals(0L, Profiler.getInstance().getCount("SI_NATIVE_SOLVER_CREATIONS_COUNT"));
    }

    @Test
    void productionConstructorRejectsConflictingResultBeforeNativeAllocation() {
        var history = committedTxn();
        var first = history.getTransaction(1L);
        var second = history.addTransaction(history.addSession(2L), 2L);
        second.setStatus(Transaction.TransactionStatus.COMMIT);
        var graph = new KnownGraph<>(history);
        graph.putEdge(first, second, new Edge<>(EdgeType.WR, "x"));
        graph.putEdge(second, first, new Edge<>(EdgeType.RW, "x"));
        var settings = SIVerifier.SolverSettings.defaults();
        var oracle = new SIReachabilityOracle<>(graph);
        var prepared = SISolverTestSupport.prepare(history, graph, oracle, settings);
        assertTrue(prepared.hasConflict());
        assertFalse(prepared.conflictReasons().isEmpty());
        Profiler.getInstance().clear();

        assertThrows(IllegalArgumentException.class, () -> new SISolverInduced<>(
                history, graph, List.of(), true, false, settings, oracle, prepared));

        assertEquals(0L, Profiler.getInstance().getCount("SI_NATIVE_SOLVER_CREATIONS_COUNT"));
    }

    private static History<String, Integer> committedTxn() {
        var history = new History<String, Integer>();
        history.addTransaction(history.addSession(1L), 1L)
                .setStatus(Transaction.TransactionStatus.COMMIT);
        return history;
    }
}
