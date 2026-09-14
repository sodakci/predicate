package verifier;

import graph.KnownGraph;
import util.Profiler;
import graph.Edge;
import graph.EdgeType;

import java.util.*;

import org.apache.commons.lang3.tuple.Pair;

public class Pruning {
    private static final double STOP_THRESHOLD = 0.01;

    static <KeyType, ValueType> Optional<SIConstraint<KeyType, ValueType>> pruneConstraints(
            KnownGraph<KeyType, ValueType> knownGraph,
            Collection<SIConstraint<KeyType, ValueType>> constraints) {
        if (constraints.isEmpty()) {
            return Optional.empty();
        }

        var profiler = Profiler.getInstance();
        profiler.startTick("SI_PRUNE");

        int rounds = 1, solvedConstraints = 0;
        SIConstraint<KeyType, ValueType> conflict = null;
        while (conflict == null) {
            System.err.printf("Pruning round %d\n", rounds);
            var result = pruneConstraintsWithPostChecking(knownGraph, constraints);

            conflict = result.getRight();
            solvedConstraints += result.getLeft();

            int remainingConstraints = constraints.size();
            if (remainingConstraints == 0
                    || result.getLeft() <= STOP_THRESHOLD * Math.max(1, remainingConstraints)) {
                break;
            }
            rounds++;
        }

        profiler.endTick("SI_PRUNE");
        System.err.printf("Pruned %d rounds, solved %d constraints\n" + "After prune: graphA: %d, graphB: %d\n", rounds,
                solvedConstraints, knownGraph.getKnownGraphA().edges().size(),
                knownGraph.getKnownGraphB().edges().size());
        return Optional.ofNullable(conflict);
    }

    private static <KeyType, ValueType> Pair<Integer, SIConstraint<KeyType, ValueType>>
            pruneConstraintsWithPostChecking(
            KnownGraph<KeyType, ValueType> knownGraph,
            Collection<SIConstraint<KeyType, ValueType>> constraints) {
        var profiler = Profiler.getInstance();

        var solvedConstraints = new ArrayList<SIConstraint<KeyType, ValueType>>();

        profiler.startTick("SI_PRUNE_POST_CHECK");
        int checked = 0;
        int total = constraints.size();
        var oracle = new SIVerifier.InducedGraph.Oracle<KeyType, ValueType>(knownGraph);
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
                progress.refresh(checked, solvedConstraints.size(), true);
                profiler.endTick("SI_PRUNE_POST_CHECK");
                return Pair.of(0, c);
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
        profiler.endTick("SI_PRUNE_POST_CHECK");

        // constraints.removeAll(solvedConstraints);
        // java removeAll has performance bugs; do it manually
        solvedConstraints.forEach(constraints::remove);
        return Pair.of(solvedConstraints.size(), null);
    }

    private static final class PostCheckProgress {
        private static final int BAR_WIDTH = 15;

        private final int total;
        private final int refreshStep;

        private PostCheckProgress(int total) {
            this.total = total;
            this.refreshStep = Math.max(1,
                    Math.min(100, Math.max(1, total / 100)));
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
            Collection<SIEdge<KeyType, ValueType>> edges) {
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

}
