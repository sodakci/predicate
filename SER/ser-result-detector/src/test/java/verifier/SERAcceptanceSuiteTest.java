package verifier;

import graph.Edge;
import graph.EdgeType;
import graph.KnownGraph;
import history.Event;
import history.History;
import history.Transaction;
import monosat.Graph;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import util.Profiler;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static history.Event.EventType.READ;
import static history.Event.EventType.WRITE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Acceptance tests for the T1-T8 SER test-suite contract. */
class SERAcceptanceSuiteTest {
    private static final int COALESCE_EXTENDED_CASES = 10_000;
    private static final int MODE_EXTENDED_CASES = 100_000;

    @AfterEach
    void restoreGlobalDefaults() {
        Pruning.setEnablePruning(true);
        SERVerifier.setCoalesceConstraints(true);
        SERVerifier.setDotOutput(false);
        SERVerifier.setCompareDerivedPredicateEdges(false);
    }

    @Nested
    @DisplayName("T1 basic Adya correctness")
    class BasicAdyaCorrectness {
        @Test
        void t1_1_singleWrAccepts() {
            var history = withInitial("x", 0);
            var writer = addTransaction(history, 1L, 1L);
            var reader = addTransaction(history, 2L, 2L);
            history.addEvent(writer, WRITE, "x", 1);
            history.addEvent(reader, READ, "x", 1);
            commitAll(history);

            assertEquals(SERVerifier.AuditResult.ACCEPT, audit(history));
        }

        @Test
        void t1_2_reverseSessionOrderRejectsWrSoCycle() {
            var history = withInitial("x", 0);
            var session = history.addSession(1L);
            var reader = history.addTransaction(session, 2L);
            var writer = history.addTransaction(session, 1L);
            history.addEvent(reader, READ, "x", 1);
            history.addEvent(writer, WRITE, "x", 1);
            commitAll(history);

            assertEquals(SERVerifier.AuditResult.REJECT, audit(history));
        }

        @Test
        void t1_3_multipleReadersAccept() {
            var history = withInitial("x", 0);
            var writer = addTransaction(history, 1L, 1L);
            var firstReader = addTransaction(history, 2L, 2L);
            var secondReader = addTransaction(history, 3L, 3L);
            history.addEvent(writer, WRITE, "x", 1);
            history.addEvent(firstReader, READ, "x", 1);
            history.addEvent(secondReader, READ, "x", 1);
            commitAll(history);

            assertEquals(SERVerifier.AuditResult.ACCEPT, audit(history));
        }
    }

    @Nested
    @DisplayName("T2 WW/RW encoding")
    class WwRwEncoding {
        @Test
        void t2_1_eitherLegalWwDirectionMayBeChosen() {
            assertEquals(SERVerifier.AuditResult.ACCEPT,
                    audit(twoWritersReaderHistory(false)));
        }

        @Test
        void t2_2_sessionOrderForcesConflictingWwRwBranch() {
            assertEquals(SERVerifier.AuditResult.REJECT,
                    audit(twoWritersReaderHistory(true)));
        }

        @Test
        void t2_3_coalescingDoesNotChangeVerdict() {
            for (int seed = 0; seed < 64; seed++) {
                var history = randomPointHistory(seed);
                assertEquals(
                        audit(history, SERVerifier.PredicateSolvingMode.EAGER,
                                SERVerifier.PruningMode.REACHABILITY,
                                SERVerifier.SerPropagationMode.WW_ONLY, true),
                        audit(history, SERVerifier.PredicateSolvingMode.EAGER,
                                SERVerifier.PruningMode.REACHABILITY,
                                SERVerifier.SerPropagationMode.WW_ONLY, false),
                        "coalesce mismatch at seed=" + seed);
            }
        }

