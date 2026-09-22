package verifier;

import graph.KnownGraph;
import history.History;
import history.Transaction;
import monosat.Lit;
import monosat.Solver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LatestVisibleCheckerTest {
    @Test
    void repeatedFrontierReusesFormulasWithoutAllocatingSatVariables() {
        var history = new History<String, Integer>();
        var writes = writes(history, 2);
        var reader = history.addTransaction(history.addSession(3L), 3L);
        try (var solver = new Solver()) {
            var firstVisible = new Lit(solver);
            var secondVisible = new Lit(solver);
            var firstBeforeSecond = new Lit(solver);
            var order = new LatestVisibleChecker.SerializationOrder<String, Integer>() {
                @Override
                public Lit beforeReader(String key, KnownGraph.WriteRef<String, Integer> writer,
                        Transaction<String, Integer> targetReader) {
                    return writer == writes.get(0) ? firstVisible : secondVisible;
                }

                @Override
                public Lit beforeWriter(String key, KnownGraph.WriteRef<String, Integer> left,
                        KnownGraph.WriteRef<String, Integer> right) {
                    return left == writes.get(0) ? firstBeforeSecond : firstBeforeSecond.not();
                }
            };
            var checker = new LatestVisibleChecker<String, Integer>();
            var first = checker.check(reader, "x", writes, order);
            int variables = solver.nVars();
            var repeated = checker.check(reader, "x", List.of(writes.get(1), writes.get(0)), order);

            assertEquals(variables, solver.nVars(), "重复 frontier 不应新增 SAT 变量");
            assertSame(first.get(0).valid, repeated.get(1).valid);
            assertSame(first.get(1).valid, repeated.get(0).valid);
            assertTrue(solver.solve(List.of(firstVisible, secondVisible,
                    firstBeforeSecond, repeated.get(0).valid)));
            assertFalse(solver.solve(List.of(firstVisible, secondVisible,
                    firstBeforeSecond, repeated.get(1).valid)));
        }
    }

    @Test
    void latestConjunctionUsesOneGateAndPreservesEveryTruthAssignment() {
        var history = new History<String, Integer>();
        var writes = writes(history, 4);
        var reader = history.addTransaction(history.addSession(5L), 5L);
        try (var solver = new Solver()) {
            var inputs = List.of(new Lit(solver), new Lit(solver),
                    new Lit(solver), new Lit(solver));
            var order = new LatestVisibleChecker.SerializationOrder<String, Integer>() {
                @Override
                public Lit beforeReader(String key, KnownGraph.WriteRef<String, Integer> writer,
                        Transaction<String, Integer> targetReader) {
                    return writer == writes.get(0) ? inputs.get(0) : Lit.True;
                }

                @Override
                public Lit beforeWriter(String key, KnownGraph.WriteRef<String, Integer> left,
                        KnownGraph.WriteRef<String, Integer> right) {
                    return left == writes.get(0) ? inputs.get(writes.indexOf(right)) : Lit.False;
                }
            };
            var checker = new LatestVisibleChecker<String, Integer>();
            int variables = solver.nVars();
            var validity = checker.check(reader, "x", writes, order);
            assertEquals(1, solver.nVars() - variables, "多输入 latest 合取只需一个辅助变量");
            var reversed = new ArrayList<>(writes);
            Collections.reverse(reversed);
            var repeated = checker.check(reader, "x", reversed, order);
            assertEquals(1, solver.nVars() - variables, "调整候选顺序后仍应共享公式");
            assertSame(validity.get(0).valid, repeated.get(3).valid);

            for (int mask = 0; mask < 16; mask++) {
                var assumptions = new ArrayList<Lit>();
                for (int index = 0; index < inputs.size(); index++) {
                    assumptions.add((mask & (1 << index)) != 0
                            ? inputs.get(index) : inputs.get(index).not());
                }
                var expected = mask == 1 ? validity.get(0).valid : validity.get(0).valid.not();
                assumptions.add(expected);
                assertTrue(solver.solve(assumptions), "赋值 " + mask);
                assumptions.set(assumptions.size() - 1, expected.not());
                assertFalse(solver.solve(assumptions), "相反结论应不可满足，赋值 " + mask);
            }
        }
    }

    @Test
    void returnsOnlyTheSerializationLatestVisibleWriterAsValid() {
        var history = new History<String, Integer>();
        var firstWriter = history.addTransaction(history.addSession(1L), 1L);
        var latestWriter = history.addTransaction(history.addSession(2L), 2L);
        var reader = history.addTransaction(history.addSession(3L), 3L);
        var firstWrite = new KnownGraph.WriteRef<>(
                firstWriter, history.addWriteEvent(firstWriter, "x", 1, 101L), 0, 101L);
        var latestWrite = new KnownGraph.WriteRef<>(
                latestWriter, history.addWriteEvent(latestWriter, "x", 2, 102L), 0, 102L);

        var validity = new LatestVisibleChecker<String, Integer>().check(
                reader, "x", List.of(firstWrite, latestWrite),
                new LatestVisibleChecker.SerializationOrder<String, Integer>() {
                    @Override
                    public Lit beforeReader(String key,
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
                });

        assertSame(Lit.False, validity.get(0).valid);
        assertSame(Lit.True, validity.get(1).valid);
    }

    private static List<KnownGraph.WriteRef<String, Integer>> writes(
            History<String, Integer> history, int count) {
        var writes = new ArrayList<KnownGraph.WriteRef<String, Integer>>();
        for (int index = 1; index <= count; index++) {
            var writer = history.addTransaction(history.addSession(index), index);
            writes.add(new KnownGraph.WriteRef<>(writer,
                    history.addWriteEvent(writer, "x", index, 100L + index), 0, 100L + index));
        }
        return writes;
    }
}
