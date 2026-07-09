package verifier;

import graph.Edge;
import graph.EdgeType;
import graph.KnownGraph;
import history.Event;
import history.History;
import history.HistoryLoader;
import history.Transaction;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
import java.util.stream.Collectors;

import lombok.Getter;
import lombok.Setter;
import com.google.common.graph.ValueGraph;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;

import util.Profiler;
import util.TriConsumer;

@SuppressWarnings("UnstableApiUsage")
public class SERVerifier<KeyType, ValueType> {
    private final History<KeyType, ValueType> history;

    @Getter
    @Setter
    private static boolean coalesceConstraints = true;

    @Getter
    @Setter
    private static boolean dotOutput = false;

    @Getter
    @Setter
    private static boolean compareDerivedPredicateEdges = false;

    public SERVerifier(HistoryLoader<KeyType, ValueType> loader) {
        history = loader.loadHistory();
        System.err.printf("Sessions count: %d\nTransactions count: %d\nEvents count: %d\n",
                history.getClientSessions().size(), history.getClientTransactions().size(), history.getEvents().size());
    }

    public boolean audit() {
        var profiler = Profiler.getInstance();

        profiler.startTick("ONESHOT_CONS");
        profiler.startTick("SER_VERIFY_INT");
        boolean satisfy_int = Utils.verifyInternalConsistency(history);
        profiler.endTick("SER_VERIFY_INT");
        if (!satisfy_int) {
            return false;
        }

        profiler.startTick("SER_GEN_PREC_GRAPH");
        var graph = new KnownGraph<>(history);
        profiler.endTick("SER_GEN_PREC_GRAPH");
        System.err.printf("Mandatory known precedence edges: %d\n",
                graph.getKnownGraphA().edges().size() + graph.getKnownGraphB().edges().size());

        // ===== SER MODE (Snapshot Isolation with predicates) =====
        // SER path: solve one strict total arbitration order (AR). Known
        // precedence and WW/RW choices are encoded as AR constraints; predicate
        // reads are encoded directly in SAT instead of trusting materialized
        // PR_* graph edges.
        System.err.printf("Mode: SER, solving strict total AR with SAT predicate constraints\n");

        profiler.startTick("SER_GEN_CONSTRAINTS");
        var constraints = generateConstraintsSER(history, graph);
        profiler.endTick("SER_GEN_CONSTRAINTS");
        System.err.printf("Unresolved WW choices: %d\nConditional AR implications: %d\n", constraints.size(),
                constraints.stream().map(c -> c.getEdges1().size() + c.getEdges2().size()).reduce(0, Integer::sum));

        if (compareDerivedPredicateEdges) {
            profiler.startTick("SER_DERIVED_PREDICATE_COMPARE");
            var derivedPredicateGraph = new KnownGraph<>(history);
            injectPredicateEdgesSER(history, derivedPredicateGraph);
            profiler.endTick("SER_DERIVED_PREDICATE_COMPARE");
            System.err.printf(
                    "[SER] Derived predicate-edge compare: PR_WR=%d, PR_RW=%d (not used by AR SAT solver)\n",
                    countEdgesOfType(derivedPredicateGraph.getKnownGraphA(), EdgeType.PR_WR),
                    countEdgesOfType(derivedPredicateGraph.getKnownGraphB(), EdgeType.PR_RW));
        }

        if (Pruning.pruneConstraints(graph, constraints, history)) {
            profiler.endTick("ONESHOT_CONS");
            emitRejectDiagnostics(graph, constraints, Pruning.getLastConflicts());
            return false;
        }
        profiler.endTick("ONESHOT_CONS");

        profiler.startTick("ONESHOT_SOLVE");
        var solver = new SERSolverAR<>(history, graph, constraints);

        boolean accepted = solver.solve();
        profiler.endTick("ONESHOT_SOLVE");

        if (!accepted) {
            emitRejectDiagnostics(graph, constraints, solver.getConflicts());
        }

        return accepted;
    }

    private void emitRejectDiagnostics(
            KnownGraph<KeyType, ValueType> graph,
            Collection<SERConstraint<KeyType, ValueType>> constraints,
            Pair<Collection<Pair<com.google.common.graph.EndpointPair<Transaction<KeyType, ValueType>>, Collection<Edge<KeyType>>>>,
                    Collection<SERConstraint<KeyType, ValueType>>> conflicts) {
        var txns = conflictTransactions(conflicts);
        var cycleWitness = buildCycleWitness(graph, constraints, txns);
        printRejectReason(graph, constraints, conflicts, cycleWitness);

        if (dotOutput) {
            cycleWitness.ifPresent(cycle -> System.err.print(formatCycleWitness(cycle)));
            System.out.print(Utils.conflictsToDot(txns, conflicts.getLeft(), conflicts.getRight()));
        } else {
            cycleWitness.ifPresent(cycle -> System.out.print(formatCycleWitness(cycle)));
            if (cycleWitness.isEmpty() || !conflicts.getLeft().isEmpty() || !conflicts.getRight().isEmpty()) {
                System.out.print(Utils.conflictsToLegacy(txns, conflicts.getLeft(), conflicts.getRight()));
            }
        }
    }


    private Set<Transaction<KeyType, ValueType>> conflictTransactions(
            Pair<Collection<Pair<com.google.common.graph.EndpointPair<Transaction<KeyType, ValueType>>, Collection<Edge<KeyType>>>>,
                    Collection<SERConstraint<KeyType, ValueType>>> conflicts) {
        var txns = new HashSet<Transaction<KeyType, ValueType>>();
        conflicts.getLeft().forEach(e -> {
            txns.add(e.getLeft().source());
            txns.add(e.getLeft().target());
        });
        conflicts.getRight().forEach(c -> {
            txns.add(c.getWriteTransaction1());
            txns.add(c.getWriteTransaction2());
            var addEdges = ((Consumer<Collection<SEREdge<KeyType, ValueType>>>) edges -> edges.forEach(e -> {
                txns.add(e.getFrom());
                txns.add(e.getTo());
            }));
            addEdges.accept(c.getEdges1());
            addEdges.accept(c.getEdges2());
        });
        return txns;
    }

