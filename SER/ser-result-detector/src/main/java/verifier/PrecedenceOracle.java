package verifier;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Audit-scoped incremental transitive closure shared by SER modules.
 *
 * <p>Only deterministic order facts may be added. Conditional MonoSAT/SAT
 * decisions remain in the solver encoding and must never be published here.</p>
 */
final class PrecedenceOracle<NodeType> {
    static final class Relation<NodeType> {
        final NodeType from;
        final NodeType to;

        Relation(NodeType from, NodeType to) {
            this.from = from;
            this.to = to;
        }

        @Override
        public boolean equals(Object object) {
            if (!(object instanceof Relation)) {
                return false;
            }
            var other = (Relation<?>) object;
            return Objects.equals(from, other.from) && Objects.equals(to, other.to);
        }

        @Override
        public int hashCode() {
            return Objects.hash(from, to);
        }
    }

    private final List<NodeType> nodes;
    private final Map<NodeType, Integer> nodeIndex;
    private final BitSet[] successors;
    private final BitSet[] predecessors;
    private long addAttempts;
    private long closureUpdates;
    private long rejectedAdds;
    private long cycleChecks;

    PrecedenceOracle(Collection<NodeType> nodes) {
        this.nodes = new ArrayList<>(nodes.size());
        this.nodeIndex = new LinkedHashMap<>();
        for (var node : nodes) {
            if (!nodeIndex.containsKey(node)) {
                nodeIndex.put(node, this.nodes.size());
                this.nodes.add(node);
            }
        }
        this.successors = emptyRows(this.nodes.size());
        this.predecessors = emptyRows(this.nodes.size());
    }

    boolean before(NodeType from, NodeType to) {
        return successors[indexOf(from)].get(indexOf(to));
    }

    Set<NodeType> successor(NodeType from) {
        return nodesIn(successors[indexOf(from)]);
    }

    Set<NodeType> predecessor(NodeType to) {
        return nodesIn(predecessors[indexOf(to)]);
    }

    boolean wouldCycle(NodeType from, NodeType to) {
        cycleChecks++;
        int fromIndex = indexOf(from);
        int toIndex = indexOf(to);
        return fromIndex == toIndex || successors[toIndex].get(fromIndex);
    }

    boolean wouldCycle(Collection<Relation<NodeType>> additions) {
        cycleChecks++;
        if (additions.isEmpty()) {
            return false;
        }
        var localIndex = new LinkedHashMap<Integer, Integer>();
        var globalNodes = new ArrayList<Integer>();
        var localEdges = new ArrayList<int[]>();
        for (var relation : additions) {
            int globalFrom = indexOf(relation.from);
            int globalTo = indexOf(relation.to);
            int localFrom = localIndex.computeIfAbsent(globalFrom, ignored -> {
                globalNodes.add(globalFrom);
                return globalNodes.size() - 1;
            });
            int localTo = localIndex.computeIfAbsent(globalTo, ignored -> {
                globalNodes.add(globalTo);
                return globalNodes.size() - 1;
            });
            localEdges.add(new int[] { localFrom, localTo });
        }

        var localClosure = emptyRows(globalNodes.size());
        for (int from = 0; from < globalNodes.size(); from++) {
            for (int to = 0; to < globalNodes.size(); to++) {
                if (successors[globalNodes.get(from)].get(globalNodes.get(to))) {
                    localClosure[from].set(to);
                }
            }
        }
        for (var edge : localEdges) {
            if (edge[0] == edge[1] || localClosure[edge[1]].get(edge[0])) {
                return true;
            }
            addToClosure(localClosure, edge[0], edge[1]);
        }
        return false;
    }

    /** Adds one precedence relation; returns false only when it would create a cycle. */
    boolean add(NodeType from, NodeType to) {
        addAttempts++;
        int fromIndex = indexOf(from);
        int toIndex = indexOf(to);
        if (fromIndex == toIndex || successors[toIndex].get(fromIndex)) {
            rejectedAdds++;
            return false;
        }
        if (successors[fromIndex].get(toIndex)) {
            return true;
        }
        closureUpdates++;

        var affectedPredecessors = (BitSet) predecessors[fromIndex].clone();
        affectedPredecessors.set(fromIndex);
        var affectedSuccessors = (BitSet) successors[toIndex].clone();
        affectedSuccessors.set(toIndex);
        for (int predecessor = affectedPredecessors.nextSetBit(0);
                predecessor >= 0;
                predecessor = affectedPredecessors.nextSetBit(predecessor + 1)) {
            successors[predecessor].or(affectedSuccessors);
        }
        for (int successor = affectedSuccessors.nextSetBit(0);
                successor >= 0;
                successor = affectedSuccessors.nextSetBit(successor + 1)) {
            predecessors[successor].or(affectedPredecessors);
        }
        return true;
    }

    int relationCount() {
        int count = 0;
        for (var row : successors) {
            count += row.cardinality();
        }
        return count;
    }

    long addAttemptCount() {
        return addAttempts;
    }

    long closureUpdateCount() {
        return closureUpdates;
    }

    long rejectedAddCount() {
        return rejectedAdds;
    }

    long cycleCheckCount() {
        return cycleChecks;
    }

    private Set<NodeType> nodesIn(BitSet indexes) {
        var result = new LinkedHashSet<NodeType>();
        for (int index = indexes.nextSetBit(0); index >= 0;
                index = indexes.nextSetBit(index + 1)) {
            result.add(nodes.get(index));
        }
        return Collections.unmodifiableSet(result);
    }

    private int indexOf(NodeType node) {
        var index = nodeIndex.get(node);
        if (index == null) {
            throw new IllegalStateException("node missing from precedence oracle: " + node);
        }
        return index;
    }

    private static BitSet[] emptyRows(int size) {
        var rows = new BitSet[size];
        for (int index = 0; index < size; index++) {
            rows[index] = new BitSet(size);
        }
        return rows;
    }

    private static void addToClosure(BitSet[] closure, int from, int to) {
        if (closure[from].get(to)) {
            return;
        }
        var targets = (BitSet) closure[to].clone();
        targets.set(to);
        for (int predecessor = 0; predecessor < closure.length; predecessor++) {
            if ((predecessor == from || closure[predecessor].get(from))
                    && !closure[predecessor].get(to)) {
                closure[predecessor].or(targets);
            }
        }
    }
}