        @Test
        @EnabledIfEnvironmentVariable(named = "SER_ACCEPTANCE_EXTENDED", matches = "true")
        void t2_3_extendedTenThousandHistoryCoalescingDifferential() {
            for (int seed = 0; seed < COALESCE_EXTENDED_CASES; seed++) {
                var history = randomPointHistory(seed);
                assertEquals(
                        audit(history, SERVerifier.PredicateSolvingMode.EAGER,
                                SERVerifier.PruningMode.REACHABILITY,
                                SERVerifier.SerPropagationMode.WW_ONLY, true),
                        audit(history, SERVerifier.PredicateSolvingMode.EAGER,
                                SERVerifier.PruningMode.REACHABILITY,
                                SERVerifier.SerPropagationMode.WW_ONLY, false),
                        "coalesce mismatch at seed=" + seed);
            }
        }

        @Test
        void t2_4_multipleWritersCreateMultipleWwChoices() {
            var history = withInitial("x", 0);
            var first = addTransaction(history, 1L, 1L);
            var second = addTransaction(history, 2L, 2L);
            var third = addTransaction(history, 3L, 3L);
            var reader = addTransaction(history, 4L, 4L);
            history.addEvent(first, WRITE, "x", 1);
            history.addEvent(second, WRITE, "x", 2);
            history.addEvent(third, WRITE, "x", 3);
            history.addEvent(reader, READ, "x", 1);
            commitAll(history);

            var graph = new KnownGraph<>(history);
            long clientWriterChoices = SERVerifier.generateConstraintsSER(history, graph)
                    .stream()
                    .filter(constraint -> constraint.getWriteTransaction1().getId() >= 0)
                    .filter(constraint -> constraint.getWriteTransaction2().getId() >= 0)
                    .count();

            assertEquals(3L, clientWriterChoices);
            assertEquals(SERVerifier.AuditResult.ACCEPT, audit(history));
        }
    }

    @Nested
    @DisplayName("T3 predicate correctness")
    class PredicateCorrectness {
        @Test
        void t3_1_returnedTupleUsesLatestVisibleSource() {
            var history = withInitial("x", 0);
            var writer = addTransaction(history, 1L, 1L);
            var reader = addTransaction(history, 2L, 2L);
            history.addEvent(writer, WRITE, "x", 5);
            history.addPredicateReadEvent(reader, greaterThan(Set.of("x"), 0),
                    List.of(result("x", 5)));
            commitAll(history);

            assertAllPredicateModes(history, SERVerifier.AuditResult.ACCEPT);
        }

        @Test
        void t3_2_stalePredicateVersionRejects() {
            var history = returnedVersionHistory(false);

            assertAllPredicateModes(history, SERVerifier.AuditResult.REJECT);
        }

        @Test
        void t3_3_overwriteOrderCanMakeRecordedVersionValid() {
            var history = returnedVersionHistory(true);

            assertAllPredicateModes(history, SERVerifier.AuditResult.ACCEPT);
        }

        @Test
        void t3_4_absentMatchingKeyMustBeOrderedAfterReader() {
            var unordered = absentMatchingWriterHistory(false);
            var writerBeforeReader = absentMatchingWriterHistory(true);

            assertAllPredicateModes(unordered, SERVerifier.AuditResult.ACCEPT);
            assertAllPredicateModes(writerBeforeReader, SERVerifier.AuditResult.REJECT);
        }

        @Test
        void t3_5_goodWriterRepairsBadWriterOnlyInCorrectOrder() {
            assertAllPredicateModes(badAndRepairWriterHistory(true),
                    SERVerifier.AuditResult.ACCEPT);
            assertAllPredicateModes(badAndRepairWriterHistory(false),
                    SERVerifier.AuditResult.REJECT);
        }

