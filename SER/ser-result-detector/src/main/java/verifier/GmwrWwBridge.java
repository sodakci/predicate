package verifier;

import graph.Edge;
import graph.EdgeType;
import graph.KnownGraph;
import history.Transaction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

/** One batch GMWR-to-WW propagation epoch over residual baseline constraints. */
final class GmwrWwBridge<KeyType, ValueType> {
    private final PrecedenceOracle<Transaction<KeyType, ValueType>> precedence;

    GmwrWwBridge(PrecedenceOracle<Transaction<KeyType, ValueType>> precedence) {
        this.precedence = Objects.requireNonNull(precedence, "precedence");
    }

    PrecedenceOracle<Transaction<KeyType, ValueType>> precedenceOracle() {
        return precedence;
    }

    Result scan(
            KnownGraph<KeyType, ValueType> graph,
            Collection<SERConstraint<KeyType, ValueType>> residualWw) {
        return scan(graph, residualWw, null, false);
    }

    Result scanAffected(
            KnownGraph<KeyType, ValueType> graph,
            Collection<SERConstraint<KeyType, ValueType>> residualWw,
            Collection<Transaction<KeyType, ValueType>> affectedTxns,
            boolean verifyAgainstFullScan) {
        return scan(graph, residualWw, affectedTxns, verifyAgainstFullScan);
    }

    private Result scan(
            KnownGraph<KeyType, ValueType> graph,
            Collection<SERConstraint<KeyType, ValueType>> residualWw,
            Collection<Transaction<KeyType, ValueType>> affectedTxns,
            boolean verifyAgainstFullScan) {
        var candidates = affectedTxns == null || affectedTxns.isEmpty()
                ? residualWw
                : constraintsTouching(residualWw, affectedTxns);
        var incremental = collectForced(candidates, precedence);
        if (incremental.conflict) {
            return incremental.toResult();
        }
        if (verifyAgainstFullScan && affectedTxns != null && !affectedTxns.isEmpty()) {
            var full = collectForced(residualWw, precedence);
            if (full.conflict != incremental.conflict
                    || full.forced.size() != incremental.forced.size()) {
                throw new IllegalStateException(
                        "incremental GMWR-WW scan missed a forced WW/RW branch");
            }
        }

        var forcedConstraints = Collections.newSetFromMap(
                new IdentityHashMap<SERConstraint<KeyType, ValueType>, Boolean>());
        for (var branch : incremental.forced) {
            commit(graph, branch.edges);
            forcedConstraints.add(branch.constraint);
        }
        residualWw.removeIf(forcedConstraints::contains);
        return incremental.toResult();
    }

    private static <KeyType, ValueType> ScanState<KeyType, ValueType> collectForced(
            Collection<SERConstraint<KeyType, ValueType>> candidates,
            PrecedenceOracle<Transaction<KeyType, ValueType>> precedence) {
        var forced = new ArrayList<ForcedBranch<KeyType, ValueType>>();
        int scanned = 0;
        for (var constraint : candidates) {
            scanned++;
            boolean firstValid = !precedence.wouldCycle(
                    precedenceRelations(constraint.getEdges1()));
            boolean secondValid = !precedence.wouldCycle(
                    precedenceRelations(constraint.getEdges2()));
            if (!firstValid && !secondValid) {
                return ScanState.conflict(scanned);
            }
            if (!firstValid) {
                forced.add(new ForcedBranch<>(constraint, constraint.getEdges2()));
            } else if (!secondValid) {
                forced.add(new ForcedBranch<>(constraint, constraint.getEdges1()));
            }
        }
        return new ScanState<>(scanned, forced, false);
    }

    private static <KeyType, ValueType> Collection<PrecedenceOracle.Relation<
            Transaction<KeyType, ValueType>>> precedenceRelations(
                    Collection<SEREdge<KeyType, ValueType>> edges) {
        var relations = new ArrayList<PrecedenceOracle.Relation<
                Transaction<KeyType, ValueType>>>();
        for (var edge : edges) {
            if (edge.getType() == EdgeType.WW || edge.getType() == EdgeType.RW) {
                relations.add(new PrecedenceOracle.Relation<>(
                        edge.getFrom(), edge.getTo()));
            }
        }
        return relations;
    }

    private static <KeyType, ValueType> Collection<SERConstraint<KeyType, ValueType>>
            constraintsTouching(
                    Collection<SERConstraint<KeyType, ValueType>> residualWw,
                    Collection<Transaction<KeyType, ValueType>> affectedTxns) {
        var affected = affectedTxns instanceof Set
                ? (Set<Transaction<KeyType, ValueType>>) affectedTxns
                : new HashSet<>(affectedTxns);
        var touching = new ArrayList<SERConstraint<KeyType, ValueType>>();
        for (var constraint : residualWw) {
            if (touches(constraint, affected)) {
                touching.add(constraint);
            }
        }
        return touching;
    }

    private static <KeyType, ValueType> boolean touches(
            SERConstraint<KeyType, ValueType> constraint,
            Set<Transaction<KeyType, ValueType>> affected) {
        if (affected.contains(constraint.getWriteTransaction1())
                || affected.contains(constraint.getWriteTransaction2())) {
            return true;
        }
        for (var edge : constraint.getEdges1()) {
            if (affected.contains(edge.getFrom()) || affected.contains(edge.getTo())) {
                return true;
            }
        }
        for (var edge : constraint.getEdges2()) {
            if (affected.contains(edge.getFrom()) || affected.contains(edge.getTo())) {
                return true;
            }
        }
        return false;
    }

    private static <KeyType, ValueType> void commit(
            KnownGraph<KeyType, ValueType> graph,
            Collection<SEREdge<KeyType, ValueType>> edges) {
        for (var edge : edges) {
            if (edge.getType() == EdgeType.WW || edge.getType() == EdgeType.RW) {
                graph.putEdge(edge.getFrom(), edge.getTo(),
                        new Edge<KeyType>(edge.getType(), edge.getKey()));
            }
        }
    }

    static final class Result {
        final int scannedConstraints;
        final int forcedConstraints;
        final boolean conflict;

        Result(int scannedConstraints, int forcedConstraints, boolean conflict) {
            this.scannedConstraints = scannedConstraints;
            this.forcedConstraints = forcedConstraints;
            this.conflict = conflict;
        }
    }

    private static final class ScanState<KeyType, ValueType> {
        final int scanned;
        final ArrayList<ForcedBranch<KeyType, ValueType>> forced;
        final boolean conflict;

        ScanState(int scanned,
                  ArrayList<ForcedBranch<KeyType, ValueType>> forced,
                  boolean conflict) {
            this.scanned = scanned;
            this.forced = forced;
            this.conflict = conflict;
        }

        static <KeyType, ValueType> ScanState<KeyType, ValueType> conflict(int scanned) {
            return new ScanState<>(scanned, new ArrayList<>(), true);
        }

        Result toResult() {
            return new Result(scanned, forced.size(), conflict);
        }
    }

    private static final class ForcedBranch<KeyType, ValueType> {
        final SERConstraint<KeyType, ValueType> constraint;
        final Collection<SEREdge<KeyType, ValueType>> edges;

        ForcedBranch(SERConstraint<KeyType, ValueType> constraint,
                     Collection<SEREdge<KeyType, ValueType>> edges) {
            this.constraint = constraint;
            this.edges = Collections.unmodifiableCollection(edges);
        }
    }
}
