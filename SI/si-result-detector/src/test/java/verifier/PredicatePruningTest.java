package verifier;

import graph.EdgeType;
import graph.KnownGraph;
import history.History;
import history.Transaction;
import history.loaders.PredicateHistoryLoader;
import history.loaders.PredicateHistoryLoader.PredicateValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import util.Profiler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 独立阶段的来源域、不可变交接与传播调度回归。 */
class PredicatePruningTest {
    @TempDir Path temporaryDirectory;

    @Test
    void feedbackCommitsRwBranchesAndContinuesUntilNoFurtherWwIsForced() throws Exception {
        var path = history("ww-feedback-cascade",
                "[{\"key\":\"kv:k0\",\"value\":4},{\"key\":\"control:c\",\"value\":0},"
                        + "{\"key\":\"aux:y\",\"value\":0}]",
                txn(1, 11, write("kv:k0", 8)),
                txn(1, 14, write("aux:y", 2)),
                txn(2, 12, write("kv:k0", 7) + "," + write("control:c", 1)),
                txn(3, 15, write("aux:y", 1)),
                txn(3, 16, read("kv:k0", 7)),
                txn(4, 13, read("control:c", 1) + "," + emptyRead()));
        var profiler = Profiler.getInstance();
        for (boolean wwEnabled : List.of(true, false)) {
            profiler.clear();
            var settings = SIVerifier.SolverSettings.defaults();
            settings.wwReachabilityPruning = wwEnabled;
            assertEquals(SIVerifier.AuditResult.ACCEPT,
                    new SIVerifier<>(new PredicateHistoryLoader(path), settings, true).auditResult());
            assertEquals(wwEnabled ? 2 : 6, profiler.getCount("WW_AFTER_REACHABILITY"));
            assertEquals(wwEnabled ? 2 : 0, profiler.getCount("WW_GMWR_FEEDBACK_FORCED"));
            assertEquals(wwEnabled ? 2 : 0, profiler.getCount("WW_GMWR_FEEDBACK_ROUNDS"));
            assertEquals(wwEnabled ? 0 : 6, profiler.getCount("SI_PROP_WW_CHOICE_VARIABLES_COUNT"));
        }
    }

    @Test
    void gmwrFeedbackResolvesResidualWwWithoutRebuildingPredicatePreparation() throws Exception {
        var path = history("ww-feedback",
                "[{\"key\":\"kv:k0\",\"value\":4},{\"key\":\"control:c\",\"value\":0}]",
                txn(1, 11, write("kv:k0", 8)),
                txn(2, 12, write("kv:k0", 7) + "," + write("control:c", 1)),
                txn(3, 13, read("control:c", 1) + "," + emptyRead()));
        var profiler = Profiler.getInstance();
        for (boolean enabled : List.of(true, false)) {
            profiler.clear();
            var settings = SIVerifier.SolverSettings.defaults();
            settings.gmwrPrepropagation = enabled;
            assertEquals(SIVerifier.AuditResult.ACCEPT,
                    new SIVerifier<>(new PredicateHistoryLoader(path), settings, true).auditResult());
            assertEquals(1, profiler.getCount("WW_AFTER_REACHABILITY"));
            assertEquals(enabled ? 0 : 1, profiler.getCount("SI_PROP_WW_CHOICE_VARIABLES_COUNT"));
            assertEquals(enabled ? 1 : 0, profiler.getCount("WW_GMWR_FEEDBACK_FORCED"));
            assertEquals(enabled ? 0 : 1, profiler.getCount("WW_AFTER_GMWR_FEEDBACK"));
            assertEquals(1, profiler.getCount("SI_NATIVE_SOLVER_CREATIONS_COUNT"));
            assertEquals(1, profiler.getCount("SI_ORACLE_BUILDS"));
        }
    }

