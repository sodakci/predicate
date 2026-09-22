package verifier;

import static history.query.PredicateReadSemantics.relationResolverFor;
import static verifier.GmwrPropagationState.isBottomTxn;

import graph.Edge;
import graph.EdgeType;
import graph.KnownGraph;
import history.Event;
import verifier.PredicateAnalysis.KeyWriteIndex;
import history.History;
import history.Transaction;
import util.Profiler;
import java.util.*;

/** WW 剪枝之后、SAT 编码之前的独立谓词剪枝阶段。 */
final class PredicatePruning<KeyType, ValueType> {
    private final History<KeyType, ValueType> history;
    private final KnownGraph<KeyType, ValueType> graph;
    private final PrecedenceOracle<Transaction<KeyType, ValueType>> precedence;
    private final SERVerifier.SolverSettings settings;
    private final PredicateAnalysis<KeyType, ValueType> predicateAnalysis;
    private final List<ConflictReason<KeyType, ValueType>> conflicts = new ArrayList<>();
    private GmwrPropagationState<KeyType, ValueType> propagation;
    private long gmwrItemObligations;
    private long gmwrAbsentItemObligations;
    private long gmwrIntervalCandidatesPruned;
    private long gmwrPrWrSourceAlternatives;
    private long gmwrPrWrReachabilityPruned;
    private long gmwrPrWrPrRwCyclePruned;
    private long prWrInitialConstraints;
    private long prWrResidualConstraints;
    private long prWrForcedConstraints;
    private long prWrInitialCandidates;
    private long prWrResidualCandidates;
    private long prWrPrunedCandidates;
    private final List<Transaction<KeyType, ValueType>> txns;
    private final Map<Transaction<KeyType, ValueType>, Integer> txnIndex = new HashMap<>();
    private final Map<KnownGraph.PredicateObservation<KeyType, ValueType>,
            PreparedObservation<KeyType, ValueType>> observations = new LinkedHashMap<>();

    PredicatePruning(History<KeyType, ValueType> history,
                     KnownGraph<KeyType, ValueType> graph,
                     PrecedenceOracle<Transaction<KeyType, ValueType>> precedence,
                     SERVerifier.SolverSettings settings,
                     PredicateAnalysis<KeyType, ValueType> analysis) {
        this.history = history;
        this.graph = graph;
        this.precedence = Objects.requireNonNull(precedence, "precedence");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.predicateAnalysis = Objects.requireNonNull(analysis, "analysis");
        this.txns = new ArrayList<>(history.getTransactions());
        for (int i = 0; i < txns.size(); i++) {
            txnIndex.put(txns.get(i), i);
        }
    }

    Result<KeyType, ValueType> prune() {
        var profiler = Profiler.getInstance();
        boolean gmwr = settings.predicateSolvingMode == SERVerifier.PredicateSolvingMode.GMWR;
        long buildStarted = System.nanoTime();
        try {
            if (gmwr) {
                propagation = new GmwrPropagationState<>(history, graph, precedence);
                propagation.seedKnownDependencies();
            }
            prepareObservations();
        } finally {
            profiler.addDurationNanos("GMWR_BUILD_MS", gmwr ? System.nanoTime() - buildStarted : 0L);
        }
        if (gmwr) {
            long pruningStarted = System.nanoTime();
            try {
                pruneGmwrItemCandidates();
            } finally {
                profiler.addDurationNanos("GMWR_PRUNING_MS", System.nanoTime() - pruningStarted);
            }
            buildStarted = System.nanoTime();
            try {
                collectGmwrLogicalConstraints();
            } finally {
                profiler.addDurationNanos("GMWR_BUILD_MS", System.nanoTime() - buildStarted);
            }
            if (settings.gmwrPrepropagation) {
                propagation.propagate();
            }
            conflicts.addAll(propagation.propagationConflicts());
            predicateAnalysis.refreshKnownWwFacts();
        }
        // 传播已结束；这里完成 PR_WR、PR_RW 环及区间剪枝，SAT 只消费固定候选域。
        long domainsStarted = System.nanoTime();
        finalizeSourceDomains(gmwr);
        var residualItems = prepareResidualItems();
        if (gmwr) {
            profiler.addDurationNanos("GMWR_PRUNING_MS", System.nanoTime() - domainsStarted);
        }
        if (propagation != null) {
            propagation.releasePropagationIndexes();
        }
        var result = new Result<>(predicateAnalysis, precedence, propagation, conflicts,
                gmwrItemObligations, gmwrAbsentItemObligations, observations,
                residualItems, gmwrIntervalCandidatesPruned);
        publishMetrics(result);
        return result;
    }

