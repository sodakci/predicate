package verifier;

import static history.query.PredicateReadSemantics.expectedPredicateInputs;
import static history.query.PredicateReadSemantics.predicateSnapshotMatches;
import static history.query.PredicateReadSemantics.relationResolverFor;

import static verifier.GmwrPropagationState.isBottomTxn;

import graph.EdgeType;
import graph.KnownGraph;
import history.Event;
import history.Transaction;
import history.query.*;
import java.util.*;
import java.util.stream.Collectors;

/** 每次检测共享的写索引和谓词语义分析，不依赖 MonoSAT。 */
final class PredicateAnalysis<KeyType, ValueType> {
    private static final int COMPACT_MATCH_UNAVAILABLE = -1;
    private static final int COMPACT_MATCH_INVALID = 0;
    private static final int COMPACT_MATCH_FALSE = 1;
    private static final int COMPACT_MATCH_TRUE = 2;
    private static final int MAX_GENERAL_ROW_CONTRIBUTIONS = 32_768;

    // Per-key write lists provide the local write order candidates used by
    // predicate-read encodings.
    private final Map<KeyType, List<KnownGraph.WriteRef<KeyType, ValueType>>> writesByKey;
    // Rule 2 consumes only already fixed typed WW facts. A nested index avoids
    // probing every unresolved WW literal for every predicate source.
    private final Map<KeyType, Map<Transaction<KeyType, ValueType>,
            Set<Transaction<KeyType, ValueType>>>> knownWwSuccessorsByKey;
    // Sort the complete key universe once. Each index retains the original
    // write order and precomputes the last write of every writer transaction.
    private final List<KeyWriteIndex<KeyType, ValueType>> sortedKeyWriteIndexes;
    // Only scopes with an explicit stable value key participate in this cache.
    private final Map<Object, List<KeyWriteIndex<KeyType, ValueType>>> scopedWritesCache =
            new HashMap<>();
    // Row contribution caches are intentionally audit-local. Compact k/value
    // plans use three BitSets; general row-local projections use a bounded LRU.
    private final IdentityHashMap<KnownGraph.WriteRef<KeyType, ValueType>, Integer> writeRefIds =
            new IdentityHashMap<>();
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
    private final KnownGraph<KeyType, ValueType> graph;
    private final PrecedenceOracle<Transaction<KeyType, ValueType>> precedence;

    PredicateAnalysis(KnownGraph<KeyType, ValueType> graph,
                      PrecedenceOracle<Transaction<KeyType, ValueType>> precedence) {
        this.graph = graph;
        this.precedence = Objects.requireNonNull(precedence, "precedence");
        validateSupportedPredicates();
        this.writesByKey = buildWritesByKey(graph);
        this.knownWwSuccessorsByKey = buildKnownWwSuccessorsByKey(graph);
        for (int i = 0; i < graph.getAllWrites().size(); i++) {
            writeRefIds.put(graph.getAllWrites().get(i), i);
        }
        this.sortedKeyWriteIndexes = buildKeyWriteIndexes(writesByKey);
    }

    Map<KeyType, List<KnownGraph.WriteRef<KeyType, ValueType>>> writesByKey() {
        return writesByKey;
    }

    void refreshKnownWwFacts() {
        knownWwSuccessorsByKey.clear();
        knownWwSuccessorsByKey.putAll(buildKnownWwSuccessorsByKey(graph));
    }

    List<KeyWriteIndex<KeyType, ValueType>> scopedWrites(QueryScope<KeyType> scope) {
        var cacheKey = scope.cacheKey();
        if (cacheKey.isEmpty()) {
            return buildScopedWrites(scope);
        }
        return scopedWritesCache.computeIfAbsent(cacheKey.get(), ignored -> buildScopedWrites(scope));
    }

    private List<KeyWriteIndex<KeyType, ValueType>> buildScopedWrites(QueryScope<KeyType> scope) {
        return sortedKeyWriteIndexes.stream()
                .filter(entry -> scope.covers(entry.key))
                .collect(Collectors.toUnmodifiableList());
    }

