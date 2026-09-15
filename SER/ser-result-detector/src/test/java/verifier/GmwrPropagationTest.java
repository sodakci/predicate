package verifier;

import graph.Edge;
import graph.EdgeType;
import graph.KnownGraph;
import history.History;
import history.Transaction;
import org.junit.jupiter.api.Test;
import util.Profiler;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static history.Event.EventType.WRITE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GmwrPropagationTest {
    @Test
    void wwOnlyDoesNotBuildOrRunGmwrPropagation() {
        var profiler = Profiler.getInstance();
        profiler.clear();
        var history = new History<String, Integer>();
        history.addTransaction(history.addSession(1L), 1L)
                .setStatus(Transaction.TransactionStatus.COMMIT);
        var graph = new KnownGraph<String, Integer>(history);

        var solver = new SERSolverAR<>(history, graph, List.of(), true, false,
                SERVerifier.PredicateSolvingMode.GMWR,
                SERVerifier.SerPropagationMode.WW_ONLY);

        assertEquals(SolveStatus.SAT, solver.solve());
        assertEquals(0L, profiler.getCount("GMWR_WW_BRIDGE_SCANS"));
    }

    @Test
    void gmwrPrepropagationCanBeDisabledIndependentlyOfEncoding() {
        var profiler = Profiler.getInstance();
        profiler.clear();
        var history = new History<String, Integer>();
        history.addTransaction(history.addSession(1L), 1L)
                .setStatus(Transaction.TransactionStatus.COMMIT);
        var graph = new KnownGraph<String, Integer>(history);
        var settings = SERVerifier.SolverSettings.forModes(
                SERVerifier.PredicateSolvingMode.GMWR,
                SERVerifier.PruningMode.REACHABILITY,
                SERVerifier.SerPropagationMode.WW_GMWR);
        settings.gmwrPrepropagation = false;

        var solver = new SERSolverAR<>(history, graph, List.of(), true, false, settings);

        assertEquals(SolveStatus.SAT, solver.solve());
        assertEquals(0L, profiler.getCount("GMWR_WW_BRIDGE_SCANS"));
        assertEquals(0L, profiler.getTime("GMWR_REDUCTION_MS"));
    }

    @Test
    void overlayReachabilityUpdatesWithoutOwningWwConstraints() {
        var history = new History<String, Integer>();
        var a = history.addTransaction(history.addSession(1L), 1L);
        var b = history.addTransaction(history.addSession(2L), 2L);
        var c = history.addTransaction(history.addSession(3L), 3L);
        commitAll(history);

        var graph = new KnownGraph<String, Integer>(history);
        graph.putEdge(a, b, new Edge<>(EdgeType.SO, null));
        var state = gmwrState(history, graph);
        state.seedKnownDependencies();
        assertTrue(state.precedenceOracle().before(a, b));
        assertFalse(state.precedenceOracle().before(b, c));

        assertTrue(state.addKnownFact(b, c, EdgeType.PR_WR, "k"));
        assertTrue(state.precedenceOracle().before(a, c));
        assertTrue(state.precedenceOracle().wouldCycle(c, a));
    }

    @Test
    void singletonRepairPublishesDefiniteGmwrFacts() {
        var history = new History<String, Integer>();
        var bad = history.addTransaction(history.addSession(1L), 1L);
        var repair = history.addTransaction(history.addSession(2L), 2L);
        var reader = history.addTransaction(history.addSession(3L), 3L);
        commitAll(history);

        var graph = new KnownGraph<String, Integer>(history);
        graph.putEdge(bad, reader, new Edge<>(EdgeType.SO, null));
        var state = gmwrState(history, graph);
        state.seedKnownDependencies();
        state.addGmwrItem(reader, bad, List.of(repair), "k");

        assertFalse(state.propagate());
        assertEquals(2L, state.definiteFactCount());
        assertTrue(state.precedenceOracle().before(bad, repair));
        assertTrue(state.precedenceOracle().before(repair, reader));
        assertEquals(2L, state.stats.forcedFacts);
    }

    @Test
    void bridgeScansResidualWwOnceAndCommitsOnlyForcedBranch() {
        var history = new History<String, Integer>();
        var a = history.addTransaction(history.addSession(1L), 1L);
        var b = history.addTransaction(history.addSession(2L), 2L);
        history.addEvent(a, WRITE, "k", 1);
        history.addEvent(b, WRITE, "k", 2);
        commitAll(history);

        var graph = new KnownGraph<String, Integer>(history);
        var state = gmwrState(history, graph);
        state.seedKnownDependencies();
        state.addKnownFact(a, b, EdgeType.PR_WR, "k");
        assertFalse(state.propagate());
        assertTrue(state.precedenceOracle().before(a, b));

        var constraint = new SERConstraint<>(
                List.of(new SEREdge<>(b, a, EdgeType.WW, "k")),
                List.of(new SEREdge<>(a, b, EdgeType.WW, "k")),
                a, b, 0);
        var residual = new ArrayList<>(List.of(constraint));
        var result = new GmwrWwBridge<>(state.precedenceOracle())
                .scan(graph, residual);

        assertFalse(result.conflict);
        assertEquals(1, result.scannedConstraints);
        assertEquals(1, result.forcedConstraints);
        assertTrue(residual.isEmpty());
        assertTrue(graph.getKnownGraphA().edgeValue(a, b)
                .orElse(List.of()).contains(new Edge<>(EdgeType.WW, "k")));
    }

    @Test
    void sameReaderBadWriterObligationsKeepMinimalAntichain() {
        var history = new History<String, Integer>();
        var reader = history.addTransaction(history.addSession(1L), 1L);
        var bad = history.addTransaction(history.addSession(2L), 2L);
        var a1 = history.addTransaction(history.addSession(3L), 3L);
        var a2 = history.addTransaction(history.addSession(4L), 4L);
        var a3 = history.addTransaction(history.addSession(5L), 5L);
        commitAll(history);

        var state = gmwrState(history, new KnownGraph<>(history));
        state.seedKnownDependencies();
        state.addGmwrItem(reader, bad, List.of(a1, a2), "x");
        state.addGmwrItem(reader, bad, List.of(a1, a2, a3), "y");
        var obligations = new ArrayList<>(state.gmwrObligations());

        assertEquals(1, obligations.size());
        assertEquals(1, obligations.get(0).items.size());
        assertEquals(Set.of(a1, a2), obligations.get(0).items.get(0).repairs);
        assertTrue(state.stats.mergedConstraints > 0);
    }

    @Test
    void unrepairableKeyConflictsEvenIfAnotherKeyStillHasRepairs() {
        var history = new History<String, Integer>();
        var bad = history.addTransaction(history.addSession(1L), 1L);
        var blockedRepair = history.addTransaction(history.addSession(2L), 2L);
        var otherRepair = history.addTransaction(history.addSession(3L), 3L);
        var reader = history.addTransaction(history.addSession(4L), 4L);
        commitAll(history);

        var graph = new KnownGraph<String, Integer>(history);
        graph.putEdge(bad, reader, new Edge<>(EdgeType.SO, null));
        graph.putEdge(reader, blockedRepair, new Edge<>(EdgeType.SO, null));
        var state = gmwrState(history, graph);
        state.seedKnownDependencies();
        state.addGmwrItem(reader, bad, List.of(blockedRepair), "k1");
        state.addGmwrItem(reader, bad, List.of(otherRepair), "k2");

        assertTrue(state.propagate());
        assertTrue(state.isConflict());
    }

    @Test
    void emptyRepairsForceOutsideWhenOutsideIsStillPossible() {
        var history = new History<String, Integer>();
        var bad = history.addTransaction(history.addSession(1L), 1L);
        var blockedRepair = history.addTransaction(history.addSession(2L), 2L);
        var reader = history.addTransaction(history.addSession(3L), 3L);
        commitAll(history);

        var graph = new KnownGraph<String, Integer>(history);
        graph.putEdge(reader, blockedRepair, new Edge<>(EdgeType.SO, null));
        var state = gmwrState(history, graph);
        state.seedKnownDependencies();
        state.addGmwrItem(reader, bad, List.of(blockedRepair), "k");

        assertFalse(state.propagate());
        assertTrue(state.precedenceOracle().before(reader, bad));
        assertEquals(GmwrPropagationState.FactKind.DERIVED_ORDER,
                state.definiteFacts().iterator().next().kind);
    }

    private static void commitAll(History<?, ?> history) {
        history.getTransactions().forEach(
                txn -> txn.setStatus(Transaction.TransactionStatus.COMMIT));
    }

    private static <KeyType, ValueType> GmwrPropagationState<KeyType, ValueType>
            gmwrState(
                    History<KeyType, ValueType> history,
                    KnownGraph<KeyType, ValueType> graph) {
        return new GmwrPropagationState<>(history, graph,
                new PrecedenceOracle<>(history.getTransactions()));
    }
}
