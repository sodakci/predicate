package verifier;

import com.fasterxml.jackson.databind.ObjectMapper;
import graph.EdgeType;
import graph.Edge;
import graph.KnownGraph;
import history.Event;
import history.History;
import history.Transaction;
import history.query.QueryPlan;
import history.query.QueryValue;
import history.query.RelationResolver;
import history.query.StructuredQueryParser;
import history.query.ValueAdapter;
import monosat.Lit;
import monosat.Solver;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.junit.jupiter.api.Test;
import util.Profiler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static history.Event.EventType.READ;
import static history.Event.EventType.WRITE;
import static org.junit.jupiter.api.Assertions.*;

class SERSolverARSatEncodingTest {
    private static final ObjectMapper QUERY_MAPPER = new ObjectMapper();
    private static final ValueAdapter<Integer> INTEGER_VALUES =
            value -> QueryValue.integer(value);
    private static final StructuredQueryParser<String, Integer> QUERY_PARSER =
            new StructuredQueryParser<>(
                    INTEGER_VALUES, RelationResolver.canonicalStringKeys());

    private static History<String, Integer> makeHistory(
            Set<Long> sessions,
            Map<Long, List<Long>> sessionToTxns,
            Map<Long, List<Triple<Event.EventType, String, Integer>>> normalEvents,
            Map<Long, Pair<PredicateFixtures.RowPredicate<String, Integer>, List<Event.PredResult<String, Integer>>>> predicateReads) {
        var h = new History<>(sessions, sessionToTxns, normalEvents);
        for (var entry : predicateReads.entrySet()) {
            h.addPredicateReadEvent(h.getTransaction(entry.getKey()), entry.getValue().getLeft(), entry.getValue().getRight());
        }
        return h;
    }

    private static Collection<SERConstraint<String, Integer>> generateConstraints(
            History<String, Integer> history,
            KnownGraph<String, Integer> graph) {
        return SERVerifier.generateConstraintsSER(history, graph);
    }

    private static boolean verifySer(History<String, Integer> history) {
        return new SERVerifier<>(() -> history).audit() == SERVerifier.AuditResult.ACCEPT;
    }

    private static void commitAll(History<?, ?> history) {
        history.getTransactions().forEach(txn -> txn.setStatus(Transaction.TransactionStatus.COMMIT));
    }

    private static PredicateFixtures.RowPredicate<String, Integer> keyAtLeast(String key, int threshold) {
        return new PredicateFixtures.RowPredicate<>() {
            @Override
            public boolean test(String candidateKey, Integer value) {
                return key.equals(candidateKey) && value >= threshold;
            }

            @Override
            public boolean covers(String candidateKey) {
                return key.equals(candidateKey);
            }
        };
    }

    private static boolean solveSer(History<String, Integer> history) {
        var graph = new KnownGraph<>(history);
        return new SERSolverAR<>(history, graph, generateConstraints(history, graph)).solve() == SolveStatus.SAT;
    }

    private static History<String, Integer> singleTxnHistory() {
        var history = new History<String, Integer>();
        var session = history.addSession(1L);
        history.addTransaction(session, 1L);
        return history;
    }

