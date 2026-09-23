package verifier;

import graph.KnownGraph;
import history.History;
import history.Transaction;
import monosat.Lit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;

class LatestVisibleCheckerTest {
    @Test
    void latestCombinesSiVisibilityWithPerKeyWriterOrder() {
        var history = new History<String, Integer>();
        var firstWriter = history.addTransaction(history.addSession(1L), 1L);
        var latestWriter = history.addTransaction(history.addSession(2L), 2L);
        var reader = history.addTransaction(history.addSession(3L), 3L);
        var firstWrite = new KnownGraph.WriteRef<>(
                firstWriter, history.addWriteEvent(firstWriter, "x", 1, 101L), 0, 101L);
        var latestWrite = new KnownGraph.WriteRef<>(
                latestWriter, history.addWriteEvent(latestWriter, "x", 2, 102L), 0, 102L);

        var order = new LatestVisibleChecker.SnapshotOrder<String, Integer>() {
                    @Override
                    public Lit visibleToReader(String key,
                            KnownGraph.WriteRef<String, Integer> writer,
                            Transaction<String, Integer> candidateReader) {
                        return Lit.True;
                    }

                    @Override
                    public Lit beforeWriter(String key,
                            KnownGraph.WriteRef<String, Integer> left,
                            KnownGraph.WriteRef<String, Integer> right) {
                        return left == firstWrite && right == latestWrite
                                ? Lit.True : Lit.False;
                    }
                };
        var checker = new LatestVisibleChecker<String, Integer>();
        var validity = checker.check(reader, "x", List.of(firstWrite, latestWrite),
                List.of(firstWrite, latestWrite), order);
        var pruned = checker.check(reader, "x", List.of(firstWrite),
                List.of(firstWrite, latestWrite), order);
        org.junit.jupiter.api.Assertions.assertEquals(1, pruned.size());
        assertSame(Lit.False, pruned.get(0).latest,
                "被来源剪枝排除的竞争写仍须排除旧 latest");

        assertSame(Lit.False, validity.get(0).latest);
        assertSame(Lit.True, validity.get(1).latest);
    }
    @Test
    void equivalentFrontiersReuseFormulasAcrossWriterEnumerationOrder() {
        try (var solver = new monosat.Solver()) {
            var history = new History<String, Integer>();
            var reader = history.addTransaction(history.addSession(99L), 99L);
            var writes = new java.util.ArrayList<KnownGraph.WriteRef<String, Integer>>();
            for (int i = 1; i <= 4; i++) {
                var txn = history.addTransaction(history.addSession(i), i);
                writes.add(new KnownGraph.WriteRef<>(txn,
                        history.addWriteEvent(txn, "x", i, (long) i), 0, (long) i));
            }
            var visibility = new java.util.IdentityHashMap<KnownGraph.WriteRef<String, Integer>, Lit>();
            var ordering = new java.util.IdentityHashMap<KnownGraph.WriteRef<String, Integer>, Lit>();
            for (var write : writes) {
                visibility.put(write, new Lit(solver));
                ordering.put(write, new Lit(solver));
            }
            var order = new LatestVisibleChecker.SnapshotOrder<String, Integer>() {
                public Lit visibleToReader(String key, KnownGraph.WriteRef<String, Integer> writer,
                        Transaction<String, Integer> txn) {
                    return visibility.get(writer);
                }
                public Lit beforeWriter(String key, KnownGraph.WriteRef<String, Integer> left,
                        KnownGraph.WriteRef<String, Integer> right) {
                    return ordering.get(right);
                }
            };
            var checker = new LatestVisibleChecker<String, Integer>();
            var first = checker.check(reader, "x", List.of(writes.get(0)), writes, order).get(0);
            int variables = solver.nVars();
            var reversed = new java.util.ArrayList<>(writes);
            java.util.Collections.reverse(reversed);
            var second = checker.check(reader, "x", List.of(writes.get(0)), reversed, order).get(0);
            org.junit.jupiter.api.Assertions.assertEquals(variables, solver.nVars(),
                    "相同条件换序枚举不应再次创建合取变量");
            assertSame(first.latest, second.latest);
            var sourceVisible = visibility.get(writes.get(0));
            var laterVisible = visibility.get(writes.get(1));
            var later = ordering.get(writes.get(1));
            org.junit.jupiter.api.Assertions.assertFalse(solver.solve(
                    List.of(first.latest, sourceVisible, laterVisible, later)));
            org.junit.jupiter.api.Assertions.assertFalse(solver.solve(
                    List.of(first.latest, sourceVisible.not())));
            org.junit.jupiter.api.Assertions.assertTrue(solver.solve(List.of(first.latest,
                    sourceVisible, laterVisible.not(), visibility.get(writes.get(2)).not(),
                    visibility.get(writes.get(3)).not())));
        }
    }

}
