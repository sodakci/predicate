package verifier;

import graph.EdgeType;
import graph.KnownGraph;
import history.History;
import history.Transaction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrecedenceOracleInjectionTest {
    @Test
    void allOrderReasoningComponentsShareOneBeforeRelation() {
        var history = historyWithTwoTransactions();
        var graph = new KnownGraph<String, Integer>(history);
        var oracle = new PrecedenceOracle<Transaction<String, Integer>>(
                history.getTransactions());
        var reachability = new Pruning<String, Integer>(oracle);
        var snapshot = new Prun<String, Integer>(oracle);
        var propagation = new GmwrPropagationState<>(history, graph, oracle);
        var bridge = new GmwrWwBridge<String, Integer>(oracle);
        var solver = new SERSolverAR<>(history, graph, List.of(), true, false,
                eagerSettings(), oracle);

        assertSame(oracle, reachability.precedenceOracle());
        assertSame(oracle, snapshot.precedenceOracle());
        assertSame(oracle, propagation.precedenceOracle());
        assertSame(oracle, bridge.precedenceOracle());
        assertSame(oracle, solver.precedenceOracle());

        var first = history.getTransaction(1L);
        var second = history.getTransaction(2L);
        assertTrue(propagation.addKnownFact(first, second, EdgeType.PR_WR, "k"));
        assertTrue(reachability.precedenceOracle().before(first, second));
        assertTrue(snapshot.precedenceOracle().before(first, second));
        assertTrue(propagation.precedenceOracle().before(first, second));
        assertTrue(bridge.precedenceOracle().before(first, second));
        assertTrue(solver.precedenceOracle().before(first, second));
    }

    @Test
    void monosatDecisionOrdersDoNotEnterDeterministicOracle() {
        var history = historyWithTwoTransactions();
        var graph = new KnownGraph<String, Integer>(history);
        var oracle = new PrecedenceOracle<Transaction<String, Integer>>(
                history.getTransactions());
        var first = history.getTransaction(1L);
        var second = history.getTransaction(2L);
        var constraint = new SERConstraint<>(
                List.of(new SEREdge<>(first, second, EdgeType.WW, "k")),
                List.of(new SEREdge<>(second, first, EdgeType.WW, "k")),
                first, second, 0);

        new SERSolverAR<>(history, graph, List.of(constraint), true, false,
                eagerSettings(), oracle);

        assertFalse(oracle.before(first, second));
        assertFalse(oracle.before(second, first));
    }

    private static History<String, Integer> historyWithTwoTransactions() {
        var history = new History<String, Integer>();
        history.addTransaction(history.addSession(1L), 1L)
                .setStatus(Transaction.TransactionStatus.COMMIT);
        history.addTransaction(history.addSession(2L), 2L)
                .setStatus(Transaction.TransactionStatus.COMMIT);
        return history;
    }

    private static SERVerifier.SolverSettings eagerSettings() {
        return SERVerifier.SolverSettings.forModes(
                SERVerifier.PredicateSolvingMode.EAGER,
                SERVerifier.PruningMode.NONE,
                SERVerifier.SerPropagationMode.WW_ONLY);
    }
}
