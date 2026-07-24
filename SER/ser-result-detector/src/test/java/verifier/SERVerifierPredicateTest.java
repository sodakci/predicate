package verifier;

import graph.KnownGraph;
import history.Event;
import history.History;
import history.Session;
import history.Transaction;
import org.apache.commons.lang3.tuple.Triple;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 纯单元测试：验证 SERVerifier 中的 predicate 边推导辅助函数。
 * 所有方法均为 private static，通过反射调用。
 *
 * 测试目标：
 * 1. writeRowIsInPredicateResult — 写入行是否进入谓词结果集
 * 2. writeChangesPredicateResultSet — Delta 的 canonical row transition 判断
 * 3. uniqueTopologicalSort — 拓扑排序唯一性
 * 4. buildWritesByKey — 按 key 分组 writes
 */
public class SERVerifierPredicateTest {

    // ================================================================
    // 辅助：最小 fixture 构造
    // ================================================================

    private static History<String, Integer> makeHistory(
            List<Triple<String, Event.EventType, Integer>> events) {
        return makeHistory(events, Map.of());
    }

    private static History<String, Integer> makeHistory(
            List<Triple<String, Event.EventType, Integer>> events,
            Map<Long, List<Long>> txnMap) {
        Map<Long, List<Triple<Event.EventType, String, Integer>>> txnEvents = new HashMap<>();
        for (int i = 0; i < events.size(); i++) {
            int txnId = i; // 每个事件一个独立事务
            txnEvents.computeIfAbsent((long) txnId, k -> new ArrayList<>())
                    .add(Triple.of(events.get(i).getMiddle(), events.get(i).getLeft(), events.get(i).getRight()));
        }
        // 合并外部传入的 txnMap
        for (var e : txnMap.entrySet()) {
            txnEvents.put(e.getKey(), txnEvents.getOrDefault(e.getKey(), new ArrayList<>()));
        }

        Set<Long> sessions = new HashSet<>();
        Map<Long, List<Long>> finalTxnMap = new HashMap<>();
        for (Long txnId : txnEvents.keySet()) {
            long sessId = txnId; // 1:1 mapping for simplicity
            sessions.add(sessId);
            finalTxnMap.computeIfAbsent(sessId, k -> new ArrayList<>()).add(txnId);
        }

        return new History<>(sessions, finalTxnMap, txnEvents);
    }

    private static History<String, Integer> makeSingleSessionHistory(
            List<Triple<String, Event.EventType, Integer>> events) {
        Map<Long, List<Triple<Event.EventType, String, Integer>>> txnEvents = new HashMap<>();
        txnEvents.put(0L, new ArrayList<>());
        for (var e : events) {
            txnEvents.get(0L).add(Triple.of(e.getMiddle(), e.getLeft(), e.getRight()));
        }
        return new History<>(Set.of(0L), Map.of(0L, List.of(0L)), txnEvents);
    }

    private static KnownGraph<String, Integer> makeGraph(History<String, Integer> h) {
        return new KnownGraph<>(h);
    }

    private static KnownGraph.WriteRef<String, Integer> makeWriteRef(
            KnownGraph<String, Integer> graph, String key, Integer value) {
        var entry = graph.getWrites().entrySet().stream()
                .filter(e -> e.getKey().getLeft().equals(key) && e.getKey().getRight().equals(value))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Write not found: " + key + "=" + value));
        return entry.getValue();
    }

    private static KnownGraph.WriteRef<String, Integer> makeWriteRefById(
            KnownGraph<String, Integer> graph, long writeId) {
        return graph.getAllWrites().stream()
                .filter(w -> Objects.equals(w.getWriteId(), writeId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Write not found by id: " + writeId));
    }

    private static Event<String, Integer> makePredicateReadEvent(
            Transaction<String, Integer> txn,
            PredicateFixtures.RowPredicate<String, Integer> predicate,
            List<Event.PredResult<String, Integer>> results) {
        return new Event<>(txn, Event.EventType.PREDICATE_READ, null, null,
                predicate, results, null);
    }

    // ================================================================
    // 反射调用工具
    // ================================================================

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
            throw new AssertionError("Reflection invoke failed", e);
        }
    }

