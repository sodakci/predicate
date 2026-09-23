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

import static org.junit.jupiter.api.Assertions.*;

class SIInducedCoverageTest {
    @Test
    void physicalEdgeKeysDistributeSequentialNodePairsWithoutConcentratedCollisions()
            throws ReflectiveOperationException {
        var history = new History<String, Integer>();
        var transactions = new ArrayList<Transaction<String, Integer>>();
        int size = 128;
        for (long id = 1; id <= size; id++) {
            var txn = history.addTransaction(history.addSession(id), id);
            txn.setStatus(Transaction.TransactionStatus.COMMIT);
            transactions.add(txn);
        }
        var graph = new KnownGraph<>(history, false);
        for (int from = 0; from < size; from++) {
            for (int to = from + 1; to < size; to++) {
                graph.getKnownGraphA().putEdgeValue(transactions.get(from), transactions.get(to),
                        List.of(new Edge<>(EdgeType.WR, "x")));
            }
        }
        var oracle = new SIReachabilityOracle<>(graph);
        Profiler.getInstance().clear();
        var solver = SISolverTestSupport.create(history, graph, List.of(), false, true,
                SIVerifier.SolverSettings.defaults(), oracle);
        var field = SISolverInduced.class.getDeclaredField("inducedEdgeSupports");
        field.setAccessible(true);
        var edges = (java.util.Map<?, ?>) field.get(solver);
        assertEquals(size * (size - 1) / 2, edges.size());
        // 回归连续节点端点的哈希退化，不以机器相关的耗时作为断言。
        long distinctHashes = edges.keySet().stream().map(Object::hashCode).distinct().count();
        assertTrue(distinctHashes > edges.size() / 4,
                "物理边哈希过度集中: " + distinctHashes + " / " + edges.size());
        assertEquals(size - 1, Profiler.getInstance().getCount("SI_PROP_MONOSAT_GRAPH_EDGES_COUNT"));
        assertTrue(solver.solve());
    }

    @Test
    void conditionalCompositionRetainsItsSupportAlongsideKnownPath() {
        for (boolean interning : List.of(false, true)) {
            var f = new Fixture();
            f.known(0, 1, EdgeType.WR);
            f.known(1, 2, EdgeType.RW);
            f.known(3, 2, EdgeType.RW);
            var oracle = new SIReachabilityOracle<>(f.graph);
            assertFalse(oracle.reachesA(f.txns.get(0), f.txns.get(2)));
            var choice = f.choice(0, 3, 3, 0);
            var solver = f.solver(List.of(choice), oracle, interning);

            // 三条直接 A 支持与两条到 2 的组合支持均保留，端点合并不能丢 guard。
            assertTrue(Profiler.getInstance().getCount("SI_PRED_INDUCED_QUEUED_COUNT") >= 5);
            assertTrue(solver.solve());
        }
    }

    @Test
    void committedWwRoundDoesNotSuppressConditionalCompositionSupport() {
        var f = new Fixture();
        f.known(0, 1, EdgeType.WR);
        f.known(3, 2, EdgeType.RW);
        var oracle = new SIReachabilityOracle<>(f.graph);
        f.known(1, 3, EdgeType.WW);
        assertTrue(oracle.commitRound(List.of(
                new SIEdge<>(f.txns.get(1), f.txns.get(3), EdgeType.WW, "x"))));
        var solver = f.solver(List.of(f.choice(0, 3, 3, 0)), oracle, true);
        // 已知 0→1→2 不替代条件 0→3→2；四条直接支持与两条组合支持均编码。
        assertTrue(Profiler.getInstance().getCount("SI_PRED_INDUCED_QUEUED_COUNT") >= 6);
        assertTrue(solver.solve());
    }

    @Test
    void fixedCompositionStillRejectsBothReverseChoices() {
        var f = new Fixture();
        f.known(0, 1, EdgeType.WR);
        f.known(1, 2, EdgeType.RW);
        var solver = f.solver(List.of(f.choice(2, 0, 1, 0)),
                new SIReachabilityOracle<>(f.graph), true);
        // 2->0 closes the induced path; 1->0 closes the direct A path.
        assertFalse(solver.solve());
    }

    @Test
    void derivedVisibilityDoesNotSuppressConditionalCompositionSupport() {
        var f = new Fixture();
        f.known(3, 2, EdgeType.RW);
        var oracle = new SIReachabilityOracle<>(f.graph);
        oracle.addVisibility(f.txns.get(0), f.txns.get(2));
        var solver = f.solver(List.of(f.choice(0, 3, 3, 0)), oracle, true);
        // Oracle 的确定信息不代替 SAT 内两个方向的直接支持及其条件组合。
        assertTrue(Profiler.getInstance().getCount("SI_PRED_INDUCED_QUEUED_COUNT") >= 3);
        assertTrue(solver.solve());
    }

    @Test
    void conditionalSelfLoopsRemainConflicts() {
        var f = new Fixture();
        f.known(1, 0, EdgeType.RW);
        f.known(0, 1, EdgeType.RW);
        var solver = f.solver(List.of(f.choice(0, 1, 1, 0)),
                new SIReachabilityOracle<>(f.graph), true);
        assertFalse(solver.solve());
    }

