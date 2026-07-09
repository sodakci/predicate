package verifier;

import graph.KnownGraph;
import history.History;
import history.Transaction;
import util.Profiler;
import graph.Edge;
import graph.EdgeType;
import com.google.common.graph.EndpointPair;
import com.google.common.graph.ValueGraph;

import java.util.*;

import org.apache.commons.lang3.tuple.Pair;

import lombok.Getter;
import lombok.Setter;

public class Pruning {
    @Getter
    @Setter
    private static boolean enablePruning = true;

    @Getter
    @Setter
    private static double stopThreshold = 0.01;

    private static Pair<?, ?> lastConflicts = emptyConflicts();

    static <KeyType, ValueType> boolean pruneConstraints(KnownGraph<KeyType, ValueType> knownGraph,
            Collection<SERConstraint<KeyType, ValueType>> constraints, History<KeyType, ValueType> history) {
        if (!enablePruning) {
            return false;
        }

        lastConflicts = emptyConflicts();

        var profiler = Profiler.getInstance();
        profiler.startTick("SER_PRUNE");

        int rounds = 1, solvedConstraints = 0, totalConstraints = constraints.size();
        boolean hasCycle = false;
        while (!hasCycle) {
            System.err.printf("Pruning round %d\n", rounds);
            var result = pruneConstraintsWithPostChecking(knownGraph, constraints, history);

            hasCycle = result.getRight();
            solvedConstraints += result.getLeft();

            if (result.getLeft() <= stopThreshold * totalConstraints
                    || totalConstraints - solvedConstraints <= stopThreshold * totalConstraints) {
                break;
            }
            rounds++;
        }

        profiler.endTick("SER_PRUNE");
        System.err.printf("Pruned %d rounds, solved %d constraints\n" + "After prune: graphA: %d, graphB: %d\n", rounds,
                solvedConstraints, knownGraph.getKnownGraphA().edges().size(),
                knownGraph.getKnownGraphB().edges().size());
        return hasCycle;
    }

    private static <KeyType, ValueType> Pair<Integer, Boolean> pruneConstraintsWithPostChecking(
            KnownGraph<KeyType, ValueType> knownGraph, Collection<SERConstraint<KeyType, ValueType>> constraints,
            History<KeyType, ValueType> history) {
        var profiler = Profiler.getInstance();

        var solvedConstraints = new ArrayList<SERConstraint<KeyType, ValueType>>();

        profiler.startTick("SER_PRUNE_POST_CHECK");
        int checked = 0;
        int total = constraints.size();
        var oracle = new ReachabilityOracle<>(history, knownGraph);
        var progress = new PostCheckProgress(total);
        progress.refresh(checked, solvedConstraints.size(), false);
        if (total == 0) {
            progress.refresh(checked, solvedConstraints.size(), true);
        }
        for (var c : constraints) {
            boolean okEither = oracle.canAddAll(c.getEdges1());
            boolean okOr = oracle.canAddAll(c.getEdges2());
            checked++;

            if (!okEither && !okOr) {
                lastConflicts = Pair.of(Collections.emptyList(), List.of(c));
                progress.refresh(checked, solvedConstraints.size(), true);
                profiler.endTick("SER_PRUNE_POST_CHECK");
                return Pair.of(0, true);
            }

            if (!okEither) {
                oracle.addAll(c.getEdges2());
                addToKnownGraph(knownGraph, c.getEdges2());
                solvedConstraints.add(c);
            } else if (!okOr) {
                oracle.addAll(c.getEdges1());
                addToKnownGraph(knownGraph, c.getEdges1());
                solvedConstraints.add(c);
            }

            progress.refresh(checked, solvedConstraints.size(), checked == total);
        }
        profiler.endTick("SER_PRUNE_POST_CHECK");

        // constraints.removeAll(solvedConstraints);
        // java removeAll has performance bugs; do it manually
        solvedConstraints.forEach(constraints::remove);
        return Pair.of(solvedConstraints.size(), false);
    }

    private static final class PostCheckProgress {
        private static final int BAR_WIDTH = 30;

        private final int total;
        private final boolean interactive;
        private final int nonInteractiveStep;

        private PostCheckProgress(int total) {
            this.total = total;
            this.interactive = System.console() != null;
            this.nonInteractiveStep = Math.max(1, Math.min(100, Math.max(1, total / 100)));
        }

        private void refresh(int checked, int solved, boolean done) {
            if (!interactive && !done && checked != 0 && checked % nonInteractiveStep != 0) {
                return;
            }

            var line = format(checked, solved);
            if (interactive) {
                System.err.print("\r" + line);
                if (done) {
                    System.err.println();
                }
            } else {
                System.err.println(line);
            }
            System.err.flush();
        }