    private void printRejectReason(
            KnownGraph<KeyType, ValueType> graph,
            Collection<SERConstraint<KeyType, ValueType>> constraints,
            Pair<Collection<Pair<com.google.common.graph.EndpointPair<Transaction<KeyType, ValueType>>, Collection<Edge<KeyType>>>>,
                    Collection<SERConstraint<KeyType, ValueType>>> conflicts,
            Optional<List<CycleEdge<KeyType, ValueType>>> cycleWitness) {
        int knownEdges = graph.getKnownGraphA().edges().size() + graph.getKnownGraphB().edges().size();
        int conditionalEdges = constraints.stream()
                .map(c -> c.getEdges1().size() + c.getEdges2().size())
                .reduce(0, Integer::sum);
        System.err.println("[SER] Reject reason: strict total AR constraints are UNSAT.");
        System.err.printf(
                "[SER] Diagnostic counts: knownEdges=%d, unresolvedWWChoices=%d, conditionalARImplications=%d, predicateReads=%d\n",
                knownEdges, constraints.size(), conditionalEdges, graph.getPredicateObservations().size());
        cycleWitness.ifPresentOrElse(
                cycle -> System.err.printf("[SER] Cycle witness: %d edges explain the contradiction.\n", cycle.size()),
                () -> System.err.println("[SER] Cycle witness: not available from current mandatory/forced edges."));

        if (conflicts.getLeft().isEmpty() && conflicts.getRight().isEmpty()) {
            System.err.println("[SER] No compact conflict core was extracted; the contradiction may come from SAT-derived RW or predicate-visibility constraints.");
        } else {
            System.err.printf("[SER] Conflict core: knownEdges=%d, wwChoices=%d\n",
                    conflicts.getLeft().size(), conflicts.getRight().size());
        }
    }

    private Optional<List<CycleEdge<KeyType, ValueType>>> buildCycleWitness(
            KnownGraph<KeyType, ValueType> graph,
            Collection<SERConstraint<KeyType, ValueType>> constraints,
            Set<Transaction<KeyType, ValueType>> conflictTxns) {
        var labelsByPair = new LinkedHashMap<Pair<Transaction<KeyType, ValueType>, Transaction<KeyType, ValueType>>, List<String>>();
        Set<Transaction<KeyType, ValueType>> allowedTxns = conflictTxns.isEmpty() ? null : conflictTxns;

        addKnownCycleEdges(graph.getKnownGraphA(), labelsByPair, allowedTxns);
        addKnownCycleEdges(graph.getKnownGraphB(), labelsByPair, allowedTxns);
        addForcedBottomConstraintEdges(constraints, labelsByPair, allowedTxns);
        addPredicateCycleEdges(graph, labelsByPair, allowedTxns);

        return findCycle(labelsByPair);
    }

    private void addKnownCycleEdges(
            ValueGraph<Transaction<KeyType, ValueType>, Collection<Edge<KeyType>>> known,
            Map<Pair<Transaction<KeyType, ValueType>, Transaction<KeyType, ValueType>>, List<String>> labelsByPair,
            Set<Transaction<KeyType, ValueType>> allowedTxns) {
        for (var ep : known.edges()) {
            var labels = known.edgeValue(ep).orElse(List.of()).stream()
                    .filter(edge -> edge.getType() != EdgeType.PR_WR && edge.getType() != EdgeType.PR_RW)
                    .map(edge -> String.format("known %s%s",
                            edge.getType(),
                            edge.getKey() == null ? "" : String.format(" key=%s", edge.getKey())))
                    .collect(Collectors.toList());
            if (!labels.isEmpty()) {
                addCycleEdge(labelsByPair, ep.source(), ep.target(), String.join("; ", labels), allowedTxns);
            }
        }
    }

    private void addForcedBottomConstraintEdges(
            Collection<SERConstraint<KeyType, ValueType>> constraints,
            Map<Pair<Transaction<KeyType, ValueType>, Transaction<KeyType, ValueType>>, List<String>> labelsByPair,
            Set<Transaction<KeyType, ValueType>> allowedTxns) {
        for (var constraint : constraints) {
            if (isBottomTxn(constraint.getWriteTransaction1()) && !isBottomTxn(constraint.getWriteTransaction2())) {
                addConstraintSideEdges(labelsByPair, constraint.getEdges1(), constraint.getWriteTransaction2(), allowedTxns);
            } else if (!isBottomTxn(constraint.getWriteTransaction1()) && isBottomTxn(constraint.getWriteTransaction2())) {
                addConstraintSideEdges(labelsByPair, constraint.getEdges2(), constraint.getWriteTransaction1(), allowedTxns);
            }
        }
    }

