package verifier;

import graph.Edge;
import graph.KnownGraph;
import util.Profiler;

import java.util.ArrayList;
import java.util.Collection;

/** Always-on safe WW branch reduction using one shared SI oracle. */
final class SIReachabilityPruner {
    private SIReachabilityPruner() {
    }

    static final class Result<KeyType, ValueType> {
        final int forced;
        final boolean rejected;
        final SIConstraint<KeyType, ValueType> conflict;

        Result(int forced, boolean rejected, SIConstraint<KeyType, ValueType> conflict) {
            this.forced = forced;
            this.rejected = rejected;
            this.conflict = conflict;
        }
    }

    static <KeyType, ValueType> Result<KeyType, ValueType> reduceOnce(
            KnownGraph<KeyType, ValueType> graph,
            Collection<SIConstraint<KeyType, ValueType>> constraints,
            SIReachabilityOracle<KeyType, ValueType> oracle) {
        var profiler = Profiler.getInstance();
        profiler.startTick("SI_REACHABILITY_PRUNE");
        try {
            if (!oracle.isAcyclic()) {
                return new Result<>(0, true, null);
            }
            var solved = new ArrayList<SIConstraint<KeyType, ValueType>>();
            var batch = new ArrayList<SIEdge<KeyType, ValueType>>();
            for (var constraint : constraints) {
                boolean firstPossible = !oracle.hasWwBranchConflict(constraint.getEdges1());
                boolean secondPossible = !oracle.hasWwBranchConflict(constraint.getEdges2());
                if (!firstPossible && !secondPossible) {
                    return new Result<>(0, true, constraint);
                }
                if (firstPossible == secondPossible) {
                    continue;
                }
                var selected = firstPossible
                        ? constraint.getEdges1()
                        : constraint.getEdges2();
                batch.addAll(selected);
                solved.add(constraint);
            }
            for (var edge : batch) {
                graph.putEdge(edge.getFrom(), edge.getTo(),
                        new Edge<KeyType>(edge.getType(), edge.getKey()));
            }
            boolean acyclic = oracle.commitRound(batch);
            solved.forEach(constraints::remove);
            return new Result<>(solved.size(), !acyclic, null);
        } finally {
            profiler.endTick("SI_REACHABILITY_PRUNE");
        }
    }

}
