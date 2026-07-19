package verifier;

import graph.Edge;
import graph.EdgeType;
import graph.KnownGraph;
import history.Event;
import history.History;
import history.HistoryLoader;
import history.Transaction;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.*;

import static history.Event.EventType.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 小型集成测试：验证 predicate 边推导的组合行为。
 *
 * 测试目标：
 * 1. buildConfirmedWriteOrder — 基于确认边建立 key 的写全序
 * 2. resolveObservationIndex   — 确定 predicate read 的观察点
 * 3. refreshDerivedPredicateEdges — PR_WR / PR_RW 边生成
 *
 * 全部通过 SERVerifier 的 package-private static 方法直接调用。
 */
public class SERVerifierPredicateIntegrationTest {

    // ================================================================
    // 辅助：最小 HistoryLoader（含 PREDICATE_READ）
    // ================================================================

    /** 构造含有 PREDICATE_READ 事件的 HistoryLoader (测试中实际用 buildHistoryWithPredicateRead) */
    static class PredicateTestLoader implements HistoryLoader<String, Integer> {
        final Set<Long> sessions;
        final Map<Long, List<Long>> transactions;

        @FunctionalInterface
        interface PredicateEvaluator {
            boolean test(String key, Integer value);
        }

        PredicateTestLoader(Set<Long> sessions, Map<Long, List<Long>> transactions) {
            this.sessions = sessions;
            this.transactions = transactions;
        }

        @Override
        public History<String, Integer> loadHistory() {
            return buildHistoryWithPredicateRead(sessions, transactions,
                    new HashMap<>(), new HashMap<>());
        }
    }

    // 手动构建含 PREDICATE_READ 的历史记录
    private static History<String, Integer> buildHistoryWithPredicateRead(
            Set<Long> sessions,
            Map<Long, List<Long>> sessionToTxns,
            // txnId -> list of (type, key, value) for normal events
            Map<Long, List<Triple<Event.EventType, String, Integer>>> normalEvents,
            // txnId -> (predicateEval, list of (key, value) results)
            Map<Long, Pair<Event.PredEval<String, Integer>, List<Event.PredResult<String, Integer>>>> predicateReads) {

        var h = new History<>(sessions, sessionToTxns, normalEvents);

        for (var entry : predicateReads.entrySet()) {
            long txnId = entry.getKey();
            var txn = h.getTransaction(txnId);
            assertNotNull(txn, "Transaction " + txnId + " not found");
            var pred = entry.getValue().getLeft();
            var results = entry.getValue().getRight();
            h.addPredicateReadEvent(txn, pred, results);
        }

        return h;
    }

    private static KnownGraph<String, Integer> makeGraph(History<String, Integer> h) {
        return new KnownGraph<>(h);
    }

