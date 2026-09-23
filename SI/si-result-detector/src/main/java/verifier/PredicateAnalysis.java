package verifier;

import static history.query.PredicateReadSemantics.expectedPredicateInputs;
import static history.query.PredicateReadSemantics.predicateSnapshotMatches;
import static history.query.PredicateReadSemantics.relationResolverFor;
import static verifier.SIReachabilityOracle.isBottomTxn;

import graph.KnownGraph;
import history.Event;
import history.History;
import history.Transaction;
import history.query.MapVisibleState;
import history.query.QueryException;
import history.query.QueryPlan;
import history.query.QueryScope;
import history.query.RelationResolver;
import history.query.RowContribution;
import util.Profiler;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** 每次检测共享的写索引、来源完整性与行贡献分析，不创建求解器或修改顺序事实。 */
final class PredicateAnalysis<KeyType, ValueType> {
    private static final int COMPACT_MATCH_UNAVAILABLE = -1;
    private static final int COMPACT_MATCH_INVALID = 0;
    private static final int COMPACT_MATCH_FALSE = 1;
    private static final int COMPACT_MATCH_TRUE = 2;
    private static final int MAX_GENERAL_ROW_CONTRIBUTIONS = 32_768;

    private final Map<List<KnownGraph.WriteRef<KeyType, ValueType>>,
            List<KnownGraph.WriteRef<KeyType, ValueType>>> finalWritesCache = new IdentityHashMap<>();
    private final KnownGraph<KeyType, ValueType> graph;
    private final SIReachabilityOracle<KeyType, ValueType> vis;
    private final Map<KeyType, List<KnownGraph.WriteRef<KeyType, ValueType>>> writesByKey;
    private final Map<Object, List<Map.Entry<KeyType,
            List<KnownGraph.WriteRef<KeyType, ValueType>>>>> scopedWritesCache =
            new HashMap<>();
    private final IdentityHashMap<KnownGraph.WriteRef<KeyType, ValueType>, Integer>
            writeRefIds = new IdentityHashMap<>();
    private final IdentityHashMap<QueryPlan<KeyType, ValueType>, CompactRowMatchCache>
            compactRowMatchCaches = new IdentityHashMap<>();
    private final IdentityHashMap<QueryPlan<KeyType, ValueType>, Integer>
            rowContributionPlanIds = new IdentityHashMap<>();
    private final IdentityHashMap<Event<KeyType, ValueType>, QueryPlan<KeyType, ValueType>>
            rowLocalQueryPlans = new IdentityHashMap<>();
    private int nextRowContributionPlanId;
    private final LinkedHashMap<Long, CachedRowContribution<KeyType>> generalRowContributions =
            new LinkedHashMap<Long, CachedRowContribution<KeyType>>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<Long, CachedRowContribution<KeyType>> eldest) {
                    return size() > MAX_GENERAL_ROW_CONTRIBUTIONS;
                }
            };

    private long scopeLookupNanos;
    private long snapshotValidationNanos;
    private long latestWriterLookups;
    private long latestWriterInputWrites;
    private long latestWriterResults;
    private long compactCacheHits;
    private long compactCacheMisses;
    private long rowContributionCacheHits;
    private long rowContributionCacheMisses;

    PredicateAnalysis(KnownGraph<KeyType, ValueType> graph,
            SIReachabilityOracle<KeyType, ValueType> vis) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.vis = Objects.requireNonNull(vis, "vis");
        validateSupportedPredicates();
        this.writesByKey = buildWritesByKey(graph);
        int writeRefId = 0;
        for (var write : graph.getAllWrites()) {
            writeRefIds.put(write, writeRefId++);
        }
    }

    KnownGraph<KeyType, ValueType> graph() {
        return graph;
    }

    SIReachabilityOracle<KeyType, ValueType> visibilityOracle() {
        return vis;
    }

    /** 写列表与外层索引均只读，保留原始 WriteRef 身份和事务内事件顺序。 */
    Map<KeyType, List<KnownGraph.WriteRef<KeyType, ValueType>>> getWritesByKey() {
        return writesByKey;
    }

    private void validateSupportedPredicates() {
        for (var observation : graph.getPredicateObservations()) {
            validateSupportedPredicate(observation.getPredicateReadEvent());
        }
    }

    /** 在内部一致性检查前区分不支持的查询与历史本身的语义冲突。 */
    static <K, V> void validateSupportedPredicates(History<K, V> history) {
        for (var event : history.getEvents()) {
            validateSupportedPredicate(event);
        }
    }

    private static <K, V> void validateSupportedPredicate(Event<K, V> event) {
        var predicate = event.getPredicate();
        if (predicate == null) {
            return;
        }
        if (predicate instanceof QueryPlan && ((QueryPlan<?, ?>) predicate).distinct()) {
            throw new QueryException("SI 不支持 DISTINCT 查询");
        }
        if (!predicate.isRowLocal()
                && (!(predicate instanceof QueryPlan)
                        || !((QueryPlan<?, ?>) predicate).isMonotone())) {
            throw new QueryException("SI 仅支持声明为 row-local 的谓词或受支持的单调 QueryPlan");
        }
    }

    boolean recordedPredicateInputsValid(
            Event<KeyType, ValueType> predicateRead,
            Map<KeyType, KnownGraph.WriteRef<KeyType, ValueType>> resultSourcesByKey) {
        long started = System.nanoTime();
        try {
            final Map<KeyType, ValueType> expectedInputs;
            try {
                expectedInputs = expectedPredicateInputs(predicateRead);
            } catch (QueryException exception) {
                return false;
            }
            if (!expectedInputs.keySet().equals(resultSourcesByKey.keySet())) {
                return false;
            }
            var predicate = predicateRead.getPredicate();
            for (var source : resultSourcesByKey.entrySet()) {
                if (!predicate.scope().covers(source.getKey())
                        || !writesByKey.containsKey(source.getKey())
                        || !Objects.equals(expectedInputs.get(source.getKey()),
                                source.getValue().getEvent().getValue())) {
                    return false;
                }
            }
            return predicateSnapshotMatches(predicateRead, expectedInputs);
        } finally {
            snapshotValidationNanos += System.nanoTime() - started;
        }
    }

    boolean hasEmptyPredicateContribution(
            Event<KeyType, ValueType> predicateRead,
            RelationResolver<KeyType> relationResolver,
            KnownGraph.WriteRef<KeyType, ValueType> write) {
        if (write.getEvent().getValue() == null) {
            return true;
        }
        var compactStatus = compactRowMatchStatus(predicateRead, write);
        if (compactStatus != COMPACT_MATCH_UNAVAILABLE) {
            return compactStatus == COMPACT_MATCH_FALSE;
        }
        var evaluated = rowContribution(predicateRead, relationResolver, write);
        if (!evaluated.valid) {
            return false;
        }
        if (predicateRead.getRecordedPredicateResult() == null) {
            return evaluated.contribution.inputsEmpty();
        }
        return evaluated.contribution.inputsEmpty()
                && evaluated.contribution.valuesEmpty();
    }

    KnownGraph.WriteRef<KeyType, ValueType> latestSelfBefore(
            List<KnownGraph.WriteRef<KeyType, ValueType>> writes,
            Transaction<KeyType, ValueType> reader,
            int eventIndex) {
        return writes.stream()
                .filter(write -> write.getTxn().equals(reader)
                        && write.getIndex() < eventIndex)
                .max(Comparator.comparingInt(KnownGraph.WriteRef::getIndex))
                .orElse(null);
    }

    List<KnownGraph.WriteRef<KeyType, ValueType>> latestExternalWrites(
            List<KnownGraph.WriteRef<KeyType, ValueType>> writes,
            Transaction<KeyType, ValueType> reader) {
        latestWriterLookups++;
        latestWriterInputWrites += writes.size();
        var result = latestExternalWritesRaw(writes, reader);
        latestWriterResults += result.size();
        return result;
    }

    private List<KnownGraph.WriteRef<KeyType, ValueType>> latestExternalWritesRaw(
            List<KnownGraph.WriteRef<KeyType, ValueType>> writes,
            Transaction<KeyType, ValueType> reader) {
        var finalWrites = finalWritesCache.computeIfAbsent(writes, input -> {
            var latestByWriter = new LinkedHashMap<Transaction<KeyType, ValueType>,
                    KnownGraph.WriteRef<KeyType, ValueType>>();
            for (var write : input) {
                latestByWriter.put(write.getTxn(), write);
            }
            return List.copyOf(latestByWriter.values());
        });
        for (int i = 0; i < finalWrites.size(); i++) {
            if (finalWrites.get(i).getTxn().equals(reader)) {
                var external = new ArrayList<>(finalWrites);
                external.remove(i);
                return List.copyOf(external);
            }
        }
        return finalWrites;
    }

    List<Map.Entry<KeyType, List<KnownGraph.WriteRef<KeyType, ValueType>>>>
            scopedWrites(QueryScope<KeyType> scope) {
        long started = System.nanoTime();
        try {
            var cacheKey = scope.cacheKey();
            if (cacheKey.isEmpty()) {
                return buildScopedWrites(scope);
            }
            return scopedWritesCache.computeIfAbsent(
                    cacheKey.get(), ignored -> buildScopedWrites(scope));
        } finally {
            scopeLookupNanos += System.nanoTime() - started;
        }
    }

    boolean writeChangesPredicateResult(
            KnownGraph.WriteRef<KeyType, ValueType> source,
            KnownGraph.WriteRef<KeyType, ValueType> later,
            Event<KeyType, ValueType> predicateRead) {
        boolean sourceMatches = writeMatchesPredicate(source, predicateRead);
        boolean laterMatches = writeMatchesPredicate(later, predicateRead);
        if (sourceMatches != laterMatches) {
            return true;
        }
        if (!sourceMatches) {
            return false;
        }

        var plan = rowLocalQueryPlan(predicateRead);
        if (plan == null || plan.compactResultProjection() == null) {
            var resolver = relationResolverFor(predicateRead);
            var sourceContribution = rowContribution(
                    predicateRead, resolver, source);
            var laterContribution = rowContribution(
                    predicateRead, resolver, later);
            if (sourceContribution.valid && laterContribution.valid) {
                return !sourceContribution.contribution.canonicalEquals(
                        laterContribution.contribution);
            }
        }
        return !Objects.equals(source.getEvent().getKey(), later.getEvent().getKey())
                || !Objects.equals(source.getEvent().getValue(), later.getEvent().getValue());
    }

    boolean writeMatchesPredicate(
            KnownGraph.WriteRef<KeyType, ValueType> write,
            Event<KeyType, ValueType> predicateRead) {
        if (write.getEvent().getValue() == null) {
            return false;
        }
        var compactStatus = compactRowMatchStatus(predicateRead, write);
        if (compactStatus != COMPACT_MATCH_UNAVAILABLE) {
            return compactStatus == COMPACT_MATCH_TRUE;
        }
        var event = write.getEvent();
        var evaluated = rowContribution(
                predicateRead, relationResolverFor(predicateRead), write);
        return evaluated.valid
                && evaluated.contribution.containsInput(event.getKey());
    }

    private List<Map.Entry<KeyType, List<KnownGraph.WriteRef<KeyType, ValueType>>>>
            buildScopedWrites(QueryScope<KeyType> scope) {
        return writesByKey.entrySet().stream()
                .filter(entry -> scope.covers(entry.getKey()))
                .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                .map(entry -> Map.entry(entry.getKey(), entry.getValue()))
                .collect(Collectors.toUnmodifiableList());
    }

    private int compactRowMatchStatus(
            Event<KeyType, ValueType> predicateRead,
            KnownGraph.WriteRef<KeyType, ValueType> write) {
        var plan = rowLocalQueryPlan(predicateRead);
        if (plan == null || plan.compactResultProjection() == null
                || plan.compiledRowMatcher().isEmpty()) {
            return COMPACT_MATCH_UNAVAILABLE;
        }
        var writeRefId = writeRefIds.get(write);
        if (writeRefId == null) {
            return COMPACT_MATCH_UNAVAILABLE;
        }
        var cache = compactRowMatchCaches.computeIfAbsent(
                plan, ignored -> new CompactRowMatchCache());
        if (cache.computed.get(writeRefId)) {
            compactCacheHits++;
            if (cache.invalid.get(writeRefId)) {
                return COMPACT_MATCH_INVALID;
            }
            return cache.matched.get(writeRefId)
                    ? COMPACT_MATCH_TRUE : COMPACT_MATCH_FALSE;
        }

        compactCacheMisses++;
        cache.computed.set(writeRefId);
        var event = write.getEvent();
        try {
            if (plan.compiledRowMatcher().get().test(
                    event.getKey(), event.getValue())) {
                cache.matched.set(writeRefId);
                return COMPACT_MATCH_TRUE;
            }
            return COMPACT_MATCH_FALSE;
        } catch (QueryException exception) {
            cache.invalid.set(writeRefId);
            return COMPACT_MATCH_INVALID;
        }
    }

    private CachedRowContribution<KeyType> rowContribution(
            Event<KeyType, ValueType> predicateRead,
            RelationResolver<KeyType> relationResolver,
            KnownGraph.WriteRef<KeyType, ValueType> write) {
        var plan = rowLocalQueryPlan(predicateRead);
        var writeRefId = writeRefIds.get(write);
        if (plan == null || plan.compactResultProjection() != null
                || writeRefId == null) {
            return evaluateRowContribution(
                    predicateRead, relationResolver, write, plan);
        }

        var planId = rowContributionPlanIds.computeIfAbsent(
                plan, ignored -> nextRowContributionPlanId++);
        var cacheKey = ((long) planId << 32) | (writeRefId & 0xffffffffL);
        var cached = generalRowContributions.get(cacheKey);
        if (cached != null) {
            rowContributionCacheHits++;
            return cached;
        }
        rowContributionCacheMisses++;
        var evaluated = evaluateRowContribution(
                predicateRead, relationResolver, write, plan);
        generalRowContributions.put(cacheKey, evaluated);
        return evaluated;
    }

    private CachedRowContribution<KeyType> evaluateRowContribution(
            Event<KeyType, ValueType> predicateRead,
            RelationResolver<KeyType> relationResolver,
            KnownGraph.WriteRef<KeyType, ValueType> write,
            QueryPlan<KeyType, ValueType> plan) {
        var event = write.getEvent();
        try {
            var contribution = plan == null
                    ? RowContribution.from(predicateRead.getPredicate().evaluate(
                            new MapVisibleState<>(
                                    Map.of(event.getKey(), event.getValue()),
                                    relationResolver)))
                    : plan.evaluateRowContribution(
                            event.getKey(), event.getValue(), relationResolver);
            return CachedRowContribution.valid(contribution);
        } catch (QueryException exception) {
            return CachedRowContribution.invalid();
        }
    }

    @SuppressWarnings("unchecked")
    private QueryPlan<KeyType, ValueType> rowLocalQueryPlan(
            Event<KeyType, ValueType> predicateRead) {
        if (rowLocalQueryPlans.containsKey(predicateRead)) {
            return rowLocalQueryPlans.get(predicateRead);
        }
        if (!(predicateRead.getPredicate() instanceof QueryPlan)) {
            rowLocalQueryPlans.put(predicateRead, null);
            return null;
        }
        var plan = (QueryPlan<KeyType, ValueType>) predicateRead.getPredicate();
        var rowLocalPlan = plan.isRowLocal() ? plan : null;
        rowLocalQueryPlans.put(predicateRead, rowLocalPlan);
        return rowLocalPlan;
    }

    private Map<KeyType, List<KnownGraph.WriteRef<KeyType, ValueType>>> buildWritesByKey(
            KnownGraph<KeyType, ValueType> graph) {
        var result = new HashMap<KeyType, List<KnownGraph.WriteRef<KeyType, ValueType>>>();
        for (var write : graph.getAllWrites()) {
            result.computeIfAbsent(write.getEvent().getKey(), ignored -> new ArrayList<>()).add(write);
        }
        for (var writes : result.values()) {
            writes.sort(Comparator
                    .comparing((KnownGraph.WriteRef<KeyType, ValueType> write) -> !isBottomTxn(write.getTxn()))
                    .thenComparing(write -> write.getTxn().getId())
                    .thenComparingInt(KnownGraph.WriteRef::getIndex));
        }
        result.replaceAll((key, writes) -> List.copyOf(writes));
        return Collections.unmodifiableMap(result);
    }

    /** 阶段收尾调用一次；接入后 solver 不再重复发布这些分析指标。 */
    void publishMetrics(Profiler profiler, boolean includeCounts) {
        profiler.addDurationNanos("SI_PRED_SCOPE_LOOKUP", scopeLookupNanos);
        profiler.addDurationNanos("SI_PRED_SNAPSHOT_VALIDATE", snapshotValidationNanos);
        if (!includeCounts) {
            return;
        }
        profiler.addCount("SI_PRED_LATEST_WRITER_LOOKUPS_COUNT", latestWriterLookups);
        profiler.addCount("SI_PRED_LATEST_WRITER_INPUT_WRITES_COUNT", latestWriterInputWrites);
        profiler.addCount("SI_PRED_LATEST_WRITER_RESULTS_COUNT", latestWriterResults);
        profiler.addCount("SI_PRED_COMPACT_CACHE_HITS_COUNT", compactCacheHits);
        profiler.addCount("SI_PRED_COMPACT_CACHE_MISSES_COUNT", compactCacheMisses);
        profiler.addCount("SI_PRED_ROW_CACHE_HITS_COUNT", rowContributionCacheHits);
        profiler.addCount("SI_PRED_ROW_CACHE_MISSES_COUNT", rowContributionCacheMisses);
    }

    private static final class CompactRowMatchCache {
        private final BitSet computed = new BitSet();
        private final BitSet matched = new BitSet();
        private final BitSet invalid = new BitSet();
    }

    private static final class CachedRowContribution<KeyType> {
        private final boolean valid;
        private final RowContribution<KeyType> contribution;

        private CachedRowContribution(
                boolean valid, RowContribution<KeyType> contribution) {
            this.valid = valid;
            this.contribution = contribution;
        }

        private static <KeyType> CachedRowContribution<KeyType> valid(
                RowContribution<KeyType> contribution) {
            return new CachedRowContribution<>(true, contribution);
        }

        private static <KeyType> CachedRowContribution<KeyType> invalid() {
            return new CachedRowContribution<>(false, null);
        }
    }
}
