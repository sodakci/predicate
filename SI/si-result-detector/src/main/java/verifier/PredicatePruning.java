package verifier;

import graph.EdgeType;
import graph.KnownGraph;
import history.History;
import history.Transaction;
import util.Profiler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static history.query.PredicateReadSemantics.expectedPredicateInputs;
import static history.query.PredicateReadSemantics.predicateSnapshotMatches;
import static history.query.PredicateReadSemantics.relationResolverFor;

/** SAT 创建前的 SI 谓词准备、充分剪枝与只读交接。 */
final class PredicatePruning<K, V> {
    private final KnownGraph<K, V> graph;
    private final SIReachabilityOracle<K, V> oracle;
    private final SIVerifier.SolverSettings settings;
    private final PredicateAnalysis<K, V> analysis;
    private final List<ObservationState<K, V>> observations = new ArrayList<>();
    private final List<ConflictReason<K, V>> conflicts = new ArrayList<>();
    private final List<SiGmwrPropagationState.Fact<K, V>> facts = new ArrayList<>();
    private final Map<Transaction<K, V>, Set<Transaction<K, V>>> invisibleReadersByWriter = new HashMap<>();
    private final Set<List<Object>> factKeys = new LinkedHashSet<>();
    private SiGmwrPropagationState<K, V> propagation;

    PredicatePruning(History<K, V> history, KnownGraph<K, V> graph,
            SIReachabilityOracle<K, V> oracle, SIVerifier.SolverSettings settings,
            PredicateAnalysis<K, V> analysis) {
        Objects.requireNonNull(history, "history");
        this.graph = Objects.requireNonNull(graph, "graph");
        this.oracle = Objects.requireNonNull(oracle, "oracle");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.analysis = Objects.requireNonNull(analysis, "analysis");
        if (analysis.visibilityOracle() != oracle || analysis.graph() != graph) {
            throw new IllegalArgumentException("谓词分析必须共享本次 audit 的 graph 与 oracle");
        }
    }

    Result<K, V> prune() {
        var profiler = Profiler.getInstance();
        boolean gmwr = settings.predicateMode == SIVerifier.PredicateMode.GMWR;
        long started = System.nanoTime();
        prepareObservations();
        if (!oracle.isAcyclic()) {
            conflicts.add(new ConflictReason<>(null, null, "已知 SI induced 图存在冲突"));
        }
        long prepareNanos = System.nanoTime() - started;
        profiler.addDurationNanos("SI_PRED_PREPARE_MS", prepareNanos);

        started = System.nanoTime();
        if (conflicts.isEmpty()) {
            finalizeSourceDomains();
        }
        long pruningNanos = System.nanoTime() - started;
        started = System.nanoTime();
        if (gmwr && conflicts.isEmpty()) {
            propagation = new SiGmwrPropagationState<>(oracle, freezeObservations());
        }
        profiler.addDurationNanos("GMWR_BUILD_MS", gmwr ? prepareNanos + System.nanoTime() - started : 0L);
        started = System.nanoTime();
        if (propagation != null && settings.gmwrPrepropagation) {
            propagation.propagate();
            conflicts.addAll(propagation.conflictReasons());
            for (var fact : propagation.facts()) {
                rememberFact(fact);
            }
            if (propagation.hasConflict() && conflicts.isEmpty()) {
                conflicts.add(new ConflictReason<>(null, null, "GMWR 确定事实导致 SI induced 冲突"));
            }
        }
        profiler.addDurationNanos("GMWR_REDUCTION_MS", System.nanoTime() - started);
        started = System.nanoTime();
        if (conflicts.isEmpty()) {
            finalizeSourceDomains();
        }
        pruningNanos += System.nanoTime() - started;
        profiler.addDurationNanos("GMWR_PRUNING_MS", gmwr ? pruningNanos : 0L);
        profiler.addDurationNanos("SI_PRED_PRUNING_MS", pruningNanos);
        var residual = propagation == null ? List.<ResidualItem<K, V>>of() : propagation.residualItems();
        var result = new Result<>(analysis, oracle, freezeObservations(), residual, facts, conflicts,
                propagation == null ? 0L : propagation.initialItems(),
                propagation == null ? 0L : propagation.removedCandidates());
        publishMetrics(result);
        return result;
    }