        private String format(int checked, int solved) {
            int percent = total == 0 ? 100 : (int) Math.floor(checked * 100.0 / total);
            int filled = Math.min(BAR_WIDTH, Math.max(0, checked * BAR_WIDTH / Math.max(1, total)));
            var bar = new StringBuilder(BAR_WIDTH);
            for (int i = 0; i < BAR_WIDTH; i++) {
                bar.append(i < filled ? '=' : '-');
            }
            return String.format("Pruning post-check [%s] %3d%% checked %d/%d, solved %d",
                    bar, percent, checked, total, solved);
        }
    }

    private static <KeyType, ValueType> void addToKnownGraph(KnownGraph<KeyType, ValueType> knownGraph,
            Collection<SEREdge<KeyType, ValueType>> edges) {
        for (var e : edges) {
            switch (e.getType()) {
            case WW:
                knownGraph.putEdge(e.getFrom(), e.getTo(), new Edge<KeyType>(EdgeType.WW, e.getKey()));
                break;
            case RW:
                knownGraph.putEdge(e.getFrom(), e.getTo(), new Edge<KeyType>(e.getType(), e.getKey()));
                break;
            case PR_RW:
                break;
            default:
                throw new Error("only WW, RW and PR_RW edges should appear in constraints");
            }
        }
    }

    private static boolean isPruningEdge(EdgeType type) {
        return type == EdgeType.WW || type == EdgeType.RW;
    }

    private static boolean isNonPredicateEdge(EdgeType type) {
        return type != EdgeType.PR_WR && type != EdgeType.PR_RW;
    }

    private static final class ReachabilityOracle<KeyType, ValueType> {
        private final Map<Transaction<KeyType, ValueType>, Integer> nodeIndex = new HashMap<>();
        private final BitSet[] reachable;

        private ReachabilityOracle(History<KeyType, ValueType> history,
                                   KnownGraph<KeyType, ValueType> knownGraph) {
            int index = 0;
            for (var txn : history.getTransactions()) {
                nodeIndex.put(txn, index++);
            }
            reachable = new BitSet[nodeIndex.size()];
            for (int i = 0; i < reachable.length; i++) {
                reachable[i] = new BitSet(reachable.length);
            }

            addKnownEdges(knownGraph.getKnownGraphA());
            addKnownEdges(knownGraph.getKnownGraphB());
            transitiveClosure();
        }

        private boolean reaches(Transaction<KeyType, ValueType> from,
                                Transaction<KeyType, ValueType> to) {
            return reaches(indexOf(from), indexOf(to));
        }

        private boolean reaches(int from, int to) {
            return reachable[from].get(to);
        }

        private boolean canAddEdge(Transaction<KeyType, ValueType> from,
                                   Transaction<KeyType, ValueType> to) {
            return canAddEdge(indexOf(from), indexOf(to));
        }

        private boolean canAddEdge(int from, int to) {
            return from != to && !reaches(to, from);
        }

        private boolean canAddAll(Collection<SEREdge<KeyType, ValueType>> edges) {
            var trial = copyReachable();
            for (var edge : edges) {
                if (!isPruningEdge(edge.getType())) {
                    continue;
                }
                int from = indexOf(edge.getFrom());
                int to = indexOf(edge.getTo());
                if (!canAddEdge(trial, from, to)) {
                    return false;
                }
                addEdge(trial, from, to);
            }
            return true;
        }

        private void addEdge(Transaction<KeyType, ValueType> from,
                             Transaction<KeyType, ValueType> to) {
            addEdge(indexOf(from), indexOf(to));
        }

        private void addEdge(int from, int to) {
            addEdge(reachable, from, to);
        }

        private void addAll(Collection<SEREdge<KeyType, ValueType>> edges) {
            for (var edge : edges) {
                if (isPruningEdge(edge.getType())) {
                    addEdge(edge.getFrom(), edge.getTo());
                }
            }
        }

        private void addKnownEdges(ValueGraph<Transaction<KeyType, ValueType>, Collection<Edge<KeyType>>> graph) {
            for (var ep : graph.edges()) {
                var edges = graph.edgeValue(ep).orElse(Collections.emptyList());
                if (edges.stream().anyMatch(edge -> isNonPredicateEdge(edge.getType()))) {
                    reachable[indexOf(ep.source())].set(indexOf(ep.target()));
                }
            }
        }

        private void transitiveClosure() {
            for (int k = 0; k < reachable.length; k++) {
                for (int i = 0; i < reachable.length; i++) {
                    if (reachable[i].get(k)) {
                        reachable[i].or(reachable[k]);
                    }
                }
            }
        }

        private BitSet[] copyReachable() {
            var copy = new BitSet[reachable.length];
            for (int i = 0; i < reachable.length; i++) {
                copy[i] = (BitSet) reachable[i].clone();
            }
            return copy;
        }

        private void addEdge(BitSet[] graph, int from, int to) {
            var newTargets = (BitSet) graph[to].clone();
            newTargets.set(to);
            for (int p = 0; p < graph.length; p++) {
                if (p == from || graph[p].get(from)) {
                    graph[p].or(newTargets);
                }
            }
        }

        private boolean canAddEdge(BitSet[] graph, int from, int to) {
            return from != to && !graph[to].get(from);
        }

