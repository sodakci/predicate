package verifier;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrecedenceOracleTest {
    @Test
    void exposesSharedIncrementalPrecedenceQueries() {
        var oracle = new PrecedenceOracle<>(List.of("a", "b", "c"));

        assertTrue(oracle.add("a", "b"));
        assertTrue(oracle.add("b", "c"));

        assertTrue(oracle.before("a", "c"));
        assertEquals(Set.of("b", "c"), oracle.successor("a"));
        assertEquals(Set.of("a", "b"), oracle.predecessor("c"));
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
