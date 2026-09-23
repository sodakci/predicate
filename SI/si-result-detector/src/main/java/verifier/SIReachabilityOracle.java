package verifier;

import com.google.common.graph.ValueGraph;
import graph.Edge;
import graph.EdgeType;
import graph.KnownGraph;
import history.Transaction;

import java.util.BitSet;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * SI 的确定顺序与可见性查询，共享已知 I = A ∪ (A;B) 闭包。
 *
 * <p>WW 候选使用每轮固定闭包进行充分冲突检查。已知可见性为 I*;A，
 * 不能把 I 可达直接当成 VIS。纯 VIS/NOT_VIS 只登记内部 A/B 类支持，
 * 不修改 KnownGraph 中的 typed 依赖，也不接收未知 SAT 选择。</p>
 */
final class SIReachabilityOracle<KeyType, ValueType> {
    private final Map<Transaction<KeyType, ValueType>, Integer> nodeIndex =
            new IdentityHashMap<>();
    private final BitSet[] directA;
    private final BitSet[] directB;
    private final BitSet[] directPredecessorsA;
    private final BitSet[] reachA;
    private final BitSet[] reachInduced;
    private boolean acyclic;
    private boolean invalidVisibilityFact;
    private final BitSet[] predecessorsInduced;
    private final List<Transaction<KeyType, ValueType>> nodes = new ArrayList<>();
    private long updateCount;

    SIReachabilityOracle(KnownGraph<KeyType, ValueType> graph) {
        int next = 0;
        for (var txn : graph.getKnownGraphA().nodes()) {
            if (!isBottomTxn(txn)) {
                nodeIndex.put(txn, next++);
                nodes.add(txn);
            }
        }
        directA = emptyRows(nodeIndex.size());
        directB = emptyRows(nodeIndex.size());
        addKnownEdges(graph.getKnownGraphA(), directA);
        addKnownEdges(graph.getKnownGraphB(), directB);
        directPredecessorsA = emptyRows(nodeIndex.size());
        reachA = emptyRows(nodeIndex.size());
        for (int from = 0; from < directA.length; from++) {
            for (int to = directA[from].nextSetBit(0); to >= 0;
                    to = directA[from].nextSetBit(to + 1)) {
                directPredecessorsA[to].set(from);
            }
        }
        reachInduced = emptyRows(nodeIndex.size());
        predecessorsInduced = emptyRows(nodeIndex.size());
        acyclic = buildInitialClosure();
        for (int from = 0; from < reachInduced.length; from++) {
            reachA[from].or(directA[from]);
            for (int to = reachInduced[from].nextSetBit(0); to >= 0;
                    to = reachInduced[from].nextSetBit(to + 1)) {
                predecessorsInduced[to].set(from);
                reachA[from].or(directA[to]);
            }
        }
    }

    /** 保留调用接口名称；返回已知 VIS = I*;A，而不是仅 A 的传递闭包。 */
    boolean reachesA(Transaction<KeyType, ValueType> from,
            Transaction<KeyType, ValueType> to) {
        if (from == null || to == null || from.equals(to)) {
            return false;
        }
        if (isBottomTxn(from)) {
            return true;
        }
        if (isBottomTxn(to)) {
            return false;
        }
        if (!acyclic) {
            return false;
        }
        var fromIndex = nodeIndex.get(from);
        var toIndex = nodeIndex.get(to);
        if (fromIndex == null || toIndex == null) {
            return false;
        }
        return reachA[fromIndex].get(toIndex);
    }

    /** A true result proves this branch impossible; false leaves it unresolved. */
    boolean hasConflict(Collection<SIEdge<KeyType, ValueType>> edges) {
        if (!acyclic) {
            return true;
        }
        if (edges == null) {
            return false;
        }
        for (var edge : edges) {
            if (edge.getFrom().equals(edge.getTo()) || isBottomTxn(edge.getTo())) {
                return true;
            }
            if (isBottomTxn(edge.getFrom())) {
                continue;
            }
            int from = indexOf(edge.getFrom()), to = indexOf(edge.getTo());
            if (isDependencyEdgeA(edge.getType())) {
                if (reachInduced[to].get(from)) {
                    return true;
                }
            } else if (isDependencyEdgeB(edge.getType())) {
                // p -> from in A, followed by this RW, introduces p -> to.
                if (directPredecessorsA[from].get(to)
                        || directPredecessorsA[from].intersects(reachInduced[to])) {
                    return true;
                }
            } else {
                throw new IllegalArgumentException("unsupported SI dependency edge type: " + edge.getType());
            }
        }
        return false;
    }