        @Test
        void t3_6_internalPredicateKeyHasEagerGmwrParity() {
            var history = withInitial("x", 0);
            var reader = addTransaction(history, 1L, 1L);
            history.addEvent(reader, WRITE, "x", 5);
            history.addPredicateReadEvent(reader, greaterThan(Set.of("x"), 0),
                    List.of(result("x", 5)));
            commitAll(history);

            var graph = new KnownGraph<>(history);
            assertEquals(KnownGraph.PredicateReadType.INTERNAL,
                    graph.getPredicateObservations().get(0).getPredicateReadType("x"));
            assertAllPredicateModes(history, SERVerifier.AuditResult.ACCEPT);
        }

        @Test
        void t3_7_mixedExternalInternalAndAbsentKeysHaveModeParity() {
            var history = withInitial("x", 0, "y", -1, "z", -2);
            var externalWriter = addTransaction(history, 1L, 1L);
            var reader = addTransaction(history, 2L, 2L);
            history.addEvent(externalWriter, WRITE, "x", 5);
            history.addEvent(reader, WRITE, "y", 6);
            history.addPredicateReadEvent(reader,
                    greaterThan(Set.of("x", "y", "z"), 0),
                    List.of(result("x", 5), result("y", 6)));
            commitAll(history);

            var observation = new KnownGraph<>(history).getPredicateObservations().get(0);
            assertEquals(KnownGraph.PredicateReadType.EXTERNAL,
                    observation.getPredicateReadType("x"));
            assertEquals(KnownGraph.PredicateReadType.INTERNAL,
                    observation.getPredicateReadType("y"));
            assertEquals(KnownGraph.PredicateReadType.EXTERNAL,
                    observation.getPredicateReadType("z"));
            assertAllPredicateModes(history, SERVerifier.AuditResult.ACCEPT);
        }
    }

    @Nested
    @DisplayName("T4 EAGER/GMWR equivalence")
    class EagerGmwrEquivalence {
        @Test
        void t4_handcraftedPredicateCorpusHasIdenticalVerdicts() {
            var histories = List.of(
                    returnedVersionHistory(false),
                    returnedVersionHistory(true),
                    absentMatchingWriterHistory(false),
                    absentMatchingWriterHistory(true),
                    badAndRepairWriterHistory(true),
                    badAndRepairWriterHistory(false));

            for (var history : histories) {
                assertModeParity(history, SERVerifier.PruningMode.REACHABILITY, true);
                assertModeParity(history, SERVerifier.PruningMode.NONE, false);
            }
        }

        @Test
        @EnabledIfEnvironmentVariable(named = "SER_ACCEPTANCE_EXTENDED", matches = "true")
        void t4_extendedOneHundredThousandHistoryModeDifferential() {
            for (int seed = 0; seed < MODE_EXTENDED_CASES; seed++) {
                assertModeParity(randomPredicateHistory(seed),
                        SERVerifier.PruningMode.REACHABILITY, (seed & 1) == 0);
            }
        }
    }

    @Nested
    @DisplayName("T5 PrecedenceOracle propagation")
    class PrecedenceOraclePropagation {
        @Test
        void t5_1_allOrderComponentsShareOneOracleInstance() {
            var history = twoEmptyTransactions();
            var graph = new KnownGraph<String, Integer>(history);
            var oracle = new PrecedenceOracle<Transaction<String, Integer>>(
                    history.getTransactions());
            var reachability = new Pruning<String, Integer>(oracle);
            var snapshot = new Prun<String, Integer>(oracle);
            var propagation = new GmwrPropagationState<>(history, graph, oracle);
            var bridge = new GmwrWwBridge<String, Integer>(oracle);
            var solver = new SERSolverAR<>(history, graph, List.of(), true, false,
                    settings(SERVerifier.PredicateSolvingMode.GMWR,
                            SERVerifier.PruningMode.REACHABILITY,
                            SERVerifier.SerPropagationMode.WW_GMWR), oracle);

            assertSame(oracle, reachability.precedenceOracle());
            assertSame(oracle, snapshot.precedenceOracle());
            assertSame(oracle, propagation.precedenceOracle());
            assertSame(oracle, bridge.precedenceOracle());
            assertSame(oracle, solver.precedenceOracle());
        }

