package verifier;

import graph.Edge;
import graph.EdgeType;
import graph.KnownGraph;
import history.History;
import history.Transaction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SISolverInducedDifferentialTest {
    private static final int TRANSACTIONS = 3;
    private static final int CASES = 160;

    @Test
    void solverAndReachabilityReductionMatchBruteForceInducedGraphOracle() {
        var random = new Random(0x51AD7A);
        for (int caseId = 0; caseId < CASES; caseId++) {
            var specification = CaseSpecification.random(random);
            boolean expected = specification.bruteForceSatisfiable();
            for (boolean reduction : List.of(false, true)) {
                assertEquals(expected, solve(specification, reduction),
                        () -> "case=" + specification.id
                                + " reduction=" + reduction
                                + " A=" + specification.knownA
                                + " B=" + specification.knownB
                                + " forward=" + specification.extraForward
                                + " backward=" + specification.extraBackward);
            }
        }
    }

    private static boolean solve(
            CaseSpecification specification,
            boolean reduction) {
        var instance = specification.instantiate();
        var oracle = new SIReachabilityOracle<String, Integer>(instance.graph);
        if (reduction) {
            while (true) {
                var result = SIReachabilityPruner.reduceOnce(
                        instance.graph, instance.constraints, oracle);
                if (result.rejected) {
                    return false;
                }
                if (instance.constraints.isEmpty()
                        || result.forced <= 0.01 * Math.max(1, instance.constraints.size())) {
                    break;
                }
            }
        }
        return SISolverTestSupport.solveStatus(
                instance.history, instance.graph, instance.constraints,
                false, false, SIVerifier.SolverSettings.defaults(), oracle) == SolveStatus.SAT;
    }

    private static final class CaseSpecification {
        private static int nextId;

        private final int id;
        private final int knownA;
        private final int knownB;
        private final DirectedEdge extraForward;
        private final DirectedEdge extraBackward;

        private CaseSpecification(int knownA, int knownB,
                DirectedEdge extraForward, DirectedEdge extraBackward) {
            this.id = nextId++;
            this.knownA = knownA;
            this.knownB = knownB;
            this.extraForward = extraForward;
            this.extraBackward = extraBackward;
        }

        private static CaseSpecification random(Random random) {
            return new CaseSpecification(
                    random.nextInt(1 << 6),
                    random.nextInt(1 << 6),
                    random.nextBoolean() ? DirectedEdge.random(random) : null,
                    random.nextBoolean() ? DirectedEdge.random(random) : null);
        }

        private Instance instantiate() {
            var history = new History<String, Integer>();
            var txns = new ArrayList<Transaction<String, Integer>>();
            for (int i = 0; i < TRANSACTIONS; i++) {
                var txn = history.addTransaction(history.addSession(i + 1L), i + 1L);
                txn.setStatus(Transaction.TransactionStatus.COMMIT);
                txns.add(txn);
            }
            var graph = new KnownGraph<String, Integer>(history);
            addMask(graph, txns, knownA, EdgeType.WR);
            addMask(graph, txns, knownB, EdgeType.RW);
            var forward = new ArrayList<SIEdge<String, Integer>>();
            forward.add(new SIEdge<>(txns.get(0), txns.get(1), EdgeType.WW, "x"));
            append(forward, txns, extraForward);
            var backward = new ArrayList<SIEdge<String, Integer>>();
            backward.add(new SIEdge<>(txns.get(1), txns.get(0), EdgeType.WW, "x"));
            append(backward, txns, extraBackward);
            var constraints = new ArrayList<SIConstraint<String, Integer>>();
            constraints.add(new SIConstraint<>(
                    forward, backward, txns.get(0), txns.get(1), 0));
            return new Instance(history, graph, constraints);
        }

        private boolean bruteForceSatisfiable() {
            return branchSatisfiable(true) || branchSatisfiable(false);
        }

        private boolean branchSatisfiable(boolean forward) {
            var a = matrixFromMask(knownA);
            var b = matrixFromMask(knownB);
            if (forward) {
                a[0][1] = true;
                addExtra(a, b, extraForward);
            } else {
                a[1][0] = true;
                addExtra(a, b, extraBackward);
            }
            var induced = copy(a);
            for (int from = 0; from < TRANSACTIONS; from++) {
                for (int middle = 0; middle < TRANSACTIONS; middle++) {
                    if (!a[from][middle]) {
                        continue;
                    }
                    for (int to = 0; to < TRANSACTIONS; to++) {
                        induced[from][to] |= b[middle][to];
                    }
                }
            }
            for (int middle = 0; middle < TRANSACTIONS; middle++) {
                for (int from = 0; from < TRANSACTIONS; from++) {
                    if (!induced[from][middle]) {
                        continue;
                    }
                    for (int to = 0; to < TRANSACTIONS; to++) {
                        induced[from][to] |= induced[middle][to];
                    }
                }
            }
            for (int node = 0; node < TRANSACTIONS; node++) {
                if (induced[node][node]) {
                    return false;
                }
            }
            return true;
        }

        private static void addExtra(
                boolean[][] a, boolean[][] b, DirectedEdge edge) {
            if (edge == null) {
                return;
            }
            (edge.inA ? a : b)[edge.from][edge.to] = true;
        }

        private static void append(
                Collection<SIEdge<String, Integer>> edges,
                List<Transaction<String, Integer>> txns,
                DirectedEdge extra) {
            if (extra != null) {
                edges.add(new SIEdge<>(
                        txns.get(extra.from), txns.get(extra.to),
                        extra.inA ? EdgeType.WW : EdgeType.RW, "extra"));
            }
        }

        private static void addMask(
                KnownGraph<String, Integer> graph,
                List<Transaction<String, Integer>> txns,
                int mask,
                EdgeType type) {
            int bit = 0;
            for (int from = 0; from < TRANSACTIONS; from++) {
                for (int to = 0; to < TRANSACTIONS; to++) {
                    if (from == to) {
                        continue;
                    }
                    if ((mask & (1 << bit)) != 0) {
                        graph.putEdge(txns.get(from), txns.get(to),
                                new Edge<>(type, "known"));
                    }
                    bit++;
                }
            }
        }

        private static boolean[][] matrixFromMask(int mask) {
            var result = new boolean[TRANSACTIONS][TRANSACTIONS];
            int bit = 0;
            for (int from = 0; from < TRANSACTIONS; from++) {
                for (int to = 0; to < TRANSACTIONS; to++) {
                    if (from == to) {
                        continue;
                    }
                    result[from][to] = (mask & (1 << bit)) != 0;
                    bit++;
                }
            }
            return result;
        }

        private static boolean[][] copy(boolean[][] source) {
            var result = new boolean[source.length][source.length];
            for (int row = 0; row < source.length; row++) {
                System.arraycopy(source[row], 0, result[row], 0, source.length);
            }
            return result;
        }
    }

    private static final class DirectedEdge {
        private final int from;
        private final int to;
        private final boolean inA;

        private DirectedEdge(int from, int to, boolean inA) {
            this.from = from;
            this.to = to;
            this.inA = inA;
        }

        private static DirectedEdge random(Random random) {
            int from = random.nextInt(TRANSACTIONS);
            int to = random.nextInt(TRANSACTIONS - 1);
            if (to >= from) {
                to++;
            }
            // Solver-stage constraints carry the selected WW direction. The
            // corresponding ordinary RW edges are derived from WR + WW in a
            // later stage, so a synthetic branch edge must stay in A.
            return new DirectedEdge(from, to, true);
        }

        @Override
        public String toString() {
            return from + "->" + to + (inA ? "(A)" : "(B)");
        }
    }

    private static final class Instance {
        private final History<String, Integer> history;
        private final KnownGraph<String, Integer> graph;
        private final ArrayList<SIConstraint<String, Integer>> constraints;

        private Instance(History<String, Integer> history,
                KnownGraph<String, Integer> graph,
                ArrayList<SIConstraint<String, Integer>> constraints) {
            this.history = history;
            this.graph = graph;
            this.constraints = constraints;
        }
    }
}