    private static QueryPlan<String, Integer> kvPlan(String predicate) {
        try {
            return QUERY_PARSER.parse(QUERY_MAPPER.readTree("{"
                    + "\"from\":{\"relation\":\"kv\"},"
                    + "\"where\":[\"" + predicate + "\"],"
                    + "\"select\":{\"columns\":[\"k\",\"value\"],"
                    + "\"distinct\":false}}"));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    @Test
    void monoSatAssertOrAcceptsClauseLargerThanDefaultJniBuffer() {
        try (var solver = new Solver()) {
            var clause = new ArrayList<Lit>();
            var allFalse = new ArrayList<Lit>();
            for (int index = 0; index < 4097; index++) {
                var literal = new Lit(solver);
                clause.add(literal);
                allFalse.add(literal.not());
            }

            solver.assertOr(clause);
            assertFalse(solver.solve(allFalse));
        }
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
        assertEquals(SolveStatus.SAT, solver.solve());
    }

    @Test
    void profilesArEncodingAndMonoSatSolveAsSeparateStages() {
        var profiler = Profiler.getInstance();
        profiler.clear();
        var history = makeHistory(
                Set.of(1L),
                Map.of(1L, List.of(1L, 2L, 3L)),
                Map.of(),
                Map.of());
        var graph = new KnownGraph<>(history);

        var solver = new SERSolverAR<>(history, graph, List.of());
        assertEquals(SolveStatus.SAT, solver.solve());

        assertEquals(1, profiler.getCounter("SER_AR_ENCODE_SETUP"));
        assertEquals(1, profiler.getCounter("SER_AR_ENCODE_KNOWN_EDGES"));
        assertEquals(1, profiler.getCounter("SER_AR_ENCODE_WW"));
        assertEquals(1, profiler.getCounter("SER_AR_ENCODE_PREDICATE"));
        assertEquals(1, profiler.getCounter("SER_AR_ENCODE_DEPENDENCIES"));
        assertEquals(1, profiler.getCounter("SER_AR_ENCODE_ACYCLIC"));
        assertEquals(1, profiler.getCounter("SER_MONOSAT_SOLVE"));
        assertEquals(1, profiler.getCounter("SER_AR_PREDICATE_REFINEMENT"));
    }

    @Test
    void profilesPredicateEncodingSubstagesAndCounts() {
        var profiler = Profiler.getInstance();
        profiler.clear();
        var history = makeHistory(
                Set.of(1L, 2L),
                Map.of(1L, List.of(1L), 2L, List.of(2L)),
                Map.of(1L, List.of(Triple.of(WRITE, "x", 10))),
                Map.of(2L, Pair.of(
                        (PredicateFixtures.RowPredicate<String, Integer>)
                                (key, value) -> value > 5,
                        List.of(new Event.PredResult<>("x", 10)))));
        var graph = new KnownGraph<>(history);

        var solver = new SERSolverAR<>(
                history, graph, generateConstraints(history, graph), true, true);

        assertEquals(1, profiler.getCount("SER_PRED_OBSERVATIONS_COUNT"));
        assertEquals(1, profiler.getCount("SER_PRED_RESULT_SOURCES_COUNT"));
        assertEquals(1, profiler.getCount("SER_PRED_SCOPED_KEYS_COUNT"));
        assertEquals(1, profiler.getCount("SER_PRED_GENERAL_COUNT"));
        assertEquals(1, profiler.getCount("SER_PRED_FRONTIERS_COUNT"));
        assertEquals(1, profiler.getCount("SER_PRED_FRONTIER_CANDIDATES_COUNT"));
        assertTrue(profiler.getCount("SER_PRED_DEPENDENCY_ATTEMPTS_COUNT") > 0);
        assertEquals(SolveStatus.SAT, solver.solve());
    }

    @Test
    void eagerModeMaterializesRowLocalConstraintsBeforeSolving() {
        var profiler = Profiler.getInstance();
        profiler.clear();
        var history = new History<String, Integer>();
        var session = history.addSession(1L);
        var writer = history.addTransaction(session, 1L);
        var reader = history.addTransaction(session, 2L);
        history.addEvent(writer, WRITE, "kv:x", 10);
        history.addPredicateReadEvent(reader, kvPlan("value > 5"), List.of());
        commitAll(history);

        var graph = new KnownGraph<>(history);
        var solver = new SERSolverAR<>(
                history, graph, generateConstraints(history, graph), true, true,
                SERVerifier.PredicateSolvingMode.EAGER);

        assertEquals(1L, profiler.getCount("SER_PRED_ROW_LOCAL_KEY_VISITS_COUNT"));
        assertEquals(SolveStatus.UNSAT, solver.solve());
        var reasons = solver.getConflictReasons();
        assertTrue(reasons.stream().anyMatch(reason ->
                        reason.getKind() == SERSolverAR.AssumptionKind.PREDICATE_OBLIGATION),
                "predicate UNSAT must map the assumption conflict to its obligation");
        assertTrue(reasons.stream().allMatch(reason ->
                reason.assumptionId().matches("A\\d+")));
    }

    @Test
    void invalidRecordedRowLocalResultIsRejectedInsteadOfFallback() {
        var profiler = Profiler.getInstance();
        profiler.clear();
        var history = new History<String, Integer>();
        var writer = history.addTransaction(history.addSession(1L), 1L);
        var reader = history.addTransaction(history.addSession(2L), 2L);
        history.addEvent(writer, WRITE, "kv:x", 10);
        history.addPredicateReadEvent(reader, kvPlan("value > 100"), List.of(
                new Event.PredResult<>("kv:x", 10)));
        commitAll(history);

        var graph = new KnownGraph<>(history);
        var solver = new SERSolverAR<>(
                history, graph, generateConstraints(history, graph), true, true,
                SERVerifier.PredicateSolvingMode.EAGER);

        assertEquals(0L, profilerCountOrZero("SER_PRED_ROW_LOCAL_FALLBACKS_COUNT"));
        assertEquals(SolveStatus.UNSAT, solver.solve());
    }

    private static long profilerCountOrZero(String tag) {
        return Profiler.getInstance().getCount(tag);
    }

    @Test
    void prunesSameTransactionPredicateWitnessesAcrossKeys() {
        var profiler = Profiler.getInstance();
        profiler.clear();
        var history = new History<String, Integer>();
        var writer = history.addTransaction(history.addSession(1L), 1L);
        var reader = history.addTransaction(history.addSession(2L), 2L);
        history.addEvent(writer, WRITE, "kv:x", 10);
        history.addEvent(writer, WRITE, "kv:y", 10);
        history.addPredicateReadEvent(reader, kvPlan("value > 5"), List.of(
                new Event.PredResult<>("kv:x", 10),
                new Event.PredResult<>("kv:y", 10)));
        commitAll(history);

        var graph = new KnownGraph<>(history);
        var solver = new SERSolverAR<>(
                history, graph, generateConstraints(history, graph), true, true,
                SERVerifier.PredicateSolvingMode.GMWR);

        long candidates = profiler.getCount("SER_PRED_DEPENDENCY_CANDIDATES_COUNT");
        long fixedCandidates = profiler.getCount(
                "SER_PRED_DEPENDENCY_FIXED_CANDIDATES_COUNT");
        long physical = profiler.getCount("SER_PRED_DEPENDENCY_PHYSICAL_EDGES_COUNT");
        long physicalPrWr = profiler.getCount(
                "SER_PRED_DEPENDENCY_PHYSICAL_PR_WR_EDGES_COUNT");
        long physicalPrRw = profiler.getCount(
                "SER_PRED_DEPENDENCY_PHYSICAL_PR_RW_EDGES_COUNT");
        long physicalSourced = profiler.getCount(
                "SER_PRED_DEPENDENCY_PHYSICAL_SOURCED_EDGES_COUNT");
        long physicalSourceless = profiler.getCount(
                "SER_PRED_DEPENDENCY_PHYSICAL_SOURCELESS_EDGES_COUNT");
        long physicalMixed = profiler.getCount(
                "SER_PRED_DEPENDENCY_PHYSICAL_MIXED_EDGES_COUNT");
        long physicalKnownOrInternal = profiler.getCount(
                "SER_PRED_DEPENDENCY_PHYSICAL_KNOWN_INTERNAL_EDGES_COUNT");
        long coalesced = profiler.getCount("SER_PRED_DEPENDENCY_COALESCED_COUNT");
        assertTrue(candidates > physical,
                "same writer/reader predicate witnesses from x and y must share one physical edge");
        assertTrue(fixedCandidates > 0,
                "recorded predicate sources must be reported separately as fixed PR edges");
        assertTrue(fixedCandidates <= candidates);
        assertEquals(candidates - physical, coalesced);
        assertEquals(physical, physicalPrWr + physicalPrRw,
                "the physical predicate edge total must equal its PR_WR/PR_RW partition");
        assertEquals(physical, physicalSourced + physicalSourceless + physicalMixed
                        + physicalKnownOrInternal,
                "the physical predicate edge total must equal its source-origin partition");
        assertEquals(SolveStatus.SAT, solver.solve());
    }

    @Test
    void gmwrPrunesReachablePrWrSourceAndForcesTheLastAlternative() {
        var profiler = Profiler.getInstance();
        profiler.clear();
        var history = new History<String, Integer>();
        var reader = history.addTransaction(history.addSession(1L), 1L);
        var blockedWriter = history.addTransaction(history.addSession(2L), 2L);
        var remainingWriter = history.addTransaction(history.addSession(3L), 3L);
        history.addEvent(reader, WRITE, "dep", 1);
        history.addPredicateReadEvent(reader, kvPlan("value > 5"), List.of());
        history.addEvent(blockedWriter, WRITE, "kv:x", 0);
        history.addEvent(remainingWriter, WRITE, "kv:x", 1);
        commitAll(history);

        var graph = new KnownGraph<>(history);
        graph.putEdge(reader, blockedWriter, new Edge<>(EdgeType.WR, "dep"));
        var solver = new SERSolverAR<>(
                history, graph, generateConstraints(history, graph), true, true,
                SERVerifier.PredicateSolvingMode.GMWR);

        assertEquals(1L, profiler.getCount(
                "SER_PRED_PR_WR_REACHABILITY_PRUNED_COUNT"));
        assertEquals(0L, profiler.getCount(
                "SER_PRED_PR_WR_REACHABILITY_FORCED_COUNT"));
        assertFalse(graph.getKnownGraphA().edgeValue(remainingWriter, reader)
                .orElse(List.of()).contains(new Edge<>(EdgeType.PR_WR, "kv:x")));
        assertEquals(SolveStatus.SAT, solver.solve());
    }

    @Test
    void gmwrPrunesPrWrSourceWhenKnownWwForcesCyclicPrRw() {
        var profiler = Profiler.getInstance();
        profiler.clear();
        var history = new History<String, Integer>();
        var source = history.addTransaction(history.addSession(1L), 1L);
        var badWriter = history.addTransaction(history.addSession(2L), 2L);
        var repair = history.addTransaction(history.addSession(3L), 3L);
        var reader = history.addTransaction(history.addSession(4L), 4L);
        history.addEvent(source, WRITE, "kv:x", 0);
        history.addEvent(badWriter, WRITE, "kv:x", 10);
        history.addEvent(badWriter, WRITE, "dep", 1);
        history.addEvent(repair, WRITE, "kv:x", -1);
        history.addEvent(reader, READ, "dep", 1);
        history.addPredicateReadEvent(reader, kvPlan("value > 5"), List.of());
        commitAll(history);

        var graph = new KnownGraph<>(history);
        graph.putEdge(source, badWriter, new Edge<>(EdgeType.WW, "kv:x"));
        var solver = new SERSolverAR<>(
                history, graph, generateConstraints(history, graph), true, true,
                SERVerifier.PredicateSolvingMode.GMWR);

        assertEquals(0L, profiler.getCount(
                "SER_PRED_PR_WR_REACHABILITY_PRUNED_COUNT"));
        assertEquals(1L, profiler.getCount(
                "SER_PRED_PR_WR_PR_RW_CYCLE_PRUNED_COUNT"));
        assertFalse(graph.getKnownGraphA().edgeValue(source, reader)
                .orElse(List.of()).contains(new Edge<>(EdgeType.PR_WR, "kv:x")));
        assertEquals(SolveStatus.SAT, solver.solve());
    }

    @Test
    void gmwrPrRwCyclePruningDoesNotInferWwFromTransactionReachability() {
        var profiler = Profiler.getInstance();
        profiler.clear();
        var history = new History<String, Integer>();
        var source = history.addTransaction(history.addSession(1L), 1L);
        var badWriter = history.addTransaction(history.addSession(2L), 2L);
        var repair = history.addTransaction(history.addSession(3L), 3L);
        var reader = history.addTransaction(history.addSession(4L), 4L);
        history.addEvent(source, WRITE, "kv:x", 0);
        history.addEvent(badWriter, WRITE, "kv:x", 10);
        history.addEvent(badWriter, WRITE, "dep", 1);
        history.addEvent(repair, WRITE, "kv:x", -1);
        history.addEvent(reader, READ, "dep", 1);
        history.addPredicateReadEvent(reader, kvPlan("value > 5"), List.of());
        commitAll(history);

        var graph = new KnownGraph<>(history);
        graph.putEdge(source, badWriter, new Edge<>(EdgeType.SO, null));
        new SERSolverAR<>(
                history, graph, generateConstraints(history, graph), true, true,
                SERVerifier.PredicateSolvingMode.GMWR);

        assertTrue(graph.getKnownGraphA().edgeValue(source, badWriter)
                        .orElse(List.of()).contains(new Edge<>(EdgeType.WW, "kv:x")),
                "SO(source,bad) makes WW(bad,source,k) cyclic, so propagation must force WW(source,bad,k)");
    }

    @Test
    void gmwrModeResolvesKnownBadWriter() {
        var profiler = Profiler.getInstance();
        profiler.clear();
        var history = new History<String, Integer>();
        var session = history.addSession(1L);
        var writer = history.addTransaction(session, 1L);
        var reader = history.addTransaction(session, 2L);
        history.addEvent(writer, WRITE, "kv:x", 10);
        history.addPredicateReadEvent(reader, kvPlan("value > 5"), List.of());
        commitAll(history);

        var graph = new KnownGraph<>(history);
        var solver = new SERSolverAR<>(
                history, graph, generateConstraints(history, graph), true, true,
                SERVerifier.PredicateSolvingMode.GMWR);

        assertEquals(SolveStatus.UNSAT, solver.solve());
        assertTrue(solver.getConflictReasons().stream().anyMatch(reason ->
                        reason.getKind() == SERSolverAR.AssumptionKind.GMWR_RULE),
                "GMWR propagation conflicts must retain an assumption reason");
        assertEquals(1L, profiler.getCount("SER_GMWR_ITEM_OBLIGATIONS_COUNT"));
        assertEquals(0L, profiler.getCount("SER_PRED_EXTERNAL_SOURCED_KEYS_COUNT"));
        assertEquals(1L, profiler.getCount("SER_PRED_EXTERNAL_SOURCELESS_KEYS_COUNT"));
        assertEquals(1L, profiler.getCount("SER_GMWR_BUNDLES_COUNT"));
        assertEquals(0L, profiler.getCount("SER_GMWR_RESIDUAL_BUNDLES_COUNT"));
        assertEquals(0L, profiler.getCount("SER_GMWR_FORCED_ORDERS_COUNT"));
    }

    @Test
    void gmwrBuildsTypedFrontierForRecordedArMaxSource() {
        var profiler = Profiler.getInstance();
        profiler.clear();
        var history = new History<String, Integer>();
        var session = history.addSession(1L);
        var recordedWriter = history.addTransaction(session, 1L);
        var laterMatchingWriter = history.addTransaction(session, 2L);
        var reader = history.addTransaction(session, 3L);

        history.addWriteEvent(recordedWriter, "kv:x", 10, 101L);
        history.addWriteEvent(laterMatchingWriter, "kv:x", 20, 102L);
        history.addPredicateReadEvent(reader, kvPlan("value > 5"), List.of(
                new Event.PredResult<>("kv:x", 10, 101L, 1L, 0)));
        commitAll(history);

        var graph = new KnownGraph<>(history);
        var solver = new SERSolverAR<>(
                history, graph, generateConstraints(history, graph), true, true,
                SERVerifier.PredicateSolvingMode.GMWR);

        assertEquals(SolveStatus.UNSAT, solver.solve(),
                "PR_WR must originate at ARmax visible writer, not an earlier matching write");
        assertEquals(1L, profiler.getCount("SER_PRED_EXTERNAL_SOURCED_KEYS_COUNT"));
        assertEquals(0L, profiler.getCount("SER_PRED_EXTERNAL_SOURCELESS_KEYS_COUNT"));
        assertTrue(profiler.getCount("SER_PRED_FRONTIERS_COUNT") > 0,
                "GMWR must construct the source-aware typed frontier before clause compression");
    }

    @Test
    void gmwrSubsumesProjectionAcrossKeysWithoutDroppingSemanticObligations() {
        var profiler = Profiler.getInstance();
        profiler.clear();
        var history = new History<String, Integer>();
        var badWriter = history.addTransaction(history.addSession(1L), 1L);
        var commonRepair = history.addTransaction(history.addSession(2L), 2L);
        var extraRepair = history.addTransaction(history.addSession(3L), 3L);
        var reader = history.addTransaction(history.addSession(4L), 4L);

        history.addEvent(badWriter, WRITE, "kv:x", 10);
        history.addEvent(badWriter, WRITE, "kv:y", 10);
        history.addEvent(commonRepair, WRITE, "kv:x", 0);
        history.addEvent(commonRepair, WRITE, "kv:y", 0);
        history.addEvent(extraRepair, WRITE, "kv:x", -1);
        history.addPredicateReadEvent(reader, kvPlan("value > 5"), List.of());
        commitAll(history);

        var graph = new KnownGraph<>(history);
        var solver = new SERSolverAR<>(
                history, graph, generateConstraints(history, graph), true, true,
                SERVerifier.PredicateSolvingMode.GMWR);

        assertTrue(solver.getAssumptionReasons().stream().anyMatch(reason ->
                        reason.getKind() == SERSolverAR.AssumptionKind.GMWR_RULE
                                && reason.getReason().contains("badWriter=")),
                "materialized residual GMWR rules must have an assumption id and reason");
        assertEquals(SolveStatus.SAT, solver.solve());
        assertEquals(2L, profiler.getCount("SER_GMWR_ITEM_OBLIGATIONS_COUNT"));
        assertEquals(2L, profiler.getCount(
                "SER_GMWR_SEMANTIC_ITEM_OBLIGATIONS_COUNT"));
        assertEquals(1L, profiler.getCount("SER_GMWR_BUNDLES_COUNT"));
        assertEquals(1L, profiler.getCount("SER_GMWR_UNIQUE_ITEM_CLAUSES_COUNT"));
        assertEquals(1L, profiler.getCount("SER_GMWR_SUBSUMED_ITEM_CLAUSES_COUNT"));
        assertEquals(1L, profiler.getCount("SER_GMWR_RESIDUAL_CLAUSES_COUNT"));
    }

    @Test
    void gmwrOmitsUnactivatablePrWrOnAbsentKey() {
        var history = new History<String, Integer>();
        var goodWriter = history.addTransaction(history.addSession(1L), 1L);
        var badWriter = history.addTransaction(history.addSession(2L), 2L);
        var reader = history.addTransaction(history.addSession(3L), 3L);
        history.addEvent(goodWriter, WRITE, "kv:x", 0);
        history.addEvent(badWriter, WRITE, "kv:x", 10);
        history.addPredicateReadEvent(reader, kvPlan("value > 5"), List.of());
        commitAll(history);

        var eagerGraph = new KnownGraph<>(history);
        var eagerConstraints = generateConstraints(history, eagerGraph);
        var profiler = Profiler.getInstance();
        profiler.clear();
        var eager = new SERSolverAR<>(
                history, eagerGraph, eagerConstraints, true, true,
                SERVerifier.PredicateSolvingMode.EAGER);
        long eagerQueued = profiler.getCount("SER_PRED_DEPENDENCY_QUEUED_COUNT");
        assertEquals(SolveStatus.SAT, eager.solve());

        var gmwrGraph = new KnownGraph<>(history);
        var gmwrConstraints = generateConstraints(history, gmwrGraph);
        profiler.clear();
        var gmwr = new SERSolverAR<>(
                history, gmwrGraph, gmwrConstraints, true, true,
                SERVerifier.PredicateSolvingMode.GMWR);
        long gmwrQueued = profiler.getCount("SER_PRED_DEPENDENCY_QUEUED_COUNT");
        assertEquals(SolveStatus.SAT, gmwr.solve());
        assertEquals(1L, profiler.getCount("SER_GMWR_ITEM_OBLIGATIONS_COUNT"));
        assertEquals(eagerQueued - 2, gmwrQueued,
                "absent-key GMWR must skip PR_WR/PR_RW that are guarded by an unactivatable bad source");
    }

    @Test
    void gmwrUsesKnownWwIntervalForAbsentExternalKey() {
        var history = new History<String, Integer>();
        var olderBadWriter = history.addTransaction(history.addSession(1L), 1L);
        var orderedSession = history.addSession(2L);
        var latestGoodWriter = history.addTransaction(orderedSession, 2L);
        var reader = history.addTransaction(orderedSession, 3L);
        history.addEvent(olderBadWriter, WRITE, "kv:x", 10);
        history.addEvent(latestGoodWriter, WRITE, "kv:x", 0);
        history.addPredicateReadEvent(reader, kvPlan("value > 5"), List.of());
        commitAll(history);

        var graph = new KnownGraph<>(history);
        graph.putEdge(olderBadWriter, latestGoodWriter,
                new Edge<>(EdgeType.WW, "kv:x"));
        var profiler = Profiler.getInstance();
        profiler.clear();
        var solver = new SERSolverAR<>(
                history, graph, List.of(), true, true,
                SERVerifier.PredicateSolvingMode.GMWR);

        assertEquals(KnownGraph.PredicateReadType.EXTERNAL,
                graph.getPredicateObservations().get(0).getPredicateReadType("kv:x"));
        assertEquals(1L, profiler.getCount("SER_PRED_FRONTIER_CANDIDATES_COUNT"),
                "the older WW predecessor cannot be the ARmax predicate source");
        assertTrue(profiler.getCount(
                "SER_GMWR_INTERVAL_CANDIDATES_PRUNED_COUNT") > 0,
                "the known WW interval must remove the older writer before contribution encoding");
        assertEquals(0L, profiler.getCount("SER_GMWR_ITEM_OBLIGATIONS_COUNT"),
                "a shadowed bad writer must not produce a GMWR obligation");
        assertEquals(SolveStatus.SAT, solver.solve());
    }

    @Test
    void gmwrCoalescesPredicateKeysOnSharedEndpoint() {
        var history = new History<String, Integer>();
        var t1 = history.addTransaction(history.addSession(1L), 1L);
        var t2 = history.addTransaction(history.addSession(2L), 2L);
        var edge = new SEREdge<>(t1, t2, EdgeType.PR_WR, "k1");
        assertTrue(edge.addKey("k2"));
        assertTrue(edge.addKey("k3"));
        assertFalse(edge.addKey("k1"));
        assertEquals(Set.of("k1", "k2", "k3"), edge.getKeys());
        assertEquals("k1", edge.getKey());
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
        assertEquals(SolveStatus.SAT, solver.solve());
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

        assertEquals(SolveStatus.UNSAT, solver.solve(), "bottom < writer fixes the WW choice and derives reader RW writer");
    }

    @Test
    void rwIsActivatedByItsWwDecisionBranch() {
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
        var logicalTypes = solver.getLogicalDependencies().stream()
                .map(SEREdge::getType)
                .collect(java.util.stream.Collectors.toSet());

        assertTrue(logicalTypes.containsAll(Set.of(EdgeType.WR, EdgeType.WW, EdgeType.RW)));
        assertEquals(SolveStatus.UNSAT, solver.solve());
    }

    @Test
    void wwOnlyBranchDoesNotTriggerIndependentRwReconstruction() {
        var history = makeHistory(
                Set.of(1L),
                Map.of(1L, List.of(1L, 3L, 2L)),
                Map.of(
                        1L, List.of(Triple.of(WRITE, "x", 1)),
                        3L, List.of(Triple.of(WRITE, "x", 2)),
                        2L, List.of(Triple.of(READ, "x", 1))),
                Map.of());
        var graph = new KnownGraph<>(history);
        var source = history.getTransaction(1L);
        var laterWriter = history.getTransaction(3L);
        var constraint = new SERConstraint<>(
                List.of(new SEREdge<>(source, laterWriter, EdgeType.WW, "x")),
                List.of(new SEREdge<>(laterWriter, source, EdgeType.WW, "x")),
                source, laterWriter, 0);

        var solver = new SERSolverAR<>(history, graph, List.of(constraint));

        assertEquals(SolveStatus.SAT, solver.solve(),
                "ordinary RW must come from the selected WW branch, not a second reconstruction pass");
    }

    @Test
    void rwBranchUsesWrWriterToLaterWriterDirection() {
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

        assertEquals(SolveStatus.UNSAT, solver.solve());
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
                        (PredicateFixtures.RowPredicate<String, Integer>) (k, v) -> v > 5,
                        List.of(new Event.PredResult<>("x", 10)))));

