package verifier;

import history.Event;
import history.History;
import history.Transaction;
import history.query.PredicateEvaluator;
import history.query.QueryEvaluation;
import history.query.QueryException;
import history.query.QueryScope;
import history.query.VisibleState;
import org.junit.jupiter.api.Test;
import util.Profiler;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SISupportedPredicateTest {
    @Test
    void declaredProgrammaticRowLocalIsFullyEncodedBeforeTheOnlySolve() {
        for (var mode : SIVerifier.PredicateMode.values()) {
            var solved = new boolean[1];
            var history = history(true, solved);
            var calls = new int[1];
            var settings = SIVerifier.SolverSettings.defaults();
            settings.predicateMode = mode;
            settings.satSolveBackend = (solver, assumptions) -> {
                solved[0] = true;
                calls[0]++;
                return solver.solve(assumptions);
            };
            assertEquals(SIVerifier.AuditResult.ACCEPT,
                    new SIVerifier<>(() -> history, settings, false).auditResult());
            assertEquals(1, calls[0]);
        }
    }

    @Test
    void undeclaredWholeSnapshotEvaluatorIsErrorBeforeNativeAllocation() {
        var profiler = Profiler.getInstance();
        profiler.clear();
        var history = history(false, new boolean[1]);
        assertThrows(QueryException.class, () -> new SIVerifier<>(() -> history).audit());
        assertEquals(0L, profiler.getCount("SI_NATIVE_SOLVER_CREATIONS_COUNT"));
    }

    private History<String, Integer> history(boolean rowLocal, boolean[] solved) {
        var history = new History<String, Integer>();
        var bottom = history.addTransaction(history.addSession(-1L), -1L);
        var reader = history.addTransaction(history.addSession(1L), 1L);
        history.addWriteEvent(bottom, "x", 7, null);
        PredicateFixtures.RowPredicate<String, Integer> delegate = (key, value) -> value > 0;
        var predicate = new PredicateEvaluator<String, Integer>() {
            @Override public QueryScope<String> scope() { return delegate.scope(); }
            @Override public boolean isRowLocal() { return rowLocal; }
            @Override public Object identity() { return this; }
            @Override public QueryEvaluation<String, Integer> evaluate(VisibleState<String, Integer> state) {
                assertFalse(solved[0], "SAT 后不得通过谓词求值补充模型约束");
                return delegate.evaluate(state);
            }
        };
        history.addPredicateReadEvent(reader, predicate, List.of(new Event.PredResult<>("x", 7)));
        history.getTransactions().forEach(txn -> txn.setStatus(Transaction.TransactionStatus.COMMIT));
        return history;
    }
}