    @Test
    void disablingWwFeedbackKeepsInitialPruningAndGmwrActive() throws Exception {
        var path = history("ww-feedback",
                "[{\"key\":\"kv:k0\",\"value\":4},{\"key\":\"control:c\",\"value\":0}]",
                txn(1, 11, write("kv:k0", 8)),
                txn(2, 12, write("kv:k0", 7) + "," + write("control:c", 1)),
                txn(3, 13, read("control:c", 1) + "," + emptyRead()));
        var profiler = Profiler.getInstance();
        for (boolean enabled : List.of(true, false)) {
            profiler.clear();
            var settings = SIVerifier.SolverSettings.defaults();
            settings.wwFeedback = enabled;
            assertTrue(settings.gmwrPrepropagation);
            assertEquals(SIVerifier.AuditResult.ACCEPT,
                    new SIVerifier<>(new PredicateHistoryLoader(path), settings, true).auditResult());
            assertEquals(1, profiler.getCount("WW_AFTER_REACHABILITY"));
            assertEquals(enabled ? 1 : 0, profiler.getCount("WW_GMWR_FEEDBACK_ROUNDS"));
            assertEquals(enabled ? 0 : 1, profiler.getCount("SI_PROP_WW_CHOICE_VARIABLES_COUNT"));
            assertEquals(enabled ? 1 : 0, profiler.getCount("WW_GMWR_FEEDBACK_FORCED"));
            assertEquals(enabled ? 0 : 1, profiler.getCount("WW_AFTER_GMWR_FEEDBACK"));
            assertEquals(1, profiler.getCount("SI_NATIVE_SOLVER_CREATIONS_COUNT"));
            assertEquals(1, profiler.getCount("SI_ORACLE_BUILDS"));
        }
    }

    @Test
    void finalWriterListsAreSharedImmutableAndExcludeReadersOwnFinalWrite() throws Exception {
        var path = history("shared-final-writes", initial("kv:k0", 4),
                txn(1, 11, write("kv:k0", 7) + "," + write("kv:k0", 8)),
                txn(2, 12, emptyRead()), txn(3, 13, emptyRead()));
        var fixture = prepare(path, SIVerifier.PredicateMode.GMWR, false);
        var observations = fixture.result.observations();
        var first = observations.get(0).key("kv:k0");
        var second = observations.get(1).key("kv:k0");
        assertSame(first.allExternalWrites, second.allExternalWrites);
        assertEquals(2, first.allExternalWrites.size());
        assertTrue(first.badWrites.isEmpty(), "被覆盖的中间写不能进入 bad 候选");
        assertThrows(UnsupportedOperationException.class, () -> first.allExternalWrites.clear());
        assertThrows(UnsupportedOperationException.class, () -> first.goodWrites.clear());
        var writes = observations.get(0).scopedEntries.get(0).getValue();
        var external = fixture.result.analysis().latestExternalWrites(writes, fixture.history.getTransaction(11L));
        assertEquals(1, external.size());
        assertTrue(SIReachabilityOracle.isBottomTxn(external.get(0).getTxn()));
        assertEquals(2, first.allExternalWrites.size(), "排除自写不能改动共享集合");
    }

    @Test
    void disablingPropagationKeepsResidualItemsAndImmutableDomains() throws Exception {
        var path = history("outside", initial("kv:k0", 4),
                txn(1, 11, write("kv:k0", 7)), txn(2, 12, emptyRead()));
        var disabled = prepare(path, SIVerifier.PredicateMode.GMWR, false);
        assertFalse(disabled.result.hasConflict());
        assertEquals(1, disabled.result.itemObligationCount());
        assertEquals(1, disabled.result.residualItemCount());
        assertTrue(disabled.result.definiteFacts().isEmpty());
        var item = disabled.result.residualItems().get(0);
        assertTrue(item.outsidePossible);
        assertThrows(UnsupportedOperationException.class, () -> item.repairs.clear());
        var key = disabled.result.observations().get(0).key("kv:k0");
        assertEquals(2, key.allExternalWrites.size());
        assertEquals(1, key.sourceCandidates.size());
        assertThrows(UnsupportedOperationException.class, () -> key.sourceCandidates.clear());
        assertThrows(UnsupportedOperationException.class,
                () -> disabled.result.observations().get(0).sources.clear());

        var enabled = prepare(path, SIVerifier.PredicateMode.GMWR, true);
        assertFalse(enabled.result.hasConflict());
        assertEquals(0, enabled.result.residualItemCount());
        assertTrue(enabled.result.definiteFacts().stream().anyMatch(fact ->
                fact.kind == SiGmwrPropagationState.FactKind.NOT_VIS
                        && fact.from.getId() == 11L && fact.to.getId() == 12L));
        assertFalse(enabled.oracle.reachesA(enabled.history.getTransaction(12L),
                enabled.history.getTransaction(11L)));
    }