    private void prepareObservations() {
        for (var observation : graph.getPredicateObservations()) {
            var read = observation.getPredicateReadEvent();
            if (read.getPredicate() == null) {
                continue;
            }
            var sources = new LinkedHashMap<K, KnownGraph.WriteRef<K, V>>();
            boolean duplicate = false;
            for (var tuple : observation.getTupleSources()) {
                duplicate |= sources.putIfAbsent(tuple.getKey(), tuple.getSourceWrite()) != null;
            }
            var scoped = analysis.scopedWrites(read.getPredicate().scope());
            var state = new ObservationState<K, V>(observation, sources, scoped);
            observations.add(state);
            if (duplicate || !analysis.recordedPredicateInputsValid(read, sources)) {
                conflicts.add(new ConflictReason<>(observation, null, "记录的输入来源或查询结果不一致"));
                continue;
            }
            state.recordedInputs.putAll(expectedPredicateInputs(read));
            for (var entry : scoped) {
                var key = new KeyState<K, V>();
                key.key = entry.getKey();
                key.recordedSource = sources.get(key.key);
                key.readType = observation.getPredicateReadType(key.key);
                key.internal = key.readType == KnownGraph.PredicateReadType.INTERNAL;
                key.rowLocal = read.getPredicate().isRowLocal();
                key.latestSelf = analysis.latestSelfBefore(entry.getValue(), observation.getTxn(),
                        observation.getEventIndex());
                key.allExternalWrites = analysis.latestExternalWrites(entry.getValue(), observation.getTxn());
                state.keys.add(key);
                if (key.internal) {
                    var effective = key.latestSelf == null ? key.recordedSource : key.latestSelf;
                    if (key.recordedSource != null && effective != key.recordedSource
                            || key.rowLocal && key.recordedSource == null && effective != null
                                    && !analysis.hasEmptyPredicateContribution(read, relationResolverFor(read), effective)) {
                        conflicts.add(new ConflictReason<>(observation, key.key, "INTERNAL 来源与事件前自写不一致"));
                    }
                    continue;
                }
                if (key.rowLocal && key.recordedSource == null) {
                    var good = new ArrayList<KnownGraph.WriteRef<K, V>>();
                    var bad = new ArrayList<KnownGraph.WriteRef<K, V>>();
                    for (var write : key.allExternalWrites) {
                        if (analysis.hasEmptyPredicateContribution(read, relationResolverFor(read), write)) {
                            good.add(write);
                        } else {
                            bad.add(write);
                        }
                    }
                    key.goodWrites = List.copyOf(good);
                    key.badWrites = List.copyOf(bad);
                }
                if (key.recordedSource != null) {
                    if (!key.allExternalWrites.contains(key.recordedSource)) {
                        conflicts.add(new ConflictReason<>(observation, key.key, "记录来源不是外部事务最终写"));
                        continue;
                    }
                    key.sourceCandidates.add(key.recordedSource);
                    forceSource(observation, key, key.recordedSource, "记录的外部谓词来源");
                } else {
                    key.sourceCandidates.addAll(key.rowLocal ? key.goodWrites : key.allExternalWrites);
                    key.implicitBottomPossible = key.allExternalWrites.stream()
                            .noneMatch(write -> SIReachabilityOracle.isBottomTxn(write.getTxn()));
                }
                key.sourceObligation = key.rowLocal && key.recordedSource == null;
                key.initialCandidates = key.sourceCandidates.size() + (key.implicitBottomPossible ? 1 : 0);
            }
            if (!state.keys.isEmpty() && state.keys.stream().allMatch(key -> key.latestSelf != null)) {
                var snapshot = new LinkedHashMap<K, V>();
                for (var key : state.keys) {
                    snapshot.put(key.key, key.latestSelf.getEvent().getValue());
                }
                if (!predicateSnapshotMatches(read, snapshot)) {
                    conflicts.add(new ConflictReason<>(observation, null, "完整自写快照与记录查询结果不一致"));
                }
            }
        }
    }

