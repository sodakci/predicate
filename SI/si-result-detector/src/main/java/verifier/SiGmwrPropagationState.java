package verifier;

import graph.EdgeType;
import graph.KnownGraph;
import history.Transaction;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 仅消费已经准备的逐 key 数据；活动传播状态不交给 SAT。 */
final class SiGmwrPropagationState<K, V> {
    enum FactKind { VIS, NOT_VIS, WW, PR_WR }

    static final class Fact<K, V> {
        final Transaction<K, V> from;
        final Transaction<K, V> to;
        final K key;
        final FactKind kind;
        final KnownGraph.PredicateObservation<K, V> observation;
        final String reason;

        Fact(Transaction<K, V> from, Transaction<K, V> to, K key, FactKind kind,
                KnownGraph.PredicateObservation<K, V> observation, String reason) {
            this.from = from;
            this.to = to;
            this.key = key;
            this.kind = kind;
            this.observation = observation;
            this.reason = reason;
        }

        @Override
        public String toString() {
            return kind + "(" + from + ", " + to + ") key=" + key + " " + reason;
        }
    }

    private static final class Item<K, V> {
        final KnownGraph.PredicateObservation<K, V> observation;
        final K key;
        final KnownGraph.WriteRef<K, V> bad;
        final List<KnownGraph.WriteRef<K, V>> repairs;
        boolean satisfied;

        Item(KnownGraph.PredicateObservation<K, V> observation,
                PredicatePruning.PreparedKey<K, V> key, KnownGraph.WriteRef<K, V> bad) {
            this.observation = observation;
            this.key = key.key;
            this.bad = bad;
            this.repairs = new ArrayList<>(key.goodWrites);
        }
    }

    private static final class Bundle<K, V> {
        final Transaction<K, V> reader;
        final Transaction<K, V> badWriter;
        final List<Item<K, V>> items = new ArrayList<>();
        Bundle(Transaction<K, V> reader, Transaction<K, V> badWriter) {
            this.reader = reader;
            this.badWriter = badWriter;
        }
    }

    private final SIReachabilityOracle<K, V> oracle;
    private final Map<Pair<Transaction<K, V>, Transaction<K, V>>, Bundle<K, V>> bundles =
            new LinkedHashMap<>();
    private final Set<Pair<Transaction<K, V>, Transaction<K, V>>> excludedVis = new LinkedHashSet<>();
    private final Set<List<Object>> factKeys = new LinkedHashSet<>();
    private final List<Fact<K, V>> facts = new ArrayList<>();
    private final List<PredicatePruning.ConflictReason<K, V>> conflicts = new ArrayList<>();
    private final Map<Transaction<K, V>, Set<Bundle<K, V>>> watchers =
            new LinkedHashMap<>();
    private final ArrayDeque<Bundle<K, V>> work = new ArrayDeque<>();
    private final Set<Bundle<K, V>> queued = new LinkedHashSet<>();
    private long initialItems;
    private long removedCandidates;
    private long reductionSteps;

    SiGmwrPropagationState(SIReachabilityOracle<K, V> oracle,
            Collection<PredicatePruning.PreparedObservation<K, V>> observations) {
        this.oracle = oracle;
        for (var prepared : observations) {
            for (var key : prepared.keys) {
                if (!key.rowLocal || key.internal || key.recordedSource != null) {
                    continue;
                }
                for (var bad : key.badWrites) {
                    var reader = prepared.observation.getTxn();
                    var bundle = bundles.computeIfAbsent(Pair.of(reader, bad.getTxn()),
                            ignored -> new Bundle<>(reader, bad.getTxn()));
                    bundle.items.add(new Item<>(prepared.observation, key, bad));
                    initialItems++;
                }
            }
        }
        for (var bundle : bundles.values()) {
            watch(bundle, bundle.badWriter, bundle.reader);
            for (var item : bundle.items) {
                for (var repair : item.repairs) {
                    watch(bundle, repair.getTxn(), bundle.badWriter);
                    watch(bundle, bundle.badWriter, repair.getTxn());
                    watch(bundle, repair.getTxn(), bundle.reader);
                }
            }
            enqueue(bundle);
        }
    }

    boolean propagate() {
        while (!work.isEmpty()) {
            var bundle = work.removeFirst();
            queued.remove(bundle);
            reductionSteps++;
            reduce(bundle);
            if (hasConflict()) {
                return true;
            }
        }
        return hasConflict();
    }

    private void watch(Bundle<K, V> bundle, Transaction<K, V> from, Transaction<K, V> to) {
        watchers.computeIfAbsent(from, ignored -> new LinkedHashSet<>()).add(bundle);
        watchers.computeIfAbsent(to, ignored -> new LinkedHashSet<>()).add(bundle);
    }

    private void enqueue(Bundle<K, V> bundle) {
        if (queued.add(bundle)) {
            work.addLast(bundle);
        }
    }