    private void addPredicateCycleEdges(
            KnownGraph<KeyType, ValueType> graph,
            Map<Pair<Transaction<KeyType, ValueType>, Transaction<KeyType, ValueType>>, List<String>> labelsByPair,
            Set<Transaction<KeyType, ValueType>> allowedTxns) {
        var writesByKey = buildWritesByKey(graph);
        for (var observation : graph.getPredicateObservations()) {
            var predicateRead = observation.getPredicateReadEvent();
            if (predicateRead.getPredicate() == null) {
                continue;
            }

            var resultSourceByKey = observation.getTupleSources().stream()
                    .collect(Collectors.toMap(
                            KnownGraph.PredicateTupleSource::getKey,
                            KnownGraph.PredicateTupleSource::getSourceWrite));

            for (var resultEntry : resultSourceByKey.entrySet()) {
                var key = resultEntry.getKey();
                var resultSource = resultEntry.getValue();
                if (!writeRowIsInPredicateResult(resultSource, predicateRead)) {
                    continue;
                }

                for (var write : writesByKey.getOrDefault(key, List.of())) {
                    if (write == resultSource || write.getTxn().equals(observation.getTxn())) {
                        continue;
                    }
                    if (isBottomTxn(write.getTxn())) {
                        continue;
                    }
                    if (writeRowIsInPredicateResult(write, predicateRead)) {
                        continue;
                    }
                    addCycleEdge(labelsByPair, observation.getTxn(), write.getTxn(),
                            String.format(
                                    "PR_RW key=%s (predicate result contained value %s from %s; %s writes value %s outside the result)",
                                    key,
                                    resultSource.getEvent().getValue(),
                                    resultSource.getTxn(),
                                    write.getTxn(),
                                    write.getEvent().getValue()),
                            allowedTxns);
                }
            }

            for (var entry : writesByKey.entrySet()) {
                var key = entry.getKey();
                if (resultSourceByKey.containsKey(key)) {
                    continue;
                }
                for (var write : entry.getValue()) {
                    if (write.getTxn().equals(observation.getTxn())) {
                        continue;
                    }
                    if (isBottomTxn(write.getTxn())) {
                        continue;
                    }
                    if (!writeRowIsInPredicateResult(write, predicateRead)) {
                        continue;
                    }
                    addCycleEdge(labelsByPair, observation.getTxn(), write.getTxn(),
                            String.format(
                                    "PR_RW key=%s (predicate result omitted this key; %s writes value %s into the result)",
                                    key, write.getTxn(), write.getEvent().getValue()),
                            allowedTxns);
                }
            }
        }
    }

    private void addConstraintSideEdges(
            Map<Pair<Transaction<KeyType, ValueType>, Transaction<KeyType, ValueType>>, List<String>> labelsByPair,
            Collection<SEREdge<KeyType, ValueType>> edges,
            Transaction<KeyType, ValueType> realWriter,
            Set<Transaction<KeyType, ValueType>> allowedTxns) {
        if (edges == null) {
            return;
        }
        for (var edge : edges) {
            addCycleEdge(labelsByPair, edge.getFrom(), edge.getTo(),
                    String.format("%s key=%s (forced by T_bottom < %s)",
                            edge.getType(), edge.getKey(), realWriter),
                    allowedTxns);
        }
    }

    private void addCycleEdge(
            Map<Pair<Transaction<KeyType, ValueType>, Transaction<KeyType, ValueType>>, List<String>> labelsByPair,
            Transaction<KeyType, ValueType> from,
            Transaction<KeyType, ValueType> to,
            String label,
            Set<Transaction<KeyType, ValueType>> allowedTxns) {
        if (allowedTxns != null && (!allowedTxns.contains(from) || !allowedTxns.contains(to))) {
            return;
        }
        labelsByPair.computeIfAbsent(Pair.of(from, to), ignored -> new ArrayList<>()).add(label);
    }

    private Optional<List<CycleEdge<KeyType, ValueType>>> findCycle(
            Map<Pair<Transaction<KeyType, ValueType>, Transaction<KeyType, ValueType>>, List<String>> labelsByPair) {
        var adjacency = new LinkedHashMap<Transaction<KeyType, ValueType>, LinkedHashSet<Transaction<KeyType, ValueType>>>();
        for (var pair : labelsByPair.keySet()) {
            adjacency.computeIfAbsent(pair.getLeft(), ignored -> new LinkedHashSet<>()).add(pair.getRight());
            adjacency.computeIfAbsent(pair.getRight(), ignored -> new LinkedHashSet<>());
        }

        List<CycleEdge<KeyType, ValueType>> best = null;
        for (var pair : labelsByPair.keySet()) {
            var path = shortestPath(pair.getRight(), pair.getLeft(), adjacency);
            if (path.isEmpty()) {
                continue;
            }
            var cycleNodes = new ArrayList<Transaction<KeyType, ValueType>>();
            cycleNodes.add(pair.getLeft());
            cycleNodes.addAll(path.get());
            var cycle = cycleEdgesFromNodes(cycleNodes, labelsByPair);
            if (best == null || cycle.size() < best.size()) {
                best = cycle;
            }
        }
        return Optional.ofNullable(best);
    }

    private Optional<List<Transaction<KeyType, ValueType>>> shortestPath(
            Transaction<KeyType, ValueType> start,
            Transaction<KeyType, ValueType> goal,
            Map<Transaction<KeyType, ValueType>, LinkedHashSet<Transaction<KeyType, ValueType>>> adjacency) {
        var queue = new ArrayDeque<Transaction<KeyType, ValueType>>();
        var predecessor = new HashMap<Transaction<KeyType, ValueType>, Transaction<KeyType, ValueType>>();
        queue.add(start);
        predecessor.put(start, null);

        while (!queue.isEmpty()) {
            var current = queue.removeFirst();
            if (current.equals(goal)) {
                break;
            }
            for (var next : adjacency.getOrDefault(current, new LinkedHashSet<>())) {
                if (predecessor.containsKey(next)) {
                    continue;
                }
                predecessor.put(next, current);
                queue.addLast(next);
            }
        }

        if (!predecessor.containsKey(goal)) {
            return Optional.empty();
        }

        var result = new ArrayList<Transaction<KeyType, ValueType>>();
        for (var node = goal; node != null; node = predecessor.get(node)) {
            result.add(node);
        }
        Collections.reverse(result);
        return Optional.of(result);
    }