    private void finalizeSourceDomains() {
        boolean changed;
        do {
            int priorFacts = facts.size();
            changed = false;
            for (var state : observations) {
                for (var key : state.keys) {
                    if (key.internal) {
                        continue;
                    }
                    int before = key.sourceCandidates.size();
                    key.sourceCandidates.removeIf(source -> !sourcePossible(state.observation, key, source));
                    changed |= before != key.sourceCandidates.size();
                    if (key.implicitBottomPossible && key.allExternalWrites.stream()
                            .anyMatch(write -> oracle.reachesA(write.getTxn(), state.observation.getTxn()))) {
                        key.implicitBottomPossible = false;
                        changed = true;
                    }
                    int size = key.sourceCandidates.size() + (key.implicitBottomPossible ? 1 : 0);
                    if (size == 0) {
                        conflicts.add(new ConflictReason<>(state.observation, key.key, "没有合法的 latest-visible 来源"));
                        return;
                    }
                    if (key.sourceObligation && size == 1 && !key.sourceForced) {
                        key.sourceForced = true;
                        key.forcedSource = key.sourceCandidates.isEmpty() ? null : key.sourceCandidates.get(0);
                        if (key.forcedSource != null) {
                            forceSource(state.observation, key, key.forcedSource, "充分来源剪枝后仅剩唯一来源");
                        }
                    }
                    if (!oracle.isAcyclic()) {
                        conflicts.add(new ConflictReason<>(state.observation, key.key, "确定来源与已知 SI 依赖冲突"));
                        return;
                    }
                }
            }
            changed |= priorFacts != facts.size();
        } while (changed && conflicts.isEmpty());
    }

    private boolean sourcePossible(KnownGraph.PredicateObservation<K, V> observation,
            KeyState<K, V> key, KnownGraph.WriteRef<K, V> source) {
        var reader = observation.getTxn();
        var writer = source.getTxn();
        if (!SIReachabilityOracle.isBottomTxn(writer)
                && (oracle.reachesInduced(reader, writer)
                        || oracle.hasConflict(List.of(new SIEdge<>(writer, reader, EdgeType.PR_WR, key.key)))
                        || invisibleReadersByWriter.getOrDefault(writer, Collections.emptySet()).contains(reader))) {
            return false;
        }
        for (var later : key.allExternalWrites) {
            if (later == source || !knownWwBefore(source, later)) {
                continue;
            }
            if (oracle.reachesA(later.getTxn(), reader)) {
                return false;
            }
            if (key.rowLocal && analysis.writeChangesPredicateResult(source, later,
                    observation.getPredicateReadEvent())
                    && oracle.hasConflict(List.of(new SIEdge<>(reader, later.getTxn(), EdgeType.PR_RW, key.key)))) {
                return false;
            }
        }
        return true;
    }

    private boolean knownWwBefore(KnownGraph.WriteRef<K, V> left, KnownGraph.WriteRef<K, V> right) {
        if (left == right) {
            return false;
        }
        if (left.getTxn().equals(right.getTxn())) {
            return left.getIndex() < right.getIndex();
        }
        if (SIReachabilityOracle.isBottomTxn(left.getTxn())) {
            return true;
        }
        return !SIReachabilityOracle.isBottomTxn(right.getTxn())
                && oracle.reachesInduced(left.getTxn(), right.getTxn());
    }