    /**
     * 精确检查生成器的单方向 WW 分支：WW(u,v)，以及共同指向 v 的 RW。
     * 原检查覆盖新 A 本身及旧 A;新 B；此处补齐新 A;旧 B。
     * 两类新增 induced 边若共同成环，必有旧路径 v→*u，已被原 WW 检查捕获。
     * 非生成器形状仍使用原充分检查；候选来源不调用此入口。
     */
    boolean hasWwBranchConflict(Collection<SIEdge<KeyType, ValueType>> edges) {
        if (hasConflict(edges)) {
            return true;
        }
        Transaction<KeyType, ValueType> writer = null, later = null;
        for (var edge : edges) {
            if (edge.getType() == EdgeType.WW) {
                if (writer == null) {
                    writer = edge.getFrom();
                    later = edge.getTo();
                } else if (writer != edge.getFrom() || later != edge.getTo()) {
                    return false; // 非生成器形状继续使用原充分检查。
                }
            }
        }
        if (writer == null) {
            return false;
        }
        for (var edge : edges) {
            if (edge.getType() != EdgeType.WW
                    && (edge.getType() != EdgeType.RW || edge.getTo() != later)) {
                return false;
            }
        }
        if (isBottomTxn(writer)) {
            return false;
        }
        int from = indexOf(writer), to = indexOf(later);
        // 新增 WW(from,to) 与已有 B(to,*) 组合；无需复制或修改闭包。
        boolean conflict = directB[to].get(from) || directB[to].intersects(predecessorsInduced[from]);
        if (conflict) {
            util.Profiler.getInstance().addCount("WW_BRANCH_EXTRA_CONFLICTS", 1);
        }
        return conflict;
    }

    boolean isAcyclic() {
        return acyclic;
    }

    /** 已提交 typed 依赖及确定 VIS/NOT_VIS 支持的 induced 可达性。 */
    boolean reachesInduced(Transaction<KeyType, ValueType> from,
            Transaction<KeyType, ValueType> to) {
        if (!acyclic || from.equals(to) || isBottomTxn(from) || isBottomTxn(to)) {
            return false;
        }
        return reachInduced[indexOf(from)].get(indexOf(to));
    }

    /** 整轮依次提交支持；组合产生的环同样会保留为冲突。 */
    boolean commitRound(Collection<SIEdge<KeyType, ValueType>> edges) {
        for (var edge : edges) {
            if (edge.getFrom().equals(edge.getTo()) || isBottomTxn(edge.getTo())) {
                throw new IllegalArgumentException("cannot commit invalid SI dependency edge");
            }
            if (isBottomTxn(edge.getFrom())) {
                continue;
            }
            boolean inA = isDependencyEdgeA(edge.getType());
            if (!inA && !isDependencyEdgeB(edge.getType())) {
                throw new IllegalArgumentException("unsupported SI dependency edge type: " + edge.getType());
            }
            addSupport(indexOf(edge.getFrom()), indexOf(edge.getTo()), inA, null);
        }
        return acyclic;
    }

    private int indexOf(Transaction<KeyType, ValueType> txn) {
        var index = nodeIndex.get(txn);
        if (index == null) {
            throw new IllegalStateException("transaction missing from SI reachability oracle");
        }
        return index;
    }

    long buildCount() {
        return 1L;
    }

    long updateCount() {
        return updateCount;
    }

    private void addKnownEdges(
            ValueGraph<Transaction<KeyType, ValueType>, Collection<Edge<KeyType>>> graph,
            BitSet[] target) {
        for (var endpoint : graph.edges()) {
            if (isBottomTxn(endpoint.source()) || isBottomTxn(endpoint.target())) {
                continue;
            }
            target[nodeIndex.get(endpoint.source())]
                    .set(nodeIndex.get(endpoint.target()));
        }
    }

    /** 登记 VIS(writer,reader) 的确定 A 类支持，返回是否新增；冲突查询 isAcyclic。 */
    boolean addVisibility(Transaction<KeyType, ValueType> writer,
            Transaction<KeyType, ValueType> reader) {
        return addVisibility(writer, reader, null);
    }

    boolean addVisibility(Transaction<KeyType, ValueType> writer,
            Transaction<KeyType, ValueType> reader,
            Consumer<Transaction<KeyType, ValueType>> onAffected) {
        if (isBottomTxn(writer)) {
            return false;
        }
        if (isBottomTxn(reader)) {
            return recordInvalidVisibilityFact();
        }
        return addSupport(indexOf(writer), indexOf(reader), true, onAffected);
    }

    /** 登记 NOT_VIS(writer,reader)，其 B 类支持方向是 reader→writer。 */
    boolean addInvisibility(Transaction<KeyType, ValueType> writer,
            Transaction<KeyType, ValueType> reader) {
        return addInvisibility(writer, reader, null);
    }

    boolean addInvisibility(Transaction<KeyType, ValueType> writer,
            Transaction<KeyType, ValueType> reader,
            Consumer<Transaction<KeyType, ValueType>> onAffected) {
        if (isBottomTxn(writer)) {
            return recordInvalidVisibilityFact();
        }
        if (writer.equals(reader) || isBottomTxn(reader)) {
            return false;
        }
        return addSupport(indexOf(reader), indexOf(writer), false, onAffected);
    }

    private boolean recordInvalidVisibilityFact() {
        boolean changed = !invalidVisibilityFact;
        invalidVisibilityFact = true;
        acyclic = false;
        if (changed) {
            updateCount++;
        }
        return changed;
    }