        @Test
        void t5_2_gmwrFactIsImmediatelyVisibleToPruning() {
            var history = twoEmptyTransactions();
            var graph = new KnownGraph<String, Integer>(history);
            var oracle = new PrecedenceOracle<Transaction<String, Integer>>(
                    history.getTransactions());
            var propagation = new GmwrPropagationState<>(history, graph, oracle);
            var pruning = new Pruning<String, Integer>(oracle);
            var before = history.getTransaction(1L);
            var after = history.getTransaction(2L);

            assertTrue(propagation.addKnownFact(before, after, EdgeType.PR_WR, "x"));
            assertTrue(pruning.precedenceOracle().before(before, after));
        }

        @Test
        void t5_3_reverseFactReportsContradictionBeforeSolver() {
            var oracle = new PrecedenceOracle<>(List.of("a", "b"));

            assertTrue(oracle.add("a", "b"));
            assertTrue(oracle.wouldCycle("b", "a"));
            assertFalse(oracle.add("b", "a"));
        }
    }

    @Nested
    @DisplayName("T6 graph architecture equivalence")
    class GraphArchitectureEquivalence {
        @Test
        void t6_solverOwnsOnlyTheSingleSerializationGraph() {
            var graphFields = Arrays.stream(SERSolverAR.class.getDeclaredFields())
                    .filter(field -> field.getType() == Graph.class)
                    .map(Field::getName)
                    .collect(Collectors.toList());

            assertEquals(List.of("serializationGraph"), graphFields);
        }

        @Test
        void t6_endpointInterningDoesNotChangeVerdict() {
            for (int seed = 0; seed < 64; seed++) {
                var history = randomPointHistory(seed);
                var baseline = settings(SERVerifier.PredicateSolvingMode.EAGER,
                        SERVerifier.PruningMode.NONE,
                        SERVerifier.SerPropagationMode.WW_ONLY);
                baseline.graphEdgeInterning = false;
                var singleGraph = settings(SERVerifier.PredicateSolvingMode.EAGER,
                        SERVerifier.PruningMode.NONE,
                        SERVerifier.SerPropagationMode.WW_ONLY);
                singleGraph.graphEdgeInterning = true;

                assertEquals(solve(history, baseline), solve(history, singleGraph),
                        "graph encoding mismatch at seed=" + seed);
            }
        }
    }

    @Nested
    @DisplayName("T7 pruning soundness")
    class PruningSoundness {
        @Test
        void t7_eachPruningAndPropagationStagePreservesVerdict() {
            var histories = List.of(
                    twoWritersReaderHistory(false),
                    twoWritersReaderHistory(true),
                    returnedVersionHistory(false),
                    returnedVersionHistory(true),
                    absentMatchingWriterHistory(false),
                    absentMatchingWriterHistory(true),
                    badAndRepairWriterHistory(true),
                    badAndRepairWriterHistory(false));

            for (var history : histories) {
                var expected = audit(history, SERVerifier.PredicateSolvingMode.EAGER,
                        SERVerifier.PruningMode.NONE,
                        SERVerifier.SerPropagationMode.WW_ONLY, true);
                for (var pruning : List.of(
                        SERVerifier.PruningMode.NONE,
                        SERVerifier.PruningMode.REACHABILITY)) {
                    assertEquals(expected,
                            audit(history, SERVerifier.PredicateSolvingMode.EAGER,
                                    pruning, SERVerifier.SerPropagationMode.WW_ONLY, true),
                            "EAGER pruning mismatch: " + pruning);
                    assertEquals(expected,
                            audit(history, SERVerifier.PredicateSolvingMode.GMWR,
                                    pruning, SERVerifier.SerPropagationMode.WW_GMWR_ONEWAY,
                                    true),
                            "GMWR pruning mismatch: " + pruning);
                }
                assertEquals(expected,
                        audit(history, SERVerifier.PredicateSolvingMode.GMWR,
                                SERVerifier.PruningMode.REACHABILITY,
                                SERVerifier.SerPropagationMode.WW_GMWR, true),
                        "WWBridge mismatch");
            }
        }