    private void forceSource(KnownGraph.PredicateObservation<K, V> observation,
            KeyState<K, V> key, KnownGraph.WriteRef<K, V> source, String reason) {
        if (SIReachabilityOracle.isBottomTxn(source.getTxn()) || source.getTxn().equals(observation.getTxn())) {
            return;
        }
        var fact = new SiGmwrPropagationState.Fact<>(source.getTxn(), observation.getTxn(), key.key,
                SiGmwrPropagationState.FactKind.PR_WR, observation, reason);
        if (rememberFact(fact)) {
            oracle.addVisibility(source.getTxn(), observation.getTxn());
        }
    }

    private boolean rememberFact(SiGmwrPropagationState.Fact<K, V> fact) {
        if (!factKeys.add(java.util.Arrays.<Object>asList(fact.kind, fact.from, fact.to, fact.key))) {
            return false;
        }
        facts.add(fact);
        if (fact.kind == SiGmwrPropagationState.FactKind.NOT_VIS) {
            invisibleReadersByWriter.computeIfAbsent(fact.from, ignored -> new HashSet<>()).add(fact.to);
        }
        return true;
    }

    private List<PreparedObservation<K, V>> freezeObservations() {
        var result = new ArrayList<PreparedObservation<K, V>>();
        for (var state : observations) {
            var keys = new ArrayList<PreparedKey<K, V>>();
            for (var key : state.keys) {
                keys.add(new PreparedKey<>(key));
            }
            state.prepared = new PreparedObservation<>(state.observation, state.sources,
                    state.scopedEntries, keys, state.recordedInputs, state.prepared);
            result.add(state.prepared);
        }
        return List.copyOf(result);
    }

    private void publishMetrics(Result<K, V> result) {
        long initial = 0, forced = 0, initialCandidates = 0, remainingCandidates = 0;
        for (var state : observations) {
            for (var key : state.keys) {
                if (key.sourceObligation) {
                    initial++;
                    initialCandidates += key.initialCandidates;
                    if (key.sourceForced) {
                        forced++;
                    } else {
                        remainingCandidates += key.sourceCandidates.size() + (key.implicitBottomPossible ? 1 : 0);
                    }
                }
            }
        }
        long pruned = initialCandidates - remainingCandidates - forced;
        if (pruned < 0) {
            throw new IllegalStateException("SI PR_WR 来源统计不守恒");
        }
        var profiler = Profiler.getInstance();
        profiler.addCount("SI_PRED_PR_WR_INITIAL_CONSTRAINTS_COUNT", initial);
        profiler.addCount("SI_PRED_PR_WR_RESIDUAL_CONSTRAINTS_COUNT", initial - forced);
        profiler.addCount("SI_PRED_PR_WR_FORCED_CONSTRAINTS_COUNT", forced);
        profiler.addCount("SI_PRED_PR_WR_INITIAL_CANDIDATES_COUNT", initialCandidates);
        profiler.addCount("SI_PRED_PR_WR_RESIDUAL_CANDIDATES_COUNT", remainingCandidates);
        profiler.addCount("SI_PRED_PR_WR_PRUNED_CANDIDATES_COUNT", pruned);
        profiler.addCount("SI_PRED_PR_WR_FIXED_CANDIDATES_COUNT", forced);
        profiler.addCount("GMWR_INITIAL_CONSTRAINTS", result.itemObligationCount());
        profiler.addCount("GMWR_RESIDUAL_CONSTRAINTS", result.residualItems.size());
        profiler.addCount("GMWR_REMOVED_CANDIDATES", result.removedCandidates);
        profiler.addCount("GMWR_FORCED_FACTS", propagation == null ? 0L : propagation.facts().size());
        profiler.addCount("SI_GMWR_REDUCTION_STEPS_COUNT",
                propagation == null ? 0L : propagation.reductionSteps());
        profiler.addCount("SI_ORACLE_BUILDS", oracle.buildCount());
        profiler.addCount("SI_ORACLE_UPDATES", oracle.updateCount());
    }