    /** Common recorded-source contract; no full-snapshot materialization is retained. */
    boolean recordedPredicateInputsValid(
            Event<KeyType, ValueType> predicateRead,
            Map<KeyType, KnownGraph.WriteRef<KeyType, ValueType>> resultSourcesByKey) {
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
        // Complete contributing inputs must reproduce the recorded bag and provenance.
        return predicateSnapshotMatches(predicateRead, expectedInputs);
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

    /** Returns true only for an already fixed key-local WW fact. */
    boolean knownWwBefore(
            KnownGraph.WriteRef<KeyType, ValueType> from,
            KnownGraph.WriteRef<KeyType, ValueType> to) {
        if (from == to) {
            return false;
        }
        if (from.getTxn().equals(to.getTxn())) {
            return from.getIndex() < to.getIndex();
        }
        if (isBottomTxn(from.getTxn())) {
            return !isBottomTxn(to.getTxn());
        }
        return knownWwSuccessorsByKey
                .getOrDefault(from.getEvent().getKey(), Collections.emptyMap())
                .getOrDefault(from.getTxn(), Collections.emptySet())
                .contains(to.getTxn());
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
        if (plan != null && plan.compactResultProjection() == null) {
            var resolver = relationResolverFor(predicateRead);
            var sourceContribution = rowContribution(predicateRead, resolver, source);
            var laterContribution = rowContribution(predicateRead, resolver, later);
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
        return evaluated.valid && evaluated.contribution.containsInput(event.getKey());
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
            if (cache.invalid.get(writeRefId)) {
                return COMPACT_MATCH_INVALID;
            }
            return cache.matched.get(writeRefId)
                    ? COMPACT_MATCH_TRUE : COMPACT_MATCH_FALSE;
        }

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
            return evaluateRowContribution(predicateRead, relationResolver, write, plan);
        }

        var planId = rowContributionPlanIds.computeIfAbsent(
                plan, ignored -> nextRowContributionPlanId++);
        long eventId = System.identityHashCode(predicateRead) & 0xffffffffL;
        var cacheKey = (eventId << 32) | (writeRefId & 0xffffffffL);
        cacheKey ^= ((long) planId << 16);
        var cached = generalRowContributions.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        var evaluated = evaluateRowContribution(predicateRead, relationResolver, write, plan);
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
                            new MapVisibleState<>(Map.of(event.getKey(), event.getValue()),
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

    private static final class CompactRowMatchCache {
        private final BitSet computed = new BitSet();
        private final BitSet matched = new BitSet();
        private final BitSet invalid = new BitSet();
    }

    private static final class CachedRowContribution<KeyType> {
        private final boolean valid;
        private final RowContribution<KeyType> contribution;

        private CachedRowContribution(boolean valid, RowContribution<KeyType> contribution) {
            this.valid = valid;
            this.contribution = contribution;
        }

        private static <KeyType> CachedRowContribution<KeyType> valid(
                RowContribution<KeyType> contribution) {
            return new CachedRowContribution<>(true,
                    Objects.requireNonNull(contribution, "contribution"));
        }

        private static <KeyType> CachedRowContribution<KeyType> invalid() {
            return new CachedRowContribution<>(false, null);
        }
    }

    /** Groups writes by key and gives each key a deterministic iteration order. */
    private Map<KeyType, List<KnownGraph.WriteRef<KeyType, ValueType>>> buildWritesByKey(KnownGraph<KeyType, ValueType> graph) {
        var result = new HashMap<KeyType, List<KnownGraph.WriteRef<KeyType, ValueType>>>();
        for (var write : graph.getAllWrites()) {
            result.computeIfAbsent(write.getEvent().getKey(), ignored -> new ArrayList<>()).add(write);
        }
        for (var writes : result.values()) {
            writes.sort(Comparator
                    .comparing((KnownGraph.WriteRef<KeyType, ValueType> w) -> w.getTxn().getId())
                    .thenComparingInt(KnownGraph.WriteRef::getIndex));
        }
        return result;
    }

    private Map<KeyType, Map<Transaction<KeyType, ValueType>,
            Set<Transaction<KeyType, ValueType>>>> buildKnownWwSuccessorsByKey(
                    KnownGraph<KeyType, ValueType> knownGraph) {
        var result = new HashMap<KeyType, Map<Transaction<KeyType, ValueType>,
                Set<Transaction<KeyType, ValueType>>>>();
        for (var endpoint : knownGraph.getKnownGraphA().edges()) {
            for (var edge : knownGraph.getKnownGraphA().edgeValue(endpoint)
                    .orElse(Collections.emptyList())) {
                if (edge.getType() != EdgeType.WW) {
                    continue;
                }
                result.computeIfAbsent(edge.getKey(), ignored -> new HashMap<>())
                        .computeIfAbsent(endpoint.source(), ignored -> new HashSet<>())
                        .add(endpoint.target());
            }
        }
        return result;
    }

    private static <KeyType, ValueType> List<KeyWriteIndex<KeyType, ValueType>>
            buildKeyWriteIndexes(
                    Map<KeyType, List<KnownGraph.WriteRef<KeyType, ValueType>>> writesByKey) {
        var entries = writesByKey.entrySet().stream()
                .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                .collect(Collectors.toList());
        var indexes = new ArrayList<KeyWriteIndex<KeyType, ValueType>>(entries.size());
        for (int keyId = 0; keyId < entries.size(); keyId++) {
            var entry = entries.get(keyId);
            indexes.add(new KeyWriteIndex<>(keyId, entry.getKey(), entry.getValue()));
        }
        return Collections.unmodifiableList(indexes);
    }

    /**
     * Immutable per-audit index over one key's already sorted write list.
     * Writer groups retain their first-occurrence order while the candidate for
     * each group is its final write, matching LinkedHashMap put replacement.
     */
    static final class KeyWriteIndex<KeyType, ValueType> {
        final int keyId;
        final KeyType key;
        final List<KnownGraph.WriteRef<KeyType, ValueType>> writes;
        final List<Transaction<KeyType, ValueType>> writers;
        final List<List<KnownGraph.WriteRef<KeyType, ValueType>>> writesByWriter;
        final List<KnownGraph.WriteRef<KeyType, ValueType>> latestWritesByWriter;

        KeyWriteIndex(int keyId, KeyType key,
                List<KnownGraph.WriteRef<KeyType, ValueType>> writes) {
            this.keyId = keyId;
            this.key = key;
            this.writes = writes;

            var groupedWrites = new LinkedHashMap<Transaction<KeyType, ValueType>,
                    List<KnownGraph.WriteRef<KeyType, ValueType>>>();
            for (var write : writes) {
                groupedWrites.computeIfAbsent(
                        write.getTxn(), ignored -> new ArrayList<>()).add(write);
            }
            this.writers = new ArrayList<>(groupedWrites.keySet());
            var writerWrites = new ArrayList<
                    List<KnownGraph.WriteRef<KeyType, ValueType>>>(groupedWrites.size());
            var latestWrites = new ArrayList<KnownGraph.WriteRef<KeyType, ValueType>>();
            for (var writerGroup : groupedWrites.values()) {
                writerWrites.add(writerGroup);
                latestWrites.add(writerGroup.get(writerGroup.size() - 1));
            }
            this.writesByWriter = writerWrites;
            this.latestWritesByWriter = List.copyOf(latestWrites);
        }

        KnownGraph.WriteRef<KeyType, ValueType> latestSelfBefore(
                Transaction<KeyType, ValueType> reader, int eventIndex) {
            int writer = writerIndex(reader);
            if (writer < 0) {
                return null;
            }
            var writerWrites = writesByWriter.get(writer);
            int low = 0;
            int high = writerWrites.size();
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (writerWrites.get(middle).getIndex() < eventIndex) {
                    low = middle + 1;
                } else {
                    high = middle;
                }
            }
            return low == 0 ? null : writerWrites.get(low - 1);
        }

        List<KnownGraph.WriteRef<KeyType, ValueType>> latestExternalWrites(
                Transaction<KeyType, ValueType> reader) {
            int readerWriter = writerIndex(reader);
            if (readerWriter < 0) {
                return latestWritesByWriter;
            }
            if (latestWritesByWriter.size() == 1) {
                return Collections.emptyList();
            }
            var externalWrites = new ArrayList<KnownGraph.WriteRef<KeyType, ValueType>>(
                    latestWritesByWriter.size() - 1);
            for (int index = 0; index < latestWritesByWriter.size(); index++) {
                if (index != readerWriter) {
                    externalWrites.add(latestWritesByWriter.get(index));
                }
            }
            return List.copyOf(externalWrites);
        }

        int writerIndex(Transaction<KeyType, ValueType> writer) {
            for (int index = 0; index < writers.size(); index++) {
                var candidate = writers.get(index);
                if (candidate == writer || candidate.equals(writer)) {
                    return index;
                }
            }
            return -1;
        }
    }

    boolean isKnownShadowedSource(SEREdge<KeyType, ValueType> edge) {
        var successors = knownWwSuccessorsByKey
                .getOrDefault(edge.getKey(), Collections.emptyMap())
                .getOrDefault(edge.getFrom(), Collections.emptySet());
        for (var later : successors) {
            if (knownBefore(later, edge.getTo())) {
                return true;
            }
        }
        return false;
    }

    /** Rejects unsupported semantics before allocating the native solver. */
    private void validateSupportedPredicates() {
        for (var observation : graph.getPredicateObservations()) {
            var predicate = observation.getPredicateReadEvent().getPredicate();
            if (predicate == null) {
                continue;
            }
            if (predicate instanceof QueryPlan && ((QueryPlan<?, ?>) predicate).distinct()) {
                throw new QueryException("SER does not support DISTINCT under the unique key/value model");
            }
            if (!predicate.isRowLocal()
                    && (!(predicate instanceof QueryPlan)
                            || !((QueryPlan<?, ?>) predicate).isMonotone())) {
                throw new QueryException("SER does not support custom whole-snapshot predicates; "
                        + "use a row-local predicate or a supported monotone QueryPlan");
            }
        }
    }

    boolean knownBefore(
            Transaction<KeyType, ValueType> from,
            Transaction<KeyType, ValueType> to) {
        if (from.equals(to)) {
            return false;
        }
        if (isBottomTxn(from)) {
            return !isBottomTxn(to);
        }
        if (isBottomTxn(to)) {
            return false;
        }
        return precedence.before(from, to);
    }

}