    private void publishMetrics(Result<KeyType, ValueType> result) {
        var profiler = Profiler.getInstance();
        if (prWrInitialConstraints - prWrResidualConstraints != prWrForcedConstraints
                || prWrInitialCandidates != prWrResidualCandidates
                        + prWrPrunedCandidates + prWrForcedConstraints) {
            throw new IllegalStateException(
                    "PR_WR 来源约束及候选统计不守恒");
        }
        if (profiler.getCounter("GMWR_BUILD_MS") == 0) {
            profiler.addDurationNanos("GMWR_BUILD_MS", 0L);
        }
        if (profiler.getCounter("GMWR_REDUCTION_MS") == 0) {
            profiler.addDurationNanos("GMWR_REDUCTION_MS", 0L);
        }
        if (profiler.getCounter("GMWR_PRUNING_MS") == 0) {
            profiler.addDurationNanos("GMWR_PRUNING_MS", 0L);
        }
        profiler.addCount("GMWR_INITIAL_CONSTRAINTS",
                result.itemObligationCount());
        profiler.addCount("GMWR_RESIDUAL_CONSTRAINTS",
                result.residualItemCount());
        profiler.addCount("SER_PRED_PR_WR_INITIAL_CONSTRAINTS_COUNT", prWrInitialConstraints);
        profiler.addCount("SER_PRED_PR_WR_RESIDUAL_CONSTRAINTS_COUNT", prWrResidualConstraints);
        profiler.addCount("SER_PRED_PR_WR_FORCED_CONSTRAINTS_COUNT", prWrForcedConstraints);
        profiler.addCount("SER_PRED_PR_WR_INITIAL_CANDIDATES_COUNT", prWrInitialCandidates);
        profiler.addCount("SER_PRED_PR_WR_RESIDUAL_CANDIDATES_COUNT", prWrResidualCandidates);
        profiler.addCount("SER_PRED_PR_WR_PRUNED_CANDIDATES_COUNT", prWrPrunedCandidates);
        profiler.addCount("SER_PRED_PR_WR_FIXED_CANDIDATES_COUNT", prWrForcedConstraints);
        profiler.addCount("GMWR_REMOVED_CANDIDATES",
                propagation == null ? 0L : propagation.stats.removedCandidates);
        long forced = propagation == null ? 0L : propagation.stats.forcedFacts;
        profiler.addCount("GMWR_FORCED_FACTS", forced);
        if (propagation != null) {
            profiler.addCount("SER_GMWR_FORCED_ORDERS_COUNT", forced);
            profiler.addCount("SER_GMWR_ITEM_OBLIGATIONS_COUNT", result.itemObligationCount());
            profiler.addCount("SER_GMWR_SEMANTIC_ITEM_OBLIGATIONS_COUNT", result.itemObligationCount());
            profiler.addCount("SER_GMWR_ABSENT_ITEM_OBLIGATIONS_COUNT", result.absentItemObligationCount());
            profiler.addCount("SER_GMWR_INTERVAL_CANDIDATES_PRUNED_COUNT", result.intervalCandidatesPruned());
            profiler.addCount("SER_PRED_PR_WR_SOURCE_ALTERNATIVES_COUNT", gmwrPrWrSourceAlternatives);
            profiler.addCount("SER_PRED_PR_WR_REACHABILITY_PRUNED_COUNT", gmwrPrWrReachabilityPruned);
            profiler.addCount("SER_PRED_PR_WR_PR_RW_CYCLE_PRUNED_COUNT", gmwrPrWrPrRwCyclePruned);
        }
    }