        @Test
        void t7_1_reachabilityDropsTheCyclicBranch() {
            var history = twoEmptyTransactions();
            var first = history.getTransaction(1L);
            var second = history.getTransaction(2L);
            var graph = new KnownGraph<String, Integer>(history);
            graph.putEdge(first, second, new Edge<>(EdgeType.SO, null));
            var constraints = new ArrayList<>(List.of(new SERConstraint<>(
                    List.of(new SEREdge<>(second, first, EdgeType.WW, "x")),
                    List.of(new SEREdge<>(first, second, EdgeType.WW, "x")),
                    second, first, 0)));

            assertFalse(new Pruning<String, Integer>(
                    new PrecedenceOracle<>(history.getTransactions()))
                    .pruneConstraints(graph, constraints));
            assertTrue(constraints.isEmpty());
            assertTrue(graph.getKnownGraphA().hasEdgeConnecting(first, second));
        }
    }

    @Nested
    @DisplayName("T8 performance metric contract")
    class PerformanceMetricContract {
        @Test
        void t8_eagerAndGmwrPublishPaperExperimentMetrics() {
            var profiler = Profiler.getInstance();
            profiler.clear();
            audit(badAndRepairWriterHistory(true),
                    SERVerifier.PredicateSolvingMode.EAGER,
                    SERVerifier.PruningMode.REACHABILITY,
                    SERVerifier.SerPropagationMode.WW_ONLY, true, true);
            var eagerTags = countTags(profiler);

            profiler.clear();
            audit(badAndRepairWriterHistory(true),
                    SERVerifier.PredicateSolvingMode.GMWR,
                    SERVerifier.PruningMode.REACHABILITY,
                    SERVerifier.SerPropagationMode.WW_GMWR, true, true);
            var gmwrTags = countTags(profiler);

            assertTrue(eagerTags.contains("WW_INITIAL_CHOICES"));
            assertTrue(eagerTags.contains("WW_REACHABILITY_FORCED"));
            assertTrue(eagerTags.contains("WW_AFTER_REACHABILITY"));
            assertTrue(eagerTags.contains("SER_PROP_RESIDUAL_SAT_VARIABLES_COUNT"));
            assertTrue(eagerTags.contains("SER_PROP_RESIDUAL_SAT_CONSTRAINTS_COUNT"));
            assertTrue(eagerTags.contains("SER_PRED_FRONTIER_CANDIDATES_COUNT"));
            assertTrue(gmwrTags.contains("SER_GMWR_ITEM_OBLIGATIONS_COUNT"));
            assertTrue(gmwrTags.contains("SER_GMWR_RESIDUAL_CLAUSES_COUNT"));
        }
    }

    private static History<String, Integer> twoWritersReaderHistory(boolean forceConflict) {
        var history = withInitial("x", 0);
        Transaction<String, Integer> first;
        Transaction<String, Integer> second;
        Transaction<String, Integer> reader;
        if (forceConflict) {
            var session = history.addSession(1L);
            first = history.addTransaction(session, 1L);
            second = history.addTransaction(session, 2L);
            reader = history.addTransaction(session, 3L);
        } else {
            first = addTransaction(history, 1L, 1L);
            second = addTransaction(history, 2L, 2L);
            reader = addTransaction(history, 3L, 3L);
        }
        history.addEvent(first, WRITE, "x", 1);
        history.addEvent(second, WRITE, "x", 2);
        history.addEvent(reader, READ, "x", 1);
        commitAll(history);
        return history;
    }

