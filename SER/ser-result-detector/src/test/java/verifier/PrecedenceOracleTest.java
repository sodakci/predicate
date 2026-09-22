package verifier;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrecedenceOracleTest {
    @Test
    void notifiesOnlyChangedEndpointsOnceAfterClosureIsUpdated() {
        var oracle = new PrecedenceOracle<>(List.of("a", "b", "c", "d", "isolated"));
        oracle.add("a", "b");
        oracle.add("a", "c");
        oracle.add("c", "d");
        var notified = new ArrayList<String>();

        assertTrue(oracle.add("b", "c", node -> {
            assertTrue(oracle.before("b", "d"));
            assertTrue(oracle.wouldCycle("d", "b"));
            notified.add(node);
        }));
        assertEquals(Set.of("b", "c", "d"), new HashSet<>(notified));
        assertEquals(3, notified.size());

        notified.clear();
        assertTrue(oracle.add("b", "d", notified::add));
        assertFalse(oracle.add("d", "b", notified::add));
        assertFalse(oracle.add("b", "b", notified::add));
        assertTrue(notified.isEmpty());
        assertEquals(6, oracle.relationCount());
    }

    @Test
    void changedEndpointsAndClosureMatchIndependentGraphTraversal() {
        var nodes = new ArrayList<Integer>();
        for (int i = 0; i < 70; i++) {
            nodes.add(i);
        }
        var oracle = new PrecedenceOracle<>(nodes);
        var edges = new boolean[nodes.size()][nodes.size()];
        var random = new Random(70923L);
        for (int step = 0; step < 160; step++) {
            int from = random.nextInt(nodes.size());
            int to = random.nextInt(nodes.size());
            var before = reachableByTraversal(edges);
            boolean accepted = from != to && !before[to][from];
            if (accepted) {
                edges[from][to] = true;
            }
            var after = reachableByTraversal(edges);
            var expected = new HashSet<Integer>();
            var notified = new ArrayList<Integer>();
            assertEquals(accepted, oracle.add(from, to, notified::add));
            for (int source : nodes) {
                for (int target : nodes) {
                    assertEquals(after[source][target], oracle.before(source, target));
                    if (after[source][target] && !before[source][target]) {
                        expected.add(source);
                        expected.add(target);
                    }
                }
            }
            assertEquals(expected, new HashSet<>(notified));
            assertEquals(expected.size(), notified.size());
        }
    }

    private static boolean[][] reachableByTraversal(boolean[][] edges) {
        var reachable = new boolean[edges.length][edges.length];
        for (int source = 0; source < edges.length; source++) {
            var pending = new ArrayDeque<Integer>();
            pending.add(source);
            while (!pending.isEmpty()) {
                int node = pending.removeFirst();
                for (int target = 0; target < edges.length; target++) {
                    if (edges[node][target] && !reachable[source][target]) {
                        reachable[source][target] = true;
                        pending.addLast(target);
                    }
                }
            }
        }
        return reachable;
    }

    @Test
    void exposesSharedIncrementalPrecedenceQueries() {
        var oracle = new PrecedenceOracle<>(List.of("a", "b", "c"));

        assertTrue(oracle.add("a", "b"));
        assertTrue(oracle.add("b", "c"));

        assertTrue(oracle.before("a", "c"));
        assertEquals(Set.of("b", "c"), oracle.successor("a"));
        assertTrue(oracle.before("b", "c"));
        assertTrue(oracle.wouldCycle("c", "a"));
        assertFalse(oracle.add("c", "a"));
    }

    @Test
    void detectsCycleCreatedOnlyByACombinationOfNewRelations() {
        var oracle = new PrecedenceOracle<>(List.of("a", "b", "c"));
        oracle.add("a", "b");

        assertFalse(oracle.wouldCycle("b", "c"));
        assertFalse(oracle.wouldCycle("c", "a"));
        assertTrue(oracle.wouldCycle(List.of(
                new PrecedenceOracle.Relation<>("b", "c"),
                new PrecedenceOracle.Relation<>("c", "a"))));
    }
}