    // ================================================================
    // Test 1: writeRowIsInPredicateResult — 写入行是否进入谓词结果集
    // ================================================================

    @Test
    void writeRowIsInPredicateResult_writeValueSatisfiesPredicate() throws Exception {
        // 构造历史: T0: W(x, 10)
        var h = makeSingleSessionHistory(List.of(
                Triple.of("x", Event.EventType.WRITE, 10)
        ));
        var graph = makeGraph(h);

        // 谓词: value > 5
        PredicateFixtures.RowPredicate<String, Integer> pred = (k, v) -> v > 5;
        var writeRef = makeWriteRef(graph, "x", 10);

        // 构造一个最小 predicate read event（用于传参）
        var prEvent = makePredicateReadEvent(
                h.getTransaction(0L), pred, List.of());

        Method m = findMethod(SERVerifier.class, "writeRowIsInPredicateResult",
                KnownGraph.WriteRef.class, Event.class);
        boolean result = invokeStatic(m, writeRef, prEvent);

        assertTrue(result, "value=10 should satisfy predicate v > 5");
    }

    @Test
    void writeRowIsInPredicateResult_writeValueDoesNotSatisfyPredicate() throws Exception {
        // 构造历史: T0: W(x, 3)
        var h = makeSingleSessionHistory(List.of(
                Triple.of("x", Event.EventType.WRITE, 3)
        ));
        var graph = makeGraph(h);

        // 谓词: value > 5
        PredicateFixtures.RowPredicate<String, Integer> pred = (k, v) -> v > 5;
        var writeRef = makeWriteRef(graph, "x", 3);

        var prEvent = makePredicateReadEvent(
                h.getTransaction(0L), pred, List.of());

        Method m = findMethod(SERVerifier.class, "writeRowIsInPredicateResult",
                KnownGraph.WriteRef.class, Event.class);
        boolean result = invokeStatic(m, writeRef, prEvent);

        assertFalse(result, "value=3 should NOT satisfy predicate v > 5");
    }

    @Test
    void writeRowIsInPredicateResult_predicateOnKey() throws Exception {
        // 构造历史: T0: W(x, 100)
        var h = makeSingleSessionHistory(List.of(
                Triple.of("x", Event.EventType.WRITE, 100)
        ));
        var graph = makeGraph(h);

        // 谓词: key == "x"
        PredicateFixtures.RowPredicate<String, Integer> pred = (k, v) -> "x".equals(k);
        var writeRef = makeWriteRef(graph, "x", 100);

        var prEvent = makePredicateReadEvent(
                h.getTransaction(0L), pred, List.of());

        Method m = findMethod(SERVerifier.class, "writeRowIsInPredicateResult",
                KnownGraph.WriteRef.class, Event.class);
        boolean result = invokeStatic(m, writeRef, prEvent);

        assertTrue(result, "key=x should satisfy predicate key == 'x'");
    }

    // ================================================================
    // Test 2: writeChangesPredicateResultSet — 谓词结果是否变化
    // ================================================================

    @Test
    void writeChangesPredicateResultSet_prevEmpty_currHasResult() throws Exception {
        // 构造: T0: W(x, 1) -> T1: W(x, 10)
        // predicate: v > 5
        // prev(T0): empty | curr(T1): (x,10) => Delta holds
        var h = makeHistory(List.of(
                Triple.of("x", Event.EventType.WRITE, 1),
                Triple.of("x", Event.EventType.WRITE, 10)
        ), Map.of(0L, List.of(0L), 1L, List.of(1L)));
        var graph = makeGraph(h);

        var pred = (PredicateFixtures.RowPredicate<String, Integer>) (k, v) -> v > 5;
        var prevRef = makeWriteRef(graph, "x", 1);
        var currRef = makeWriteRef(graph, "x", 10);

        var prEvent = makePredicateReadEvent(h.getTransaction(1L), pred, List.of());

        Method m = findMethod(SERVerifier.class, "writeChangesPredicateResultSet",
                KnownGraph.WriteRef.class, KnownGraph.WriteRef.class, Event.class);
        boolean result = invokeStatic(m, currRef, prevRef, prEvent);

        assertTrue(result, "empty result -> (x,10) should satisfy Delta");
    }