    /** Add WW constraint edges to graph (simulates pruning-confirmed WW choices). */
    private static void addWWConstraint(KnownGraph<String, Integer> graph,
            Transaction<String, Integer> from, Transaction<String, Integer> to, String key) {
        graph.putEdge(from, to, new Edge<>(EdgeType.WW, key));
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>... paramTypes) {
        try {
            var m = cls.getDeclaredMethod(name, paramTypes);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            throw new AssertionError("Method not found: " + name, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <R> R invokeStatic(Method m, Object... args) {
        try {
            return (R) m.invoke(null, args);
        } catch (Exception e) {
            throw new AssertionError("Reflection invoke failed for " + m.getName(), e);
        }
    }

    // ================================================================
    // Test 1: buildConfirmedWriteOrder — 单事务内部顺序
    // ================================================================

    @Test
    void buildConfirmedWriteOrder_singleTransaction_preservesProgramOrder() throws Exception {
        // 同一事务内的两次写: W(x,1) idx=0, W(x,2) idx=1
        // 无需跨事务边，内部按 event index 排序
        var h = buildHistoryWithPredicateRead(
                Set.of(0L),
                Map.of(0L, List.of(0L)),
                Map.of(0L, List.of(
                        Triple.of(WRITE, "x", 1),
                        Triple.of(WRITE, "x", 2)
                )),
                Map.of()
        );
        var graph = makeGraph(h);

        List<KnownGraph.WriteRef<String, Integer>> writesOnKey = new ArrayList<>();
        graph.getWrites().forEach((kv, wr) -> {
            if ("x".equals(kv.getLeft())) writesOnKey.add(wr);
        });
        writesOnKey.sort(Comparator.comparingInt(KnownGraph.WriteRef::getIndex));

        Method m = findMethod(SERVerifier.class, "buildConfirmedWriteOrder",
                Object.class, List.class, KnownGraph.class);
        List<KnownGraph.WriteRef<String, Integer>> result =
                invokeStatic(m, "x", writesOnKey, graph);

        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals(1, result.get(0).getEvent().getValue());
        assertEquals(2, result.get(1).getEvent().getValue());
    }

    @Test
    void buildConfirmedWriteOrder_twoTransactionsWithSO() throws Exception {
        // S0: T0 -> T1, 都写 x
        // SO 边建立了顺序: T0 before T1
        var h = buildHistoryWithPredicateRead(
                Set.of(0L),
                Map.of(0L, List.of(0L, 1L)),  // session 0: T0, T1
                Map.of(0L, List.of(Triple.of(WRITE, "x", 1)),
                        1L, List.of(Triple.of(WRITE, "x", 2))),
                Map.of()
        );
        var graph = makeGraph(h);

        List<KnownGraph.WriteRef<String, Integer>> writesOnKey = new ArrayList<>();
        graph.getWrites().forEach((kv, wr) -> {
            if ("x".equals(kv.getLeft())) writesOnKey.add(wr);
        });

        Method m = findMethod(SERVerifier.class, "buildConfirmedWriteOrder",
                Object.class, List.class, KnownGraph.class);
        List<KnownGraph.WriteRef<String, Integer>> result =
                invokeStatic(m, "x", writesOnKey, graph);

        assertNotNull(result, "SO edge should enable unique ordering");
        assertEquals(2, result.size());
        assertEquals(0L, result.get(0).getTxn().getId());
        assertEquals(1L, result.get(1).getTxn().getId());
    }

    @Test
    void buildConfirmedWriteOrder_twoTransactionsWithWR() throws Exception {
        // T0: W(x,1); T1: R(x,1) W(x,2)
        // WR 边: T0 -> T1, 建立了 x 的写顺序: T0 before T1
        var h = buildHistoryWithPredicateRead(
                Set.of(0L),
                Map.of(0L, List.of(0L, 1L)),
                Map.of(0L, List.of(Triple.of(WRITE, "x", 1)),
                        1L, List.of(Triple.of(READ, "x", 1),
                                Triple.of(WRITE, "x", 2))),
                Map.of()
        );
        var graph = makeGraph(h);

        List<KnownGraph.WriteRef<String, Integer>> writesOnKey = new ArrayList<>();
        graph.getWrites().forEach((kv, wr) -> {
            if ("x".equals(kv.getLeft())) writesOnKey.add(wr);
        });

        Method m = findMethod(SERVerifier.class, "buildConfirmedWriteOrder",
                Object.class, List.class, KnownGraph.class);
        List<KnownGraph.WriteRef<String, Integer>> result =
                invokeStatic(m, "x", writesOnKey, graph);

        assertNotNull(result, "WR edge should establish T0 before T1 ordering");
        assertEquals(2, result.size());
        assertEquals(0L, result.get(0).getTxn().getId());
        assertEquals(1L, result.get(1).getTxn().getId());
    }

    @Test
    void buildConfirmedWriteOrder_incomparableTransactions_returnsNull() throws Exception {
        // T0: W(x,1); T1: W(x,2)  (不同 session，无 WR，无 SO)
        // 两者不可比 -> 返回 null（保守策略）
        var h = buildHistoryWithPredicateRead(
                Set.of(0L, 1L),
                Map.of(0L, List.of(0L), 1L, List.of(1L)),
                Map.of(0L, List.of(Triple.of(WRITE, "x", 1)),
                        1L, List.of(Triple.of(WRITE, "x", 2))),
                Map.of()
        );
        var graph = makeGraph(h);

        List<KnownGraph.WriteRef<String, Integer>> writesOnKey = new ArrayList<>();
        graph.getWrites().forEach((kv, wr) -> {
            if ("x".equals(kv.getLeft())) writesOnKey.add(wr);
        });

        Method m = findMethod(SERVerifier.class, "buildConfirmedWriteOrder",
                Object.class, List.class, KnownGraph.class);
        var result = invokeStatic(m, "x", writesOnKey, graph);

        assertNull(result, "Incomparable transactions (no SO, no WR) should return null");
    }

    @Test
    void buildConfirmedWriteOrder_threeTxnsInSameSession() throws Exception {
        // T0, T1, T2 在同一 session 0 -> SO: T0->T1->T2
        // 3个事务都写 x
        var h = buildHistoryWithPredicateRead(
                Set.of(0L),
                Map.of(0L, List.of(0L, 1L, 2L)),
                Map.of(0L, List.of(Triple.of(WRITE, "x", 1)),
                        1L, List.of(Triple.of(WRITE, "x", 2)),
                        2L, List.of(Triple.of(WRITE, "x", 3))),
                Map.of()
        );
        var graph = makeGraph(h);

        List<KnownGraph.WriteRef<String, Integer>> writesOnKey = new ArrayList<>();
        graph.getWrites().forEach((kv, wr) -> {
            if ("x".equals(kv.getLeft())) writesOnKey.add(wr);
        });

        Method m = findMethod(SERVerifier.class, "buildConfirmedWriteOrder",
                Object.class, List.class, KnownGraph.class);
        List<KnownGraph.WriteRef<String, Integer>> result =
                invokeStatic(m, "x", writesOnKey, graph);

        assertNotNull(result,
                "3 txns in same session with SO edges should give unique order, "
                + "but result=null. writesOnKey txn ids: "
                + writesOnKey.stream().map(wr -> wr.getTxn().getId()).collect(java.util.stream.Collectors.toList()));
        assertEquals(3, result.size());
        assertEquals(0L, result.get(0).getTxn().getId());
        assertEquals(1L, result.get(1).getTxn().getId());
        assertEquals(2L, result.get(2).getTxn().getId());
    }

    // ================================================================
    // Test 2: resolveObservationIndex
    // ================================================================

    @Test
    void resolveObservationIndex_keyInResult_usesResultSource() throws Exception {
        // T0: W(x,1); T1: W(x,10); T2: PRED_READ(P={v>5}, results=[(x,1)])
        // orderedWrites = [W(x,1), W(x,10)] (T0, T1)
        // key x 在 predicate 结果中，source 是 W(x,1) (T0)
        // expected index = 0
        var predResults = List.of(new Event.PredResult<>("x", 1));

        var h = buildHistoryWithPredicateRead(
                Set.of(0L),
                Map.of(0L, List.of(0L, 1L, 2L)),
                Map.of(0L, List.of(Triple.of(WRITE, "x", 1)),
                        1L, List.of(Triple.of(WRITE, "x", 10))),
                Map.of(2L, Pair.of(
                        (Event.PredEval<String, Integer>) (k, v) -> v > 5,
                        predResults))
        );
        var graph = makeGraph(h);

        var prEvent = h.getTransaction(2L).getEvents().stream()
                .filter(e -> e.getType() == PREDICATE_READ)
                .findFirst().orElseThrow();

        List<KnownGraph.WriteRef<String, Integer>> orderedWrites = new ArrayList<>();
        graph.getWrites().forEach((kv, wr) -> {
            if ("x".equals(kv.getLeft())) orderedWrites.add(wr);
        });
        orderedWrites.sort(Comparator.comparingInt(wr -> {
            int txnOrder = (int) wr.getTxn().getId();
            return txnOrder * 100 + wr.getIndex();
        }));

        Map<String, KnownGraph.WriteRef<String, Integer>> resultSourceByKey = new HashMap<>();
        for (var ts : graph.getPredicateObservations().get(0).getTupleSources()) {
            resultSourceByKey.put(ts.getKey(), ts.getSourceWrite());
        }

        Method m = findMethod(SERVerifier.class, "resolveObservationIndex",
                Object.class, Transaction.class, Event.class, List.class, Map.class, KnownGraph.class);
        int result = invokeStatic(m, "x", h.getTransaction(2L), prEvent, orderedWrites, resultSourceByKey, graph);

        assertEquals(0, result, "Key in predicate result should return source write's index");
    }

    @Test
    void resolveObservationIndex_keyNotInResultNoBWrite_usesLatestPredecessor() throws Exception {
        // T0: W(x,1); T1: W(x,10); T2: PRED_READ(P={v>5}, results=[])
        // T0, T1, T2 分别在不同 session，无 SO 边连接它们
        // orderedWrites = [W(x,1), W(x,10)] (T0, T1)
        // key x 不在结果中，T2 不写 x，无 SO 边 -> OBS_UNDETERMINED (-2)
        var h = buildHistoryWithPredicateRead(
                Set.of(0L, 1L, 2L),
                Map.of(0L, List.of(0L), 1L, List.of(1L), 2L, List.of(2L)),
                Map.of(0L, List.of(Triple.of(WRITE, "x", 1)),
                        1L, List.of(Triple.of(WRITE, "x", 10))),
                Map.of(2L, Pair.of(
                        (Event.PredEval<String, Integer>) (k, v) -> v > 5,
                        List.of()))  // 空结果
        );
        var graph = makeGraph(h);

        var prEvent = h.getTransaction(2L).getEvents().stream()
                .filter(e -> e.getType() == PREDICATE_READ)
                .findFirst().orElseThrow();

        List<KnownGraph.WriteRef<String, Integer>> orderedWrites = new ArrayList<>();
        graph.getWrites().forEach((kv, wr) -> {
            if ("x".equals(kv.getLeft())) orderedWrites.add(wr);
        });
        orderedWrites.sort(Comparator.comparingInt(wr ->
                (int) wr.getTxn().getId() * 100 + wr.getIndex()));

        Map<String, KnownGraph.WriteRef<String, Integer>> resultSourceByKey = new HashMap<>();

        Method m = findMethod(SERVerifier.class, "resolveObservationIndex",
                Object.class, Transaction.class, Event.class, List.class, Map.class, KnownGraph.class);
        int result = invokeStatic(m, "x", h.getTransaction(2L), prEvent, orderedWrites, resultSourceByKey, graph);

        assertEquals(-2, result, "No result, no B-write, no predecessor edge -> OBS_UNDETERMINED");
    }

    @Test
    void resolveObservationIndex_bWritesBeforePredicateRead() throws Exception {
        // T0: W(x,1); T1: W(x,5), PRED_READ(P={v>3}, results=[(x,5)])
        // orderedWrites = [W(x,1), W(x,5)] (T0, T1)
        // key x 在结果中，source 是 W(x,5) -> index=1
        var predResults = List.of(new Event.PredResult<>("x", 5));

        var h = buildHistoryWithPredicateRead(
                Set.of(0L),
                Map.of(0L, List.of(0L, 1L)),
                Map.of(0L, List.of(Triple.of(WRITE, "x", 1)),
                        1L, List.of(Triple.of(WRITE, "x", 5))),
                Map.of(1L, Pair.of(
                        (Event.PredEval<String, Integer>) (k, v) -> v > 3,
                        predResults))
        );
        var graph = makeGraph(h);

        var prEvent = h.getTransaction(1L).getEvents().stream()
                .filter(e -> e.getType() == PREDICATE_READ)
                .findFirst().orElseThrow();

        List<KnownGraph.WriteRef<String, Integer>> orderedWrites = new ArrayList<>();
        graph.getWrites().forEach((kv, wr) -> {
            if ("x".equals(kv.getLeft())) orderedWrites.add(wr);
        });
        orderedWrites.sort(Comparator.comparingInt(wr ->
                (int) wr.getTxn().getId() * 100 + wr.getIndex()));

        Map<String, KnownGraph.WriteRef<String, Integer>> resultSourceByKey = new HashMap<>();
        if (!graph.getPredicateObservations().isEmpty()) {
            for (var ts : graph.getPredicateObservations().get(0).getTupleSources()) {
                resultSourceByKey.put(ts.getKey(), ts.getSourceWrite());
            }
        }

        Method m = findMethod(SERVerifier.class, "resolveObservationIndex",
                Object.class, Transaction.class, Event.class, List.class, Map.class, KnownGraph.class);
        int result = invokeStatic(m, "x", h.getTransaction(1L), prEvent, orderedWrites, resultSourceByKey, graph);

        assertEquals(1, result, "Source write of predicate result W(x,5) should be at index 1");
    }

    @Test
    void resolveObservationIndex_multipleEqualPredicateReadsUsesObservationEventIndex() throws Exception {
        var h = new History<String, Integer>();
        var session = h.addSession(0L);
        var txn = h.addTransaction(session, 1L);
        var predicate = (Event.PredEval<String, Integer>) (k, v) -> "y".equals(k) && v >= 100;
        h.addPredicateReadEvent(txn, predicate, List.of());
        h.addEvent(txn, WRITE, "y", 120);
        h.addPredicateReadEvent(txn, predicate, List.of());

        var graph = makeGraph(h);
        var predicateReads = txn.getEvents().stream()
                .filter(e -> e.getType() == PREDICATE_READ)
                .collect(java.util.stream.Collectors.toList());
        assertEquals(predicateReads.get(0), predicateReads.get(1),
                "regression setup requires Event.equals to conflate the two predicate reads");

        List<KnownGraph.WriteRef<String, Integer>> orderedWrites = new ArrayList<>();
        graph.getWrites().forEach((kv, wr) -> {
            if ("y".equals(kv.getLeft())) orderedWrites.add(wr);
        });
        orderedWrites.sort(Comparator.comparingInt(KnownGraph.WriteRef::getIndex));

        Method m = findMethod(SERVerifier.class, "resolveObservationIndex",
                Object.class, Transaction.class, Event.class, List.class, Map.class, KnownGraph.class);
        int result = invokeStatic(m, "y", txn, predicateReads.get(1),
                orderedWrites, Map.of(), graph);

        assertEquals(0, result,
                "second predicate read must resolve after the intervening self-write, not at the first equal PR");
    }

    // ================================================================
    // Test 3: refreshDerivedPredicateEdges — PR_WR / PR_RW 生成
    // ================================================================

    @Test
    void refreshDerivedPredicateEdges_prWr_frontierFromOtherTxn() throws Exception {
        // T0: W(x,1) [不满足 v>5]; T1: W(x,10) [满足 v>5]; T2: PRED_READ(P={v>5}, results=[(x,10)])
        // orderedWrites = [W(x,1), W(x,10)] (T0, T1)
        // obsIdx = 1 (W(x,10) is source)
        // W(x,10) is the latest visible frontier for T2 on key x.
        // PR_WR(T1, T2) should be created (T1 is the frontier writer, T2 is reader).
        var predResults = List.of(new Event.PredResult<>("x", 10));

        var h = buildHistoryWithPredicateRead(
                Set.of(0L),
                Map.of(0L, List.of(0L, 1L, 2L)),
                Map.of(0L, List.of(Triple.of(WRITE, "x", 1)),
                        1L, List.of(Triple.of(WRITE, "x", 10))),
                Map.of(2L, Pair.of(
                        (Event.PredEval<String, Integer>) (k, v) -> v > 5,
                        predResults))
        );
        var graph = makeGraph(h);
        assertFalse(graph.getPredicateObservations().isEmpty(), "Should have predicate observation");

        SERVerifier.refreshDerivedPredicateEdges(h, graph);

        // 检查 PR_WR 边: T1 -> T2 (frontier writer txn -> reader txn)
        var prWrEdges = graph.getKnownGraphA().successors(h.getTransaction(1L));
        assertTrue(prWrEdges.contains(h.getTransaction(2L)),
                "PR_WR edge should exist from T1 (frontier writer) to T2 (reader)");

        // 验证边类型
        var edgeValues = graph.getKnownGraphA().edgeValue(h.getTransaction(1L), h.getTransaction(2L));
        assertTrue(edgeValues.isPresent());
        boolean hasPrWr = edgeValues.get().stream()
                .anyMatch(e -> e.getType() == EdgeType.PR_WR);
        assertTrue(hasPrWr, "Edge type should be PR_WR");
    }

    @Test
    void refreshDerivedPredicateEdges_prWr_emptyResultUsesLatestNonMatchingWriter() throws Exception {
        // T0: W(x,1) [不满足 v>5]; T1: W(x,2) [不满足 v>5]; T2: PRED_READ(P={v>5}, results=[])
        // 所有写都不满足谓词且结果为空；仍应由最新可见 writer T1 解释 x 未返回。
        var predResults = List.<Event.PredResult<String, Integer>>of();  // 空结果

        var h = buildHistoryWithPredicateRead(
                Set.of(0L),
                Map.of(0L, List.of(0L, 1L, 2L)),
                Map.of(0L, List.of(Triple.of(WRITE, "x", 1)),
                        1L, List.of(Triple.of(WRITE, "x", 2))),
                Map.of(2L, Pair.of(
                        (Event.PredEval<String, Integer>) (k, v) -> v > 5,
                        predResults))
        );
        var graph = makeGraph(h);
        assertEquals(KnownGraph.PredicateReadType.EXTERNAL,
                graph.getPredicateObservations().get(0).getPredicateReadType("x"));

        SERVerifier.refreshDerivedPredicateEdges(h, graph);

        boolean latestWriterHasPrWr = graph.getKnownGraphA()
                .edgeValue(h.getTransaction(1L), h.getTransaction(2L))
                .orElse(List.of()).stream()
                .anyMatch(e -> e.getType() == EdgeType.PR_WR && "x".equals(e.getKey()));
        boolean olderWriterHasPrWr = graph.getKnownGraphA()
                .edgeValue(h.getTransaction(0L), h.getTransaction(2L))
                .orElse(List.of()).stream()
                .anyMatch(e -> e.getType() == EdgeType.PR_WR && "x".equals(e.getKey()));

        assertTrue(latestWriterHasPrWr,
                "latest visible non-matching writer should produce PR_WR for an omitted key");
        assertFalse(olderWriterHasPrWr,
                "only the latest visible writer should produce PR_WR for the key");
    }

    @Test
    void refreshDerivedPredicateEdges_internalReadIsDeferred() throws Exception {
        // T1 在谓词读前已写 x，因此该 key 是 INTERNAL，不进入当前 PR_WR/PR_RW 推导链。
        //
        // 场景设计:
        //   T0: W(x,10) [满足 v>5]
        //   T1: R(x,10), W(x,20) [满足], PRED_READ(results=[(x,20)])
        //   T2: R(x,20), W(x,3) [不满足 v>5]
        //
        // orderedWrites for x: [W(T0,x,10), W(T1,x,20), W(T2,x,3)]
        // T1's latest visible frontier is its own write x=20, so no cross-txn
        // PR_WR edge is emitted. PR_RW(T1→T2) is emitted because T1 WW(x)→T2
        // and Δ(x=20,x=3) holds.
        var h = buildHistoryWithPredicateRead(
                Set.of(0L),
                Map.of(0L, List.of(0L, 1L, 2L)),
                Map.of(0L, List.of(Triple.of(WRITE, "x", 10)),
                        1L, List.of(Triple.of(READ, "x", 10),
                                Triple.of(WRITE, "x", 20),
                                Triple.of(PREDICATE_READ, "x", 20)),
                        2L, List.of(Triple.of(READ, "x", 20),
                                Triple.of(WRITE, "x", 3))),
                Map.of(1L, Pair.of(
                        (Event.PredEval<String, Integer>) (k, v) -> v > 5,
                        List.of(new Event.PredResult<>("x", 20))))
        );
        var graph = makeGraph(h);

        // 注入 WW 约束边以建立 orderedWrites
        addWWConstraint(graph, h.getTransaction(0L), h.getTransaction(1L), "x");
        addWWConstraint(graph, h.getTransaction(1L), h.getTransaction(2L), "x");

        assertFalse(graph.getPredicateObservations().isEmpty(), "Predicate observation must be registered");
        assertEquals(1L, graph.getPredicateObservations().get(0).getTxn().getId(),
                "Predicate observation should be from T1");
        assertEquals(KnownGraph.PredicateReadType.INTERNAL,
                graph.getPredicateObservations().get(0).getPredicateReadType("x"));

        SERVerifier.refreshDerivedPredicateEdges(h, graph);

        var edgeValues = graph.getKnownGraphA().edgeValue(h.getTransaction(0L), h.getTransaction(1L));
        boolean hasPrWr = edgeValues.orElse(List.of()).stream()
                .anyMatch(e -> e.getType() == EdgeType.PR_WR);
        assertFalse(hasPrWr, "internal predicate reads must not emit PR_WR in the external path");
        assertFalse(graph.getKnownGraphB().hasEdgeConnecting(h.getTransaction(1L), h.getTransaction(2L)),
                "internal predicate reads must not emit PR_RW in the external path");
    }

    @Test
    void refreshDerivedPredicateEdges_prRw_unconfirmedWriteOrder_noPrRwEdge() throws Exception {
        // T0: W(x,10); T1: PRED_READ(P={v>5}, results=[(x,10)]); T2: W(x,20)
        // T0/T2 没有确认的 WW 写顺序；derived refresh 层无法解析唯一 frontier，因此不建立 PR_RW。
        var predResults = List.of(new Event.PredResult<>("x", 10));

        var h = buildHistoryWithPredicateRead(
                Set.of(0L),
                Map.of(0L, List.of(0L, 1L, 2L)),
                Map.of(0L, List.of(Triple.of(WRITE, "x", 10)),
                        2L, List.of(Triple.of(WRITE, "x", 20))),
                Map.of(1L, Pair.of(
                        (Event.PredEval<String, Integer>) (k, v) -> v > 5,
                        predResults))
        );
        var graph = makeGraph(h);

        SERVerifier.refreshDerivedPredicateEdges(h, graph);

        // 不应有 PR_RW 边
        boolean hasPrRw = graph.getKnownGraphB().edges().stream().anyMatch(ep -> {
            var opt = graph.getKnownGraphB().edgeValue(ep.source(), ep.target());
            return opt.isPresent() && opt.get().stream().anyMatch(e -> e.getType() == EdgeType.PR_RW);
        });
        assertFalse(hasPrRw, "Unconfirmed write order should produce no derived PR_RW edge");
    }

    @Test
    void refreshDerivedPredicateEdges_clearDerived_removesOldEdges() throws Exception {
        // 第一次构建 PR_WR，第二次重建时应该清空旧边
        var predResults = List.of(new Event.PredResult<>("x", 10));

        var h = buildHistoryWithPredicateRead(
                Set.of(0L),
                Map.of(0L, List.of(0L, 1L, 2L)),
                Map.of(0L, List.of(Triple.of(WRITE, "x", 1)),
                        1L, List.of(Triple.of(WRITE, "x", 10))),
                Map.of(2L, Pair.of(
                        (Event.PredEval<String, Integer>) (k, v) -> v > 5,
                        predResults))
        );
        var graph = makeGraph(h);

        // 第一次构建
        SERVerifier.refreshDerivedPredicateEdges(h, graph);

        // 确认有 PR_WR 边
        boolean hasPrWrBefore = graph.getKnownGraphA().edges().stream()
                .anyMatch(ep -> {
                    var opt = graph.getKnownGraphA().edgeValue(ep.source(), ep.target());
                    return opt.isPresent() && opt.get().stream().anyMatch(e -> e.getType() == EdgeType.PR_WR);
                });
        assertTrue(hasPrWrBefore, "First call should create PR_WR");

        // 第二次构建（同样输入，不应重复添加）
        SERVerifier.refreshDerivedPredicateEdges(h, graph);

        // PR_WR 边仍然存在（不是重新添加更多）
        boolean hasPrWrAfter = graph.getKnownGraphA().edges().stream()
                .anyMatch(ep -> {
                    var opt = graph.getKnownGraphA().edgeValue(ep.source(), ep.target());
                    return opt.isPresent() && opt.get().stream().anyMatch(e -> e.getType() == EdgeType.PR_WR);
                });
        assertTrue(hasPrWrAfter, "Second call should keep PR_WR edge (not accumulate duplicates)");

        // 确认 clearDerived 效果: 旧边被清空后再重建（对于这个简单场景，重建后还是同样边）
        // 关键验证: edges 不应该翻倍
        long prWrCount = graph.getKnownGraphA().edges().stream()
                .filter(ep -> {
                    var opt = graph.getKnownGraphA().edgeValue(ep.source(), ep.target());
                    return opt.isPresent() && opt.get().stream().anyMatch(e -> e.getType() == EdgeType.PR_WR);
                })
                .count();
        assertEquals(1, prWrCount, "Should have exactly one PR_WR edge, not accumulated duplicates");
    }

    @Test
    void refreshDerivedPredicateEdges_noPredicateObservations_doesNothing() throws Exception {
        // 无 predicate read 观察 -> 直接返回，不 crash
        var h = buildHistoryWithPredicateRead(
                Set.of(0L),
                Map.of(0L, List.of(0L)),
                Map.of(0L, List.of(Triple.of(WRITE, "x", 1))),
                Map.of()  // 无 predicate reads
        );
        var graph = makeGraph(h);

        assertTrue(graph.getPredicateObservations().isEmpty());

        // 不应抛出异常
        SERVerifier.refreshDerivedPredicateEdges(h, graph);

        // 无 predicate 相关边
        boolean hasPredicateEdge = graph.getKnownGraphA().edges().stream()
                .anyMatch(ep -> {
                    var opt = graph.getKnownGraphA().edgeValue(ep.source(), ep.target());
                    return opt.isPresent() && opt.get().stream().anyMatch(e ->
                            e.getType() == EdgeType.PR_WR || e.getType() == EdgeType.PR_RW);
                });
        assertFalse(hasPredicateEdge);
    }
}