    @Test
    void forcingUniqueRealSourceKeepsAllCompetingVersionsAndCountersConserve() throws Exception {
        var path = history("unique", initial("kv:k0", 7),
                txn(1, 11, write("kv:k0", 8)), txn(2, 12, emptyRead()));
        Profiler.getInstance().clear();
        var fixture = prepare(path, SIVerifier.PredicateMode.GMWR, true);
        assertFalse(fixture.result.hasConflict());
        var key = fixture.result.observations().get(0).key("kv:k0");
        assertEquals(2, key.allExternalWrites.size());
        assertEquals(1, key.sourceCandidates.size());
        assertTrue(key.sourceForced);
        assertEquals(11L, key.forcedSource.getTxn().getId());
        assertTrue(fixture.result.definiteFacts().stream().anyMatch(fact ->
                fact.kind == SiGmwrPropagationState.FactKind.PR_WR && fact.from.getId() == 11L));
        assertTrue(fixture.graph.getKnownGraphA().edgeValue(
                fixture.history.getTransaction(11L), fixture.history.getTransaction(12L)).isEmpty(),
                "唯一来源由事实交接给 SAT，不无条件污染 typed KnownGraph");
        var profiler = Profiler.getInstance();
        assertEquals(profiler.getCount("SI_PRED_PR_WR_INITIAL_CONSTRAINTS_COUNT"),
                profiler.getCount("SI_PRED_PR_WR_RESIDUAL_CONSTRAINTS_COUNT")
                        + profiler.getCount("SI_PRED_PR_WR_FORCED_CONSTRAINTS_COUNT"));
        assertEquals(profiler.getCount("SI_PRED_PR_WR_INITIAL_CANDIDATES_COUNT"),
                profiler.getCount("SI_PRED_PR_WR_RESIDUAL_CANDIDATES_COUNT")
                        + profiler.getCount("SI_PRED_PR_WR_PRUNED_CANDIDATES_COUNT")
                        + profiler.getCount("SI_PRED_PR_WR_FIXED_CANDIDATES_COUNT"));
    }

    @Test
    void knownCommitOrderAloneDoesNotRemoveBottomOrForceARealSource() throws Exception {
        var path = history("ordered-not-visible",
                "[{\"key\":\"kv:k0\",\"value\":4},{\"key\":\"control:c\",\"value\":0}]",
                txn(1, 11, write("kv:k0", 8)),
                txn(2, 12, read("kv:k0", 8) + "," + read("control:c", 0)),
                txn(3, 13, emptyRead() + "," + write("control:c", 1)));
        var fixture = prepare(path, SIVerifier.PredicateMode.EAGER, false);
        assertFalse(fixture.result.hasConflict());
        var writer = fixture.history.getTransaction(11L);
        var reader = fixture.history.getTransaction(13L);
        assertTrue(fixture.oracle.reachesInduced(writer, reader));
        assertFalse(fixture.oracle.reachesA(writer, reader));
        var key = fixture.result.observations().get(0).key("kv:k0");
        assertEquals(2, key.sourceCandidates.size());
        assertFalse(key.sourceForced);
    }