    @Test
    void writeChangesPredicateResultSet_prevHasResult_currEmpty() throws Exception {
        // 构造: T0: W(x, 10) -> T1: W(x, 1)
        // predicate: v > 5
        // prev(T0): (x,10) | curr(T1): empty => Delta holds
        var h = makeHistory(List.of(
                Triple.of("x", Event.EventType.WRITE, 10),
                Triple.of("x", Event.EventType.WRITE, 1)
        ), Map.of(0L, List.of(0L), 1L, List.of(1L)));
        var graph = makeGraph(h);

        var pred = (PredicateFixtures.RowPredicate<String, Integer>) (k, v) -> v > 5;
        var prevRef = makeWriteRef(graph, "x", 10);
        var currRef = makeWriteRef(graph, "x", 1);

        var prEvent = makePredicateReadEvent(h.getTransaction(1L), pred, List.of());

        Method m = findMethod(SERVerifier.class, "writeChangesPredicateResultSet",
                KnownGraph.WriteRef.class, KnownGraph.WriteRef.class, Event.class);
        boolean result = invokeStatic(m, currRef, prevRef, prEvent);

        assertTrue(result, "(x,10) -> empty result should satisfy Delta");
    }

    @Test
    void writeChangesPredicateResultSet_resultSetValueChangedWithoutPresenceChange() throws Exception {
        // prev(T0): result=(x,10) | curr(T1): result=(x,20) => Delta holds
        var h = makeHistory(List.of(
                Triple.of("x", Event.EventType.WRITE, 10),
                Triple.of("x", Event.EventType.WRITE, 20)
        ), Map.of(0L, List.of(0L), 1L, List.of(1L)));
        var graph = makeGraph(h);

        var pred = (PredicateFixtures.RowPredicate<String, Integer>) (k, v) -> v > 5;
        var prevRef = makeWriteRef(graph, "x", 10);
        var currRef = makeWriteRef(graph, "x", 20);

        var prEvent = makePredicateReadEvent(h.getTransaction(1L), pred, List.of());

        Method m = findMethod(SERVerifier.class, "writeChangesPredicateResultSet",
                KnownGraph.WriteRef.class, KnownGraph.WriteRef.class, Event.class);
        boolean result = invokeStatic(m, currRef, prevRef, prEvent);

        assertTrue(result, "(x,10) -> (x,20) should satisfy Delta");
    }

    @Test
    void writeChangesPredicateResultSet_sourceRelationChangesButResultSame() throws Exception {
        var h = new History<String, Integer>();
        var session = h.addSession(0L);
        var t0 = h.addTransaction(session, 0L);
        var t1 = h.addTransaction(session, 1L);
        h.addWriteEvent(t0, "x", 10, 100L);
        h.addWriteEvent(t1, "x", 10, 101L);
        var graph = makeGraph(h);

        var pred = (PredicateFixtures.RowPredicate<String, Integer>) (k, v) -> v > 5;
        var prevRef = makeWriteRefById(graph, 100L);
        var currRef = makeWriteRefById(graph, 101L);
        var prEvent = makePredicateReadEvent(t1, pred, List.of());

        Method m = findMethod(SERVerifier.class, "writeChangesPredicateResultSet",
                KnownGraph.WriteRef.class, KnownGraph.WriteRef.class, Event.class);
        boolean result = invokeStatic(m, currRef, prevRef, prEvent);

        assertFalse(result, "source/write relation changed but canonical PredicateResult stayed (x,10)");
    }

