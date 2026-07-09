package verifier;

import graph.EdgeType;
import graph.Edge;
import graph.KnownGraph;
import history.Event;
import history.History;
import history.Transaction;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static history.Event.EventType.READ;
import static history.Event.EventType.WRITE;
import static org.junit.jupiter.api.Assertions.*;

class SERSolverARSatEncodingTest {

    private static History<String, Integer> makeHistory(
            Set<Long> sessions,
            Map<Long, List<Long>> sessionToTxns,
            Map<Long, List<Triple<Event.EventType, String, Integer>>> normalEvents,
            Map<Long, Pair<Event.PredEval<String, Integer>, List<Event.PredResult<String, Integer>>>> predicateReads) {
        var h = new History<>(sessions, sessionToTxns, normalEvents);
        for (var entry : predicateReads.entrySet()) {
            h.addPredicateReadEvent(h.getTransaction(entry.getKey()), entry.getValue().getLeft(), entry.getValue().getRight());
        }
        return h;
    }

    @SuppressWarnings("unchecked")
    private static Collection<SERConstraint<String, Integer>> generateConstraints(
            History<String, Integer> history,
            KnownGraph<String, Integer> graph) {
        try {
            Method method = SERVerifier.class.getDeclaredMethod("generateConstraints", History.class, KnownGraph.class);
            method.setAccessible(true);
            return (Collection<SERConstraint<String, Integer>>) method.invoke(null, history, graph);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static boolean verifySer(History<String, Integer> history) {
        return new SERVerifier<>(() -> history).audit();
    }

    private static void commitAll(History<?, ?> history) {
        history.getTransactions().forEach(txn -> txn.setStatus(Transaction.TransactionStatus.COMMIT));
    }

    private static Event.PredEval<String, Integer> keyAtLeast(String key, int threshold) {
        return (k, v) -> key.equals(k) && v >= threshold;
    }

    private static boolean solveSer(History<String, Integer> history) {
        var graph = new KnownGraph<>(history);
        return new SERSolverAR<>(history, graph, generateConstraints(history, graph)).solve();
    }

    private static History<String, Integer> singleTxnHistory() {
        var history = new History<String, Integer>();
        var session = history.addSession(1L);
        history.addTransaction(session, 1L);
        return history;
    }

    @Test
    void arTotalOrderCreatesVariablesForEveryTransactionPair() {
        var history = makeHistory(
                Set.of(1L),
                Map.of(1L, List.of(1L, 2L, 3L)),
                Map.of(),
                Map.of());
        var graph = new KnownGraph<>(history);
        var solver = new SERSolverAR<>(history, graph, List.of());

        assertEquals(6, solver.getArVariableCount());
        assertTrue(solver.solve());
    }

    @Test
    void arTotalOrderDoesNotCreateVariablesForBottomTransaction() {
        var history = makeHistory(
                Set.of(-1L, 1L),
                Map.of(-1L, List.of(-1L), 1L, List.of(1L, 2L)),
                Map.of(
                        -1L, List.of(Triple.of(WRITE, "x", 0)),
                        1L, List.of(Triple.of(READ, "x", 0)),
                        2L, List.of(Triple.of(WRITE, "y", 1))),
                Map.of());
        var graph = new KnownGraph<>(history);
        var solver = new SERSolverAR<>(history, graph, List.of());

        assertEquals(2, solver.getArVariableCount());
        assertTrue(solver.solve());
    }

    @Test
    void bottomWriteIsAlwaysOrderedBeforeRealWritersInWwChoices() {
        var history = makeHistory(
                Set.of(-1L, 1L),
                Map.of(-1L, List.of(-1L), 1L, List.of(2L, 1L)),
                Map.of(
                        -1L, List.of(Triple.of(WRITE, "x", 0)),
                        1L, List.of(Triple.of(READ, "x", 0)),
                        2L, List.of(Triple.of(WRITE, "x", 1))),
                Map.of());
        var graph = new KnownGraph<>(history);
        var solver = new SERSolverAR<>(history, graph, generateConstraints(history, graph));

        assertFalse(solver.solve(), "bottom < writer fixes the WW choice and derives reader RW writer");
    }

    @Test
    void rwIsDerivedBySatFromWrAndWw() {
        var history = makeHistory(
                Set.of(1L, 2L),
                Map.of(1L, List.of(1L), 2L, List.of(3L, 2L)),
                Map.of(
                        1L, List.of(Triple.of(WRITE, "x", 1), Triple.of(WRITE, "z", 1)),
                        3L, List.of(Triple.of(READ, "z", 1), Triple.of(WRITE, "x", 2)),
                        2L, List.of(Triple.of(READ, "x", 1))),
                Map.of());

        var graph = new KnownGraph<>(history);
        assertFalse(graph.getKnownGraphB().hasEdgeConnecting(history.getTransaction(2L), history.getTransaction(3L)));

        var solver = new SERSolverAR<>(history, graph, generateConstraints(history, graph));
        assertFalse(solver.solve());
    }

    @Test
    void rwEncodingUsesWrWriterToLaterWriterDirection() {
        var history = makeHistory(
                Set.of(1L, 2L),
                Map.of(1L, List.of(3L, 1L), 2L, List.of(2L)),
                Map.of(
                        1L, List.of(Triple.of(WRITE, "x", 1), Triple.of(WRITE, "z", 1)),
                        2L, List.of(Triple.of(READ, "z", 1), Triple.of(WRITE, "x", 2), Triple.of(WRITE, "y", 1)),
                        3L, List.of(Triple.of(READ, "y", 1), Triple.of(READ, "x", 1))),
                Map.of());

        var graph = new KnownGraph<>(history);
        var solver = new SERSolverAR<>(history, graph, generateConstraints(history, graph));

        assertFalse(solver.solve());
    }

    @Test
    void predicateEdgesAreEncodedInSatWithoutRefreshPrecomputation() {
        var history = makeHistory(
                Set.of(1L, 2L, 3L),
                Map.of(1L, List.of(1L), 2L, List.of(3L), 3L, List.of(2L)),
                Map.of(
                        1L, List.of(Triple.of(WRITE, "x", 10), Triple.of(WRITE, "z", 1)),
                        3L, List.of(Triple.of(READ, "z", 1), Triple.of(WRITE, "x", 3), Triple.of(WRITE, "y", 1)),
                        2L, List.of(Triple.of(READ, "y", 1))),
                Map.of(2L, Pair.of(
                        (Event.PredEval<String, Integer>) (k, v) -> v > 5,
                        List.of(new Event.PredResult<>("x", 10)))));

        var graph = new KnownGraph<>(history);
        assertEquals(0L, countEdgesOfType(graph.getKnownGraphA(), EdgeType.PR_WR));
        assertEquals(0L, countEdgesOfType(graph.getKnownGraphB(), EdgeType.PR_RW));

        var solver = new SERSolverAR<>(history, graph, generateConstraints(history, graph));
        assertFalse(solver.solve());
    }

    @Test
    void predicateResultSourceMustPrecedePredicateReader() {
        var history = makeHistory(
                Set.of(1L),
                Map.of(1L, List.of(2L, 1L)),
                Map.of(1L, List.of(Triple.of(WRITE, "x", 10))),
                Map.of(2L, Pair.of(
                        (Event.PredEval<String, Integer>) (k, v) -> v > 5,
                        List.of(new Event.PredResult<>("x", 10)))));

        var graph = new KnownGraph<>(history);
        var solver = new SERSolverAR<>(history, graph, generateConstraints(history, graph));

        assertFalse(solver.solve(), "predicate result source must be visible before the predicate read");
    }

    @Test
    void predicateResultSourceMustBeLatestVisibleWrite() {
        var history = makeHistory(
                Set.of(1L),
                Map.of(1L, List.of(1L, 2L, 3L)),
                Map.of(
                        1L, List.of(Triple.of(WRITE, "x", 10)),
                        2L, List.of(Triple.of(WRITE, "x", 20))),
                Map.of(3L, Pair.of(
                        (Event.PredEval<String, Integer>) (k, v) -> v > 5,
                        List.of(new Event.PredResult<>("x", 10)))));

        var graph = new KnownGraph<>(history);
        var solver = new SERSolverAR<>(history, graph, generateConstraints(history, graph));

        assertFalse(solver.solve(), "predicate result source must be the latest visible write under AR");
    }

    @Test
    void unresolvedPredicateOrderSkippedByRefreshButRejectedByNewSatSolver() {
        var history = makeHistory(
                Set.of(1L, 2L, 3L),
                Map.of(1L, List.of(1L), 2L, List.of(2L, 4L), 3L, List.of(3L)),
                Map.of(
                        1L, List.of(Triple.of(WRITE, "x", 10), Triple.of(READ, "z", 1)),
                        3L, List.of(Triple.of(WRITE, "x", 3)),
                        4L, List.of(Triple.of(WRITE, "z", 1))),
                Map.of(2L, Pair.of(
                        (Event.PredEval<String, Integer>) (k, v) -> v > 5,
                        List.of(new Event.PredResult<>("x", 10)))));
        commitAll(history);

        var graph = new KnownGraph<>(history);
        SERVerifier.refreshDerivedPredicateEdges(history, graph);

        assertEquals(0L, countEdgesOfType(graph.getKnownGraphA(), EdgeType.PR_WR));
        assertFalse(verifySer(history));
    }

    @Test
    void predRwUsesFlipWitnessTNotLaterWriterU() {
        var history = makeHistory(
                Set.of(1L, 2L, 3L),
                Map.of(1L, List.of(1L), 2L, List.of(3L), 3L, List.of(2L)),
                Map.of(
                        1L, List.of(Triple.of(WRITE, "x", 10), Triple.of(WRITE, "z", 1)),
                        3L, List.of(Triple.of(READ, "z", 1), Triple.of(WRITE, "x", 8), Triple.of(WRITE, "y", 1)),
                        2L, List.of(Triple.of(READ, "y", 1))),
                Map.of(2L, Pair.of(
                        (Event.PredEval<String, Integer>) (k, v) -> v > 5,
                        List.of(new Event.PredResult<>("x", 10)))));

        var graph = new KnownGraph<>(history);
        assertEquals(0L, countEdgesOfType(graph.getKnownGraphA(), EdgeType.PR_WR));
        assertEquals(0L, countEdgesOfType(graph.getKnownGraphB(), EdgeType.PR_RW));

        var solver = new SERSolverAR<>(history, graph, generateConstraints(history, graph));
        assertFalse(solver.solve());
    }

    @Test
    void predRwDependsOnLaterWriterChangingAbsentResult() {
        var history = makeHistory(
                Set.of(1L, 2L),
                Map.of(1L, List.of(1L, 2L, 3L), 2L, List.of(4L)),
                Map.of(
                        1L, List.of(Triple.of(WRITE, "x", 10)),
                        2L, List.of(Triple.of(WRITE, "x", 3)),
                        3L, List.of(Triple.of(WRITE, "x", 8), Triple.of(WRITE, "y", 1)),
                        4L, List.of(Triple.of(READ, "y", 1))),
                Map.of(4L, Pair.of(
                        (Event.PredEval<String, Integer>) (k, v) -> k.equals("x") && v > 5,
                        List.of())));

        var graph = new KnownGraph<>(history);
        var solver = new SERSolverAR<>(history, graph, generateConstraints(history, graph));

        assertFalse(solver.solve(), "later writer U makes absent key satisfy the predicate, so S must precede U");
    }

    @Test
    void predicateAbsentResultRejectsMatchingVisibleFrontier() {
        var history = makeHistory(
                Set.of(1L),
                Map.of(1L, List.of(1L, 2L)),
                Map.of(1L, List.of(Triple.of(WRITE, "x", 10))),
                Map.of(2L, Pair.of(
                        (Event.PredEval<String, Integer>) (k, v) -> k.equals("x") && v > 5,
                        List.of())));

        var graph = new KnownGraph<>(history);
        var solver = new SERSolverAR<>(history, graph, generateConstraints(history, graph));

        assertFalse(solver.solve(), "empty predicate result is invalid when the latest visible x satisfies P");
    }

    @Test
    void multiplePredicateReadsInOneTransactionSecondReadSeesPriorSelfWrite() {
        var history = singleTxnHistory();
        var txn = history.getTransaction(1L);
        history.addEvent(txn, WRITE, "x", 100);
        history.addPredicateReadEvent(txn, keyAtLeast("x", 100),
                List.of(new Event.PredResult<>("x", 100)));
        history.addEvent(txn, WRITE, "y", 120);
        history.addPredicateReadEvent(txn, keyAtLeast("y", 100),
                List.of(new Event.PredResult<>("y", 120)));
        commitAll(history);

        // Theory: only one real transaction exists. The serial order [T1] is
        // valid. The first PR observes self W(x=100); the second PR observes
        // the later self W(y=120). Required edges: no cross-txn SO/WR/WW/RW,
        // and no cross-txn PR_WR/PR_RW.
        assertTrue(solveSer(history),
                "the second predicate read must use its own event position and see W(y=120)");
    }

    @Test
    void multiplePredicateReadsInOneTransactionHaveSeparateObservationBoundaries() {
        var history = singleTxnHistory();
        var txn = history.getTransaction(1L);
        history.addPredicateReadEvent(txn, keyAtLeast("y", 100), List.of());
        history.addEvent(txn, WRITE, "y", 120);
        history.addPredicateReadEvent(txn, keyAtLeast("y", 100),
                List.of(new Event.PredResult<>("y", 120)));
        commitAll(history);

        // Theory: serial order [T1]. PR1 has empty result before the self write;
        // PR2 returns y after the self write. The two predicate-read boundaries
        // are different and must not both resolve to PR1's index.
        assertTrue(solveSer(history),
                "later predicate reads must not be positioned at an equal earlier PR event");
    }

    @Test
    void predicateReadMissingEarlierSelfWriteIsRejected() {
        var history = singleTxnHistory();
        var txn = history.getTransaction(1L);
        history.addEvent(txn, WRITE, "y", 120);
        history.addPredicateReadEvent(txn, keyAtLeast("y", 100), List.of());
        commitAll(history);

        // Theory: serial order [T1] still includes T1's own earlier W(y=120).
        // The empty PR misses a matching visible tuple, so no SER order can
        // explain the observed predicate result.
        assertFalse(solveSer(history),
                "a predicate read must reject an empty result when an earlier self-write matches");
    }

    @Test
    void committedUnresolvedPredicateOrderIsRejectedByArSatNotLegacyRefresh() {
        var history = makeHistory(
                Set.of(1L, 2L, 3L),
                Map.of(1L, List.of(1L), 2L, List.of(2L, 4L), 3L, List.of(3L)),
                Map.of(
                        1L, List.of(Triple.of(WRITE, "x", 10), Triple.of(READ, "z", 1)),
                        3L, List.of(Triple.of(WRITE, "x", 3)),
                        4L, List.of(Triple.of(WRITE, "z", 1))),
                Map.of(2L, Pair.of(
                        (Event.PredEval<String, Integer>) (k, v) -> k.equals("x") && v > 5,
                        List.of(new Event.PredResult<>("x", 10)))));
        commitAll(history);

        var graph = new KnownGraph<>(history);
        SERVerifier.refreshDerivedPredicateEdges(history, graph);

        // Theory: PR_WR T1->T2 for x, SO T2->T4, and WR T4->T1 for z form
        // a SER cycle. The x writers T1/T3 are initially unordered, so the
        // Derived-edge refresh has no reliable PR_* edge before SAT. The AR SAT
        // path must still reject.
        assertEquals(0L, countEdgesOfType(graph.getKnownGraphA(), EdgeType.PR_WR));
        assertFalse(solveSer(history),
                "strict total AR predicate constraints must detect cycles even when derived PR refresh skips them");
    }

    @Test
    void pruningForcesTheOnlyConsistentWwDirection() {
        var history = makeHistory(
                Set.of(1L, 2L, 3L),
                Map.of(1L, List.of(1L), 2L, List.of(2L), 3L, List.of(3L)),
                Map.of(
                        1L, List.of(Triple.of(WRITE, "x", 1), Triple.of(WRITE, "z", 1)),
                        2L, List.of(Triple.of(READ, "z", 1), Triple.of(WRITE, "y", 1)),
                        3L, List.of(Triple.of(READ, "y", 1), Triple.of(WRITE, "x", 2))),
                Map.of());

        var graph = new KnownGraph<>(history);
        var constraints = generateConstraints(history, graph);

        assertEquals(1, constraints.size());
        assertFalse(graph.getKnownGraphA().hasEdgeConnecting(history.getTransaction(1L), history.getTransaction(3L)));
        assertFalse(graph.getKnownGraphA().hasEdgeConnecting(history.getTransaction(3L), history.getTransaction(1L)));

        boolean hasLoop = Pruning.pruneConstraints(graph, constraints, history);

        assertFalse(hasLoop);
        assertTrue(constraints.isEmpty());
        assertTrue(graph.getKnownGraphA().hasEdgeConnecting(history.getTransaction(1L), history.getTransaction(3L)));
        assertFalse(graph.getKnownGraphA().hasEdgeConnecting(history.getTransaction(3L), history.getTransaction(1L)));
    }

    @Test
    void conflictCoreReportsKnownEdgeCycleWhenGraphAloneIsUnsat() {
        var history = makeHistory(
                Set.of(1L),
                Map.of(1L, List.of(1L, 2L)),
                Map.of(1L, List.of(Triple.of(WRITE, "x", 1)),
                        2L, List.of(Triple.of(WRITE, "y", 1))),
                Map.of());

        var graph = new KnownGraph<>(history);
        graph.putEdge(history.getTransaction(1L), history.getTransaction(2L), new Edge<>(EdgeType.WW, "x"));
        graph.putEdge(history.getTransaction(2L), history.getTransaction(1L), new Edge<>(EdgeType.RW, "y"));

        var solver = new SERSolverAR<>(history, graph, List.of());

        assertFalse(solver.solve());
        var conflicts = solver.getConflicts();
        assertFalse(conflicts.getLeft().isEmpty(), "known-edge cycle should be reported");
        assertTrue(conflicts.getRight().isEmpty(), "no choice constraints are needed for this contradiction");
    }

    @Test
    void solverIgnoresStalePredicateEdgesInKnownGraph() {
        var history = makeHistory(
                Set.of(1L),
                Map.of(1L, List.of(1L, 2L)),
                Map.of(1L, List.of(Triple.of(WRITE, "x", 1)),
                        2L, List.of(Triple.of(WRITE, "y", 1))),
                Map.of());

        var graph = new KnownGraph<>(history);
        graph.putEdge(history.getTransaction(1L), history.getTransaction(2L), new Edge<>(EdgeType.PR_WR, "x"));
        graph.putEdge(history.getTransaction(2L), history.getTransaction(1L), new Edge<>(EdgeType.PR_RW, "x"));

        var solver = new SERSolverAR<>(history, graph, List.of());

        assertTrue(solver.solve(), "stale derived predicate edges must not be forced into AR");
    }

    @Test
    void conflictCoreReportsUnsatConstraintSubset() {
        var history = makeHistory(
                Set.of(1L, 2L, 3L),
                Map.of(1L, List.of(1L), 2L, List.of(2L), 3L, List.of(3L)),
                Map.of(
                        1L, List.of(Triple.of(WRITE, "x", 1)),
                        2L, List.of(Triple.of(WRITE, "y", 1)),
                        3L, List.of(Triple.of(WRITE, "z", 1))),
                Map.of());

        var graph = new KnownGraph<>(history);
        graph.putEdge(history.getTransaction(1L), history.getTransaction(2L), new Edge<>(EdgeType.SO, null));
        graph.putEdge(history.getTransaction(3L), history.getTransaction(1L), new Edge<>(EdgeType.RW, "z"));

        var constraint = new SERConstraint<>(
                List.of(new SEREdge<>(history.getTransaction(2L), history.getTransaction(3L), EdgeType.RW, "x")),
                List.of(new SEREdge<>(history.getTransaction(2L), history.getTransaction(1L), EdgeType.RW, "y")),
                history.getTransaction(2L),
                history.getTransaction(3L),
                0);
        var constraints = List.of(constraint);
        var solver = new SERSolverAR<>(history, graph, constraints);

        assertFalse(solver.solve());
        var conflicts = solver.getConflicts();
        assertFalse(conflicts.getRight().isEmpty(), "unsat remaining WW choices should be reported");
    }

    private static long countEdgesOfType(
            com.google.common.graph.ValueGraph<history.Transaction<String, Integer>, Collection<graph.Edge<String>>> graph,
            EdgeType type) {
        return graph.edges().stream()
                .flatMap(ep -> graph.edgeValue(ep).orElse(List.of()).stream())
                .filter(edge -> edge.getType() == type)
                .count();
    }
}