    private boolean addSupport(int from, int to, boolean inA,
            Consumer<Transaction<KeyType, ValueType>> onAffected) {
        var supports = inA ? directA : directB;
        if (supports[from].get(to)) {
            return false;
        }
        supports[from].set(to);
        if (inA) {
            directPredecessorsA[to].set(from);
        }
        updateCount++;
        if (!acyclic) {
            return true;
        }
        var affected = onAffected == null ? null : new BitSet(nodes.size());
        if (inA) {
            // 新 A 即使没有改变 I 的可达性，也可能扩大 I*;A。
            var predecessors = (BitSet) predecessorsInduced[from].clone();
            predecessors.set(from);
            for (int p = predecessors.nextSetBit(0); p >= 0; p = predecessors.nextSetBit(p + 1)) {
                if (!reachA[p].get(to)) {
                    reachA[p].set(to);
                    if (affected != null) {
                        affected.set(p);
                        affected.set(to);
                    }
                }
            }
            addInduced(from, to, affected);
            for (int target = directB[to].nextSetBit(0); target >= 0;
                    target = directB[to].nextSetBit(target + 1)) {
                addInduced(from, target, affected);
            }
        } else {
            for (int p = directPredecessorsA[from].nextSetBit(0); p >= 0;
                    p = directPredecessorsA[from].nextSetBit(p + 1)) {
                addInduced(p, to, affected);
            }
        }
        // 完成整次支持更新后，每个受影响端点至多通知一次。
        if (affected != null) {
            for (int node = affected.nextSetBit(0); node >= 0; node = affected.nextSetBit(node + 1)) {
                onAffected.accept(nodes.get(node));
            }
        }
        return true;
    }

    private void addInduced(int from, int to, BitSet affected) {
        if (!acyclic) {
            return;
        }
        if (from == to || reachInduced[to].get(from)) {
            acyclic = false;
            return;
        }
        if (reachInduced[from].get(to)) {
            return;
        }
        var predecessors = (BitSet) predecessorsInduced[from].clone();
        predecessors.set(from);
        var successors = (BitSet) reachInduced[to].clone();
        successors.set(to);
        for (int p = predecessors.nextSetBit(0); p >= 0; p = predecessors.nextSetBit(p + 1)) {
            if (affected != null) {
                var delta = (BitSet) successors.clone();
                delta.andNot(reachInduced[p]);
                var visibilityDelta = (BitSet) reachA[to].clone();
                visibilityDelta.andNot(reachA[p]);
                delta.or(visibilityDelta);
                if (!delta.isEmpty()) {
                    affected.set(p);
                    affected.or(delta);
                }
            }
            reachInduced[p].or(successors);
            reachA[p].or(reachA[to]);
        }
        for (int q = successors.nextSetBit(0); q >= 0; q = successors.nextSetBit(q + 1)) {
            predecessorsInduced[q].or(predecessors);
        }
    }

    private static BitSet[] emptyRows(int size) {
        var rows = new BitSet[size];
        for (int i = 0; i < size; i++) {
            rows[i] = new BitSet(size);
        }
        return rows;
    }

    /** 初始已知图只构建一次闭包，后续支持均增量更新。 */
    private boolean buildInitialClosure() {
        if (invalidVisibilityFact) {
            return false;
        }
        for (int from = 0; from < directA.length; from++) {
            reachInduced[from].clear();
            reachInduced[from].or(directA[from]);
            for (int middle = directA[from].nextSetBit(0); middle >= 0;
                    middle = directA[from].nextSetBit(middle + 1)) {
                reachInduced[from].or(directB[middle]);
            }
        }

        var inDegree = new int[reachInduced.length];
        for (var successors : reachInduced) {
            for (int to = successors.nextSetBit(0); to >= 0;
                    to = successors.nextSetBit(to + 1)) {
                inDegree[to]++;
            }
        }
        var order = new int[inDegree.length];
        int count = 0;
        for (int node = 0; node < inDegree.length; node++) {
            if (inDegree[node] == 0) {
                order[count++] = node;
            }
        }
        int visited = 0;
        while (visited < count) {
            int from = order[visited++];
            for (int to = reachInduced[from].nextSetBit(0); to >= 0;
                    to = reachInduced[from].nextSetBit(to + 1)) {
                if (--inDegree[to] == 0) {
                    order[count++] = to;
                }
            }
        }
        if (visited != reachInduced.length) {
            return false;
        }
        for (int i = count - 1; i >= 0; i--) {
            int from = order[i];
            var direct = (BitSet) reachInduced[from].clone();
            for (int to = direct.nextSetBit(0); to >= 0;
                    to = direct.nextSetBit(to + 1)) {
                reachInduced[from].or(reachInduced[to]);
            }
        }
        return true;
    }

    static boolean isDependencyEdgeA(EdgeType type) {
        return type == EdgeType.SO || type == EdgeType.WR
                || type == EdgeType.WW || type == EdgeType.PR_WR;
    }

    static boolean isDependencyEdgeB(EdgeType type) {
        return type == EdgeType.RW || type == EdgeType.PR_RW;
    }

    static boolean isBottomTxn(Transaction<?, ?> txn) {
        return txn != null
                && txn.getId() == -1L
                && txn.getSession() != null
                && txn.getSession().getId() == -1L;
    }
}