    @Test
    void writeChangesPredicateResultSet_sourceRelationAndResultSetBothChange() throws Exception {
        var h = new History<String, Integer>();
        var session = h.addSession(0L);
        var t0 = h.addTransaction(session, 0L);
        var t1 = h.addTransaction(session, 1L);
        h.addWriteEvent(t0, "x", 10, 100L);
        h.addWriteEvent(t1, "x", 20, 101L);
        var graph = makeGraph(h);

        var pred = (PredicateFixtures.RowPredicate<String, Integer>) (k, v) -> v > 5;
        var prevRef = makeWriteRefById(graph, 100L);
        var currRef = makeWriteRefById(graph, 101L);
        var prEvent = makePredicateReadEvent(t1, pred, List.of());

        Method m = findMethod(SERVerifier.class, "writeChangesPredicateResultSet",
                KnownGraph.WriteRef.class, KnownGraph.WriteRef.class, Event.class);
        boolean result = invokeStatic(m, currRef, prevRef, prEvent);

        assertTrue(result, "source/write relation changed and canonical PredicateResult Delta holds from (x,10) to (x,20)");
    }

    @Test
    void writeChangesPredicateResultSet_bothEmpty() throws Exception {
        // prev(T0): empty | curr(T1): empty => Delta does not hold
        var h = makeHistory(List.of(
                Triple.of("x", Event.EventType.WRITE, 1),
                Triple.of("x", Event.EventType.WRITE, 2)
        ), Map.of(0L, List.of(0L), 1L, List.of(1L)));
        var graph = makeGraph(h);

        var pred = (PredicateFixtures.RowPredicate<String, Integer>) (k, v) -> v > 5;
        var prevRef = makeWriteRef(graph, "x", 1);
        var currRef = makeWriteRef(graph, "x", 2);

        var prEvent = makePredicateReadEvent(h.getTransaction(1L), pred, List.of());

        Method m = findMethod(SERVerifier.class, "writeChangesPredicateResultSet",
                KnownGraph.WriteRef.class, KnownGraph.WriteRef.class, Event.class);
        boolean result = invokeStatic(m, currRef, prevRef, prEvent);

        assertFalse(result, "empty -> empty should NOT satisfy Delta");
    }

    @Test
    void writeChangesPredicateResultSet_nullPredecessor_treatedAsInitialState() throws Exception {
        // prev=null, curr=3 不满足 predicate v>5 => false ^ false = false
        // prev=null, curr=10 满足 predicate v>5 => false ^ true = true
        var h = makeSingleSessionHistory(List.of(
                Triple.of("x", Event.EventType.WRITE, 10)
        ));
        var graph = makeGraph(h);

        var pred = (PredicateFixtures.RowPredicate<String, Integer>) (k, v) -> v > 5;
        var currRef = makeWriteRef(graph, "x", 10);

        var prEvent = makePredicateReadEvent(h.getTransaction(0L), pred, List.of());

        Method m = findMethod(SERVerifier.class, "writeChangesPredicateResultSet",
                KnownGraph.WriteRef.class, KnownGraph.WriteRef.class, Event.class);

        // null predecessor -> initial state treated as empty PredicateResult
        boolean result = invokeStatic(m, currRef, null, prEvent);
        assertTrue(result, "empty initial result -> (x,10) should satisfy Delta");
    }

    // ================================================================
    // Test 3: uniqueTopologicalSort
    // ================================================================

    @Test
    void uniqueTopologicalSort_linearOrder() throws Exception {
        // A -> B -> C 只有唯一拓扑序
        List<String> nodes = List.of("A", "B", "C");
        Map<String, Set<String>> succs = new HashMap<>();
        succs.put("A", Set.of("B"));
        succs.put("B", Set.of("C"));

        Method m = findMethod(SERVerifier.class, "uniqueTopologicalSort",
                List.class, Map.class);
        List<String> result = invokeStatic(m, nodes, succs);

        assertNotNull(result);
        assertEquals(3, result.size());
        assertEquals("A", result.get(0));
        assertEquals("B", result.get(1));
        assertEquals("C", result.get(2));
    }

