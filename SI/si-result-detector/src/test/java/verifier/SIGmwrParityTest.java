package verifier;

import history.loaders.PredicateHistoryLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import util.Profiler;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SIGmwrParityTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void gmwrMatchesEagerWhenBadWriterIsRepaired() throws Exception {
        var history = writeHistory(
                "repair",
                "[{\"key\":\"kv:k0\",\"value\":4},"
                        + "{\"key\":\"control:good\",\"value\":0}]",
                transaction(1, 11,
                        "{\"type\":\"w\",\"key\":\"kv:k0\",\"value\":7}"),
                transaction(1, 12,
                        "{\"type\":\"w\",\"key\":\"kv:k0\",\"value\":8},"
                                + "{\"type\":\"w\",\"key\":\"control:good\",\"value\":1}"),
                transaction(2, 13,
                        "{\"type\":\"r\",\"key\":\"control:good\",\"value\":1},"
                                + predicateRead("[]", "[]")));

        assertEquals(SIVerifier.AuditResult.ACCEPT,
                audit(history, SIVerifier.PredicateMode.EAGER));
        assertEquals(SIVerifier.AuditResult.ACCEPT,
                audit(history, SIVerifier.PredicateMode.GMWR));
    }

    @Test
    void gmwrMatchesEagerWhenBadWriterMustBeLatest() throws Exception {
        var history = writeHistory(
                "bad-latest",
                "[{\"key\":\"kv:k0\",\"value\":4},"
                        + "{\"key\":\"control:bad\",\"value\":0}]",
                transaction(1, 11,
                        "{\"type\":\"w\",\"key\":\"kv:k0\",\"value\":7},"
                                + "{\"type\":\"w\",\"key\":\"control:bad\",\"value\":1}"),
                transaction(2, 12,
                        "{\"type\":\"r\",\"key\":\"control:bad\",\"value\":1},"
                                + predicateRead("[]", "[]")));

        assertEquals(SIVerifier.AuditResult.REJECT,
                audit(history, SIVerifier.PredicateMode.EAGER));
        assertEquals(SIVerifier.AuditResult.REJECT,
                audit(history, SIVerifier.PredicateMode.GMWR));
    }


    @Test
    void gmwrPrepropagationForcesUniqueRepairVisibility() throws Exception {
        var history = writeHistory(
                "preprop-force",
                "[{\"key\":\"kv:k0\",\"value\":4},"
                        + "{\"key\":\"control:bad\",\"value\":0}]",
                transaction(1, 11,
                        "{\"type\":\"w\",\"key\":\"kv:k0\",\"value\":7},"
                                + "{\"type\":\"w\",\"key\":\"control:bad\",\"value\":1}"),
                transaction(2, 12,
                        "{\"type\":\"w\",\"key\":\"kv:k0\",\"value\":8}"),
                transaction(3, 13,
                        "{\"type\":\"r\",\"key\":\"control:bad\",\"value\":1},"
                                + predicateRead("[]", "[]")));

        Profiler.getInstance().clear();
        assertEquals(SIVerifier.AuditResult.ACCEPT,
                audit(history, SIVerifier.PredicateMode.GMWR, true));
        assertTrue(Profiler.getInstance().getCount("GMWR_FORCED_FACTS") >= 2);
        assertEquals(1L, Profiler.getInstance().getCount("SI_ORACLE_BUILDS"));
    }

    @Test
    void gmwrUsesCompactGoodWriterFrontier() throws Exception {
        var history = writeHistory(
                "compact",
                "[{\"key\":\"kv:k0\",\"value\":4}]",
                transaction(1, 11,
                        "{\"type\":\"w\",\"key\":\"kv:k0\",\"value\":7}"),
                transaction(2, 12,
                        "{\"type\":\"w\",\"key\":\"kv:k0\",\"value\":8}"),
                transaction(3, 13,
                        "{\"type\":\"w\",\"key\":\"kv:k0\",\"value\":11}"),
                transaction(4, 14, predicateRead("[]", "[]")));

        long eagerCandidates = frontierCandidates(
                history, SIVerifier.PredicateMode.EAGER);
        Profiler.getInstance().clear();
        var gmwrResult = audit(
                history, SIVerifier.PredicateMode.GMWR, true);
        var profiler = Profiler.getInstance();

        assertEquals(SIVerifier.AuditResult.ACCEPT, gmwrResult);
        assertTrue(profiler.getCount("SI_GMWR_OBLIGATIONS_COUNT") > 0);
        assertTrue(profiler.getCount("SI_PRED_FRONTIER_CANDIDATES_COUNT")
                < eagerCandidates);
        Profiler.getInstance().clear();
        assertEquals(SIVerifier.AuditResult.ACCEPT, audit(history, SIVerifier.PredicateMode.GMWR));
        assertTrue(profiler.getCount("SI_PRED_DEPENDENCY_PHYSICAL_EDGES_COUNT") > 0,
                "default CLI needs nonzero predicate counts without --solver-stats");
    }

    @Test
    void outsideDoesNotCreateReverseVisibility() throws Exception {
        var path = writeHistory("outside", "[{\"key\":\"kv:k0\",\"value\":4}]",
                transaction(1, 11, "{\"type\":\"w\",\"key\":\"kv:k0\",\"value\":7}"),
                transaction(2, 12, predicateRead("[]", "[]")));
        var history = new PredicateHistoryLoader(path).loadHistory();
        var graph = new graph.KnownGraph<>(history);
        var vis = new SIReachabilityOracle<>(graph);
        var prepared = SISolverTestSupport.prepare(history, graph, vis,
                SIVerifier.SolverSettings.defaults());
        assertFalse(prepared.hasConflict());
        assertTrue(prepared.definiteFacts().stream().anyMatch(fact ->
                fact.kind == SiGmwrPropagationState.FactKind.NOT_VIS
                        && fact.from.equals(history.getTransaction(11L))
                        && fact.to.equals(history.getTransaction(12L))));
        assertFalse(
                vis.reachesA(history.getTransaction(12L), history.getTransaction(11L)));
        assertEquals(SIVerifier.AuditResult.ACCEPT, audit(path, SIVerifier.PredicateMode.GMWR));
    }

    @Test
    void oneUnrepairableItemRejectsTheWholeMultiKeyBundle() throws Exception {
        var path = writeHistory("bundle", "[{\"key\":\"kv:k0\",\"value\":4},"
                        + "{\"key\":\"kv:k1\",\"value\":8},{\"key\":\"control:b\",\"value\":0}]",
                transaction(1, 11, "{\"type\":\"w\",\"key\":\"kv:k0\",\"value\":7},"
                        + "{\"type\":\"w\",\"key\":\"kv:k1\",\"value\":11},"
                        + "{\"type\":\"w\",\"key\":\"control:b\",\"value\":1}"),
                transaction(2, 12, "{\"type\":\"w\",\"key\":\"kv:k0\",\"value\":12}"),
                transaction(3, 13, "{\"type\":\"r\",\"key\":\"control:b\",\"value\":1},"
                        + predicateRead("[]", "[]")));
        for (var mode : SIVerifier.PredicateMode.values()) {
            for (boolean prepropagation : java.util.List.of(false, true)) {
                var settings = SIVerifier.SolverSettings.defaults();
                settings.predicateMode = mode;
                settings.gmwrPrepropagation = prepropagation;
                assertEquals(SIVerifier.AuditResult.REJECT,
                        new SIVerifier<>(new PredicateHistoryLoader(path), settings, false).auditResult());
            }
        }
    }

    @Test
    void deterministicPredicateConflictIsReportedBeforeSolverConstruction() throws Exception {
        var path = writeHistory("direct-unsat", "[{\"key\":\"kv:k0\",\"value\":4},"
                        + "{\"key\":\"control:b\",\"value\":0}]",
                transaction(1, 11, "{\"type\":\"w\",\"key\":\"kv:k0\",\"value\":7},"
                        + "{\"type\":\"w\",\"key\":\"control:b\",\"value\":1}"),
                transaction(2, 12, "{\"type\":\"r\",\"key\":\"control:b\",\"value\":1},"
                        + predicateRead("[]", "[]")));
        for (var mode : SIVerifier.PredicateMode.values()) {
            var history = new PredicateHistoryLoader(path).loadHistory();
            var graph = new graph.KnownGraph<>(history);
            var settings = SIVerifier.SolverSettings.defaults();
            settings.predicateMode = mode;
            var prepared = SISolverTestSupport.prepare(history, graph,
                    new SIReachabilityOracle<>(graph), settings);
            assertTrue(prepared.hasConflict());
            assertTrue(prepared.conflictReasons().stream().anyMatch(reason ->
                    reason.observation != null && "kv:k0".equals(reason.key)));
        }
    }

    private long frontierCandidates(
            Path history, SIVerifier.PredicateMode mode) {
        Profiler.getInstance().clear();
        audit(history, mode, true);
        return Profiler.getInstance().getCount(
                "SI_PRED_FRONTIER_CANDIDATES_COUNT");
    }

    private SIVerifier.AuditResult audit(
            Path history, SIVerifier.PredicateMode mode) {
        return audit(history, mode, false);
    }

    private SIVerifier.AuditResult audit(
            Path history, SIVerifier.PredicateMode mode,
            boolean detailedMetrics) {
        var settings = SIVerifier.SolverSettings.defaults();
        settings.predicateMode = mode;
        settings.detailedPredicateMetrics = detailedMetrics;
        return new SIVerifier<>(
                new PredicateHistoryLoader(history), settings,
                detailedMetrics).auditResult();
    }

    private Path writeHistory(
            String name, String initialState, String... transactions)
            throws Exception {
        var directory = Files.createDirectories(
                temporaryDirectory.resolve(name));
        Files.writeString(directory.resolve("initial_state.json"), initialState);
        Files.writeString(directory.resolve("history.prhist.jsonl"),
                String.join("\n", transactions));
        return directory;
    }

    private static String transaction(
            long session, long transaction, String operations) {
        return "{\"session\":" + session
                + ",\"session_seq\":" + transaction
                + ",\"txn\":" + transaction
                + ",\"status\":\"commit\",\"ops\":["
                + operations + "]}";
    }

    private static String predicateRead(String inputs, String values) {
        return "{\"type\":\"pr\",\"query\":{"
                + "\"from\":{\"relation\":\"kv\"},"
                + "\"select\":{\"columns\":[\"value\"],\"distinct\":false},"
                + "\"where\":[\"value % 4 = 3\"]},"
                + "\"result\":{\"inputs\":" + inputs
                + ",\"values\":" + values + "}}";
    }
}
