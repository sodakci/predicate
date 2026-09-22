package verifier;

import graph.KnownGraph;
import history.Transaction;
import monosat.Lit;
import monosat.Logic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Computes each candidate writer's latest-visible validity. */
final class LatestVisibleChecker<KeyType, ValueType> {
    // 缓存归属当前 checker，随 SERSolverAR 实例一起释放。
    private final Map<BinaryConjunction, Lit> binaryConjunctions = new HashMap<>();
    private final Map<List<Lit>, Lit> naryConjunctions = new HashMap<>();

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
            var terms = new ArrayList<Lit>(candidates.size());
            terms.add(visible.get(candidateIndex));
            for (int otherIndex = 0; otherIndex < candidates.size(); otherIndex++) {
                if (candidateIndex == otherIndex) {
                    continue;
                }
                var laterVisible = and(
                        serializationOrder.beforeWriter(
                                key, candidate, candidates.get(otherIndex)),
                        visible.get(otherIndex));
                terms.add(Logic.not(laterVisible));
            }
            var latest = and(terms);
            result.add(new LatestWriterValidity<>(candidate, visible.get(candidateIndex), latest));
        }
        return List.copyOf(result);
    }

    private Lit and(List<Lit> terms) {
        var filtered = new ArrayList<Lit>(terms.size());
        for (var term : terms) {
            if (term == Lit.False) {
                return Lit.False;
            }
            if (term != Lit.True) {
                filtered.add(term);
            }
        }
        if (filtered.isEmpty()) {
            return Lit.True;
        }
        filtered.sort(Comparator.comparingInt(Lit::toInt));
        int uniqueCount = 1;
        for (int index = 1; index < filtered.size(); index++) {
            var term = filtered.get(index);
            if (term != filtered.get(uniqueCount - 1)) {
                filtered.set(uniqueCount++, term);
            }
        }
        filtered.subList(uniqueCount, filtered.size()).clear();
        if (uniqueCount == 1) {
            return filtered.get(0);
        }
        if (uniqueCount == 2) {
            return and(filtered.get(0), filtered.get(1));
        }
        return naryConjunctions.computeIfAbsent(List.copyOf(filtered), Logic::and);
    }

    private Lit and(Lit left, Lit right) {
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
        var key = new BinaryConjunction(left, right);
        return binaryConjunctions.computeIfAbsent(key, ignored -> Logic.and(left, right));
    }

    private static final class BinaryConjunction {
        private final Lit first;
        private final Lit second;

        private BinaryConjunction(Lit left, Lit right) {
            boolean leftFirst = left.toInt() < right.toInt();
            first = leftFirst ? left : right;
            second = leftFirst ? right : left;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof BinaryConjunction)) {
                return false;
            }
            var conjunction = (BinaryConjunction) other;
            return first == conjunction.first && second == conjunction.second;
        }

        @Override
        public int hashCode() {
            return 31 * first.toInt() + second.toInt();
        }
    }
}
