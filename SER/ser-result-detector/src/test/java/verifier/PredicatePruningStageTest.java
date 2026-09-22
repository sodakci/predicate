package verifier;

import graph.Edge;
import graph.EdgeType;
import graph.KnownGraph;
import history.Event;
import history.History;
import history.Transaction;
import org.junit.jupiter.api.Test;
import util.Profiler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PredicatePruningStageTest {
    @Test
    void fixedPrWrSourceRemovesOneConstraintAndCreatesOneTypedEdge() {
        for (boolean prepropagation : List.of(false, true)) {
            var profiler = Profiler.getInstance();
            profiler.clear();
            var history = new History<String, Integer>();
            var bad = history.addTransaction(history.addSession(1L), 1L);
            var oldGood = history.addTransaction(history.addSession(2L), 2L);
            var good = history.addTransaction(history.addSession(3L), 3L);
            var reader = history.addTransaction(history.addSession(4L), 4L);
            var futureGood = history.addTransaction(history.addSession(5L), 5L);
            var futureBad = history.addTransaction(history.addSession(6L), 6L);
            history.addEvent(bad, Event.EventType.WRITE, "kv:x", 10);
            history.addEvent(oldGood, Event.EventType.WRITE, "kv:x", 0);
            history.addEvent(good, Event.EventType.WRITE, "kv:x", 1);
            history.addEvent(futureGood, Event.EventType.WRITE, "kv:x", 2);
            history.addEvent(futureBad, Event.EventType.WRITE, "kv:x", 11);
            history.addPredicateReadEvent(reader,
                    (PredicateFixtures.RowPredicate<String, Integer>) (key, value) -> value > 5,
                    List.of());
            history.getTransactions().forEach(txn -> txn.setStatus(Transaction.TransactionStatus.COMMIT));
            var graph = new KnownGraph<>(history);
            graph.putEdge(bad, good, new Edge<>(EdgeType.SO, null));
            graph.putEdge(oldGood, good, new Edge<>(EdgeType.SO, null));
            graph.putEdge(good, reader, new Edge<>(EdgeType.SO, null));
            graph.putEdge(reader, futureGood, new Edge<>(EdgeType.SO, null));
            graph.putEdge(reader, futureBad, new Edge<>(EdgeType.SO, null));
            var oracle = SERVerifier.createPrecedenceOracle(history);
            var settings = SERVerifier.SolverSettings.forModes(
                    SERVerifier.PredicateSolvingMode.GMWR, SERVerifier.PruningMode.NONE);
            settings.gmwrPrepropagation = prepropagation;
            var result = new PredicatePruning<>(history, graph, oracle, settings,
                    new PredicateAnalysis<>(graph, oracle)).prune();

            assertFalse(result.hasConflict());
            assertEquals(1, profiler.getCount("SER_PRED_PR_WR_INITIAL_CONSTRAINTS_COUNT"));
            assertEquals(0, profiler.getCount("SER_PRED_PR_WR_RESIDUAL_CONSTRAINTS_COUNT"));
            assertEquals(1, profiler.getCount("SER_PRED_PR_WR_FORCED_CONSTRAINTS_COUNT"));
            assertTrue(graph.getKnownGraphA().edgeValue(good, reader)
                    .orElse(List.of()).contains(new Edge<>(EdgeType.PR_WR, "kv:x")),
                    "被解决的 PR_WR 来源约束必须成为确定的 typed PR_WR 边");
            assertEquals(1, result.observation(graph.getPredicateObservations().get(0))
                    .rowKeys.get(0).sourceWrites.size());
            assertEquals(4, profiler.getCount("SER_PRED_PR_WR_INITIAL_CANDIDATES_COUNT"));
            assertEquals(0, profiler.getCount("SER_PRED_PR_WR_RESIDUAL_CANDIDATES_COUNT"));
            assertEquals(3, profiler.getCount("SER_PRED_PR_WR_PRUNED_CANDIDATES_COUNT"));
            assertEquals(1, profiler.getCount("SER_PRED_PR_WR_FIXED_CANDIDATES_COUNT"));
            var solver = new SERSolverAR<>(history, graph,
                    SERVerifier.generateConstraintsSER(history, graph), true, true, settings, oracle, result);
            assertEquals(SolveStatus.SAT, solver.solve());
            assertTrue(solver.getLogicalDependencies().stream().anyMatch(edge ->
                    edge.getType() == EdgeType.PR_WR && edge.getFrom().equals(good)
                            && edge.getTo().equals(reader) && edge.getKeys().contains("kv:x")));
        }
    }

    @Test
    void uniqueBottomResolvesTheConstraintWithoutATypedEdge() {
        for (boolean prepropagation : List.of(false, true)) {
            var profiler = Profiler.getInstance();
            profiler.clear();
            var history = new History<String, Integer>();
            var bottom = history.addTransaction(history.addSession(-1L), -1L);
            var good = history.addTransaction(history.addSession(1L), 1L);
            var reader = history.addTransaction(history.addSession(2L), 2L);
            history.addEvent(bottom, Event.EventType.WRITE, "kv:x", 0);
            history.addEvent(good, Event.EventType.WRITE, "kv:x", 1);
            history.addPredicateReadEvent(reader,
                    (PredicateFixtures.RowPredicate<String, Integer>) (key, value) -> value > 5,
                    List.of());
            history.getTransactions().forEach(txn -> txn.setStatus(Transaction.TransactionStatus.COMMIT));
            var graph = new KnownGraph<>(history);
            graph.putEdge(reader, good, new Edge<>(EdgeType.SO, null));
            var oracle = SERVerifier.createPrecedenceOracle(history);
            var settings = SERVerifier.SolverSettings.forModes(
                    SERVerifier.PredicateSolvingMode.GMWR, SERVerifier.PruningMode.NONE);
            settings.gmwrPrepropagation = prepropagation;
            var result = new PredicatePruning<>(history, graph, oracle, settings,
                    new PredicateAnalysis<>(graph, oracle)).prune();

            assertFalse(result.hasConflict());
            var candidates = result.observation(graph.getPredicateObservations().get(0))
                    .rowKeys.get(0).sourceWrites;
            assertEquals(1, candidates.size());
            assertEquals(bottom, candidates.get(0).getTxn());
            assertEquals(1, profiler.getCount("SER_PRED_PR_WR_INITIAL_CONSTRAINTS_COUNT"));
            assertEquals(0, profiler.getCount("SER_PRED_PR_WR_RESIDUAL_CONSTRAINTS_COUNT"));
            assertEquals(1, profiler.getCount("SER_PRED_PR_WR_FORCED_CONSTRAINTS_COUNT"));
            assertEquals(2, profiler.getCount("SER_PRED_PR_WR_INITIAL_CANDIDATES_COUNT"));
            assertEquals(0, profiler.getCount("SER_PRED_PR_WR_RESIDUAL_CANDIDATES_COUNT"));
            assertEquals(1, profiler.getCount("SER_PRED_PR_WR_PRUNED_CANDIDATES_COUNT"));
            assertEquals(1, profiler.getCount("SER_PRED_PR_WR_FIXED_CANDIDATES_COUNT"));
            assertTrue(graph.getKnownGraphA().edges().stream().noneMatch(ep ->
                    graph.getKnownGraphA().edgeValue(ep).orElse(List.of()).stream()
                            .anyMatch(edge -> edge.getType() == EdgeType.PR_WR)));
        }
    }

    @Test
    void sourceChoicesAreCountedPerObservationWhileTypedEdgesAreDeduplicated() {
        for (boolean existingEdge : List.of(false, true)) {
            var profiler = Profiler.getInstance();
            profiler.clear();
            var history = new History<String, Integer>();
            var good = history.addTransaction(history.addSession(1L), 1L);
            var reader = history.addTransaction(history.addSession(2L), 2L);
            history.addEvent(good, Event.EventType.WRITE, "kv:x", 0);
            for (int i = 0; i < 2; i++) {
                history.addPredicateReadEvent(reader,
                        new PredicateFixtures.RowPredicate<String, Integer>() {
                            @Override
                            public boolean test(String key, Integer value) {
                                return value > 5;
                            }
                        }, List.of());
            }
            history.getTransactions().forEach(txn -> txn.setStatus(Transaction.TransactionStatus.COMMIT));
            var graph = new KnownGraph<>(history);
            graph.putEdge(good, reader, new Edge<>(EdgeType.SO, null));
            var edge = new Edge<String>(EdgeType.PR_WR, "kv:x");
            if (existingEdge) {
                graph.putEdge(good, reader, edge);
            }
            assertTrue(graph.getPredicateObservations().stream().allMatch(observation ->
                    observation.getPredicateReadType("kv:x") == KnownGraph.PredicateReadType.EXTERNAL));
            var oracle = SERVerifier.createPrecedenceOracle(history);
            var settings = SERVerifier.SolverSettings.forModes(
                    SERVerifier.PredicateSolvingMode.GMWR, SERVerifier.PruningMode.NONE);
            var result = new PredicatePruning<>(history, graph, oracle, settings,
                    new PredicateAnalysis<>(graph, oracle)).prune();

            assertFalse(result.hasConflict());
            assertEquals(existingEdge ? 0 : 2,
                    profiler.getCount("SER_PRED_PR_WR_INITIAL_CONSTRAINTS_COUNT"));
            assertEquals(0, profiler.getCount("SER_PRED_PR_WR_RESIDUAL_CONSTRAINTS_COUNT"));
            assertEquals(existingEdge ? 0 : 2,
                    profiler.getCount("SER_PRED_PR_WR_FORCED_CONSTRAINTS_COUNT"));
            assertEquals(1, graph.getKnownGraphA().edgeValue(good, reader).orElse(List.of())
                    .stream().filter(edge::equals).count());
        }
    }

    @Test
    void explicitAndImplicitBottomChoicesCountWithoutRealSources() {
        for (boolean bottomOnly : List.of(false, true)) {
            var profiler = Profiler.getInstance();
            profiler.clear();
            var history = new History<String, Integer>();
            var writer = history.addTransaction(history.addSession(bottomOnly ? -1L : 1L),
                    bottomOnly ? -1L : 1L);
            var reader = history.addTransaction(history.addSession(2L), 2L);
            history.addEvent(writer, Event.EventType.WRITE, "kv:x", bottomOnly ? 0 : 10);
            history.addPredicateReadEvent(reader,
                    (PredicateFixtures.RowPredicate<String, Integer>) (key, value) -> value > 5,
                    List.of());
            history.getTransactions().forEach(txn -> txn.setStatus(Transaction.TransactionStatus.COMMIT));
            var graph = new KnownGraph<>(history);
            var oracle = SERVerifier.createPrecedenceOracle(history);
            var settings = SERVerifier.SolverSettings.forModes(
                    SERVerifier.PredicateSolvingMode.GMWR, SERVerifier.PruningMode.NONE);
            new PredicatePruning<>(history, graph, oracle, settings,
                    new PredicateAnalysis<>(graph, oracle)).prune();

            assertEquals(1, profiler.getCount("SER_PRED_PR_WR_INITIAL_CONSTRAINTS_COUNT"));
            assertEquals(0, profiler.getCount("SER_PRED_PR_WR_RESIDUAL_CONSTRAINTS_COUNT"));
            assertEquals(1, profiler.getCount("SER_PRED_PR_WR_FORCED_CONSTRAINTS_COUNT"));
        }
    }

    @Test
    void unresolvedPrWrConstraintsDoNotMultiplyBySourceAlternatives() {
        for (var mode : SERVerifier.PredicateSolvingMode.values()) {
            var profiler = Profiler.getInstance();
            profiler.clear();
            var history = twoKeyHistory();
            var extraRepair = history.addTransaction(history.addSession(4L), 4L);
            history.addEvent(extraRepair, Event.EventType.WRITE, "kv:x", 1);
            history.addEvent(extraRepair, Event.EventType.WRITE, "kv:y", 1);
            extraRepair.setStatus(Transaction.TransactionStatus.COMMIT);
            var graph = new KnownGraph<>(history);
            var oracle = SERVerifier.createPrecedenceOracle(history);
            var settings = SERVerifier.SolverSettings.forModes(mode, SERVerifier.PruningMode.NONE);
            var result = new PredicatePruning<>(history, graph, oracle, settings,
                    new PredicateAnalysis<>(graph, oracle)).prune();

            boolean gmwr = mode == SERVerifier.PredicateSolvingMode.GMWR;
            assertFalse(result.hasConflict());
            assertEquals(gmwr ? 2 : 0,
                    profiler.getCount("SER_PRED_PR_WR_INITIAL_CONSTRAINTS_COUNT"));
            assertEquals(gmwr ? 2 : 0,
                    profiler.getCount("SER_PRED_PR_WR_RESIDUAL_CONSTRAINTS_COUNT"));
            assertEquals(0, profiler.getCount("SER_PRED_PR_WR_FORCED_CONSTRAINTS_COUNT"));
            assertEquals(gmwr ? 6 : 0,
                    profiler.getCount("SER_PRED_PR_WR_INITIAL_CANDIDATES_COUNT"));
            assertEquals(gmwr ? 6 : 0,
                    profiler.getCount("SER_PRED_PR_WR_RESIDUAL_CANDIDATES_COUNT"));
            assertEquals(gmwr ? 2 : 0, result.residualItemCount());
            if (gmwr) {
                assertTrue(result.residualItems().stream().allMatch(item -> item.repairs.size() == 2));
            }
        }
    }

    @Test
    void soleLegalSourceForcesOrderWithoutPreexistingVisibility() {
        for (boolean prepropagation : List.of(false, true)) {
            var profiler = Profiler.getInstance();
            profiler.clear();
            var history = new History<String, Integer>();
            var bottom = history.addTransaction(history.addSession(-1L), -1L);
            var good = history.addTransaction(history.addSession(1L), 1L);
            var bad = history.addTransaction(history.addSession(2L), 2L);
            var reader = history.addTransaction(history.addSession(3L), 3L);
            history.addEvent(bottom, Event.EventType.WRITE, "kv:x", 10);
            history.addEvent(good, Event.EventType.WRITE, "kv:x", 0);
            history.addEvent(bad, Event.EventType.WRITE, "kv:x", 11);
            history.addPredicateReadEvent(reader,
                    (PredicateFixtures.RowPredicate<String, Integer>) (key, value) -> value > 5,
                    List.of());
            history.getTransactions().forEach(txn -> txn.setStatus(Transaction.TransactionStatus.COMMIT));
            var graph = new KnownGraph<>(history);
            var oracle = SERVerifier.createPrecedenceOracle(history);
            assertFalse(oracle.before(good, reader));
            var settings = SERVerifier.SolverSettings.forModes(
                    SERVerifier.PredicateSolvingMode.GMWR, SERVerifier.PruningMode.NONE);
            settings.gmwrPrepropagation = prepropagation;
            var result = new PredicatePruning<>(history, graph, oracle, settings,
                    new PredicateAnalysis<>(graph, oracle)).prune();

            assertFalse(result.hasConflict());
            assertTrue(oracle.before(good, reader), "唯一合法来源必须直接强制 source < reader");
            assertTrue(graph.getKnownGraphA().edgeValue(good, reader).orElse(List.of())
                    .contains(new Edge<>(EdgeType.PR_WR, "kv:x")));
            assertEquals(1, profiler.getCount("SER_PRED_PR_WR_INITIAL_CONSTRAINTS_COUNT"));
            assertEquals(0, profiler.getCount("SER_PRED_PR_WR_RESIDUAL_CONSTRAINTS_COUNT"));
            assertEquals(1, profiler.getCount("SER_PRED_PR_WR_FORCED_CONSTRAINTS_COUNT"));
            assertEquals(1, profiler.getCount("SER_PRED_PR_WR_INITIAL_CANDIDATES_COUNT"));
            assertEquals(1, profiler.getCount("SER_PRED_PR_WR_SOURCE_ALTERNATIVES_COUNT"),
                    "新增强制顺序触发域复查时，不得重复累计原来源候选");
            assertEquals(0, profiler.getCount("SER_PRED_PR_WR_RESIDUAL_CANDIDATES_COUNT"));
            assertEquals(0, profiler.getCount("SER_PRED_PR_WR_PRUNED_CANDIDATES_COUNT"));
            assertEquals(1, profiler.getCount("SER_PRED_PR_WR_FIXED_CANDIDATES_COUNT"));
            var solver = new SERSolverAR<>(history, graph,
                    SERVerifier.generateConstraintsSER(history, graph), true, true, settings, oracle, result);
            assertTrue(profiler.getCount("SER_PRED_DEPENDENCY_FIXED_CANDIDATES_COUNT") > 0);
            assertEquals(SolveStatus.SAT, solver.solve());
        }
    }

    @Test
    void forcedSourceOrderRevisitsEarlierSourceDomains() {
        var profiler = Profiler.getInstance();
        profiler.clear();
        var history = new History<String, Integer>();
        var bottom = history.addTransaction(history.addSession(-1L), -1L);
        var first = history.addTransaction(history.addSession(1L), 1L);
        var second = history.addTransaction(history.addSession(2L), 2L);
        var reader = history.addTransaction(history.addSession(3L), 3L);
        history.addEvent(bottom, Event.EventType.WRITE, "kv:z", 10);
        history.addEvent(first, Event.EventType.WRITE, "kv:z", 0);
        history.addEvent(first, Event.EventType.WRITE, "kv:a", 11);
        history.addEvent(second, Event.EventType.WRITE, "kv:a", 1);
        history.addPredicateReadEvent(reader,
                (PredicateFixtures.RowPredicate<String, Integer>) (key, value) -> value > 5,
                List.of());
        history.getTransactions().forEach(txn -> txn.setStatus(Transaction.TransactionStatus.COMMIT));
        var graph = new KnownGraph<>(history);
        var oracle = SERVerifier.createPrecedenceOracle(history);
        var settings = SERVerifier.SolverSettings.forModes(
                SERVerifier.PredicateSolvingMode.GMWR, SERVerifier.PruningMode.NONE);
        settings.gmwrPrepropagation = false;
        var result = new PredicatePruning<>(history, graph, oracle, settings,
                new PredicateAnalysis<>(graph, oracle)).prune();
        assertFalse(result.hasConflict());
        assertTrue(oracle.before(first, reader));
        assertTrue(oracle.before(second, reader));
        assertTrue(graph.getKnownGraphA().edgeValue(second, reader).orElse(List.of())
                .contains(new Edge<>(EdgeType.PR_WR, "kv:a")));
        assertEquals(2, profiler.getCount("SER_PRED_PR_WR_INITIAL_CONSTRAINTS_COUNT"));
        assertEquals(0, profiler.getCount("SER_PRED_PR_WR_RESIDUAL_CONSTRAINTS_COUNT"));
        assertEquals(2, profiler.getCount("SER_PRED_PR_WR_FORCED_CONSTRAINTS_COUNT"));
        assertEquals(3, profiler.getCount("SER_PRED_PR_WR_INITIAL_CANDIDATES_COUNT"));
        assertEquals(1, profiler.getCount("SER_PRED_PR_WR_PRUNED_CANDIDATES_COUNT"));
        var solver = new SERSolverAR<>(history, graph,
                SERVerifier.generateConstraintsSER(history, graph), true, true, settings, oracle, result);
        assertEquals(SolveStatus.SAT, solver.solve());
    }

    @Test
    void noLegalSourceIsAConflictNotAForcedChoice() {
        var profiler = Profiler.getInstance();
        profiler.clear();
        var history = new History<String, Integer>();
        var bottom = history.addTransaction(history.addSession(-1L), -1L);
        var good = history.addTransaction(history.addSession(1L), 1L);
        var reader = history.addTransaction(history.addSession(2L), 2L);
        history.addEvent(bottom, Event.EventType.WRITE, "kv:x", 10);
        history.addEvent(good, Event.EventType.WRITE, "kv:x", 0);
        history.addPredicateReadEvent(reader,
                (PredicateFixtures.RowPredicate<String, Integer>) (key, value) -> value > 5,
                List.of());
        history.getTransactions().forEach(txn -> txn.setStatus(Transaction.TransactionStatus.COMMIT));
        var graph = new KnownGraph<>(history);
        graph.putEdge(reader, good, new Edge<>(EdgeType.SO, null));
        var oracle = SERVerifier.createPrecedenceOracle(history);
        var settings = SERVerifier.SolverSettings.forModes(
                SERVerifier.PredicateSolvingMode.GMWR, SERVerifier.PruningMode.NONE);
        settings.gmwrPrepropagation = false;
        var result = new PredicatePruning<>(history, graph, oracle, settings,
                new PredicateAnalysis<>(graph, oracle)).prune();
        assertTrue(result.hasConflict());
        assertEquals(1, profiler.getCount("SER_PRED_PR_WR_INITIAL_CONSTRAINTS_COUNT"));
        assertEquals(1, profiler.getCount("SER_PRED_PR_WR_RESIDUAL_CONSTRAINTS_COUNT"));
        assertEquals(0, profiler.getCount("SER_PRED_PR_WR_FORCED_CONSTRAINTS_COUNT"));
        assertEquals(1, profiler.getCount("SER_PRED_PR_WR_PRUNED_CANDIDATES_COUNT"));
        assertEquals(0, profiler.getCount("SER_PRED_PR_WR_FIXED_CANDIDATES_COUNT"));
    }

    @Test
    void ordinaryPruningIsTimedInBatchesIndependentlyOfPrepropagation() {
        for (var mode : SERVerifier.PredicateSolvingMode.values()) {
            for (boolean prepropagation : List.of(false, true)) {
                var profiler = Profiler.getInstance();
                profiler.clear();
                var history = twoKeyHistory();
                var graph = new KnownGraph<>(history);
                var oracle = SERVerifier.createPrecedenceOracle(history);
                var settings = SERVerifier.SolverSettings.forModes(mode, SERVerifier.PruningMode.NONE);
                settings.gmwrPrepropagation = prepropagation;
                var result = new PredicatePruning<>(history, graph, oracle, settings,
                        new PredicateAnalysis<>(graph, oracle)).prune();

                boolean gmwr = mode == SERVerifier.PredicateSolvingMode.GMWR;
                assertFalse(result.hasConflict());
                assertEquals(gmwr ? 2 : 0, result.itemObligationCount());
                assertEquals(gmwr ? 2 : 1, profiler.getCounter("GMWR_PRUNING_MS"),
                        "普通剪枝应按前后两批计时，关闭预传播仍执行；EAGER 仅发布零值");
                assertEquals(gmwr && prepropagation ? 1 : 0, result.reductionSteps() > 0 ? 1 : 0);
                if (!gmwr) {
                    assertEquals(0, profiler.getTime("GMWR_PRUNING_MS"));
                }
                if (!gmwr || !prepropagation) {
                    assertEquals(0, profiler.getTime("GMWR_REDUCTION_MS"));
                }
            }
        }
    }

    @Test
    void sharedWriterCandidatesCannotBeChangedByAReader() {
        var history = twoKeyHistory();
        var graph = new KnownGraph<>(history);
        var writes = new ArrayList<KnownGraph.WriteRef<String, Integer>>();
        for (var write : graph.getAllWrites()) {
            if (write.getEvent().getKey().equals("kv:x")) {
                writes.add(write);
            }
        }
        var index = new PredicateAnalysis.KeyWriteIndex<>(0, "kv:x", writes);
        for (var reader : List.of(history.getTransaction(1L), history.getTransaction(3L))) {
            var expected = List.copyOf(index.latestExternalWrites(reader));
            assertThrows(UnsupportedOperationException.class,
                    () -> index.latestExternalWrites(reader).clear());
            assertEquals(expected, index.latestExternalWrites(reader));
            assertTrue(expected.stream().noneMatch(write -> write.getTxn().equals(reader)));
        }
    }

    @Test
    void preparedDomainsRemainReadOnlyAndKeepBadWritesInBothModes() {
        for (var mode : SERVerifier.PredicateSolvingMode.values()) {
            var history = twoKeyHistory();
            var graph = new KnownGraph<>(history);
            var oracle = SERVerifier.createPrecedenceOracle(history);
            var settings = SERVerifier.SolverSettings.forModes(mode, SERVerifier.PruningMode.NONE);
            var result = new PredicatePruning<>(history, graph, oracle, settings,
                    new PredicateAnalysis<>(graph, oracle)).prune();
            var prepared = result.observation(graph.getPredicateObservations().get(0));
            assertEquals(2, prepared.rowKeys.size());
            for (var row : prepared.rowKeys) {
                assertEquals(1, row.badWrites.size());
                assertEquals(10, row.badWrites.get(0).getEvent().getValue());
                assertFalse(row.emptyContributions.contains(row.badWrites.get(0)));
                assertThrows(UnsupportedOperationException.class, () -> row.externalWrites.clear());
                assertThrows(UnsupportedOperationException.class, () -> row.sourceWrites.clear());
                assertThrows(UnsupportedOperationException.class, () -> row.badWrites.clear());
                assertThrows(UnsupportedOperationException.class, () -> row.emptyContributions.clear());
            }
            assertThrows(UnsupportedOperationException.class, () -> prepared.rowKeys.clear());
            assertThrows(UnsupportedOperationException.class, () -> prepared.sources.clear());
        }
    }

    @Test
    void residualCountExcludesSatisfiedItemsWithinAnUnresolvedPair() {
        Profiler.getInstance().clear();
        var history = new History<String, Integer>();
        var bad = history.addTransaction(history.addSession(1L), 1L);
        var repairX = history.addTransaction(history.addSession(2L), 2L);
        var repairY1 = history.addTransaction(history.addSession(3L), 3L);
        var repairY2 = history.addTransaction(history.addSession(4L), 4L);
        var reader = history.addTransaction(history.addSession(5L), 5L);
        history.addEvent(bad, Event.EventType.WRITE, "kv:x", 10);
        history.addEvent(bad, Event.EventType.WRITE, "kv:y", 10);
        history.addEvent(repairX, Event.EventType.WRITE, "kv:x", 0);
        history.addEvent(repairY1, Event.EventType.WRITE, "kv:y", 0);
        history.addEvent(repairY2, Event.EventType.WRITE, "kv:y", 1);
        history.addPredicateReadEvent(reader,
                (PredicateFixtures.RowPredicate<String, Integer>) (key, value) -> value > 5,
                List.of());
        history.getTransactions().forEach(txn ->
                txn.setStatus(Transaction.TransactionStatus.COMMIT));
        var graph = new KnownGraph<>(history);
        graph.putEdge(bad, reader, new Edge<>(EdgeType.SO, null));
        var oracle = SERVerifier.createPrecedenceOracle(history);
        var settings = SERVerifier.SolverSettings.forModes(
                SERVerifier.PredicateSolvingMode.GMWR, SERVerifier.PruningMode.NONE);
        var result = new PredicatePruning<>(history, graph, oracle, settings,
                new PredicateAnalysis<>(graph, oracle)).prune();

        assertFalse(result.hasConflict());
        assertEquals(2, result.itemObligationCount());
        assertTrue(oracle.before(bad, repairX));
        assertTrue(oracle.before(repairX, reader));
        assertEquals(1, result.residualItems().size());
        assertEquals(result.residualItems().size(), result.residualItemCount());
        assertEquals(2, Profiler.getInstance().getCount("GMWR_INITIAL_CONSTRAINTS"));
        assertEquals(1, Profiler.getInstance().getCount("GMWR_RESIDUAL_CONSTRAINTS"),
                "同组已满足的 item 不应计入残余数量");
    }

    @Test
    void enabledPropagationMetricsCountUnresolvedItemsWithinOnePair() {
        Profiler.getInstance().clear();
        var history = twoKeyHistory();
        var graph = new KnownGraph<>(history);
        var oracle = SERVerifier.createPrecedenceOracle(history);
        var settings = SERVerifier.SolverSettings.forModes(
                SERVerifier.PredicateSolvingMode.GMWR, SERVerifier.PruningMode.NONE);
        var result = new PredicatePruning<>(history, graph, oracle, settings,
                new PredicateAnalysis<>(graph, oracle)).prune();

        assertFalse(result.hasConflict());
        assertEquals(2, Profiler.getInstance().getCount("GMWR_INITIAL_CONSTRAINTS"));
        assertEquals(2, Profiler.getInstance().getCount("GMWR_RESIDUAL_CONSTRAINTS"),
                "预传播未解决两个 key 的义务，不能按单个事务对计数");
    }

    @Test
    void candidatePruningFinishesBeforeSatEncoding() {
        var profiler = Profiler.getInstance();
        profiler.clear();
        var history = twoKeyHistory();
        var graph = new KnownGraph<>(history);
        graph.putEdge(history.getTransaction(1L), history.getTransaction(2L),
                new Edge<>(EdgeType.SO, null));
        graph.putEdge(history.getTransaction(2L), history.getTransaction(3L),
                new Edge<>(EdgeType.SO, null));
        var oracle = SERVerifier.createPrecedenceOracle(history);
        var settings = SERVerifier.SolverSettings.forModes(
                SERVerifier.PredicateSolvingMode.GMWR, SERVerifier.PruningMode.NONE);
        var analysis = new PredicateAnalysis<>(graph, oracle);
        var result = new PredicatePruning<>(history, graph, oracle, settings, analysis).prune();
        var prepared = result.observation(graph.getPredicateObservations().get(0));
        assertEquals(2, prepared.rowKeys.size());
        for (var key : prepared.rowKeys) {
            assertEquals(1, key.sourceWrites.size());
            assertEquals(history.getTransaction(2L), key.sourceWrites.get(0).getTxn());
            assertThrows(UnsupportedOperationException.class, () -> key.sourceWrites.clear());
        }
        long pruningCount = result.intervalCandidatesPruned();
        assertEquals(4, pruningCount);
        var solver = new SERSolverAR<>(history, graph,
                SERVerifier.generateConstraintsSER(history, graph), true, true, settings, oracle, result);
        assertEquals(SolveStatus.SAT, solver.solve());
        assertEquals(result.intervalCandidatesPruned(),
                profiler.getCount("SER_GMWR_INTERVAL_CANDIDATES_PRUNED_COUNT"),
                "SAT 编码不得继续执行候选剪枝或增加剪枝阶段计数");
        assertEquals(pruningCount, result.intervalCandidatesPruned());
    }

    @Test
    void disabledPropagationPreservesEveryItemForSat() {
        Profiler.getInstance().clear();
        var history = twoKeyHistory();
        var graph = new KnownGraph<>(history);
        var oracle = SERVerifier.createPrecedenceOracle(history);
        var settings = SERVerifier.SolverSettings.forModes(
                SERVerifier.PredicateSolvingMode.GMWR, SERVerifier.PruningMode.NONE);
        settings.gmwrPrepropagation = false;
        var result = new PredicatePruning<>(history, graph, oracle, settings,
                new PredicateAnalysis<>(graph, oracle)).prune();

        assertSame(oracle, result.precedenceOracle());
        assertFalse(result.hasConflict());
        assertEquals(2, result.itemObligationCount());
        assertEquals(2, result.residualItemCount());
        assertEquals(2, Profiler.getInstance().getCount("GMWR_INITIAL_CONSTRAINTS"));
        assertEquals(2, Profiler.getInstance().getCount("GMWR_RESIDUAL_CONSTRAINTS"),
                "关闭预传播时，同一事务对的两个 item 仍应计为两个");
        assertEquals(0, result.reductionSteps());
        var calls = new AtomicInteger();
        settings.satSolveBackend = (solver, assumptions) -> {
            calls.incrementAndGet();
            return solver.solve(assumptions);
        };
        var solver = new SERSolverAR<>(history, graph,
                SERVerifier.generateConstraintsSER(history, graph), true, true, settings, oracle, result);
        assertEquals(2, Profiler.getInstance().getCount("SER_GMWR_RESIDUAL_CLAUSES_COUNT"));
        assertEquals(2, Profiler.getInstance().getCount("SER_GMWR_ITEM_OBLIGATIONS_COUNT"));
        assertEquals(SolveStatus.SAT, solver.solve());
        assertEquals(1, calls.get());
    }

    @Test
    void encoderRejectsDifferentPrecedenceOracle() {
        var history = twoKeyHistory();
        var graph = new KnownGraph<>(history);
        var oracle = SERVerifier.createPrecedenceOracle(history);
        var settings = SERVerifier.SolverSettings.forModes(
                SERVerifier.PredicateSolvingMode.GMWR, SERVerifier.PruningMode.NONE);
        var result = new PredicatePruning<>(history, graph, oracle, settings,
                new PredicateAnalysis<>(graph, oracle)).prune();
        var differentOracle = SERVerifier.createPrecedenceOracle(history);
        var error = assertThrows(IllegalArgumentException.class, () -> new SERSolverAR<>(
                history, graph, List.of(), true, true, settings, differentOracle, result));
        assertEquals("predicate pruning must share precedence oracle", error.getMessage());
    }

    @Test
    void eagerHasNoGmwrState() {
        var history = twoKeyHistory();
        var graph = new KnownGraph<>(history);
        var oracle = SERVerifier.createPrecedenceOracle(history);
        var settings = SERVerifier.SolverSettings.forModes(
                SERVerifier.PredicateSolvingMode.EAGER, SERVerifier.PruningMode.NONE);
        var analysis = new PredicateAnalysis<>(graph, oracle);
        var result = new PredicatePruning<>(history, graph, oracle, settings, analysis).prune();

        assertSame(oracle, result.precedenceOracle());
        assertSame(analysis, result.analysis());
        assertTrue(result.definiteFacts().isEmpty());
        assertTrue(result.residualItems().isEmpty());
        assertEquals(0, result.reductionSteps());
        assertEquals(0, result.itemObligationCount());
        assertEquals(0, result.residualItemCount());
        assertFalse(result.hasConflict());
    }

    @Test
    void wwFactsAreVisibleThroughSharedGraphAndOracle() {
        var history = twoKeyHistory();
        var graph = new KnownGraph<>(history);
        var bad = history.getTransaction(1L);
        var repair = history.getTransaction(2L);
        graph.putEdge(bad, repair, new Edge<>(EdgeType.SO, null));
        var oracle = SERVerifier.createPrecedenceOracle(history);
        var constraints = SERVerifier.generateConstraintsSER(history, graph);
        assertFalse(new Pruning<String, Integer>(oracle, false).pruneConstraints(graph, constraints));
        var settings = SERVerifier.SolverSettings.forModes(
                SERVerifier.PredicateSolvingMode.GMWR, SERVerifier.PruningMode.REACHABILITY);
        var result = new PredicatePruning<>(history, graph, oracle, settings,
                new PredicateAnalysis<>(graph, oracle)).prune();

        assertSame(oracle, result.precedenceOracle());
        assertTrue(result.precedenceOracle().before(bad, repair));
        assertTrue(graph.getKnownGraphA().edgeValue(bad, repair).orElse(List.of())
                .contains(new Edge<>(EdgeType.WW, "kv:x")));
        assertFalse(result.hasConflict());
    }

    private static History<String, Integer> twoKeyHistory() {
        var history = new History<String, Integer>();
        var bad = history.addTransaction(history.addSession(1L), 1L);
        var repair = history.addTransaction(history.addSession(2L), 2L);
        var reader = history.addTransaction(history.addSession(3L), 3L);
        for (var key : List.of("kv:x", "kv:y")) {
            history.addEvent(bad, Event.EventType.WRITE, key, 10);
            history.addEvent(repair, Event.EventType.WRITE, key, 0);
        }
        history.addPredicateReadEvent(reader,
                new PredicateFixtures.RowPredicate<String, Integer>() {
                    @Override
                    public boolean test(String key, Integer value) {
                        return value > 5;
                    }
                }, List.of());
        history.getTransactions().forEach(txn ->
                txn.setStatus(Transaction.TransactionStatus.COMMIT));
        return history;
    }

    @Test
    void deterministicPredicateConflictRejectsBeforeSatBackend() {
        Profiler.getInstance().clear();
        var history = new History<String, Integer>();
        var session = history.addSession(1L);
        var writer = history.addTransaction(session, 1L);
        var reader = history.addTransaction(session, 2L);
        history.addEvent(writer, Event.EventType.WRITE, "kv:x", 10);
        history.addPredicateReadEvent(reader,
                new PredicateFixtures.RowPredicate<String, Integer>() {
                    @Override
                    public boolean test(String key, Integer value) {
                        return value > 5;
                    }
                }, List.of());
        history.getTransactions().forEach(txn ->
                txn.setStatus(Transaction.TransactionStatus.COMMIT));

        var settings = SERVerifier.SolverSettings.forModes(
                SERVerifier.PredicateSolvingMode.GMWR,
                SERVerifier.PruningMode.NONE);
        var calls = new AtomicInteger();
        var stages = new ArrayList<SERVerifier.AuditStage>();
        settings.auditProgressListener = stages::add;
        settings.satSolveBackend = (solver, assumptions) -> {
            calls.incrementAndGet();
            return solver.solve(assumptions);
        };

        assertEquals(SERVerifier.AuditResult.REJECT,
                new SERVerifier<>(() -> history, settings, true).audit());
        assertEquals(0, calls.get());
        assertEquals(0, Profiler.getInstance().getCounter("SER_AR_ENCODE_SETUP"));
        assertEquals(List.of(SERVerifier.AuditStage.WW, SERVerifier.AuditStage.GMWR), stages);
    }
}
