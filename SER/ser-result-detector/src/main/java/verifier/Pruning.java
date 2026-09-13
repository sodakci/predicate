package verifier;

import graph.KnownGraph;
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

public class Pruning<KeyType, ValueType> {
    @Getter
    @Setter
    private static boolean enablePruning = true;

    @Getter
    @Setter
    private static double stopThreshold = 0.01;

    private static Pair<?, ?> lastConflicts = emptyConflicts();

    private final PrecedenceOracle<Transaction<KeyType, ValueType>> precedence;
    private final boolean verbose;

    Pruning(PrecedenceOracle<Transaction<KeyType, ValueType>> precedence) {
        this(precedence, true);
    }

    Pruning(PrecedenceOracle<Transaction<KeyType, ValueType>> precedence,
            boolean verbose) {
        this.precedence = Objects.requireNonNull(precedence, "precedence");
        this.verbose = verbose;
    }

    PrecedenceOracle<Transaction<KeyType, ValueType>> precedenceOracle() {
        return precedence;
    }

    boolean pruneConstraints(KnownGraph<KeyType, ValueType> knownGraph,
            Collection<SERConstraint<KeyType, ValueType>> constraints) {
        if (!enablePruning) {
            return false;
        }

        lastConflicts = emptyConflicts();
        if (constraints.isEmpty()) {
            return false;
        }

        var profiler = Profiler.getInstance();
        profiler.startTick("SER_PRUNE");
        addKnownEdges(precedence, knownGraph.getKnownGraphA());
        addKnownEdges(precedence, knownGraph.getKnownGraphB());

        int rounds = 1, solvedConstraints = 0, totalConstraints = constraints.size();
        boolean hasCycle = false;
        while (!hasCycle) {
            if (verbose) {
                System.err.printf("Pruning round %d\n", rounds);
            }
            var result = pruneConstraintsWithPostChecking(knownGraph, constraints);

            hasCycle = result.getRight();
            solvedConstraints += result.getLeft();

            int remainingConstraints = constraints.size();
            if (remainingConstraints == 0
                    || result.getLeft() <= stopThreshold * Math.max(1, remainingConstraints)) {
                break;
            }
            rounds++;
        }

        profiler.endTick("SER_PRUNE");
        if (verbose) {
            System.err.printf("Pruned %d rounds, solved %d constraints\n" + "After prune: graphA: %d, graphB: %d\n", rounds,
                    solvedConstraints, knownGraph.getKnownGraphA().edges().size(),
                    knownGraph.getKnownGraphB().edges().size());
        }
        return hasCycle;
    }

    private Pair<Integer, Boolean> pruneConstraintsWithPostChecking(
            KnownGraph<KeyType, ValueType> knownGraph,
            Collection<SERConstraint<KeyType, ValueType>> constraints) {
        var profiler = Profiler.getInstance();

        var solvedConstraints = new ArrayList<SERConstraint<KeyType, ValueType>>();

        profiler.startTick("SER_PRUNE_POST_CHECK");
        int checked = 0;
        int total = constraints.size();
        var progress = verbose ? new PostCheckProgress(total) : null;
        if (progress != null) {
            progress.refresh(checked, solvedConstraints.size(), false);
        }
        if (progress != null && total == 0) {
            progress.refresh(checked, solvedConstraints.size(), true);
        }
        for (var c : constraints) {
            boolean okEither = !wouldCycle(precedence, c.getEdges1());
            boolean okOr = !wouldCycle(precedence, c.getEdges2());
            checked++;

            if (!okEither && !okOr) {
                lastConflicts = Pair.of(Collections.emptyList(), List.of(c));
                if (progress != null) {
                    progress.refresh(checked, solvedConstraints.size(), true);
                }
                profiler.endTick("SER_PRUNE_POST_CHECK");
                return Pair.of(0, true);
            }

            if (!okEither) {
                addAll(precedence, c.getEdges2());
                addToKnownGraph(knownGraph, c.getEdges2());
                solvedConstraints.add(c);
            } else if (!okOr) {
                addAll(precedence, c.getEdges1());
                addToKnownGraph(knownGraph, c.getEdges1());
                solvedConstraints.add(c);
            }

            if (progress != null) {
                progress.refresh(checked, solvedConstraints.size(), checked == total);
            }
        }
        profiler.endTick("SER_PRUNE_POST_CHECK");

        // constraints.removeAll(solvedConstraints);
        // java removeAll has performance bugs; do it manually
        solvedConstraints.forEach(constraints::remove);
        return Pair.of(solvedConstraints.size(), false);
    }

    private static final class PostCheckProgress {
        private static final int BAR_WIDTH = 15;

        private final int total;
        private final int refreshStep;

        private PostCheckProgress(int total) {
            this.total = total;
            this.refreshStep = Math.max(1, Math.min(100, Math.max(1, total / 100)));
        }

        private void refresh(int checked, int solved, boolean done) {
            if (!done && checked != 0 && checked % refreshStep != 0) {
                return;
            }

            var line = format(checked, solved);
            System.err.print("\r" + line);
            if (done) {
                System.err.println();
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
            return String.format("Pruning post-check [%s] %3d%% %d/%d solved=%d",
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

    private static <KeyType, ValueType> void addKnownEdges(
            PrecedenceOracle<Transaction<KeyType, ValueType>> oracle,
            ValueGraph<Transaction<KeyType, ValueType>, Collection<Edge<KeyType>>> graph) {
        for (var endpoint : graph.edges()) {
            var edges = graph.edgeValue(endpoint).orElse(Collections.emptyList());
            if (edges.stream().anyMatch(edge -> isNonPredicateEdge(edge.getType()))) {
                oracle.add(endpoint.source(), endpoint.target());
            }
        }
    }

    private static <KeyType, ValueType> boolean wouldCycle(
            PrecedenceOracle<Transaction<KeyType, ValueType>> oracle,
            Collection<SEREdge<KeyType, ValueType>> edges) {
        var relations = new ArrayList<PrecedenceOracle.Relation<
                Transaction<KeyType, ValueType>>>();
        for (var edge : edges) {
            if (isPruningEdge(edge.getType())) {
                relations.add(new PrecedenceOracle.Relation<>(
                        edge.getFrom(), edge.getTo()));
            }
        }
        return oracle.wouldCycle(relations);
    }

    private static <KeyType, ValueType> void addAll(
            PrecedenceOracle<Transaction<KeyType, ValueType>> oracle,
            Collection<SEREdge<KeyType, ValueType>> edges) {
        for (var edge : edges) {
            if (isPruningEdge(edge.getType())) {
                oracle.add(edge.getFrom(), edge.getTo());
            }
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