    private void prepareObservations() {
        for (var observation : graph.getPredicateObservations()) {
            var read = observation.getPredicateReadEvent();
            if (read.getPredicate() == null) {
                continue;
            }
            var sources = new LinkedHashMap<KeyType, KnownGraph.WriteRef<KeyType, ValueType>>();
            int duplicates = 0;
            for (var source : observation.getTupleSources()) {
                if (sources.putIfAbsent(source.getKey(), source.getSourceWrite()) != null) {
                    duplicates++;
                }
            }
            var scoped = predicateAnalysis.scopedWrites(read.getPredicate().scope());
            boolean valid = predicateAnalysis.recordedPredicateInputsValid(read, sources);
            if (!valid && propagation != null) {
                conflicts.add(new ConflictReason<>(observation.getTxn(), observation.getTxn(),
                        null, "INVALID_RECORDED_PREDICATE_INPUTS"));
            }
            var rowKeys = new ArrayList<RowKey<KeyType, ValueType>>();
            if (valid && read.getPredicate().isRowLocal()) {
                for (var entry : scoped) {
                    rowKeys.add(analyzeRowKey(observation, entry, sources.get(entry.key)));
                }
            }
            observations.put(observation, new PreparedObservation<>(sources, scoped, rowKeys,
                    valid, duplicates));
        }
    }

    private RowKey<KeyType, ValueType> analyzeRowKey(
            KnownGraph.PredicateObservation<KeyType, ValueType> observation,
            KeyWriteIndex<KeyType, ValueType> entry,
            KnownGraph.WriteRef<KeyType, ValueType> recorded) {
        var reader = observation.getTxn();
        var read = observation.getPredicateReadEvent();
        var resolver = relationResolverFor(read);
        boolean internal = observation.getPredicateReadType(entry.key) == KnownGraph.PredicateReadType.INTERNAL;
        if (internal) {
            var self = entry.latestSelfBefore(reader, observation.getEventIndex());
            if (self == null) {
                self = recorded;
            }
            boolean valid = recorded != null ? self == recorded
                    : self == null || predicateAnalysis.hasEmptyPredicateContribution(read, resolver, self);
            return new RowKey<>(entry, recorded, true, valid, false, List.of(), Set.of(), List.of(), List.of());
        }
        var writes = entry.latestExternalWrites(reader);
        boolean valid = recorded == null || writes.stream().anyMatch(write -> write == recorded);
        if (!valid && propagation != null) {
            conflicts.add(new ConflictReason<>(recorded.getTxn(), reader, entry.key, "INVALID_RECORDED_SOURCE"));
        }
        if (recorded != null) {
            return new RowKey<>(entry, recorded, false, valid, false, writes, Set.of(), List.of(), writes);
        }

        Set<KnownGraph.WriteRef<KeyType, ValueType>> empty = null;
        // GMWR 初次分析不分类 badWrites，批次剪枝后暂存，最终候选域确定后重建；EAGER 直接分类。
        var bad = propagation == null
                ? new ArrayList<KnownGraph.WriteRef<KeyType, ValueType>>() : null;
        for (var write : writes) {
            if (predicateAnalysis.hasEmptyPredicateContribution(read, resolver, write)) {
                if (empty == null) {
                    empty = Collections.newSetFromMap(new IdentityHashMap<>());
                }
                empty.add(write);
            } else if (bad != null) {
                bad.add(write);
            }
        }
        if (propagation != null) {
            // 候选数量只取一次；后续因强制顺序反复检查来源域不重复累计。
            gmwrPrWrSourceAlternatives += empty == null ? 0 : empty.size();
        }
        boolean unresolvedPrWr = propagation != null && writes.stream().noneMatch(write ->
                graph.getKnownGraphA().edgeValue(write.getTxn(), reader)
                        .orElse(Collections.emptyList())
                        .contains(new Edge<KeyType>(EdgeType.PR_WR, entry.key)));
        if (unresolvedPrWr) {
            // 初始值在处理前快照；无初始版本的 key 还可选择“尚无可见版本”。
            prWrInitialConstraints++;
            prWrInitialCandidates += empty == null ? 0 : empty.size();
            if (writes.stream().noneMatch(write -> isBottomTxn(write.getTxn()))) {
                prWrInitialCandidates++;
            }
        }
        return new RowKey<>(entry, null, false, valid, unresolvedPrWr, writes,
                empty == null ? Set.of() : Collections.unmodifiableSet(empty),
                bad == null ? List.of() : bad, writes);
    }