    private Optional<List<CycleEdge<KeyType, ValueType>>> dfsCycle(
            Transaction<KeyType, ValueType> node,
            Map<Transaction<KeyType, ValueType>, LinkedHashSet<Transaction<KeyType, ValueType>>> adjacency,
            Map<Pair<Transaction<KeyType, ValueType>, Transaction<KeyType, ValueType>>, List<String>> labelsByPair,
            Map<Transaction<KeyType, ValueType>, Integer> color,
            List<Transaction<KeyType, ValueType>> stack,
            Map<Transaction<KeyType, ValueType>, Integer> stackIndex) {
        color.put(node, 1);
        stackIndex.put(node, stack.size());
        stack.add(node);

        for (var succ : adjacency.getOrDefault(node, new LinkedHashSet<>())) {
            int succColor = color.getOrDefault(succ, 0);
            if (succColor == 0) {
                var cycle = dfsCycle(succ, adjacency, labelsByPair, color, stack, stackIndex);
                if (cycle.isPresent()) {
                    return cycle;
                }
            } else if (succColor == 1) {
                var cycleNodes = new ArrayList<>(stack.subList(stackIndex.get(succ), stack.size()));
                cycleNodes.add(succ);
                return Optional.of(cycleEdgesFromNodes(cycleNodes, labelsByPair));
            }
        }

        stack.remove(stack.size() - 1);
        stackIndex.remove(node);
        color.put(node, 2);
        return Optional.empty();
    }

    private List<CycleEdge<KeyType, ValueType>> cycleEdgesFromNodes(
            List<Transaction<KeyType, ValueType>> cycleNodes,
            Map<Pair<Transaction<KeyType, ValueType>, Transaction<KeyType, ValueType>>, List<String>> labelsByPair) {
        var result = new ArrayList<CycleEdge<KeyType, ValueType>>();
        for (int i = 0; i + 1 < cycleNodes.size(); i++) {
            var from = cycleNodes.get(i);
            var to = cycleNodes.get(i + 1);
            var labels = labelsByPair.getOrDefault(Pair.of(from, to), List.of("unknown edge"));
            result.add(new CycleEdge<>(from, to, String.join("; ", labels)));
        }
        return result;
    }

    private String formatCycleWitness(List<CycleEdge<KeyType, ValueType>> cycle) {
        var builder = new StringBuilder("Cycle witness:\n");
        for (int i = 0; i < cycle.size(); i++) {
            var edge = cycle.get(i);
            builder.append(String.format("  %d. %s -> %s: %s\n",
                    i + 1, edge.from, edge.to, edge.label));
        }
        return builder.toString();
    }

    private static boolean isBottomTxn(Transaction<?, ?> txn) {
        return txn.getId() == -1L
                && txn.getSession() != null
                && txn.getSession().getId() == -1L;
    }

    private static class CycleEdge<KeyType, ValueType> {
        private final Transaction<KeyType, ValueType> from;
        private final Transaction<KeyType, ValueType> to;
        private final String label;

        private CycleEdge(Transaction<KeyType, ValueType> from,
                          Transaction<KeyType, ValueType> to,
                          String label) {
            this.from = from;
            this.to = to;
            this.label = label;
        }
    }
    /* ================================================================
     * Constraint generation (WW / ordinary RW) — unchanged from the
     * original implementation.
     * ================================================================ */

    private static <KeyType, ValueType> Collection<SERConstraint<KeyType, ValueType>> generateConstraintsCoalesce(
            History<KeyType, ValueType> history, KnownGraph<KeyType, ValueType> graph) {
        var readFrom = graph.getReadFrom();
        var writes = new HashMap<KeyType, Set<Transaction<KeyType, ValueType>>>();

        history.getEvents().stream().filter(e -> e.getType() == Event.EventType.WRITE).forEach(ev -> {
            writes.computeIfAbsent(ev.getKey(), k -> new HashSet<>()).add(ev.getTransaction());
        });

        var forEachWriteSameKey = ((Consumer<TriConsumer<Transaction<KeyType, ValueType>, Transaction<KeyType, ValueType>, KeyType>>) f -> {
            for (var p : writes.entrySet()) {
                var key = p.getKey();
                var list = new ArrayList<>(p.getValue());
                for (int i = 0; i < list.size(); i++) {
                    for (int j = i + 1; j < list.size(); j++) {
                        f.accept(list.get(i), list.get(j), key);
                    }
                }
            }
        });

        var constraintEdges = new HashMap<Pair<Transaction<KeyType, ValueType>, Transaction<KeyType, ValueType>>, Collection<SEREdge<KeyType, ValueType>>>();
        forEachWriteSameKey.accept((a, c, key) -> {
            var addEdge = ((BiConsumer<Transaction<KeyType, ValueType>, Transaction<KeyType, ValueType>>) (m, n) -> {
                constraintEdges.computeIfAbsent(Pair.of(m, n), p -> new ArrayList<>())
                        .add(new SEREdge<>(m, n, EdgeType.WW, key));
            });
            addEdge.accept(a, c);
            addEdge.accept(c, a);
        });

        for (var a : history.getTransactions()) {
            for (var b : readFrom.successors(a)) {
                for (var edge : readFrom.edgeValue(a, b).get()) {
                    for (var c : writes.get(edge.getKey())) {
                        if (a == c || b == c) {
                            continue;
                        }

                        constraintEdges.get(Pair.of(a, c)).add(new SEREdge<>(b, c, EdgeType.RW, edge.getKey()));
                    }
                }
            }
        }

        var constraints = new HashSet<SERConstraint<KeyType, ValueType>>();
        var addedPairs = new HashSet<Pair<Transaction<KeyType, ValueType>, Transaction<KeyType, ValueType>>>();
        AtomicInteger constraintId = new AtomicInteger();
        forEachWriteSameKey.accept((a, c, key) -> {
            if (addedPairs.contains(Pair.of(a, c)) || addedPairs.contains(Pair.of(c, a))) {
                return;
            }
            addedPairs.add(Pair.of(a, c));
            constraints.add(new SERConstraint<>(constraintEdges.get(Pair.of(a, c)), constraintEdges.get(Pair.of(c, a)),
                    a, c, constraintId.getAndIncrement()));
        });

        return constraints;
    }

