package verifier;

import graph.KnownGraph;
import history.History;
import history.Transaction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression: GMWR must still encode absent-result bad-writer obligations
 * when prepropagation is disabled.
 *
 * History:
 *   INIT: x = 0
 *   T1: write x = 10
 *   SO: T1 -> T2
 *   T2: query x >= 5, result = empty
 */
class GmwrMissingConstraintTest {
    @Test
    void emptyPredicateAfterVisibleMatchRejectsEveryArConfig() {
        var history = emptyPredicateAfterVisibleMatch();
        for (var settings : allArConfigs()) {
            assertEquals(SolveStatus.UNSAT, solve(history, settings),
                    () -> describe(settings));
        }
    }

    static List<SERVerifier.SolverSettings> allArConfigs() {
        var configs = new ArrayList<SERVerifier.SolverSettings>();
        for (boolean interning : List.of(true, false)) {
            var eager = SERVerifier.SolverSettings.forModes(
                    SERVerifier.PredicateSolvingMode.EAGER,
                    SERVerifier.PruningMode.NONE);
            eager.graphEdgeInterning = interning;
            eager.predicateWitnessCoalescing = interning;
            eager.gmwrPrepropagation = false;
            configs.add(eager);
        }
        for (boolean preprop : List.of(true, false)) {
            for (boolean interning : List.of(true, false)) {
                var gmwr = SERVerifier.SolverSettings.forModes(
                        SERVerifier.PredicateSolvingMode.GMWR,
                        SERVerifier.PruningMode.NONE);
                gmwr.gmwrPrepropagation = preprop;
                gmwr.graphEdgeInterning = interning;
                gmwr.predicateWitnessCoalescing = true;
                configs.add(gmwr);
            }
        }
        return configs;
    }

    @Test
    void knobsDoNotChangeAcceptingAbsentQuery() {
        var history = new History<String, Integer>();
        var init = history.addTransaction(history.addSession(-1L), -1L);
        history.addWriteEvent(init, "x", 0, 1L);
        init.setStatus(Transaction.TransactionStatus.COMMIT);
        var writer = history.addTransaction(history.addSession(1L), 1L);
        history.addWriteEvent(writer, "x", 1, 2L);
        writer.setStatus(Transaction.TransactionStatus.COMMIT);
        var reader = history.addTransaction(history.addSession(2L), 2L);
        history.addPredicateReadEvent(reader, predicateXAtLeast(5), List.of());
        reader.setStatus(Transaction.TransactionStatus.COMMIT);

        SolveStatus first = null;
        for (var settings : allArConfigs()) {
            var status = solve(history, settings);
            if (first == null) {
                first = status;
            }
            assertEquals(first, status, () -> describe(settings));
        }
        assertEquals(SolveStatus.SAT, first);
    }

    static History<String, Integer> emptyPredicateAfterVisibleMatch() {
        var history = new History<String, Integer>();
        var init = history.addTransaction(history.addSession(-1L), -1L);
        history.addWriteEvent(init, "x", 0, 1L);
        init.setStatus(Transaction.TransactionStatus.COMMIT);

        var session = history.addSession(1L);
        var writer = history.addTransaction(session, 1L);
        history.addWriteEvent(writer, "x", 10, 2L);
        writer.setStatus(Transaction.TransactionStatus.COMMIT);

        var reader = history.addTransaction(session, 2L);
        history.addPredicateReadEvent(reader, predicateXAtLeast(5), List.of());
        reader.setStatus(Transaction.TransactionStatus.COMMIT);
        return history;
    }

    private static PredicateFixtures.RowPredicate<String, Integer> predicateXAtLeast(int threshold) {
        return new PredicateFixtures.RowPredicate<>() {
            @Override
            public boolean test(String key, Integer value) {
                return value != null && "x".equals(key) && value >= threshold;
            }

            @Override
            public boolean covers(String key) {
                return "x".equals(key);
            }
        };
    }

    private static SolveStatus solve(History<String, Integer> history,
                                     SERVerifier.SolverSettings settings) {
        var graph = new KnownGraph<>(history);
        return new SERSolverAR<>(history, graph,
                SERVerifier.generateConstraintsSER(history, graph),
                true, false, settings).solve();
    }

    private static String describe(SERVerifier.SolverSettings settings) {
        return "mode=" + settings.predicateSolvingMode
                + " preprop=" + settings.gmwrPrepropagation
                + " interning=" + settings.graphEdgeInterning
                + " coalescing=" + settings.predicateWitnessCoalescing;
    }
}
