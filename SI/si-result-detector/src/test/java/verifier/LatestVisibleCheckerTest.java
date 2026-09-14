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

        var validity = new LatestVisibleChecker<String, Integer>().check(
                reader, "x", List.of(firstWrite, latestWrite),
                new LatestVisibleChecker.SnapshotOrder<String, Integer>() {
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
                });

        assertSame(Lit.False, validity.get(0).latest);
        assertSame(Lit.True, validity.get(1).latest);
    }
}