    private static History<String, Integer> returnedVersionHistory(boolean validOverwriteOrder) {
        var history = withInitial("x", 0);
        var session = history.addSession(1L);
        Transaction<String, Integer> oldWriter;
        Transaction<String, Integer> newWriter;
        if (validOverwriteOrder) {
            newWriter = history.addTransaction(session, 2L);
            oldWriter = history.addTransaction(session, 1L);
        } else {
            oldWriter = history.addTransaction(session, 1L);
            newWriter = history.addTransaction(session, 2L);
        }
        var reader = history.addTransaction(session, 3L);
        history.addEvent(oldWriter, WRITE, "x", 5);
        history.addEvent(newWriter, WRITE, "x", 10);
        history.addPredicateReadEvent(reader, greaterThan(Set.of("x"), 0),
                List.of(result("x", 5)));
        commitAll(history);
        return history;
    }

    private static History<String, Integer> absentMatchingWriterHistory(
            boolean writerBeforeReader) {
        var history = withInitial("x", 0);
        Transaction<String, Integer> writer;
        Transaction<String, Integer> reader;
        if (writerBeforeReader) {
            var session = history.addSession(1L);
            writer = history.addTransaction(session, 1L);
            reader = history.addTransaction(session, 2L);
        } else {
            writer = addTransaction(history, 1L, 1L);
            reader = addTransaction(history, 2L, 2L);
        }
        history.addEvent(writer, WRITE, "x", 20);
        history.addPredicateReadEvent(reader, greaterThan(Set.of("x"), 10),
                List.of());
        commitAll(history);
        return history;
    }

    private static History<String, Integer> badAndRepairWriterHistory(
            boolean badBeforeRepair) {
        var history = withInitial("x", 0);
        var session = history.addSession(1L);
        Transaction<String, Integer> bad;
        Transaction<String, Integer> repair;
        if (badBeforeRepair) {
            bad = history.addTransaction(session, 1L);
            repair = history.addTransaction(session, 2L);
        } else {
            repair = history.addTransaction(session, 2L);
            bad = history.addTransaction(session, 1L);
        }
        var reader = history.addTransaction(session, 3L);
        history.addEvent(bad, WRITE, "x", 20);
        history.addEvent(repair, WRITE, "x", 5);
        history.addPredicateReadEvent(reader, lessThan(Set.of("x"), 10),
                List.of(result("x", 5)));
        commitAll(history);
        return history;
    }

    private static History<String, Integer> randomPointHistory(int seed) {
        var history = withInitial("x", 0);
        var firstValue = seed * 2 + 1;
        var secondValue = seed * 2 + 2;
        var pattern = Math.floorMod(seed, 4);
        Transaction<String, Integer> first;
        Transaction<String, Integer> second;
        Transaction<String, Integer> reader;
        if (pattern == 0) {
            first = addTransaction(history, 1L, 1L);
            second = addTransaction(history, 2L, 2L);
            reader = addTransaction(history, 3L, 3L);
        } else {
            var session = history.addSession(1L);
            if (pattern == 1) {
                first = history.addTransaction(session, 1L);
                second = history.addTransaction(session, 2L);
                reader = history.addTransaction(session, 3L);
            } else if (pattern == 2) {
                second = history.addTransaction(session, 2L);
                first = history.addTransaction(session, 1L);
                reader = history.addTransaction(session, 3L);
            } else {
                first = history.addTransaction(session, 1L);
                reader = history.addTransaction(session, 3L);
                second = history.addTransaction(session, 2L);
            }
        }
        history.addEvent(first, WRITE, "x", firstValue);
        history.addEvent(second, WRITE, "x", secondValue);
        history.addEvent(reader, READ, "x", firstValue);
        commitAll(history);
        return history;
    }