    private static <KeyType, ValueType> Collection<SERConstraint<KeyType, ValueType>> generateConstraintsNoCoalesce(
            History<KeyType, ValueType> history, KnownGraph<KeyType, ValueType> graph) {
        var readFrom = graph.getReadFrom();
        var writes = new HashMap<KeyType, Set<Transaction<KeyType, ValueType>>>();

        history.getEvents().stream().filter(e -> e.getType() == Event.EventType.WRITE).forEach(ev -> {
            writes.computeIfAbsent(ev.getKey(), k -> new HashSet<>()).add(ev.getTransaction());
        });

        var constraints = new HashSet<SERConstraint<KeyType, ValueType>>();
        var constraintId = 0;
        for (var a : history.getTransactions()) {
            for (var b : readFrom.successors(a)) {
                for (var edge : readFrom.edgeValue(a, b).get()) {
                    for (var c : writes.get(edge.getKey())) {
                        if (a == c || b == c) {
                            continue;
                        }

                        constraints.add(new SERConstraint<>(
                                List.of(new SEREdge<>(a, c, EdgeType.WW, edge.getKey()),
                                        new SEREdge<>(b, c, EdgeType.RW, edge.getKey())),
                                List.of(new SEREdge<>(c, a, EdgeType.WW, edge.getKey())), a, c, constraintId++));
                    }
                }
            }
        }
        for (var write : writes.entrySet()) {
            var list = new ArrayList<>(write.getValue());
            for (int i = 0; i < list.size(); i++) {
                for (int j = i + 1; j < list.size(); j++) {
                    var a = list.get(i);
                    var c = list.get(j);
                    constraints.add(new SERConstraint<>(List.of(new SEREdge<>(a, c, EdgeType.WW, write.getKey())),
                            List.of(new SEREdge<>(c, a, EdgeType.WW, write.getKey())), a, c, constraintId++));
                }
            }
        }

        return constraints;
    }

    private static <KeyType, ValueType> Collection<SERConstraint<KeyType, ValueType>> generateConstraints(
            History<KeyType, ValueType> history, KnownGraph<KeyType, ValueType> graph) {
        if (coalesceConstraints) {
            return generateConstraintsCoalesce(history, graph);
        }
        return generateConstraintsNoCoalesce(history, graph);
    }

    /**
     * SER direct-edge constraint generation.
     *
     * The generated WW and RW edges are direct serial-precedence candidates and
     * will be placed directly into:
     *   A = {SO, WR, WW, PR_WR}
     *   B = {RW, PR_RW}
     */
    static <KeyType, ValueType> Collection<SERConstraint<KeyType, ValueType>> generateConstraintsSER(
            History<KeyType, ValueType> history, KnownGraph<KeyType, ValueType> graph) {
        return generateConstraints(history, graph);
    }

    /**
     * Diagnostic predicate-edge derivation.
     *
     * <p>The main SER path encodes predicate reads directly in {@link SERSolverAR}
     * and does not consume these PR_WR / PR_RW graph edges. This helper is kept
     * only for explicit compare/debug runs and tests of the derived-edge
     * layer.</p>
     */
    static <KeyType, ValueType> void injectPredicateEdgesSER(
            History<KeyType, ValueType> history,
            KnownGraph<KeyType, ValueType> graph) {
        refreshDerivedPredicateEdges(history, graph);
        injectConservativePredicateCandidatesSER(graph);
    }

    /* ================================================================
     * PR_WR / PR_RW derivation — RefreshDerivedEdges layer
     *
     * PR_WR and PR_RW cannot be fixed in KnownGraph because they depend
     * on the per-key total write ordering, which is only progressively
     * determined as WW edges are confirmed during pruning/SAT.
     *
     * Each call to refreshDerivedPredicateEdges:
     *   1) clears all previously derived PR_WR / PR_RW edges
     *   2) rebuilds the current confirmed write ordering per key
     *   3) for each predicate read and key, resolves the latest visible write
     *      frontier and emits current-effective PR_WR / PR_RW
     *
     * Version/source changes are internal metadata only. PR_RW is emitted only
     * when the frontier write and later write differ under the canonical
     * predicate-result transition:
     *     PT(x,v') xor PT(x,vs), or both true with v' != vs.
     * ================================================================ */

    /* ================================================================
     * PR_WR / PR_RW derivation — latest-visible frontier variant
     *
     * - PR_WR: the latest visible write T for key x emits PR_WR(T→S, x).
     * - PR_RW: if T' is the PR_WR frontier for reader T, and T' WW(x)→S, then T emits
     *           PR_RW(T→S, x) when Δ(T',T,S,x) holds.
     *
     *   Δ(T',T,S,x) compares only the canonical key/value rows produced by
     *   T' and S for P; T is carried by the dependency shape and is not part
     *   of the value comparison.
     * ================================================================ */

    private static final int OBS_INITIAL_STATE = -1;
    private static final int OBS_UNDETERMINED = -2;

    private static <KeyType, ValueType> long countEdgesOfType(
            com.google.common.graph.ValueGraph<Transaction<KeyType, ValueType>, Collection<Edge<KeyType>>> graph,
            EdgeType type) {
        return graph.edges().stream()
                .flatMap(ep -> graph.edgeValue(ep).orElse(List.of()).stream())
                .filter(edge -> edge.getType() == type)
                .count();
    }