    @Test
    void syntheticAbsentVersionRemainsARealBottomAlternative() throws Exception {
        var path = history("absent-bottom", "[]",
                txn(1, 11, write("kv:k0", 8)), txn(2, 12, emptyRead()));
        var fixture = prepare(path, SIVerifier.PredicateMode.EAGER, false);
        assertFalse(fixture.result.hasConflict());
        var key = fixture.result.observations().get(0).key("kv:k0");
        assertFalse(key.implicitBottomPossible,
                "History 已合成的 ABSENT 有真实 WriteRef，不能再重复计为隐式 bottom");
        assertTrue(key.sourceCandidates.stream().anyMatch(write ->
                SIReachabilityOracle.isBottomTxn(write.getTxn()) && write.getEvent().getValue() == null));
        assertFalse(key.sourceForced);
    }

    @Test
    void repeatedJoinDoesNotTurnAnUnwrittenExternalKeyInternal() throws Exception {
        var path = history("join-repeat",
                "[{\"key\":\"a:0\",\"value\":0},{\"key\":\"b:0\",\"value\":1}]",
                txn(1, 11, emptyJoin() + "," + write("a:0", 1) + "," + emptyJoin()));
        var fixture = prepare(path, SIVerifier.PredicateMode.EAGER, false);
        assertFalse(fixture.result.hasConflict());
        var observations = fixture.result.observations();
        assertFalse(observations.get(0).key("b:0").internal);
        assertFalse(observations.get(1).key("b:0").internal);
        assertTrue(observations.get(1).key("a:0").internal);
        assertNotNull(observations.get(1).key("a:0").latestSelf);
        assertEquals(1, observations.get(1).key("b:0").allExternalWrites.size());
    }

    @Test
    void invalidAllLocalJoinAndKnownCycleRejectBeforeSat() throws Exception {
        var path = history("local-join", "[]", txn(1, 11,
                write("a:0", 1) + "," + write("b:0", 1) + "," + emptyJoin()));
        var fixture = prepare(path, SIVerifier.PredicateMode.EAGER, false);
        assertTrue(fixture.result.hasConflict());
        assertTrue(fixture.result.conflictReasons().stream().anyMatch(reason ->
                reason.describe().contains("自写快照")));

        var empty = history("cycle", "[]", txn(1, 21, ""), txn(2, 22, ""));
        var loaded = new PredicateHistoryLoader(empty).loadHistory();
        var graph = new KnownGraph<>(loaded, false);
        var oracle = new SIReachabilityOracle<>(graph);
        oracle.addVisibility(loaded.getTransaction(21L), loaded.getTransaction(22L));
        oracle.addVisibility(loaded.getTransaction(22L), loaded.getTransaction(21L));
        var result = new PredicatePruning<>(loaded, graph, oracle,
                SIVerifier.SolverSettings.defaults(), new PredicateAnalysis<>(graph, oracle)).prune();
        assertTrue(result.hasConflict());
    }

    @Test
    void affectedQueueMatchesNaiveFixedPointAfterIndirectVisibilityChanges() throws Exception {
        var path = history("queue-chain",
                "[{\"key\":\"kv:k0\",\"value\":4},{\"key\":\"kv:k1\",\"value\":4}]",
                txn(1, 11, write("kv:k0", 7)),
                txn(1, 12, write("kv:k1", 7)),
                txn(2, 13, write("kv:k0", 8)),
                txn(3, 14, write("kv:k0", 12) + "," + write("kv:k1", 8)),
                txn(4, 15, emptyRead()));
        var queued = prepare(path, SIVerifier.PredicateMode.EAGER, false);
        var scanned = prepare(path, SIVerifier.PredicateMode.EAGER, false);
        queued.oracle.addVisibility(queued.history.getTransaction(12L), queued.history.getTransaction(15L));
        scanned.oracle.addVisibility(scanned.history.getTransaction(12L), scanned.history.getTransaction(15L));
        var propagation = new SiGmwrPropagationState<>(queued.oracle, queued.result.observations());
        assertFalse(propagation.propagate());
        assertFalse(scanToFixedPoint(scanned));
        assertEquals(0, propagation.residualItems().size());
        assertTrue(propagation.reductionSteps() > propagation.initialItems(),
                "后处理的 item 产生新可见性后，先处理的 item 必须重新入队");
        for (var left : queued.history.getClientTransactions()) {
            for (var right : queued.history.getClientTransactions()) {
                var scanLeft = scanned.history.getTransaction(left.getId());
                var scanRight = scanned.history.getTransaction(right.getId());
                assertEquals(scanned.oracle.reachesA(scanLeft, scanRight),
                        queued.oracle.reachesA(left, right));
                assertEquals(scanned.oracle.reachesInduced(scanLeft, scanRight),
                        queued.oracle.reachesInduced(left, right));
            }
        }
    }