    /** 整批完成初始区间剪枝，构建 item 时只消费保留下来的 bad writer。 */
    private void pruneGmwrItemCandidates() {
        for (var entry : observations.entrySet()) {
            var prepared = entry.getValue();
            var rows = new ArrayList<RowKey<KeyType, ValueType>>(prepared.rowKeys.size());
            for (var key : prepared.rowKeys) {
                if (key.internal || key.recordedSource != null) {
                    rows.add(key);
                    continue;
                }
                List<KnownGraph.WriteRef<KeyType, ValueType>> bad = null;
                for (var write : pruneInterval(key.externalWrites, entry.getKey().getTxn())) {
                    if (!key.emptyContributions.contains(write)) {
                        if (bad == null) {
                            bad = new ArrayList<>();
                        }
                        bad.add(write);
                    }
                }
                rows.add(bad == null ? key : new RowKey<>(key.index, key.recordedSource,
                        false, key.valid, key.unresolvedPrWr, key.externalWrites, key.emptyContributions,
                        bad, key.sourceWrites));
            }
            entry.setValue(new PreparedObservation<>(prepared.sources, prepared.scopedEntries,
                    rows, prepared.valid, prepared.duplicateSources));
        }
    }

    private void collectGmwrLogicalConstraints() {
        for (var observation : observations.entrySet()) {
            var reader = observation.getKey().getTxn();
            for (var key : observation.getValue().rowKeys) {
                if (key.internal || key.recordedSource != null) {
                    continue;
                }
                var repairs = new ArrayList<Transaction<KeyType, ValueType>>();
                for (var write : key.externalWrites) {
                    if (key.emptyContributions.contains(write)) {
                        repairs.add(write.getTxn());
                    }
                }
                for (var write : key.badWrites) {
                    gmwrItemObligations++;
                    gmwrAbsentItemObligations++;
                    propagation.addGmwrItem(reader, write.getTxn(), repairs, key.index.key);
                }
            }
        }
    }

    private void finalizeSourceDomains(boolean gmwr) {
        if (!gmwr) {
            return;
        }
        boolean orderAdded;
        do {
            orderAdded = false;
            for (var entry : observations.entrySet()) {
                var reader = entry.getKey().getTxn();
                var prepared = entry.getValue();
                var rows = new ArrayList<RowKey<KeyType, ValueType>>();
                for (var key : prepared.rowKeys) {
                    if (key.internal || key.recordedSource != null) {
                        rows.add(key);
                        continue;
                    }
                    var eligible = new LinkedHashSet<Transaction<KeyType, ValueType>>();
                    key.emptyContributions.forEach(write -> eligible.add(write.getTxn()));
                    var candidates = pruneGmwrPrWrSourceAlternatives(reader,
                            key.sourceWrites, entry.getKey().getPredicateReadEvent(), eligible);
                    candidates = pruneInterval(candidates, reader);
                    var bad = new ArrayList<KnownGraph.WriteRef<KeyType, ValueType>>();
                    for (var write : candidates) {
                        if (!key.emptyContributions.contains(write)) {
                            bad.add(write);
                        }
                    }
                    var updated = new RowKey<>(key.index, null, false, key.valid,
                            key.unresolvedPrWr, key.externalWrites, key.emptyContributions, bad, candidates);
                    orderAdded |= forceUniquePrWrSource(reader, updated);
                    rows.add(updated);
                }
                entry.setValue(new PreparedObservation<>(prepared.sources, prepared.scopedEntries, rows,
                        prepared.valid, prepared.duplicateSources));
            }
            // 新强制顺序可能使先前处理的其他来源域继续收缩。
        } while (orderAdded);

        for (var entry : observations.entrySet()) {
            for (var key : entry.getValue().rowKeys) {
                if (!key.unresolvedPrWr) {
                    continue;
                }
                long remaining = legalPrWrSourceCount(entry.getKey().getTxn(), key);
                if (remaining == 1) {
                    prWrForcedConstraints++;
                } else {
                    // 空域属于冲突，不能冒充强制解决。
                    prWrResidualConstraints++;
                    prWrResidualCandidates += remaining;
                }
            }
        }
        prWrPrunedCandidates = prWrInitialCandidates
                - prWrResidualCandidates - prWrForcedConstraints;
    }

