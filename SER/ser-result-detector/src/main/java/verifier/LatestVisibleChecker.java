package verifier;

import graph.KnownGraph;
import history.Transaction;
import monosat.Lit;
import monosat.Logic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Computes each candidate writer's latest-visible validity. */
final class LatestVisibleChecker<KeyType, ValueType> {
    interface SerializationOrder<KeyType, ValueType> {
        Lit beforeReader(
                KeyType key,
                KnownGraph.WriteRef<KeyType, ValueType> writer,
                Transaction<KeyType, ValueType> reader);

        Lit beforeWriter(
                KeyType key,
                KnownGraph.WriteRef<KeyType, ValueType> left,
                KnownGraph.WriteRef<KeyType, ValueType> right);
    }

    static final class LatestWriterValidity<KeyType, ValueType> {
        final KnownGraph.WriteRef<KeyType, ValueType> writer;
        final Lit visible;
        final Lit valid;

        private LatestWriterValidity(
                KnownGraph.WriteRef<KeyType, ValueType> writer,
                Lit visible,
                Lit valid) {
            this.writer = writer;
            this.visible = visible;
            this.valid = valid;
        }
    }

    List<LatestWriterValidity<KeyType, ValueType>> check(
            Transaction<KeyType, ValueType> reader,
            KeyType key,
            Collection<KnownGraph.WriteRef<KeyType, ValueType>> candidateWriters,
            SerializationOrder<KeyType, ValueType> serializationOrder) {
        var candidates = List.copyOf(candidateWriters);
        var visible = new ArrayList<Lit>(candidates.size());
        for (var candidate : candidates) {
            visible.add(serializationOrder.beforeReader(key, candidate, reader));
        }

        var result = new ArrayList<LatestWriterValidity<KeyType, ValueType>>(
                candidates.size());
        for (int candidateIndex = 0; candidateIndex < candidates.size(); candidateIndex++) {
            var candidate = candidates.get(candidateIndex);
            var latest = visible.get(candidateIndex);
            for (int otherIndex = 0; otherIndex < candidates.size(); otherIndex++) {
                if (candidateIndex == otherIndex) {
                    continue;
                }
                var laterVisible = and(
                        serializationOrder.beforeWriter(
                                key, candidate, candidates.get(otherIndex)),
                        visible.get(otherIndex));
                latest = and(latest, Logic.not(laterVisible));
            }
            result.add(new LatestWriterValidity<>(candidate, visible.get(candidateIndex), latest));
        }
        return List.copyOf(result);
    }

    private static Lit and(Lit left, Lit right) {
        if (left == Lit.False || right == Lit.False) {
            return Lit.False;
        }
        if (left == Lit.True) {
            return right;
        }
        if (right == Lit.True) {
            return left;
        }
        if (left == right) {
            return left;
        }
        return Logic.and(left, right);
    }
}