        private int indexOf(Transaction<KeyType, ValueType> txn) {
            var index = nodeIndex.get(txn);
            if (index == null) {
                throw new IllegalStateException("transaction missing from pruning reachability oracle: " + txn);
            }
            return index;
        }
    }

    private static Pair<?, ?> emptyConflicts() {
        return Pair.of(Collections.emptyList(), Collections.emptyList());
    }

    @SuppressWarnings("unchecked")
    static <KeyType, ValueType> Pair<Collection<Pair<EndpointPair<Transaction<KeyType, ValueType>>, Collection<Edge<KeyType>>>>,
            Collection<SERConstraint<KeyType, ValueType>>> getLastConflicts() {
        return (Pair<Collection<Pair<EndpointPair<Transaction<KeyType, ValueType>>, Collection<Edge<KeyType>>>>,
                Collection<SERConstraint<KeyType, ValueType>>>) lastConflicts;
    }

    private static <KeyType, ValueType> Collection<Pair<EndpointPair<Transaction<KeyType, ValueType>>, Collection<Edge<KeyType>>>>
    extractCycleEdges(KnownGraph<KeyType, ValueType> graph) {
        var adjacency = new HashMap<Transaction<KeyType, ValueType>, Set<Transaction<KeyType, ValueType>>>();
        addAdjacency(graph.getKnownGraphA(), adjacency);
        addAdjacency(graph.getKnownGraphB(), adjacency);

        var color = new HashMap<Transaction<KeyType, ValueType>, Integer>();
        var stack = new ArrayList<Transaction<KeyType, ValueType>>();
        var stackIndex = new HashMap<Transaction<KeyType, ValueType>, Integer>();
        for (var txn : graph.getKnownGraphA().nodes()) {
            if (color.getOrDefault(txn, 0) != 0) {
                continue;
            }
            var cycle = dfsCycle(txn, graph, adjacency, color, stack, stackIndex);
            if (!cycle.isEmpty()) {
                return cycle;
            }
        }
        return Collections.emptyList();
    }

    private static <KeyType, ValueType> void addAdjacency(
            com.google.common.graph.ValueGraph<Transaction<KeyType, ValueType>, Collection<Edge<KeyType>>> known,
            Map<Transaction<KeyType, ValueType>, Set<Transaction<KeyType, ValueType>>> adjacency) {
        for (var ep : known.edges()) {
            adjacency.computeIfAbsent(ep.source(), ignored -> new LinkedHashSet<>()).add(ep.target());
        }
    }

    private static <KeyType, ValueType> Collection<Pair<EndpointPair<Transaction<KeyType, ValueType>>, Collection<Edge<KeyType>>>>
    dfsCycle(Transaction<KeyType, ValueType> node,
             KnownGraph<KeyType, ValueType> graph,
             Map<Transaction<KeyType, ValueType>, Set<Transaction<KeyType, ValueType>>> adjacency,
             Map<Transaction<KeyType, ValueType>, Integer> color,
             List<Transaction<KeyType, ValueType>> stack,
             Map<Transaction<KeyType, ValueType>, Integer> stackIndex) {
        color.put(node, 1);
        stackIndex.put(node, stack.size());
        stack.add(node);

        for (var succ : adjacency.getOrDefault(node, Collections.emptySet())) {
            int succColor = color.getOrDefault(succ, 0);
            if (succColor == 0) {
                var cycle = dfsCycle(succ, graph, adjacency, color, stack, stackIndex);
                if (!cycle.isEmpty()) {
                    return cycle;
                }
            } else if (succColor == 1) {
                var cycleNodes = new ArrayList<>(stack.subList(stackIndex.get(succ), stack.size()));
                cycleNodes.add(succ);
                return cycleEdgesFromNodes(graph, cycleNodes);
            }
        }

        stack.remove(stack.size() - 1);
        stackIndex.remove(node);
        color.put(node, 2);
        return Collections.emptyList();
    }

    private static <KeyType, ValueType> Collection<Pair<EndpointPair<Transaction<KeyType, ValueType>>, Collection<Edge<KeyType>>>>
    cycleEdgesFromNodes(KnownGraph<KeyType, ValueType> graph,
                        List<Transaction<KeyType, ValueType>> cycleNodes) {
        var result = new ArrayList<Pair<EndpointPair<Transaction<KeyType, ValueType>>, Collection<Edge<KeyType>>>>();
        for (int i = 0; i + 1 < cycleNodes.size(); i++) {
            var from = cycleNodes.get(i);
            var to = cycleNodes.get(i + 1);
            var edges = new ArrayList<Edge<KeyType>>();
            edges.addAll(graph.getKnownGraphA().edgeValue(from, to).orElse(List.of()));
            edges.addAll(graph.getKnownGraphB().edgeValue(from, to).orElse(List.of()));
            result.add(Pair.of(EndpointPair.ordered(from, to), edges));
        }
        return result;
    }

}