    @Test
    void uniqueTopologicalSort_incomparable_returnsNull() throws Exception {
        // A, B 无边，不可比 -> 不止一种拓扑序
        List<String> nodes = List.of("A", "B");
        Map<String, Set<String>> succs = new HashMap<>();

        Method m = findMethod(SERVerifier.class, "uniqueTopologicalSort",
                List.class, Map.class);
        var result = invokeStatic(m, nodes, succs);

        assertNull(result, "Incomparable nodes should return null (not a total order)");
    }

    @Test
    void uniqueTopologicalSort_parallelEdges_returnsNull() throws Exception {
        // A -> B, A -> C, B -> D, C -> D
        // 拓扑序: A 必须第一，但 B 和 C 是平行的（都依赖 A，都指向 D）
        // 入度: A=0, B=1, C=1, D=2
        // 第一步: 队列=[A] ✓; 处理 A 后: B入度=0, C入度=0 -> 队列=[B,C] -> size>1 -> 返回 null
        List<String> nodes = List.of("A", "B", "C", "D");
        Map<String, Set<String>> succs = new HashMap<>();
        succs.put("A", Set.of("B", "C"));
        succs.put("B", Set.of("D"));
        succs.put("C", Set.of("D"));

        Method m = findMethod(SERVerifier.class, "uniqueTopologicalSort",
                List.class, Map.class);
        List<String> result = invokeStatic(m, nodes, succs);

        assertNull(result, "B and C are parallel (queue.size > 1 at step 2) — not a unique total order");
    }

    @Test
    void uniqueTopologicalSort_cycle_returnsNull() throws Exception {
        // A -> B -> C -> A 循环
        List<String> nodes = List.of("A", "B", "C");
        Map<String, Set<String>> succs = new HashMap<>();
        succs.put("A", Set.of("B"));
        succs.put("B", Set.of("C"));
        succs.put("C", Set.of("A"));

        Method m = findMethod(SERVerifier.class, "uniqueTopologicalSort",
                List.class, Map.class);
        var result = invokeStatic(m, nodes, succs);

        assertNull(result, "Cycle should return null");
    }

    // ================================================================
    // Test 4: buildWritesByKey
    // ================================================================

    @Test
    void buildWritesByKey_groupsWritesByKey() throws Exception {
        // W(x,1), W(y,2), W(x,3)
        var h = makeHistory(List.of(
                Triple.of("x", Event.EventType.WRITE, 1),
                Triple.of("y", Event.EventType.WRITE, 2),
                Triple.of("x", Event.EventType.WRITE, 3)
        ), Map.of(0L, List.of(0L), 1L, List.of(1L), 2L, List.of(2L)));
        var graph = makeGraph(h);

        Method m = findMethod(SERVerifier.class, "buildWritesByKey",
                KnownGraph.class);
        @SuppressWarnings("unchecked")
        Map<String, List<KnownGraph.WriteRef<String, Integer>>> result =
                invokeStatic(m, graph);

        assertEquals(2, result.size(), "Should have 2 keys: x and y");
        assertEquals(2, result.get("x").size(), "Key x should have 2 writes");
        assertEquals(1, result.get("y").size(), "Key y should have 1 write");
    }

    @Test
    void buildWritesByKey_emptyGraph() throws Exception {
        var h = makeSingleSessionHistory(List.of()); // 空历史
        var graph = makeGraph(h);

        Method m = findMethod(SERVerifier.class, "buildWritesByKey",
                KnownGraph.class);
        @SuppressWarnings("unchecked")
        Map<String, List<KnownGraph.WriteRef<String, Integer>>> result =
                invokeStatic(m, graph);

        assertTrue(result.isEmpty(), "Empty graph should produce empty map");
    }
}