    /** Oracle 通知包含传递影响端点；队列集合合并重复唤醒。 */
    private void enqueueAffectedBundles(Transaction<K, V> txn) {
        var affected = watchers.get(txn);
        if (affected != null) {
            affected.forEach(this::enqueue);
        }
    }

    private void reduce(Bundle<K, V> bundle) {
        if (cannotSee(bundle.badWriter, bundle.reader)) {
            bundle.items.forEach(item -> item.satisfied = true);
            return;
        }
        for (var item : bundle.items) {
            if (item.satisfied) {
                continue;
            }
            item.repairs.removeIf(repair -> {
                boolean impossible = repair.getTxn().equals(bundle.badWriter)
                        || SIReachabilityOracle.isBottomTxn(repair.getTxn())
                        || oracle.reachesInduced(repair.getTxn(), bundle.badWriter)
                        || cannotSee(repair.getTxn(), bundle.reader);
                if (impossible) {
                    removedCandidates++;
                }
                return impossible;
            });
            for (var repair : item.repairs) {
                if (knownBefore(item.bad, repair) && oracle.reachesA(repair.getTxn(), bundle.reader)) {
                    item.satisfied = true;
                    break;
                }
            }
            if (item.satisfied) {
                continue;
            }
            boolean outsidePossible = !oracle.reachesA(bundle.badWriter, bundle.reader);
            if (item.repairs.isEmpty()) {
                if (!outsidePossible) {
                    conflicts.add(new PredicatePruning.ConflictReason<>(item.observation, item.key,
                            "已知可见的 bad writer " + bundle.badWriter + " 没有可行 repair"));
                } else {
                    force(item, bundle.badWriter, bundle.reader, FactKind.NOT_VIS,
                            "bad writer 没有可行 repair，必须不可见");
                }
                return;
            }
            if (!outsidePossible && item.repairs.size() == 1) {
                var repair = item.repairs.get(0).getTxn();
                force(item, bundle.badWriter, repair, FactKind.WW,
                        "已知可见的 bad writer 仅剩一个 repair " + repair);
                if (!hasConflict()) {
                    force(item, repair, bundle.reader, FactKind.VIS,
                            "唯一 repair 必须在该快照中可见，bad=" + bundle.badWriter);
                }
            }
        }
    }

    private boolean knownBefore(KnownGraph.WriteRef<K, V> left, KnownGraph.WriteRef<K, V> right) {
        return SIReachabilityOracle.isBottomTxn(left.getTxn())
                || oracle.reachesInduced(left.getTxn(), right.getTxn());
    }

    private void force(Item<K, V> item, Transaction<K, V> from, Transaction<K, V> to,
            FactKind kind, String reason) {
        var identity = java.util.Arrays.<Object>asList(kind, from, to, item.key);
        if (!factKeys.add(identity)) {
            return;
        }
        facts.add(new Fact<>(from, to, item.key, kind, item.observation, reason));
        if (kind == FactKind.NOT_VIS) {
            excludedVis.add(Pair.of(from, to));
            oracle.addInvisibility(from, to, this::enqueueAffectedBundles);
            // 显式 NOT_VIS 即使没有新增 induced 路径，也改变 cannotSee。
            enqueueAffectedBundles(from);
            enqueueAffectedBundles(to);
        } else {
            oracle.addVisibility(from, to, this::enqueueAffectedBundles);
        }
        if (!oracle.isAcyclic()) {
            conflicts.add(new PredicatePruning.ConflictReason<>(item.observation, item.key,
                    reason + "；新增 " + kind + "(" + from + "," + to + ") 导致 induced 环"));
        }
    }

    boolean cannotSee(Transaction<K, V> writer, Transaction<K, V> reader) {
        if (SIReachabilityOracle.isBottomTxn(writer)) {
            return false;
        }
        return writer.equals(reader) || SIReachabilityOracle.isBottomTxn(reader)
                || oracle.reachesInduced(reader, writer)
                || excludedVis.contains(Pair.of(writer, reader))
                || oracle.hasConflict(List.of(new SIEdge<>(writer, reader, EdgeType.PR_WR, null)));
    }

    List<PredicatePruning.ResidualItem<K, V>> residualItems() {
        var result = new ArrayList<PredicatePruning.ResidualItem<K, V>>();
        for (var bundle : bundles.values()) {
            for (var item : bundle.items) {
                if (!item.satisfied && !cannotSee(bundle.badWriter, bundle.reader)) {
                    result.add(new PredicatePruning.ResidualItem<>(item.observation, item.key,
                            item.bad, item.repairs, !oracle.reachesA(bundle.badWriter, bundle.reader)));
                }
            }
        }
        return List.copyOf(result);
    }

    List<Fact<K, V>> facts() { return List.copyOf(facts); }
    List<PredicatePruning.ConflictReason<K, V>> conflictReasons() { return List.copyOf(conflicts); }
    long initialItems() { return initialItems; }
    long removedCandidates() { return removedCandidates; }
    long reductionSteps() { return reductionSteps; }
    boolean hasConflict() { return !conflicts.isEmpty() || !oracle.isAcyclic(); }
}