    /** 无初始版本且尚无写必然可见时，“未出现该 key”也是一种来源选择。 */
    private boolean implicitBottomPossible(Transaction<KeyType, ValueType> reader,
                                           RowKey<KeyType, ValueType> key) {
        return key.externalWrites.stream().noneMatch(write ->
                isBottomTxn(write.getTxn()) || predicateAnalysis.knownBefore(write.getTxn(), reader));
    }

    private long legalPrWrSourceCount(Transaction<KeyType, ValueType> reader,
                                      RowKey<KeyType, ValueType> key) {
        return key.sourceWrites.stream().filter(key.emptyContributions::contains).count()
                + (implicitBottomPossible(reader, key) ? 1 : 0);
    }

    /** 唯一合法来源直接推出 PR_WR 及顺序；无需事先已知 source < reader。 */
    private boolean forceUniquePrWrSource(Transaction<KeyType, ValueType> reader,
                                          RowKey<KeyType, ValueType> key) {
        long count = legalPrWrSourceCount(reader, key);
        if (count != 1) {
            if (count == 0 && conflicts.stream().noneMatch(conflict ->
                    conflict.to().equals(reader) && Objects.equals(conflict.key(), key.index.key)
                            && conflict.rule().equals("NO_LEGAL_PR_WR_SOURCE"))) {
                conflicts.add(new ConflictReason<>(reader, reader, key.index.key, "NO_LEGAL_PR_WR_SOURCE"));
            }
            return false;
        }
        for (var source : key.sourceWrites) {
            if (!key.emptyContributions.contains(source) || isBottomTxn(source.getTxn())) {
                continue;
            }
            var edge = new Edge<KeyType>(EdgeType.PR_WR, key.index.key);
            var existing = graph.getKnownGraphA().edgeValue(source.getTxn(), reader)
                    .orElse(Collections.emptyList());
            if (!existing.contains(edge)) {
                graph.putEdge(source.getTxn(), reader, edge);
            }
            boolean newOrder = !predicateAnalysis.knownBefore(source.getTxn(), reader);
            precedence.add(source.getTxn(), reader);
            return newOrder;
        }
        // 唯一 bottom 解决来源选择，但不生成真实事务间的 typed 边。
        return false;
    }

    private List<ResidualItem<KeyType, ValueType>> prepareResidualItems() {
        var result = new ArrayList<ResidualItem<KeyType, ValueType>>();
        if (propagation == null) {
            return result;
        }
        for (var gmwr : propagation.gmwrObligations()) {
            if (gmwr.resolved || gmwr.satisfied || predicateAnalysis.knownBefore(gmwr.reader, gmwr.badWriter)) {
                continue;
            }
            for (var item : gmwr.items) {
                boolean satisfied = item.repairs.stream().anyMatch(repair ->
                        predicateAnalysis.knownBefore(gmwr.badWriter, repair)
                                && predicateAnalysis.knownBefore(repair, gmwr.reader));
                if (satisfied) {
                    continue;
                }
                var repairs = new ArrayList<Transaction<KeyType, ValueType>>();
                for (var repair : item.repairs) {
                    if (!gmwr.badWriter.equals(repair) && !repair.equals(gmwr.reader)
                            && !gmwr.badWriter.equals(gmwr.reader)
                            && !predicateAnalysis.knownBefore(repair, gmwr.badWriter)
                            && !predicateAnalysis.knownBefore(gmwr.reader, repair)) {
                        repairs.add(repair);
                    }
                }
                result.add(new ResidualItem<>(gmwr.reader, gmwr.badWriter, gmwr.keys,
                        gmwr.outsidePossible && !predicateAnalysis.knownBefore(gmwr.badWriter, gmwr.reader), repairs));
            }
        }
        return result;
    }