    /** 测试侧朴素全量扫描，只核对工作队列的调度固定点，不调用生产传播规则。 */
    private static boolean scanToFixedPoint(Fixture fixture) {
        var items = new ArrayList<ScanItem>();
        for (var observation : fixture.result.observations()) {
            for (var key : observation.keys) {
                if (key.rowLocal && !key.internal && key.recordedSource == null) {
                    for (var bad : key.badWrites) {
                        items.add(new ScanItem(observation.observation.getTxn(), bad, key.goodWrites));
                    }
                }
            }
        }
        var excluded = new HashSet<String>();
        boolean changed;
        do {
            long prior = fixture.oracle.updateCount();
            int candidates = items.stream().mapToInt(item -> item.repairs.size()).sum();
            for (var item : items) {
                if (item.done || cannotSee(fixture.oracle, excluded, item.bad.getTxn(), item.reader)) {
                    item.done = true;
                    continue;
                }
                item.repairs.removeIf(repair -> repair.getTxn().equals(item.bad.getTxn())
                        || SIReachabilityOracle.isBottomTxn(repair.getTxn())
                        || fixture.oracle.reachesInduced(repair.getTxn(), item.bad.getTxn())
                        || cannotSee(fixture.oracle, excluded, repair.getTxn(), item.reader));
                if (item.repairs.stream().anyMatch(repair ->
                        (SIReachabilityOracle.isBottomTxn(item.bad.getTxn())
                                || fixture.oracle.reachesInduced(item.bad.getTxn(), repair.getTxn()))
                                && fixture.oracle.reachesA(repair.getTxn(), item.reader))) {
                    item.done = true;
                    continue;
                }
                boolean outside = !fixture.oracle.reachesA(item.bad.getTxn(), item.reader);
                if (item.repairs.isEmpty()) {
                    if (!outside) return true;
                    excluded.add(item.bad.getTxn().getId() + ":" + item.reader.getId());
                    fixture.oracle.addInvisibility(item.bad.getTxn(), item.reader);
                } else if (!outside && item.repairs.size() == 1) {
                    var repair = item.repairs.get(0).getTxn();
                    fixture.oracle.addVisibility(item.bad.getTxn(), repair);
                    fixture.oracle.addVisibility(repair, item.reader);
                }
                if (!fixture.oracle.isAcyclic()) return true;
            }
            changed = prior != fixture.oracle.updateCount()
                    || candidates != items.stream().mapToInt(item -> item.repairs.size()).sum();
        } while (changed);
        return false;
    }

    private static boolean cannotSee(SIReachabilityOracle<String, PredicateValue> oracle,
            Set<String> excluded, Transaction<String, PredicateValue> writer,
            Transaction<String, PredicateValue> reader) {
        if (SIReachabilityOracle.isBottomTxn(writer)) return false;
        return writer.equals(reader) || oracle.reachesInduced(reader, writer)
                || excluded.contains(writer.getId() + ":" + reader.getId())
                || oracle.hasConflict(List.of(new SIEdge<>(writer, reader, EdgeType.PR_WR, null)));
    }