    static final class Result<K, V> {
        private final PredicateAnalysis<K, V> analysis;
        private final SIReachabilityOracle<K, V> oracle;
        private final List<PreparedObservation<K, V>> observations;
        private final List<ResidualItem<K, V>> residualItems;
        private final List<SiGmwrPropagationState.Fact<K, V>> facts;
        private final List<ConflictReason<K, V>> conflicts;
        private final long initialItems;
        private final long removedCandidates;
        private Result(PredicateAnalysis<K, V> analysis, SIReachabilityOracle<K, V> oracle,
                List<PreparedObservation<K, V>> observations, List<ResidualItem<K, V>> residualItems,
                List<SiGmwrPropagationState.Fact<K, V>> facts, List<ConflictReason<K, V>> conflicts,
                long initialItems, long removedCandidates) {
            this.analysis = analysis;
            this.oracle = oracle;
            this.observations = List.copyOf(observations);
            this.residualItems = List.copyOf(residualItems);
            this.facts = List.copyOf(facts);
            this.conflicts = List.copyOf(conflicts);
            this.initialItems = initialItems;
            this.removedCandidates = removedCandidates;
        }
        PredicateAnalysis<K, V> analysis() { return analysis; }
        SIReachabilityOracle<K, V> oracle() { return oracle; }
        boolean hasConflict() { return !conflicts.isEmpty(); }
        List<ConflictReason<K, V>> conflictReasons() { return conflicts; }
        List<PreparedObservation<K, V>> observations() { return observations; }
        PreparedObservation<K, V> observation(KnownGraph.PredicateObservation<K, V> observation) {
            return observations.stream().filter(prepared -> prepared.observation == observation).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("缺少已准备的谓词 observation"));
        }
        List<ResidualItem<K, V>> residualItems() { return residualItems; }
        List<SiGmwrPropagationState.Fact<K, V>> definiteFacts() { return facts; }
        long itemObligationCount() { return initialItems; }
        long residualItemCount() { return residualItems.size(); }
    }

    static final class PreparedObservation<K, V> {
        final KnownGraph.PredicateObservation<K, V> observation;
        final Map<K, KnownGraph.WriteRef<K, V>> sources;
        final List<Map.Entry<K, List<KnownGraph.WriteRef<K, V>>>> scopedEntries;
        final List<PreparedKey<K, V>> keys;
        final Map<K, V> recordedInputs;
        private PreparedObservation(KnownGraph.PredicateObservation<K, V> observation,
                Map<K, KnownGraph.WriteRef<K, V>> sources,
                List<Map.Entry<K, List<KnownGraph.WriteRef<K, V>>>> scopedEntries,
                List<PreparedKey<K, V>> keys, Map<K, V> recordedInputs,
                PreparedObservation<K, V> previous) {
            this.observation = observation;
            if (previous == null) {
                this.sources = Collections.unmodifiableMap(new LinkedHashMap<>(sources));
                var entries = new ArrayList<Map.Entry<K, List<KnownGraph.WriteRef<K, V>>>>();
                scopedEntries.forEach(entry -> entries.add(Map.entry(entry.getKey(), List.copyOf(entry.getValue()))));
                this.scopedEntries = List.copyOf(entries);
                this.recordedInputs = Collections.unmodifiableMap(new LinkedHashMap<>(recordedInputs));
            } else {
                this.sources = previous.sources;
                this.scopedEntries = previous.scopedEntries;
                this.recordedInputs = previous.recordedInputs;
            }
            this.keys = List.copyOf(keys);
        }
        PreparedKey<K, V> key(K key) {
            return keys.stream().filter(candidate -> Objects.equals(candidate.key, key)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("缺少已准备的 key " + key));
        }
    }

    static final class PreparedKey<K, V> {
        final K key;
        final KnownGraph.WriteRef<K, V> recordedSource;
        final KnownGraph.WriteRef<K, V> latestSelf;
        final KnownGraph.PredicateReadType readType;
        final boolean internal;
        final boolean rowLocal;
        final List<KnownGraph.WriteRef<K, V>> allExternalWrites;
        final List<KnownGraph.WriteRef<K, V>> sourceCandidates;
        final List<KnownGraph.WriteRef<K, V>> goodWrites;
        final List<KnownGraph.WriteRef<K, V>> badWrites;
        final boolean implicitBottomPossible;
        final boolean sourceForced;
        final KnownGraph.WriteRef<K, V> forcedSource;
        private PreparedKey(KeyState<K, V> state) {
            key = state.key;
            recordedSource = state.recordedSource;
            latestSelf = state.latestSelf;
            readType = state.readType;
            internal = state.internal;
            rowLocal = state.rowLocal;
            allExternalWrites = List.copyOf(state.allExternalWrites);
            sourceCandidates = List.copyOf(state.sourceCandidates);
            goodWrites = List.copyOf(state.goodWrites);
            badWrites = List.copyOf(state.badWrites);
            implicitBottomPossible = state.implicitBottomPossible;
            sourceForced = state.sourceForced;
            forcedSource = state.forcedSource;
        }
    }

    static final class ResidualItem<K, V> {
        final KnownGraph.PredicateObservation<K, V> observation;
        final K key;
        final KnownGraph.WriteRef<K, V> bad;
        final List<KnownGraph.WriteRef<K, V>> repairs;
        final boolean outsidePossible;
        ResidualItem(KnownGraph.PredicateObservation<K, V> observation, K key,
                KnownGraph.WriteRef<K, V> bad, Collection<KnownGraph.WriteRef<K, V>> repairs,
                boolean outsidePossible) {
            this.observation = observation;
            this.key = key;
            this.bad = bad;
            this.repairs = List.copyOf(repairs);
            this.outsidePossible = outsidePossible;
        }
    }

    static final class ConflictReason<K, V> {
        final KnownGraph.PredicateObservation<K, V> observation;
        final K key;
        final String reason;
        ConflictReason(KnownGraph.PredicateObservation<K, V> observation, K key, String reason) {
            this.observation = observation;
            this.key = key;
            this.reason = reason;
        }
        String describe() {
            return (observation == null ? "" : "reader=" + observation.getTxn()
                    + " event=" + observation.getEventIndex() + " ") + "key=" + key + " " + reason;
        }
        @Override public String toString() { return describe(); }
    }

    private static final class ObservationState<K, V> {
        final KnownGraph.PredicateObservation<K, V> observation;
        final Map<K, KnownGraph.WriteRef<K, V>> sources;
        final List<Map.Entry<K, List<KnownGraph.WriteRef<K, V>>>> scopedEntries;
        final List<KeyState<K, V>> keys = new ArrayList<>();
        PreparedObservation<K, V> prepared;
        final Map<K, V> recordedInputs = new LinkedHashMap<>();
        ObservationState(KnownGraph.PredicateObservation<K, V> observation,
                Map<K, KnownGraph.WriteRef<K, V>> sources,
                List<Map.Entry<K, List<KnownGraph.WriteRef<K, V>>>> scopedEntries) {
            this.observation = observation;
            this.sources = sources;
            this.scopedEntries = scopedEntries;
        }
    }

    private static final class KeyState<K, V> {
        K key;
        KnownGraph.WriteRef<K, V> recordedSource;
        KnownGraph.WriteRef<K, V> latestSelf;
        KnownGraph.PredicateReadType readType;
        boolean internal;
        boolean rowLocal;
        List<KnownGraph.WriteRef<K, V>> allExternalWrites = List.of();
        final List<KnownGraph.WriteRef<K, V>> sourceCandidates = new ArrayList<>();
        List<KnownGraph.WriteRef<K, V>> goodWrites = List.of();
        List<KnownGraph.WriteRef<K, V>> badWrites = List.of();
        boolean implicitBottomPossible;
        boolean sourceObligation;
        boolean sourceForced;
        KnownGraph.WriteRef<K, V> forcedSource;
        long initialCandidates;
    }
}