    private static <KeyType, ValueType> void injectConservativePredicateCandidatesSER(
            KnownGraph<KeyType, ValueType> graph) {
        var observations = graph.getPredicateObservations();
        if (observations.isEmpty()) {
            return;
        }

        var writesByKey = buildWritesByKey(graph);
        if (writesByKey.isEmpty()) {
            return;
        }

        var unresolvedByKey = new HashMap<KeyType, List<KnownGraph.WriteRef<KeyType, ValueType>>>();
        for (var entry : writesByKey.entrySet()) {
            var confirmedOrder = buildConfirmedWriteOrder(entry.getKey(), entry.getValue(), graph);
            if (confirmedOrder == null && !entry.getValue().isEmpty()) {
                unresolvedByKey.put(entry.getKey(), entry.getValue());
            }
        }
        if (unresolvedByKey.isEmpty()) {
            return;
        }

        var emittedPrWr = new HashSet<Triple<Transaction<KeyType, ValueType>,
                Transaction<KeyType, ValueType>, KeyType>>();
        var emittedPrRw = new HashSet<Triple<Transaction<KeyType, ValueType>,
                Transaction<KeyType, ValueType>, KeyType>>();
        int conservativePrWr = 0;
        int conservativePrRw = 0;

        for (var obs : observations) {
            var predicateRead = obs.getPredicateReadEvent();
            if (predicateRead.getPredicate() == null) {
                continue;
            }

            var reader = obs.getTxn();
            var resultKeys = obs.getTupleSources().stream()
                    .map(KnownGraph.PredicateTupleSource::getKey)
                    .collect(Collectors.toSet());

            for (var entry : unresolvedByKey.entrySet()) {
                var key = entry.getKey();
                var writesOnKey = entry.getValue();
                boolean keyCanAffectObservation = resultKeys.contains(key)
                        || writesOnKey.stream().anyMatch(w -> writeRowIsInPredicateResult(w, predicateRead));
                if (!keyCanAffectObservation) {
                    continue;
                }

                for (var writer : writesOnKey) {
                    var writerTxn = writer.getTxn();
                    if (writerTxn.equals(reader)) {
                        continue;
                    }

                    if (emittedPrWr.add(Triple.of(writerTxn, reader, key))) {
                        graph.putEdge(writerTxn, reader, new Edge<>(EdgeType.PR_WR, key));
                        conservativePrWr++;
                    }
                    if (emittedPrRw.add(Triple.of(reader, writerTxn, key))) {
                        graph.putEdge(reader, writerTxn, new Edge<>(EdgeType.PR_RW, key));
                        conservativePrRw++;
                    }
                }
            }
        }

        if (conservativePrWr > 0 || conservativePrRw > 0) {
            System.err.printf(
                    "[SER] Recorded conservative predicate candidates (ignored by AR SAT solver): PR_WR=%d, PR_RW=%d%n",
                    conservativePrWr, conservativePrRw);
        }
    }

    /**
     * Clear and rebuild all derived PR_WR / PR_RW edges.
     *
     * Algorithm:
     * 1. For each predicate observation S ⊢ PR(P,M,x):
     *    Resolve T = max_AR(VIS^-1(S) ∩ WriteTx_x).
     *    If T exists and T != S, emit PR_WR(T→S, x).
     *
     * 2. For each later writer U with T WW(x)→U:
     *    If Δ(T,S,U,x) holds, emit PR_RW(S→U, x).
     */
    static <KeyType, ValueType> void refreshDerivedPredicateEdges(
            History<KeyType, ValueType> history,
            KnownGraph<KeyType, ValueType> graph) {

        graph.clearDerivedPredicateEdges();

        var observations = graph.getPredicateObservations();
        if (observations.isEmpty()) return;

        var writesByKey = buildWritesByKey(graph);

        // Only keys whose writers have a unique confirmed total ordering
        // are eligible for PR derivation.
        var orderedWritesByKey = new HashMap<KeyType,
                List<KnownGraph.WriteRef<KeyType, ValueType>>>();
        for (var entry : writesByKey.entrySet()) {
            var order = buildConfirmedWriteOrder(
                    entry.getKey(), entry.getValue(), graph);
            if (order != null) {
                orderedWritesByKey.put(entry.getKey(), order);
            }
        }

        // Deduplication: (from-txn, to-txn, key)
        var emittedPrWr = new HashSet<Triple<Transaction<KeyType, ValueType>,
                Transaction<KeyType, ValueType>, KeyType>>();
        var emittedPrRw = new HashSet<Triple<Transaction<KeyType, ValueType>,
                Transaction<KeyType, ValueType>, KeyType>>();

        for (var obs : observations) {
            var pr = obs.getPredicateReadEvent();
            var b = obs.getTxn();          // S in pseudocode (predicate reader)
            if (pr.getPredicate() == null) continue;

            // Result key → source WriteRef for observation-point resolution.
            var resultSourceByKey = new HashMap<KeyType,
                    KnownGraph.WriteRef<KeyType, ValueType>>();
            for (var ts : obs.getTupleSources()) {
                resultSourceByKey.put(ts.getKey(), ts.getSourceWrite());
            }

            // Iterate over keys with confirmed total write order.
            for (var entry : orderedWritesByKey.entrySet()) {
                var key = entry.getKey();
                var orderedWrites = entry.getValue();
                boolean keyInResult = resultSourceByKey.containsKey(key);
                if (!keyInResult
                        && orderedWrites.stream().noneMatch(w -> writeRowIsInPredicateResult(w, pr))) {
                    continue;
                }

                int obsIdx = resolveObservationIndex(
                        key, b, pr, orderedWrites, resultSourceByKey, graph);
                if (obsIdx == OBS_UNDETERMINED || obsIdx == OBS_INITIAL_STATE) continue;

                var frontier = orderedWrites.get(obsIdx);
                var frontierTxn = frontier.getTxn();

                // ---- Phase 1: PR_WR derivation ----
                // T = max_AR(VIS^-1(S) ∩ WriteTx_x).
                if (!frontierTxn.equals(b)
                        && emittedPrWr.add(Triple.of(frontierTxn, b, key))) {
                    graph.putEdge(frontierTxn, b, new Edge<>(EdgeType.PR_WR, key));
                }

                // ---- Phase 2: PR_RW derivation ----
                // For each later writer U where frontier WW(x)→U:
                //   If Δ(frontier,b,U,x) holds, emit PR_RW(b→U, x).
                for (int uIdx = obsIdx + 1; uIdx < orderedWrites.size(); uIdx++) {
                    var later = orderedWrites.get(uIdx);
                    var u = later.getTxn();
                    if (u.equals(b)) continue;            // No self-loop PR_RW(S→S,x)
                    if (u.equals(frontierTxn)) continue;  // WW is inter-transactional here

                    if (predicateTransitionDelta(frontier, later, pr)) {
                        if (emittedPrRw.add(Triple.of(b, u, key))) {
                            graph.putEdge(b, u, new Edge<>(EdgeType.PR_RW, key));
                        }
                    }
                }
            }
        }
    }