    /**
     * Applies only semantic-level PR_WR source pruning. It never fixes or
     * removes an unresolved WW choice. Writers that are not legal PR_WR
     * sources for this item remain in the frontier because they can still be
     * the bad AR-max writer handled by the GMWR projection clause.
     */
    private List<KnownGraph.WriteRef<KeyType, ValueType>>
            pruneGmwrPrWrSourceAlternatives(
                    Transaction<KeyType, ValueType> reader,
                    List<KnownGraph.WriteRef<KeyType, ValueType>> candidates,
                    Event<KeyType, ValueType> predicateRead,
                    Set<Transaction<KeyType, ValueType>> eligibleSourceTxns) {
        if (candidates.isEmpty()) {
            return candidates;
        }
        var retained = new ArrayList<KnownGraph.WriteRef<KeyType, ValueType>>(
                candidates.size());
        for (var candidate : candidates) {
            if (eligibleSourceTxns != null
                    && !eligibleSourceTxns.contains(candidate.getTxn())) {
                retained.add(candidate);
                continue;
            }

            if (predicateAnalysis.knownBefore(reader, candidate.getTxn())) {
                gmwrPrWrReachabilityPruned++;
                continue;
            }
            if (gmwrPrWrWouldForcePrRwCycle(
                    candidate, reader, candidates, predicateRead)) {
                gmwrPrWrPrRwCyclePruned++;
                continue;
            }
            retained.add(candidate);
        }
        return retained;
    }