    @Test
    void everySmallSimpleCycleAndSingleEdgeDeletionMatchesHandCalculatedRule() {
        for (int size : List.of(3, 4)) {
            for (int aMask = 0; aMask < (1 << size); aMask++) {
                // 简单环中相邻两个 B 打断 induced 闭环，纯 B 环也包含这种情况。
                boolean adjacentB = false;
                for (int edge = 0; edge < size; edge++) {
                    if ((aMask & (1 << edge)) == 0
                            && (aMask & (1 << ((edge + 1) % size))) == 0) {
                        adjacentB = true;
                    }
                }
                for (int removed = -1; removed < size; removed++) {
                    var history = new History<String, Integer>();
                    var transactions = new ArrayList<Transaction<String, Integer>>();
                    for (long id = 1; id <= size; id++) {
                        var txn = history.addTransaction(history.addSession(id), id);
                        txn.setStatus(Transaction.TransactionStatus.COMMIT);
                        transactions.add(txn);
                    }
                    var graph = new KnownGraph<>(history, false);
                    for (int edge = 0; edge < size; edge++) {
                        if (edge == removed) {
                            continue;
                        }
                        boolean inA = (aMask & (1 << edge)) != 0;
                        var type = inA
                                ? (edge % 2 == 0 ? EdgeType.WR : EdgeType.PR_WR)
                                : (edge % 2 == 0 ? EdgeType.RW : EdgeType.PR_RW);
                        graph.putEdge(transactions.get(edge), transactions.get((edge + 1) % size),
                                new Edge<>(type, "cycle"));
                    }
                    // 删除简单环任意一条边后只剩有向路径，所有标签组合均合法。
                    boolean expected = removed >= 0 || adjacentB;
                    assertEquals(expected, SISolverTestSupport.solve(history, graph, List.of()),
                            "size=" + size + " A-mask=" + aMask + " removed=" + removed);
                }
            }
        }
    }

    @Test
    void reverseConditionalSupportsAreForbiddenWithoutNativeEdges() {
        for (boolean interning : List.of(false, true)) {
            var f = new Fixture();
            f.known(0, 1, EdgeType.WR);
            f.known(1, 2, EdgeType.WR);
            var solver = f.solver(List.of(f.choice(2, 0, 2, 1)),
                    new SIReachabilityOracle<>(f.graph), interning);
            assertFalse(solver.solve());
            if (interning) {
                assertEquals(2, Profiler.getInstance().getCount("SI_PROP_MONOSAT_GRAPH_EDGES_COUNT"));
            }
        }
    }

    @Test
    void fanInAndFanOutUseLinearEdgesInsteadOfCartesianProduct() {
        int width = 32;
        for (boolean interning : List.of(false, true)) {
            var history = new History<String, Integer>();
            var txns = new ArrayList<Transaction<String, Integer>>();
            for (long id = 1; id <= 2 * width + 1; id++) {
                var txn = history.addTransaction(history.addSession(id), id);
                txn.setStatus(Transaction.TransactionStatus.COMMIT);
                txns.add(txn);
            }
            var graph = new KnownGraph<>(history, false);
            for (int i = 0; i < width; i++) {
                graph.putEdge(txns.get(i), txns.get(width), new Edge<>(EdgeType.WR, "x"));
                graph.putEdge(txns.get(width), txns.get(width + 1 + i), new Edge<>(EdgeType.RW, "x"));
            }
            var settings = SIVerifier.SolverSettings.defaults();
            settings.graphEdgeInterning = interning;
            Profiler.getInstance().clear();
            var solver = SISolverTestSupport.create(history, graph, List.of(), false, true, settings);
            assertTrue(solver.solve());
            // 每条 A 对应原事务边和入口边，每条 B 对应出口边；不生成 width² 条组合边。
            assertEquals(3 * width, Profiler.getInstance().getCount("SI_PROP_MONOSAT_GRAPH_EDGES_COUNT"));
            assertEquals(2 * width + 2, Profiler.getInstance().getCount("SI_PROP_MONOSAT_GRAPH_NODES_COUNT"));
        }
    }

    private static class Fixture {
        final History<String, Integer> history = new History<>();
        final List<Transaction<String, Integer>> txns = new ArrayList<>();
        final KnownGraph<String, Integer> graph;

        Fixture() {
            for (long id = 1; id <= 4; id++) {
                var txn = history.addTransaction(history.addSession(id), id);
                txn.setStatus(Transaction.TransactionStatus.COMMIT);
                txns.add(txn);
            }
            graph = new KnownGraph<>(history, false);
        }

        void known(int from, int to, EdgeType type) {
            graph.putEdge(txns.get(from), txns.get(to), new Edge<>(type, "x"));
        }

        SIConstraint<String, Integer> choice(int a, int b, int c, int d) {
            return new SIConstraint<>(
                    List.of(new SIEdge<>(txns.get(a), txns.get(b), EdgeType.WW, "x")),
                    List.of(new SIEdge<>(txns.get(c), txns.get(d), EdgeType.WW, "x")),
                    txns.get(a), txns.get(b), 0);
        }

        SISolverInduced<String, Integer> solver(List<SIConstraint<String, Integer>> choices,
                SIReachabilityOracle<String, Integer> oracle, boolean interning) {
            var settings = SIVerifier.SolverSettings.defaults();
            settings.graphEdgeInterning = interning;
            Profiler.getInstance().clear();
            return SISolverTestSupport.create(
                    history, graph, choices, false, true, settings, oracle);
        }
    }
}