    /* ---------- helper: writes-by-key index ---------- */

    private static <KeyType, ValueType> Map<KeyType,
            List<KnownGraph.WriteRef<KeyType, ValueType>>> buildWritesByKey(
                    KnownGraph<KeyType, ValueType> graph) {
        var result = new HashMap<KeyType,
                List<KnownGraph.WriteRef<KeyType, ValueType>>>();
        for (var write : graph.getAllWrites()) {
            result.computeIfAbsent(write.getEvent().getKey(),
                    k -> new ArrayList<>()).add(write);
        }
        return result;
    }

    /* ---------- helper: confirmed write ordering ---------- */

    /**
     * Build a total write ordering for {@code key} using confirmed edges
     * in knownGraphA.  Returns {@code null} if the ordering among the
     * writers is not yet uniquely determined (conservative: skip).
     *
     * <p>Within a single transaction, writes on the same key are ordered
     * by their event index (program order).  Across transactions, any
     * edge in knownGraphA (SO, WW, WR, PR_WR) implies a confirmed
     * precedence that constrains the per-key version order.
     */
    private static <KeyType, ValueType>
            List<KnownGraph.WriteRef<KeyType, ValueType>> buildConfirmedWriteOrder(
                    KeyType key,
                    List<KnownGraph.WriteRef<KeyType, ValueType>> writesOnKey,
                    KnownGraph<KeyType, ValueType> graph) {

        if (writesOnKey.isEmpty()) return null;
        if (writesOnKey.size() == 1) return new ArrayList<>(writesOnKey);

        // Group by transaction; within-txn writes sorted by event index
        var txnToWrites = new LinkedHashMap<Transaction<KeyType, ValueType>,
                List<KnownGraph.WriteRef<KeyType, ValueType>>>();
        for (var wr : writesOnKey) {
            txnToWrites.computeIfAbsent(wr.getTxn(),
                    t -> new ArrayList<>()).add(wr);
        }
        txnToWrites.values().forEach(list ->
                list.sort(Comparator.comparingInt(
                        KnownGraph.WriteRef::getIndex)));

        var txns = new ArrayList<>(txnToWrites.keySet());
        if (txns.size() == 1) {
            return txnToWrites.get(txns.get(0));
        }

        var txnSet = new HashSet<>(txns);

        // Collect all confirmed ordering edges between these transactions.
        var successors = new HashMap<Transaction<KeyType, ValueType>,
                Set<Transaction<KeyType, ValueType>>>();
        for (var ep : graph.getKnownGraphA().edges()) {
            var source = ep.source();
            var target = ep.target();
            if (source == target) continue;
            if (!txnSet.contains(source) || !txnSet.contains(target)) continue;
            successors.computeIfAbsent(source,
                    x -> new HashSet<>()).add(target);
        }

        var sorted = uniqueTopologicalSort(txns, successors);
        if (sorted == null) return null;

        var result = new ArrayList<KnownGraph.WriteRef<KeyType, ValueType>>();
        for (var txn : sorted) {
            result.addAll(txnToWrites.get(txn));
        }
        return result;
    }

    /**
     * Returns a topological ordering of {@code nodes} iff it is unique
     * (total order).  Returns {@code null} if a cycle is detected or if
     * multiple valid orderings exist (i.e. some nodes are incomparable).
     */
    private static <T> List<T> uniqueTopologicalSort(
            List<T> nodes, Map<T, Set<T>> successors) {
        var inDegree = new HashMap<T, Integer>();
        for (var n : nodes) inDegree.put(n, 0);
        for (var entry : successors.entrySet()) {
            if (!inDegree.containsKey(entry.getKey())) continue;
            for (var succ : entry.getValue()) {
                if (inDegree.containsKey(succ)) {
                    inDegree.merge(succ, 1, Integer::sum);
                }
            }
        }

        var result = new ArrayList<T>();
        var queue = new ArrayDeque<T>();
        for (var entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) queue.add(entry.getKey());
        }