        var graph = new KnownGraph<>(history);
        assertEquals(0L, countEdgesOfType(graph.getKnownGraphA(), EdgeType.PR_WR));
        assertEquals(0L, countEdgesOfType(graph.getKnownGraphB(), EdgeType.PR_RW));

        var solver = new SERSolverAR<>(history, graph, generateConstraints(history, graph));
        assertEquals(SolveStatus.UNSAT, solver.solve());
    }

    @Test
    void predicateResultSourceMustPrecedePredicateReader() {
        var history = makeHistory(
                Set.of(1L),
                Map.of(1L, List.of(2L, 1L)),
                Map.of(1L, List.of(Triple.of(WRITE, "x", 10))),
                Map.of(2L, Pair.of(
                        (PredicateFixtures.RowPredicate<String, Integer>) (k, v) -> v > 5,
                        List.of(new Event.PredResult<>("x", 10)))));

        var graph = new KnownGraph<>(history);
        assertEquals(KnownGraph.PredicateReadType.EXTERNAL,
                graph.getPredicateObservations().get(0).getPredicateReadType("x"));
        var solver = new SERSolverAR<>(history, graph, generateConstraints(history, graph));

        assertEquals(SolveStatus.UNSAT, solver.solve(), "predicate result source must be visible before the predicate read");
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
                        (PredicateFixtures.RowPredicate<String, Integer>) (k, v) -> v > 5,
                        List.of(new Event.PredResult<>("x", 10)))));

        var graph = new KnownGraph<>(history);
        var solver = new SERSolverAR<>(history, graph, generateConstraints(history, graph));

        assertEquals(SolveStatus.UNSAT, solver.solve(), "predicate result source must be the latest visible write under AR");
    }

    @Test
    void predicateFrontierUsesOnlyLastWriteFromEachWriterTransaction() {
        var history = makeHistory(
                Set.of(1L, 2L, 3L),
                Map.of(1L, List.of(1L), 2L, List.of(2L), 3L, List.of(3L)),
                Map.of(
                        1L, List.of(
                                Triple.of(WRITE, "x", 10),
                                Triple.of(WRITE, "x", 20)),
                        2L, List.of(Triple.of(WRITE, "x", 30))),
                Map.of(3L, Pair.of(
                        (PredicateFixtures.RowPredicate<String, Integer>) (k, v) -> v > 5,
                        List.of(new Event.PredResult<>("x", 20)))));
        var profiler = Profiler.getInstance();
        profiler.clear();
        var graph = new KnownGraph<>(history);
        var solver = new SERSolverAR<>(
                history, graph, generateConstraints(history, graph), true, true);

        assertEquals(1, profiler.getCount("SER_PRED_FRONTIERS_COUNT"));
        assertEquals(2, profiler.getCount("SER_PRED_FRONTIER_CANDIDATES_COUNT"));
        assertEquals(SolveStatus.SAT, solver.solve(),
                "the second write of txn 1 must remain its only external frontier candidate");

        var staleHistory = makeHistory(
                Set.of(1L, 2L, 3L),
                Map.of(1L, List.of(1L), 2L, List.of(2L), 3L, List.of(3L)),
                Map.of(
                        1L, List.of(
                                Triple.of(WRITE, "x", 10),
                                Triple.of(WRITE, "x", 20)),
                        2L, List.of(Triple.of(WRITE, "x", 30))),
                Map.of(3L, Pair.of(
                        (PredicateFixtures.RowPredicate<String, Integer>) (k, v) -> v > 5,
                        List.of(new Event.PredResult<>("x", 10)))));

        assertFalse(solveSer(staleHistory),
                "an earlier write from the same writer must not become a frontier candidate");
    }

    @Test
    void keyWriteIndexFindsLatestSelfWriteBeforePredicateEvent() {
        var history = singleTxnHistory();
        var txn = history.getTransaction(1L);
        history.addEvent(txn, WRITE, "x", 10);
        history.addPredicateReadEvent(txn, keyAtLeast("x", 5),
                List.of(new Event.PredResult<>("x", 10)));
        history.addEvent(txn, WRITE, "x", 20);
        commitAll(history);

        var graph = new KnownGraph<>(history);
        assertEquals(KnownGraph.PredicateReadType.INTERNAL,
                graph.getPredicateObservations().get(0).getPredicateReadType("x"));
        assertEquals(SolveStatus.SAT, new SERSolverAR<>(
                history, graph, generateConstraints(history, graph)).solve(),
                "the later self-write must not replace the write visible at the predicate event");
    }

    @Test
    void unresolvedPredicateOrderIsRejectedByCurrentSatSolver() {
        var history = makeHistory(
                Set.of(1L, 2L, 3L),
                Map.of(1L, List.of(1L), 2L, List.of(2L, 4L), 3L, List.of(3L)),
                Map.of(
                        1L, List.of(Triple.of(WRITE, "x", 10), Triple.of(READ, "z", 1)),
                        3L, List.of(Triple.of(WRITE, "x", 3)),
                        4L, List.of(Triple.of(WRITE, "z", 1))),
                Map.of(2L, Pair.of(
                        (PredicateFixtures.RowPredicate<String, Integer>) (k, v) -> v > 5,
                        List.of(new Event.PredResult<>("x", 10)))));
        commitAll(history);

        var graph = new KnownGraph<>(history);
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
                        (PredicateFixtures.RowPredicate<String, Integer>) (k, v) -> v > 5,
                        List.of(new Event.PredResult<>("x", 10)))));

        var graph = new KnownGraph<>(history);
        assertEquals(0L, countEdgesOfType(graph.getKnownGraphA(), EdgeType.PR_WR));
        assertEquals(0L, countEdgesOfType(graph.getKnownGraphB(), EdgeType.PR_RW));

        var solver = new SERSolverAR<>(history, graph, generateConstraints(history, graph));
        assertEquals(SolveStatus.UNSAT, solver.solve());
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
                        (PredicateFixtures.RowPredicate<String, Integer>) (k, v) -> k.equals("x") && v > 5,
                        List.of())));

        var graph = new KnownGraph<>(history);
        var solver = new SERSolverAR<>(history, graph, generateConstraints(history, graph));

        assertEquals(SolveStatus.UNSAT, solver.solve(), "later writer U makes absent key satisfy the predicate, so S must precede U");
    }

    @Test
    void predicateAbsentResultRejectsMatchingVisibleFrontier() {
        var history = makeHistory(
                Set.of(1L),
                Map.of(1L, List.of(1L, 2L)),
                Map.of(1L, List.of(Triple.of(WRITE, "x", 10))),
                Map.of(2L, Pair.of(
                        (PredicateFixtures.RowPredicate<String, Integer>) (k, v) -> k.equals("x") && v > 5,
                        List.of())));

        var graph = new KnownGraph<>(history);
        var solver = new SERSolverAR<>(history, graph, generateConstraints(history, graph));

        assertEquals(SolveStatus.UNSAT, solver.solve(), "empty predicate result is invalid when the latest visible x satisfies P");
    }

    @Test
    void externalPredicateReadAllowsAbsentFrontierBeforeInsert() {
        var history = singleTxnHistory();
        var txn = history.getTransaction(1L);
        history.addPredicateReadEvent(txn, keyAtLeast("x", 5), List.of());
        history.addEvent(txn, WRITE, "x", 3);
        commitAll(history);

        var graph = new KnownGraph<>(history);
        assertEquals(KnownGraph.PredicateReadType.EXTERNAL,
                graph.getPredicateObservations().get(0).getPredicateReadType("x"));

        var solver = new SERSolverAR<>(history, graph, generateConstraints(history, graph));
        assertEquals(SolveStatus.SAT, solver.solve(),
                "a key without an initial version is absent before its later insert");
    }

    @Test
    void selfWrittenPredicateKeysAreDeferredFromCurrentPredicatePath() {
        var history = singleTxnHistory();
        var txn = history.getTransaction(1L);
        history.addEvent(txn, WRITE, "x", 100);
        history.addPredicateReadEvent(txn, keyAtLeast("x", 100),
                List.of(new Event.PredResult<>("x", 100)));
        history.addEvent(txn, WRITE, "y", 120);
        history.addPredicateReadEvent(txn, keyAtLeast("y", 100),
                List.of(new Event.PredResult<>("y", 120)));
        commitAll(history);

        var graph = new KnownGraph<>(history);
        assertEquals(KnownGraph.PredicateReadType.INTERNAL,
                graph.getPredicateObservations().get(0).getPredicateReadType("x"));
        assertEquals(KnownGraph.PredicateReadType.INTERNAL,
                graph.getPredicateObservations().get(1).getPredicateReadType("y"));

        var solver = new SERSolverAR<>(history, graph, generateConstraints(history, graph));
        assertEquals(SolveStatus.SAT, solver.solve(), "self-written predicate keys must not enter the external predicate path");
    }

    @Test
    void repeatedPredicateKeyIsDeferredAfterExternalObservation() {
        var history = makeHistory(
                Set.of(-1L, 1L),
                Map.of(-1L, List.of(-1L), 1L, List.of(1L)),
                Map.of(-1L, List.of(Triple.of(WRITE, "y", 0))),
                Map.of());
        var txn = history.getTransaction(1L);
        history.addPredicateReadEvent(txn, keyAtLeast("y", 100), List.of());
        history.addEvent(txn, WRITE, "y", 120);
        history.addPredicateReadEvent(txn, keyAtLeast("y", 100),
                List.of(new Event.PredResult<>("y", 120)));
        commitAll(history);

        var graph = new KnownGraph<>(history);
        assertEquals(KnownGraph.PredicateReadType.EXTERNAL,
                graph.getPredicateObservations().get(0).getPredicateReadType("y"));
        assertEquals(KnownGraph.PredicateReadType.INTERNAL,
                graph.getPredicateObservations().get(1).getPredicateReadType("y"));

        var solver = new SERSolverAR<>(history, graph, generateConstraints(history, graph));
        assertEquals(SolveStatus.SAT, solver.solve(), "the repeated same-predicate key must not select a new external frontier");
    }

    @Test
    void internalPredicateReadIsDeferredFromCurrentPredicatePath() {
        var history = singleTxnHistory();
        var txn = history.getTransaction(1L);
        history.addEvent(txn, WRITE, "y", 120);
        history.addPredicateReadEvent(txn, keyAtLeast("y", 100), List.of());
        commitAll(history);

        var graph = new KnownGraph<>(history);
        assertEquals(KnownGraph.PredicateReadType.INTERNAL,
                graph.getPredicateObservations().get(0).getPredicateReadType("y"));

        var solver = new SERSolverAR<>(history, graph, generateConstraints(history, graph));
        assertEquals(SolveStatus.UNSAT, solver.solve(),
                "an internal key whose local write matches the predicate cannot have an empty result");
    }

    @Test
    void committedUnresolvedPredicateOrderIsRejectedByArSat() {
        var history = makeHistory(
                Set.of(1L, 2L, 3L),
                Map.of(1L, List.of(1L), 2L, List.of(2L, 4L), 3L, List.of(3L)),
                Map.of(
                        1L, List.of(Triple.of(WRITE, "x", 10), Triple.of(READ, "z", 1)),
                        3L, List.of(Triple.of(WRITE, "x", 3)),
                        4L, List.of(Triple.of(WRITE, "z", 1))),
                Map.of(2L, Pair.of(
                        (PredicateFixtures.RowPredicate<String, Integer>) (k, v) -> k.equals("x") && v > 5,
                        List.of(new Event.PredResult<>("x", 10)))));
        commitAll(history);

        // Theory: PR_WR T1->T2 for x, SO T2->T4, and WR T4->T1 for z form
        // a SER cycle. The x writers T1/T3 are initially unordered; the AR SAT
        // predicate encoding must still reject.
        assertFalse(solveSer(history),
                "strict total AR predicate constraints must detect the cycle");
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

        var pruning = new Pruning<String, Integer>(
                new PrecedenceOracle<>(history.getTransactions()));
        boolean hasLoop = pruning.pruneConstraints(graph, constraints);

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

        assertEquals(SolveStatus.UNSAT, solver.solve());
        var conflicts = solver.getConflicts();
        assertFalse(conflicts.getLeft().isEmpty(), "known-edge cycle should be reported");
        assertTrue(conflicts.getRight().isEmpty(), "no choice constraints are needed for this contradiction");
    }

    @Test
    void solverEncodesPredicateEdgesInKnownGraph() {
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

        assertEquals(Set.of(EdgeType.PR_WR, EdgeType.PR_RW),
                solver.getLogicalDependencies().stream()
                        .map(SEREdge::getType)
                        .filter(type -> type == EdgeType.PR_WR || type == EdgeType.PR_RW)
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(SolveStatus.UNSAT, solver.solve(),
                "known PR_* metadata must constrain the serialization graph");
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

        assertEquals(SolveStatus.UNSAT, solver.solve());
        var conflicts = solver.getConflicts();
        assertFalse(conflicts.getRight().isEmpty(), "unsat remaining WW choices should be reported");
        assertTrue(solver.getConflictReasons().stream().anyMatch(reason ->
                        reason.getKind() == SERSolverAR.AssumptionKind.WW_CHOICE),
                "WW conflict clause must map back to the choice reason");
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