    private static final class ScanItem {
        final Transaction<String, PredicateValue> reader;
        final KnownGraph.WriteRef<String, PredicateValue> bad;
        final List<KnownGraph.WriteRef<String, PredicateValue>> repairs;
        boolean done;
        ScanItem(Transaction<String, PredicateValue> reader, KnownGraph.WriteRef<String, PredicateValue> bad,
                List<KnownGraph.WriteRef<String, PredicateValue>> repairs) {
            this.reader = reader;
            this.bad = bad;
            this.repairs = new ArrayList<>(repairs);
        }
    }

    private static Fixture prepare(Path path, SIVerifier.PredicateMode mode, boolean prepropagation) {
        var history = new PredicateHistoryLoader(path).loadHistory();
        var graph = new KnownGraph<>(history, false);
        var oracle = new SIReachabilityOracle<>(graph);
        var choices = SIVerifier.generateConstraintsSI(history, graph);
        SIReachabilityPruner.Result<String, PredicateValue> round;
        do {
            round = SIReachabilityPruner.reduceOnce(graph, choices, oracle);
        } while (!round.rejected && round.forced > 0 && !choices.isEmpty());
        var settings = SIVerifier.SolverSettings.defaults();
        settings.predicateMode = mode;
        settings.gmwrPrepropagation = prepropagation;
        var result = new PredicatePruning<>(history, graph, oracle, settings,
                new PredicateAnalysis<>(graph, oracle)).prune();
        return new Fixture(history, graph, oracle, result);
    }

    private static final class Fixture {
        final History<String, PredicateValue> history;
        final KnownGraph<String, PredicateValue> graph;
        final SIReachabilityOracle<String, PredicateValue> oracle;
        final PredicatePruning.Result<String, PredicateValue> result;
        Fixture(History<String, PredicateValue> history, KnownGraph<String, PredicateValue> graph,
                SIReachabilityOracle<String, PredicateValue> oracle, PredicatePruning.Result<String, PredicateValue> result) {
            this.history = history;
            this.graph = graph;
            this.oracle = oracle;
            this.result = result;
        }
    }

    private Path history(String name, String initial, String... transactions) throws Exception {
        var directory = Files.createDirectories(temporaryDirectory.resolve(name));
        Files.writeString(directory.resolve("initial_state.json"), initial);
        Files.writeString(directory.resolve("history.prhist.jsonl"), String.join("\n", transactions));
        return directory;
    }
    private static String txn(long session, long id, String ops) {
        return "{\"session\":" + session + ",\"session_seq\":" + id + ",\"txn\":" + id
                + ",\"status\":\"commit\",\"ops\":[" + ops + "]}";
    }
    private static String write(String key, int value) {
        return "{\"type\":\"w\",\"key\":\"" + key + "\",\"value\":" + value + "}";
    }
    private static String read(String key, int value) {
        return "{\"type\":\"r\",\"key\":\"" + key + "\",\"value\":" + value + "}";
    }
    private static String initial(String key, int value) {
        return "[{\"key\":\"" + key + "\",\"value\":" + value + "}]";
    }
    private static String emptyRead() {
        return "{\"type\":\"pr\",\"query\":{\"from\":{\"relation\":\"kv\"},"
                + "\"select\":{\"columns\":[\"k\",\"value\"]},\"where\":[\"value % 4 = 3\"]},"
                + "\"result\":{\"inputs\":[],\"values\":[]}}";
    }
    private static String emptyJoin() {
        return "{\"type\":\"pr\",\"query\":{\"from\":{\"relation\":\"a\",\"alias\":\"a\"},"
                + "\"joins\":[{\"relation\":\"b\",\"alias\":\"b\",\"type\":\"INNER\","
                + "\"on\":[\"a.value = b.value\"]}],\"select\":{\"columns\":[\"a.value AS a\",\"b.value AS b\"]}},"
                + "\"result\":{\"inputs\":[],\"values\":[]}}";
    }
}
