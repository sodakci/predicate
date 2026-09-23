package verifier;

import graph.KnownGraph;
import history.Transaction;
import monosat.Lit;
import monosat.Logic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/** Computes each candidate writer's latest-visible validity. */
final class LatestVisibleChecker<KeyType, ValueType> {
    // 缓存仅归属当前求解器的 checker，不跨 native solver 共享字面量。
    private final Map<BinaryConjunction, Lit> binaryConjunctions = new HashMap<>();
    private final Map<List<Lit>, Lit> naryConjunctions = new HashMap<>();

    interface SnapshotOrder<KeyType, ValueType> {
        Lit visibleToReader(
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
        final Lit latest;

        private LatestWriterValidity(
                KnownGraph.WriteRef<KeyType, ValueType> writer,
                Lit visible,
                Lit latest) {
            this.writer = writer;
            this.visible = visible;
            this.latest = latest;
        }
    }

    List<LatestWriterValidity<KeyType, ValueType>> check(
            Transaction<KeyType, ValueType> reader,
            KeyType key,
            Collection<KnownGraph.WriteRef<KeyType, ValueType>> sourceCandidates,
            Collection<KnownGraph.WriteRef<KeyType, ValueType>> allExternalWrites,
            SnapshotOrder<KeyType, ValueType> snapshotOrder) {
        var visible = new IdentityHashMap<KnownGraph.WriteRef<KeyType, ValueType>, Lit>();
        for (var write : allExternalWrites) {
            visible.put(write, snapshotOrder.visibleToReader(key, write, reader));
        }
        var result = new ArrayList<LatestWriterValidity<KeyType, ValueType>>(sourceCandidates.size());
        for (var candidate : sourceCandidates) {
            var latest = visible.get(candidate);
            if (latest == null) {
                throw new IllegalArgumentException("来源候选不在完整外部最终写域中");
            }
            var terms = new ArrayList<Lit>(allExternalWrites.size());
            terms.add(latest);
            for (var other : allExternalWrites) {
                if (candidate == other) {
                    continue;
                }
                var laterVisible = and(visible.get(other),
                        snapshotOrder.beforeWriter(key, candidate, other));
                terms.add(Logic.not(laterVisible));
            }
            result.add(new LatestWriterValidity<>(candidate, visible.get(candidate), and(terms)));
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