    /**
     * WW(A,B,k) and a result-changing B force PR_RW(R,B,k) when A is the
     * selected source. If B already reaches R, that source would close a typed
     * dependency cycle and can be deleted without deciding any WW alternative.
     */
    private boolean gmwrPrWrWouldForcePrRwCycle(
            KnownGraph.WriteRef<KeyType, ValueType> source,
            Transaction<KeyType, ValueType> reader,
            List<KnownGraph.WriteRef<KeyType, ValueType>> writes,
            Event<KeyType, ValueType> predicateRead) {
        for (var later : writes) {
            if (later == source
                    || !predicateAnalysis.knownWwBefore(source, later)
                    || !predicateAnalysis.writeChangesPredicateResult(source, later, predicateRead)) {
                continue;
            }
            if (predicateAnalysis.knownBefore(later.getTxn(), reader)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Uses the mandatory typed-dependency closure as a conservative interval
     * around one external predicate read.  Incomparable writers remain in the
     * interval because an unresolved WW/serialization choice may still place
     * them immediately before the reader.
     */
    private List<KnownGraph.WriteRef<KeyType, ValueType>> pruneInterval(
            List<KnownGraph.WriteRef<KeyType, ValueType>> externalWrites,
            Transaction<KeyType, ValueType> reader) {
        var knownBeforeReader = new BitSet(txns.size());
        for (var write : externalWrites) {
            var writer = write.getTxn();
            if (!isBottomTxn(writer) && predicateAnalysis.knownBefore(writer, reader)) {
                knownBeforeReader.set(txnIndex.get(writer));
            }
        }

        var result = new ArrayList<KnownGraph.WriteRef<KeyType, ValueType>>();
        for (var write : externalWrites) {
            var writer = write.getTxn();
            if (predicateAnalysis.knownBefore(reader, writer)) {
                gmwrIntervalCandidatesPruned++;
                continue;
            }
            if (!predicateAnalysis.knownBefore(writer, reader)) {
                result.add(write);
                continue;
            }

            // Keep only maximal writers in the mandatory-before portion of
            // this key's interval.  Bottom is maximal only when no real writer
            // of the key is already known visible before the reader.
            if (isBottomTxn(writer)) {
                if (knownBeforeReader.isEmpty()) {
                    result.add(write);
                } else {
                    gmwrIntervalCandidatesPruned++;
                }
            } else if (!writerReachesKnownBeforeReader(writer, knownBeforeReader)) {
                result.add(write);
            } else {
                gmwrIntervalCandidatesPruned++;
            }
        }
        return result;
    }

    private boolean writerReachesKnownBeforeReader(
            Transaction<KeyType, ValueType> writer,
            BitSet knownBeforeReader) {
        for (int other = knownBeforeReader.nextSetBit(0); other >= 0;
                other = knownBeforeReader.nextSetBit(other + 1)) {
            if (predicateAnalysis.knownBefore(writer, txns.get(other))) {
                return true;
            }
        }
        return false;
    }


    /** 阶段交接对象；只包含共享分析和剪枝快照，不暴露可变的传播工作队列。 */
    static final class Result<KeyType, ValueType> {
        private final PredicateAnalysis<KeyType, ValueType> analysis;
        private final PrecedenceOracle<Transaction<KeyType, ValueType>> precedence;
        private final List<GmwrPropagationState.DependencyFact<KeyType, ValueType>> facts;
        private final long reductionSteps;
        private final List<ConflictReason<KeyType, ValueType>> conflicts;
        private final long items;
        private final long absentItems;
        private final long intervalPruned;
        private final Map<KnownGraph.PredicateObservation<KeyType, ValueType>,
                PreparedObservation<KeyType, ValueType>> observations;
        private final List<ResidualItem<KeyType, ValueType>> residualItems;

        private Result(PredicateAnalysis<KeyType, ValueType> analysis,
                       PrecedenceOracle<Transaction<KeyType, ValueType>> precedence,
                       GmwrPropagationState<KeyType, ValueType> propagation,
                       Collection<ConflictReason<KeyType, ValueType>> conflicts,
                       long items, long absentItems,
                       Map<KnownGraph.PredicateObservation<KeyType, ValueType>, PreparedObservation<KeyType, ValueType>> observations,
                       List<ResidualItem<KeyType, ValueType>> residualItems, long intervalPruned) {
            this.analysis = analysis;
            this.precedence = precedence;
            this.facts = propagation == null ? List.of() : List.copyOf(propagation.definiteFacts());
            this.reductionSteps = propagation == null ? 0L : propagation.stats.reductionSteps;
            this.conflicts = List.copyOf(conflicts);
            this.items = items;
            this.absentItems = absentItems;
            this.intervalPruned = intervalPruned;
            this.observations = Collections.unmodifiableMap(new LinkedHashMap<>(observations));
            this.residualItems = List.copyOf(residualItems);
        }

        PredicateAnalysis<KeyType, ValueType> analysis() { return analysis; }
        PrecedenceOracle<Transaction<KeyType, ValueType>> precedenceOracle() { return precedence; }
        boolean hasConflict() { return !conflicts.isEmpty(); }
        Collection<ConflictReason<KeyType, ValueType>> conflictReasons() { return conflicts; }
        long itemObligationCount() { return items; }
        long absentItemObligationCount() { return absentItems; }
        long residualItemCount() { return residualItems.size(); }
        long intervalCandidatesPruned() { return intervalPruned; }
        PreparedObservation<KeyType, ValueType> observation(KnownGraph.PredicateObservation<KeyType, ValueType> observation) {
            return Objects.requireNonNull(observations.get(observation), "prepared predicate observation");
        }
        List<ResidualItem<KeyType, ValueType>> residualItems() { return residualItems; }
        Collection<GmwrPropagationState.DependencyFact<KeyType, ValueType>> definiteFacts() {
            return facts;
        }
        long reductionSteps() { return reductionSteps; }
    }

    static final class PreparedObservation<K, V> {
        final Map<K, KnownGraph.WriteRef<K, V>> sources;
        final List<KeyWriteIndex<K, V>> scopedEntries;
        final List<RowKey<K, V>> rowKeys;
        final boolean valid;
        final int duplicateSources;

        private PreparedObservation(Map<K, KnownGraph.WriteRef<K, V>> sources, List<KeyWriteIndex<K, V>> scoped,
                            List<RowKey<K, V>> rows, boolean valid, int duplicates) {
            // sources 由阶段内独占构建，或来自已有只读快照，不再复制底层 map。
            this.sources = Collections.unmodifiableMap(sources);
            this.scopedEntries = List.copyOf(scoped);
            this.rowKeys = List.copyOf(rows);
            this.valid = valid;
            this.duplicateSources = duplicates;
        }
    }

    static final class RowKey<K, V> {
        final KeyWriteIndex<K, V> index;
        final KnownGraph.WriteRef<K, V> recordedSource;
        final boolean internal;
        final boolean valid;
        final boolean unresolvedPrWr;
        final List<KnownGraph.WriteRef<K, V>> externalWrites;
        final Set<KnownGraph.WriteRef<K, V>> emptyContributions;
        final List<KnownGraph.WriteRef<K, V>> badWrites;
        final List<KnownGraph.WriteRef<K, V>> sourceWrites;

        private RowKey(KeyWriteIndex<K, V> index, KnownGraph.WriteRef<K, V> recorded, boolean internal,
               boolean valid, boolean unresolvedPrWr, List<KnownGraph.WriteRef<K, V>> externalWrites,
               Set<KnownGraph.WriteRef<K, V>> empty, List<KnownGraph.WriteRef<K, V>> bad,
               List<KnownGraph.WriteRef<K, V>> candidates) {
            this.index = index;
            this.recordedSource = recorded;
            this.internal = internal;
            this.valid = valid;
            this.unresolvedPrWr = unresolvedPrWr;
            this.externalWrites = List.copyOf(externalWrites);
            // 空贡献集合在分类完成时冻结，最终整理仅复用这份只读数据。
            this.emptyContributions = empty;
            this.badWrites = List.copyOf(bad);
            this.sourceWrites = candidates == externalWrites
                    ? this.externalWrites : List.copyOf(candidates);
        }
    }

    static final class ResidualItem<K, V> {
        final Transaction<K, V> reader;
        final Transaction<K, V> badWriter;
        final Set<K> keys;
        final boolean outsidePossible;
        final List<Transaction<K, V>> repairs;

        ResidualItem(Transaction<K, V> reader, Transaction<K, V> badWriter, Set<K> keys,
                     boolean outsidePossible, List<Transaction<K, V>> repairs) {
            this.reader = reader;
            this.badWriter = badWriter;
            this.keys = Collections.unmodifiableSet(new LinkedHashSet<>(keys));
            this.outsidePossible = outsidePossible;
            this.repairs = List.copyOf(repairs);
        }
    }

    /** 无需 SAT literal 即可解释的确定性冲突。 */
    static final class ConflictReason<KeyType, ValueType> {
        private final Transaction<KeyType, ValueType> from;
        private final Transaction<KeyType, ValueType> to;
        private final KeyType key;
        private final String rule;

        ConflictReason(Transaction<KeyType, ValueType> from,
                       Transaction<KeyType, ValueType> to, KeyType key, String rule) {
            this.from = from;
            this.to = to;
            this.key = key;
            this.rule = rule;
        }

        Transaction<KeyType, ValueType> from() { return from; }
        Transaction<KeyType, ValueType> to() { return to; }
        KeyType key() { return key; }
        String rule() { return rule; }
        String describe() {
            return String.format("%s: from=%s to=%s key=%s", rule, from, to, key);
        }
    }
}