    private static History<String, Integer> randomPredicateHistory(int seed) {
        var random = new Random(seed);
        var key = "k" + random.nextInt(17);
        var firstValue = seed * 4 + 5;
        var secondValue = firstValue + 1;
        var history = withInitial(key, 0);
        var scenario = random.nextInt(7);

        if (scenario == 0) {
            var writer = addTransaction(history, 1L, 1L);
            var reader = addTransaction(history, 2L, 2L);
            history.addEvent(writer, WRITE, key, firstValue);
            history.addPredicateReadEvent(reader, greaterThan(Set.of(key), 0),
                    List.of(result(key, firstValue)));
        } else if (scenario == 1 || scenario == 2) {
            var session = history.addSession(1L);
            Transaction<String, Integer> firstWriter;
            Transaction<String, Integer> secondWriter;
            if (scenario == 1) {
                firstWriter = history.addTransaction(session, 1L);
                secondWriter = history.addTransaction(session, 2L);
            } else {
                secondWriter = history.addTransaction(session, 2L);
                firstWriter = history.addTransaction(session, 1L);
            }
            var reader = history.addTransaction(session, 3L);
            history.addEvent(firstWriter, WRITE, key, firstValue);
            history.addEvent(secondWriter, WRITE, key, secondValue);
            history.addPredicateReadEvent(reader, greaterThan(Set.of(key), 0),
                    List.of(result(key, firstValue)));
        } else if (scenario == 3 || scenario == 4) {
            Transaction<String, Integer> writer;
            Transaction<String, Integer> reader;
            if (scenario == 3) {
                writer = addTransaction(history, 1L, 1L);
                reader = addTransaction(history, 2L, 2L);
            } else {
                var session = history.addSession(1L);
                writer = history.addTransaction(session, 1L);
                reader = history.addTransaction(session, 2L);
            }
            history.addEvent(writer, WRITE, key, firstValue);
            history.addPredicateReadEvent(reader,
                    greaterThan(Set.of(key), firstValue - 1), List.of());
        } else if (scenario == 5) {
            var reader = addTransaction(history, 1L, 1L);
            history.addEvent(reader, WRITE, key, firstValue);
            history.addPredicateReadEvent(reader, greaterThan(Set.of(key), 0),
                    List.of(result(key, firstValue)));
        } else {
            var session = history.addSession(1L);
            var repair = history.addTransaction(session, 2L);
            var bad = history.addTransaction(session, 1L);
            var reader = history.addTransaction(session, 3L);
            history.addEvent(repair, WRITE, key, firstValue);
            history.addEvent(bad, WRITE, key, secondValue + 100);
            history.addPredicateReadEvent(reader,
                    lessThan(Set.of(key), secondValue + 50),
                    List.of(result(key, firstValue)));
        }
        commitAll(history);
        return history;
    }

    private static void assertAllPredicateModes(
            History<String, Integer> history,
            SERVerifier.AuditResult expected) {
        assertEquals(expected,
                audit(history, SERVerifier.PredicateSolvingMode.EAGER,
                        SERVerifier.PruningMode.REACHABILITY,
                        SERVerifier.SerPropagationMode.WW_ONLY, true));
        assertEquals(expected,
                audit(history, SERVerifier.PredicateSolvingMode.GMWR,
                        SERVerifier.PruningMode.REACHABILITY,
                        SERVerifier.SerPropagationMode.WW_GMWR, true));
    }

    private static void assertModeParity(
            History<String, Integer> history,
            SERVerifier.PruningMode pruning,
            boolean coalescing) {
        var eager = audit(history, SERVerifier.PredicateSolvingMode.EAGER,
                pruning, SERVerifier.SerPropagationMode.WW_ONLY, coalescing);
        var gmwr = audit(history, SERVerifier.PredicateSolvingMode.GMWR,
                pruning, SERVerifier.SerPropagationMode.WW_GMWR, coalescing);
        assertEquals(eager, gmwr);
    }

    private static SERVerifier.AuditResult audit(History<String, Integer> history) {
        return audit(history, SERVerifier.PredicateSolvingMode.EAGER,
                SERVerifier.PruningMode.REACHABILITY,
                SERVerifier.SerPropagationMode.WW_ONLY, true);
    }

