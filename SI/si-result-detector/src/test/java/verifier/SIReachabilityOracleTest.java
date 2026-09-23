package verifier;

import graph.Edge;
import graph.EdgeType;
import graph.KnownGraph;
import history.History;
import history.Transaction;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SIReachabilityOracleTest {
    @Test
    void smallBranchesDoNotAllocateWholeGraphCopies() {
        var fixture = new Fixture(2000);
        var oracle = new SIReachabilityOracle<>(fixture.graph);
        var branch = List.of(fixture.edge(0, 1, true), fixture.edge(1, 2, false));
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assumeTrue(bean.isThreadAllocatedMemorySupported());
        bean.setThreadAllocatedMemoryEnabled(true);
        for (int i = 0; i < 100; i++) {
            assertFalse(oracle.hasConflict(branch));
        }
        long thread = Thread.currentThread().getId();
        long before = bean.getThreadAllocatedBytes(thread);
        for (int i = 0; i < 200; i++) {
            assertFalse(oracle.hasConflict(branch));
        }
        long allocated = bean.getThreadAllocatedBytes(thread) - before;
        assertTrue(allocated < 8L * 1024 * 1024,
                "200 small branch checks allocated " + allocated + " bytes");
    }

    @Test
    void rwChecksUseInducedPathsAndDirectAPredecessors() {
        var fixture = new Fixture(4);
        fixture.known(0, 1, true);
        fixture.known(1, 2, false);
        fixture.known(2, 3, true);
        var oracle = new SIReachabilityOracle<>(fixture.graph);
        assertTrue(oracle.reachesA(fixture.txns.get(0), fixture.txns.get(3)),
                "已知 I(0,2) 与 A(2,3) 必须推出 VIS(0,3)");
        assertFalse(oracle.reachesA(fixture.txns.get(0), fixture.txns.get(2)),
                "已知提交顺序本身不能推出快照可见性");
        assertTrue(oracle.hasConflict(List.of(fixture.edge(3, 0, false))));
        assertFalse(oracle.hasConflict(List.of()));
    }

    @Test
    void batchCommitFindsJointCyclesMissedByIndividualQueries() {
        // New A and new B are individually safe against this round's snapshot.
        var fixture = new Fixture(3);
        fixture.known(2, 0, true);
        var oracle = new SIReachabilityOracle<>(fixture.graph);
        var a = fixture.edge(0, 1, true);
        var b = fixture.edge(1, 2, false);
        assertFalse(oracle.hasConflict(List.of(a, b)));
        assertFalse(oracle.commitRound(List.of(a, b)));
        assertFalse(oracle.isAcyclic());

        // A-only additions can also cycle together through known paths.
        fixture = new Fixture(4);
        fixture.known(1, 2, true);
        fixture.known(3, 0, true);
        oracle = new SIReachabilityOracle<>(fixture.graph);
        var additions = List.of(fixture.edge(0, 1, true), fixture.edge(2, 3, true));
        assertFalse(oracle.hasConflict(additions));
        assertFalse(oracle.commitRound(additions));
    }

    @Test
    void bOnlyCyclesAndDerivedVisibilityKeepSiSemantics() {
        var fixture = new Fixture(3);
        fixture.known(0, 1, false);
        fixture.known(1, 0, false);
        var oracle = new SIReachabilityOracle<>(fixture.graph);
        assertFalse(oracle.hasConflict(List.of()));
        assertFalse(oracle.reachesA(fixture.txns.get(0), fixture.txns.get(1)));
        oracle.addVisibility(fixture.txns.get(2), fixture.txns.get(0));
        assertTrue(oracle.reachesA(fixture.txns.get(2), fixture.txns.get(0)));
        assertTrue(oracle.hasConflict(List.of(fixture.edge(0, 2, false))),
                "纯可见性支持必须参与 A;B 冲突检查");
        assertTrue(fixture.graph.getKnownGraphA().edges().isEmpty(),
                "纯可见性支持不能伪造成 typed 依赖");
    }

    @Test
    void committedBUpdatesKnownVisibilityWithoutBecomingVisibilityItself() {
        var fixture = new Fixture(4);
        fixture.known(0, 1, true);
        fixture.known(2, 3, true);
        var oracle = new SIReachabilityOracle<>(fixture.graph);
        assertFalse(oracle.reachesA(fixture.txns.get(0), fixture.txns.get(3)));

        assertTrue(oracle.commitRound(List.of(fixture.edge(1, 2, false))));

        assertTrue(oracle.reachesInduced(fixture.txns.get(0), fixture.txns.get(2)));
        assertTrue(oracle.reachesA(fixture.txns.get(0), fixture.txns.get(3)),
                "B 更新改变 I 后，必须刷新 I*;A 可见性");
        assertFalse(oracle.reachesA(fixture.txns.get(1), fixture.txns.get(2)));
        assertFalse(oracle.reachesA(fixture.txns.get(0), fixture.txns.get(2)));
    }

    @Test
    void visibilityFactsPersistAcrossLaterWwRounds() {
        var fixture = new Fixture(4);
        var oracle = new SIReachabilityOracle<>(fixture.graph);
        assertTrue(oracle.addVisibility(fixture.txns.get(0), fixture.txns.get(1)));
        assertTrue(oracle.commitRound(List.of(fixture.edge(1, 2, true))));

        assertTrue(oracle.reachesA(fixture.txns.get(0), fixture.txns.get(2)),
                "后续轮次刷新闭包不能丢弃已经确定的可见性支持");
        assertTrue(oracle.hasConflict(List.of(fixture.edge(2, 0, false))));
    }

    @Test
    void invisibilityFactsDetectPrefixConflictsWithoutAddingTypedEdges() {
        var fixture = new Fixture(4);
        fixture.known(0, 1, true);
        fixture.known(1, 2, false);
        fixture.known(2, 3, true);
        var oracle = new SIReachabilityOracle<>(fixture.graph);

        assertTrue(oracle.addInvisibility(fixture.txns.get(0), fixture.txns.get(3)));

        assertFalse(oracle.isAcyclic(),
                "I(0,2)、A(2,3) 与 NOT_VIS(0,3) 形成 induced 环");
        assertEquals(1, fixture.graph.getKnownGraphB().edges().size(),
                "纯不可见性支持不能伪造成 typed PR_RW");
        assertFalse(oracle.commitRound(List.of()), "后续空轮不能清除冲突");
    }

    @Test
    void invisibilityOnlyCycleDoesNotImplyReverseVisibilityOrReject() {
        var fixture = new Fixture(2);
        var oracle = new SIReachabilityOracle<>(fixture.graph);

        assertTrue(oracle.addInvisibility(fixture.txns.get(0), fixture.txns.get(1)));
        assertTrue(oracle.addInvisibility(fixture.txns.get(1), fixture.txns.get(0)));

        assertTrue(oracle.isAcyclic());
        assertFalse(oracle.reachesA(fixture.txns.get(0), fixture.txns.get(1)));
        assertFalse(oracle.reachesA(fixture.txns.get(1), fixture.txns.get(0)));
        assertFalse(oracle.reachesInduced(fixture.txns.get(0), fixture.txns.get(1)));
        assertTrue(fixture.graph.getKnownGraphA().edges().isEmpty());
        assertTrue(fixture.graph.getKnownGraphB().edges().isEmpty());
    }

    @Test
    void positiveVisibilityDetectsAnEarlierInvisibilityConflict() {
        var fixture = new Fixture(2);
        var oracle = new SIReachabilityOracle<>(fixture.graph);
        assertTrue(oracle.addInvisibility(fixture.txns.get(0), fixture.txns.get(1)));
        assertTrue(oracle.isAcyclic());

        assertTrue(oracle.addVisibility(fixture.txns.get(0), fixture.txns.get(1)));

        assertFalse(oracle.isAcyclic());
        assertFalse(oracle.addVisibility(fixture.txns.get(0), fixture.txns.get(1)));
    }

    @Test
    void bottomAndSelfVisibilityFactsRespectExternalSnapshotBoundary() {
        var fixture = new Fixture(2);
        var initial = new History<String, Integer>();
        var bottom = initial.addTransaction(initial.addSession(-1L), -1L);
        var reader = fixture.txns.get(0);
        var oracle = new SIReachabilityOracle<>(fixture.graph);
        assertFalse(oracle.addVisibility(bottom, reader));
        assertFalse(oracle.addInvisibility(reader, bottom));
        assertFalse(oracle.addInvisibility(reader, reader));
        assertTrue(oracle.isAcyclic());

        assertTrue(oracle.addInvisibility(bottom, reader));
        assertFalse(oracle.isAcyclic());
        assertFalse(oracle.commitRound(List.of(fixture.edge(0, 1, true))),
                "新增支持不能清除 bottom 不可见这一持久矛盾");

        oracle = new SIReachabilityOracle<>(fixture.graph);
        assertTrue(oracle.addVisibility(reader, bottom));
        assertFalse(oracle.isAcyclic());

        oracle = new SIReachabilityOracle<>(fixture.graph);
        assertTrue(oracle.addVisibility(reader, reader));
        assertFalse(oracle.isAcyclic());
    }

    @Test
    void pruningNeverDiscardsAnAcyclicBranchAndCommitsMatchFullGraphOracle() {
        var random = new Random(0x51C105E);
        int size = 6;
        for (int testCase = 0; testCase < 100; testCase++) {
            var fixture = new Fixture(size);
            var a = new boolean[size][size];
            var b = new boolean[size][size];
            for (int from = 0; from < size; from++) {
                for (int to = 0; to < size; to++) {
                    if (from == to || random.nextInt(8) != 0) {
                        continue;
                    }
                    boolean inA = random.nextBoolean();
                    fixture.known(from, to, inA);
                    (inA ? a : b)[from][to] = true;
                }
            }
            var oracle = new SIReachabilityOracle<>(fixture.graph);
            for (int trial = 0; trial < 20; trial++) {
                var nextA = copy(a);
                var nextB = copy(b);
                var edges = new ArrayList<SIEdge<String, Integer>>();
                for (int i = 0, count = 1 + random.nextInt(4); i < count; i++) {
                    int from = random.nextInt(size);
                    int to = (from + 1 + random.nextInt(size - 1)) % size;
                    boolean inA = random.nextBoolean();
                    edges.add(fixture.edge(from, to, inA));
                    (inA ? nextA : nextB)[from][to] = true;
                }
                boolean expected = acyclic(nextA, nextB);
                if (oracle.hasConflict(edges)) {
                    assertFalse(expected, "unsafe pruning case=" + testCase + " trial=" + trial);
                }
                assertEquals(acyclic(a, b), oracle.isAcyclic(),
                        "trial changed committed state");
                if (expected && random.nextBoolean()) {
                    assertTrue(oracle.commitRound(edges));
                    a = nextA;
                    b = nextB;
                }
            }
        }
    }

    @Test
    void incrementalUpdatesMatchRebuiltClosureAndNotifyTransitiveEndpoints() {
        var fixture = new Fixture(72);
        var oracle = new SIReachabilityOracle<>(fixture.graph);
        var random = new Random(73021);
        for (int step = 0; step < 180; step++) {
            int from = random.nextInt(71);
            int to = from + 1 + random.nextInt(71 - from);
            boolean inA = random.nextBoolean();
            var beforeI = new boolean[72][72];
            var beforeV = new boolean[72][72];
            for (int i = 0; i < 72; i++) {
                for (int j = 0; j < 72; j++) {
                    beforeI[i][j] = oracle.reachesInduced(fixture.txns.get(i), fixture.txns.get(j));
                    beforeV[i][j] = oracle.reachesA(fixture.txns.get(i), fixture.txns.get(j));
                }
            }
            var notified = new java.util.HashSet<Transaction<String, Integer>>();
            java.util.function.Consumer<Transaction<String, Integer>> callback = txn -> {
                assertTrue(notified.add(txn), "同一更新重复通知端点");
            };
            if (inA) {
                oracle.addVisibility(fixture.txns.get(from), fixture.txns.get(to), callback);
            } else {
                oracle.addInvisibility(fixture.txns.get(to), fixture.txns.get(from), callback);
            }
            fixture.known(from, to, inA);
            var rebuilt = new SIReachabilityOracle<>(fixture.graph);
            assertTrue(oracle.isAcyclic());
            for (int i = 0; i < 72; i++) {
                for (int j = 0; j < 72; j++) {
                    var left = fixture.txns.get(i);
                    var right = fixture.txns.get(j);
                    boolean induced = oracle.reachesInduced(left, right);
                    boolean visible = oracle.reachesA(left, right);
                    assertEquals(rebuilt.reachesInduced(left, right), induced);
                    assertEquals(rebuilt.reachesA(left, right), visible);
                    if (beforeI[i][j] != induced || beforeV[i][j] != visible) {
                        assertTrue(notified.contains(left) && notified.contains(right),
                                "漏报传递影响端点");
                    }
                }
            }
        }
    }

    @Test
    void wwBranchIncludesCompositionWithExistingBWithoutChangingOracle() {
        var fixture = new Fixture(3);
        fixture.known(2, 0, true);
        fixture.known(1, 2, false);
        var oracle = new SIReachabilityOracle<>(fixture.graph);
        var branch = List.of(fixture.edge(0, 1, true));
        assertFalse(oracle.hasConflict(branch));
        assertTrue(oracle.hasWwBranchConflict(branch));
        assertTrue(oracle.isAcyclic());
        assertFalse(oracle.reachesInduced(fixture.txns.get(0), fixture.txns.get(2)));
    }

    @Test
    void generatedWwBranchMatchesIndependentInducedCycleOracle() {
        var random = new Random(72941);
        int checked = 0;
        int extraConflicts = 0;
        for (int sample = 0; sample < 600; sample++) {
            int size = 6;
            var fixture = new Fixture(size);
            var a = new boolean[size][size];
            var b = new boolean[size][size];
            for (int from = 0; from < size; from++) {
                for (int to = 0; to < size; to++) {
                    if (from == to) {
                        continue;
                    }
                    if (random.nextInt(15) == 0) {
                        a[from][to] = true;
                        fixture.known(from, to, true);
                    }
                    if (random.nextInt(12) == 0) {
                        b[from][to] = true;
                        fixture.known(from, to, false);
                    }
                }
            }
            if (!acyclic(a, b)) {
                continue;
            }
            var oracle = new SIReachabilityOracle<>(fixture.graph);
            for (int writer = 0; writer < size; writer++) {
                for (int later = 0; later < size; later++) {
                    if (writer == later) {
                        continue;
                    }
                    var branch = new ArrayList<SIEdge<String, Integer>>();
                    branch.add(fixture.edge(writer, later, true));
                    var assumedA = copy(a);
                    var assumedB = copy(b);
                    assumedA[writer][later] = true;
                    for (int reader = 0; reader < size; reader++) {
                        if (reader != later && random.nextBoolean()) {
                            branch.add(fixture.edge(reader, later, false));
                            assumedB[reader][later] = true;
                        }
                    }
                    boolean conflict = oracle.hasWwBranchConflict(branch);
                    assertEquals(!acyclic(assumedA, assumedB), conflict,
                            "sample=" + sample + " writer=" + writer + " later=" + later);
                    if (conflict && !oracle.hasConflict(branch)) {
                        extraConflicts++;
                    }
                    assertTrue(oracle.isAcyclic());
                    checked++;
                }
            }
        }
        assertTrue(checked > 5000);
        assertTrue(extraConflicts > 0);
    }

    private static boolean[][] copy(boolean[][] source) {
        var result = new boolean[source.length][];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i].clone();
        }
        return result;
    }

    private static boolean acyclic(boolean[][] a, boolean[][] b) {
        var reach = copy(a);
        for (int from = 0; from < a.length; from++) {
            for (int via = 0; via < a.length; via++) {
                for (int to = 0; to < a.length; to++) {
                    reach[from][to] |= a[from][via] && b[via][to];
                }
            }
        }
        for (int via = 0; via < a.length; via++) {
            for (int from = 0; from < a.length; from++) {
                for (int to = 0; to < a.length; to++) {
                    reach[from][to] |= reach[from][via] && reach[via][to];
                }
            }
        }
        for (int i = 0; i < a.length; i++) {
            if (reach[i][i]) {
                return false;
            }
        }
        return true;
    }

    private static final class Fixture {
        final List<Transaction<String, Integer>> txns = new ArrayList<>();
        final KnownGraph<String, Integer> graph;

        Fixture(int size) {
            var history = new History<String, Integer>();
            for (int i = 0; i < size; i++) {
                var txn = history.addTransaction(history.addSession(i + 1L), i + 1L);
                txn.setStatus(Transaction.TransactionStatus.COMMIT);
                txns.add(txn);
            }
            graph = new KnownGraph<>(history, false);
        }

        void known(int from, int to, boolean inA) {
            graph.putEdge(txns.get(from), txns.get(to),
                    new Edge<>(inA ? EdgeType.PR_WR : EdgeType.PR_RW, "known"));
        }

        SIEdge<String, Integer> edge(int from, int to, boolean inA) {
            return new SIEdge<>(txns.get(from), txns.get(to),
                    inA ? EdgeType.WW : EdgeType.RW, "candidate");
        }
    }
}