        while (!queue.isEmpty()) {
            if (queue.size() > 1) return null;
            var node = queue.poll();
            result.add(node);
            for (var succ : successors.getOrDefault(
                    node, Collections.emptySet())) {
                if (inDegree.containsKey(succ)) {
                    int d = inDegree.get(succ) - 1;
                    inDegree.put(succ, d);
                    if (d == 0) queue.add(succ);
                }
            }
        }
        return (result.size() == nodes.size()) ? result : null;
    }

    /* ---------- helper: observation index ---------- */

    /**
     * Determine where predicate read {@code pr} (in txn {@code b}) observes
     * key {@code key} within the ordered write chain.
     *
     * <p>Returns the chain index of the last write visible to pr, or one
     * of the sentinel values {@link #OBS_INITIAL_STATE} (no write is
     * visible — only the initial default state) or
     * {@link #OBS_UNDETERMINED} (skip this key).
     *
     * <p><b>Strategy by case:</b>
     * <ol>
     *   <li>Key appears in the predicate result — use the result's source
     *       write as the observation point (definitive under SI).</li>
     *   <li>b writes this key — anchor b's position in the chain via
     *       program order and use the latest of b's writes that precedes
     *       pr, or the predecessor of b's earliest write if pr comes
     *       first.</li>
     *   <li>Key not in result AND b does not write it — conservatively
     *       find the latest write in the chain whose transaction has a
     *       confirmed direct edge to b in knownGraphA.  If no such edge
     *       exists, skip (OBS_UNDETERMINED).</li>
     * </ol>
     */
    private static <KeyType, ValueType> int resolveObservationIndex(
            KeyType key,
            Transaction<KeyType, ValueType> b,
            Event<KeyType, ValueType> pr,
            List<KnownGraph.WriteRef<KeyType, ValueType>> orderedWrites,
            Map<KeyType, KnownGraph.WriteRef<KeyType, ValueType>> resultSourceByKey,
            KnownGraph<KeyType, ValueType> graph) {

        // Case 1: key in predicate result
        var resultSource = resultSourceByKey.get(key);
        if (resultSource != null) {
            int idx = orderedWrites.indexOf(resultSource);
            return (idx >= 0) ? idx : OBS_UNDETERMINED;
        }

        // Case 2: b writes this key
        var bWriteIndices = graph.getTxnWrites()
                .getOrDefault(Pair.of(b, key),
                        Collections.<Integer>emptyList());
        if (!bWriteIndices.isEmpty()) {
            int prIdx = resolvePredicateEventIndex(b, pr, graph);
            if (prIdx == OBS_UNDETERMINED) {
                return OBS_UNDETERMINED;
            }

            int latestBefore = -1;
            for (int idx : bWriteIndices) {
                if (idx < prIdx && idx > latestBefore) {
                    latestBefore = idx;
                }
            }

            if (latestBefore >= 0) {
                for (int i = 0; i < orderedWrites.size(); i++) {
                    var w = orderedWrites.get(i);
                    if (w.getTxn() == b && w.getIndex() == latestBefore) {
                        return i;
                    }
                }
                return OBS_UNDETERMINED;
            }

            // pr precedes all of b's writes on this key
            for (int i = 0; i < orderedWrites.size(); i++) {
                if (orderedWrites.get(i).getTxn() == b) {
                    return (i > 0) ? i - 1 : OBS_INITIAL_STATE;
                }
            }
            return OBS_UNDETERMINED;
        }

        // Case 3: key not in result AND b does not write this key.
        // Conservative: use the latest write whose txn has a confirmed
        // direct edge to b in knownGraphA.
        for (int i = orderedWrites.size() - 1; i >= 0; i--) {
            var w = orderedWrites.get(i);
            if (w.getTxn() == b) continue;
            if (graph.getKnownGraphA().hasEdgeConnecting(w.getTxn(), b)) {
                return i;
            }
        }
        return OBS_UNDETERMINED;
    }

    private static <KeyType, ValueType> int resolvePredicateEventIndex(
            Transaction<KeyType, ValueType> txn,
            Event<KeyType, ValueType> predicateRead,
            KnownGraph<KeyType, ValueType> graph) {
        for (var observation : graph.getPredicateObservations()) {
            if (observation.getTxn() == txn && observation.getPredicateReadEvent() == predicateRead) {
                return observation.getEventIndex();
            }
        }
        return OBS_UNDETERMINED;
    }

    /* ---------- helper: PredicateResult transition checks ---------- */

    private static <KeyType, ValueType> boolean predicateTransitionDelta(
            KnownGraph.WriteRef<KeyType, ValueType> source,
            KnownGraph.WriteRef<KeyType, ValueType> later,
            Event<KeyType, ValueType> predicateReadEvent) {
        return !samePredicateResultSetAfterWrite(source, later, predicateReadEvent);
    }

    /**
     * A write {@code w} triggers PR_* only when applying that write changes the
     * canonical PredicateResult set. Source/version metadata used to locate
     * rows is not the deciding condition.
     *
     * <p>When {@code prev} is {@code null} (initial state before any
     * write), the initial PredicateResult is the empty result.
     */
    private static <KeyType, ValueType> boolean writeChangesPredicateResultSet(
            KnownGraph.WriteRef<KeyType, ValueType> writeRef,
            KnownGraph.WriteRef<KeyType, ValueType> predecessor,
            Event<KeyType, ValueType> predicateReadEvent) {
        if (predecessor == null) {
            return writeRowIsInPredicateResult(writeRef, predicateReadEvent);
        }
        return !samePredicateResultSetAfterWrite(writeRef, predecessor, predicateReadEvent);
    }

    private static <KeyType, ValueType> boolean samePredicateResultSetAfterWrite(
            KnownGraph.WriteRef<KeyType, ValueType> left,
            KnownGraph.WriteRef<KeyType, ValueType> right,
            Event<KeyType, ValueType> predicateReadEvent) {
        boolean leftInResult = writeRowIsInPredicateResult(left, predicateReadEvent);
        boolean rightInResult = writeRowIsInPredicateResult(right, predicateReadEvent);
        if (!leftInResult && !rightInResult) {
            return true;
        }
        if (leftInResult != rightInResult) {
            return false;
        }
        return Objects.equals(left.getEvent().getKey(), right.getEvent().getKey())
                && Objects.equals(left.getEvent().getValue(), right.getEvent().getValue());
    }

    /**
     * Check whether the row produced by {@code writeRef} is present in the
     * predicate result set for {@code predicateReadEvent}.
     */
    private static <KeyType, ValueType> boolean writeRowIsInPredicateResult(
            KnownGraph.WriteRef<KeyType, ValueType> writeRef,
            Event<KeyType, ValueType> predicateReadEvent) {
        var ev = writeRef.getEvent();
        return predicateReadEvent.getPredicate()
                .test(ev.getKey(), ev.getValue());
    }
}