    private static SERVerifier.AuditResult audit(
            History<String, Integer> history,
            SERVerifier.PredicateSolvingMode predicate,
            SERVerifier.PruningMode pruning,
            SERVerifier.SerPropagationMode propagation,
            boolean coalescing) {
        return audit(history, predicate, pruning, propagation, coalescing, false);
    }

    private static SERVerifier.AuditResult audit(
            History<String, Integer> history,
            SERVerifier.PredicateSolvingMode predicate,
            SERVerifier.PruningMode pruning,
            SERVerifier.SerPropagationMode propagation,
            boolean coalescing,
            boolean detailedMetrics) {
        SERVerifier.setCoalesceConstraints(coalescing);
        SERVerifier.setDotOutput(false);
        SERVerifier.setCompareDerivedPredicateEdges(false);
        var solverSettings = settings(predicate, pruning, propagation);
        return new SERVerifier<String, Integer>(
                () -> history, solverSettings, detailedMetrics).audit();
    }

    private static SolveStatus solve(
            History<String, Integer> history,
            SERVerifier.SolverSettings solverSettings) {
        var graph = new KnownGraph<>(history);
        return new SERSolverAR<>(history, graph,
                SERVerifier.generateConstraintsSER(history, graph),
                true, false, solverSettings).solve();
    }

    private static SERVerifier.SolverSettings settings(
            SERVerifier.PredicateSolvingMode predicate,
            SERVerifier.PruningMode pruning,
            SERVerifier.SerPropagationMode propagation) {
        var solverSettings = SERVerifier.SolverSettings.forModes(
                predicate, pruning, propagation);
        solverSettings.gmwrPrepropagation =
                predicate == SERVerifier.PredicateSolvingMode.GMWR;
        solverSettings.predicateWitnessCoalescing = true;
        solverSettings.graphEdgeInterning = true;
        return solverSettings;
    }

    private static PredicateFixtures.RowPredicate<String, Integer> greaterThan(
            Set<String> keys, int threshold) {
        return predicate(keys, threshold, false);
    }

    private static PredicateFixtures.RowPredicate<String, Integer> lessThan(
            Set<String> keys, int threshold) {
        return predicate(keys, threshold, true);
    }

    private static PredicateFixtures.RowPredicate<String, Integer> predicate(
            Set<String> keys, int threshold, boolean lessThan) {
        return new PredicateFixtures.RowPredicate<>() {
            @Override
            public boolean test(String key, Integer value) {
                return value != null && keys.contains(key)
                        && (lessThan ? value < threshold : value > threshold);
            }

            @Override
            public boolean covers(String key) {
                return keys.contains(key);
            }
        };
    }

    private static Event.PredResult<String, Integer> result(String key, int value) {
        return new Event.PredResult<>(key, value);
    }

    private static History<String, Integer> withInitial(Object... keyValues) {
        var history = new History<String, Integer>();
        var initial = history.addTransaction(history.addSession(-1L), -1L);
        long writeId = 1L;
        for (int index = 0; index < keyValues.length; index += 2) {
            history.addWriteEvent(initial, (String) keyValues[index],
                    (Integer) keyValues[index + 1], writeId++);
        }
        initial.setStatus(Transaction.TransactionStatus.COMMIT);
        return history;
    }

    private static History<String, Integer> twoEmptyTransactions() {
        var history = new History<String, Integer>();
        addTransaction(history, 1L, 1L);
        addTransaction(history, 2L, 2L);
        commitAll(history);
        return history;
    }

    private static Transaction<String, Integer> addTransaction(
            History<String, Integer> history, long sessionId, long transactionId) {
        return history.addTransaction(history.addSession(sessionId), transactionId);
    }

    private static void commitAll(History<?, ?> history) {
        history.getTransactions().forEach(
                transaction -> transaction.setStatus(Transaction.TransactionStatus.COMMIT));
    }

    private static Set<String> countTags(Profiler profiler) {
        return profiler.getCounts().stream()
                .map(pair -> pair.getLeft())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
