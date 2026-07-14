package verifier;

import graph.Edge;
import graph.EdgeType;
import graph.KnownGraph;
import graph.MatrixGraph;
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
import com.google.common.graph.GraphBuilder;
import com.google.common.graph.MutableGraph;
import com.google.common.graph.ValueGraph;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;

import util.Profiler;
import util.TriConsumer;

@SuppressWarnings("UnstableApiUsage")
public class SIVerifier<KeyType, ValueType> {
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

    public SIVerifier(HistoryLoader<KeyType, ValueType> loader) {
        history = loader.loadHistory();
        System.err.printf("Sessions count: %d\nTransactions count: %d\nEvents count: %d\n",
                history.getClientSessions().size(), history.getClientTransactions().size(), history.getEvents().size());
    }

    public boolean audit() {
        var profiler = Profiler.getInstance();

        profiler.startTick("ONESHOT_CONS");
        profiler.startTick("SI_VERIFY_INT");
        boolean satisfy_int = Utils.verifyInternalConsistency(history);
        profiler.endTick("SI_VERIFY_INT");
        if (!satisfy_int) {
            return false;
        }

        profiler.startTick("SI_GEN_PREC_GRAPH");
        var graph = new KnownGraph<>(history);
        profiler.endTick("SI_GEN_PREC_GRAPH");
        System.err.printf("Mandatory known precedence edges: %d\n",
                graph.getKnownGraphA().edges().size() + graph.getKnownGraphB().edges().size());

        // ===== SI MODE (Snapshot Isolation with predicates) =====
        // SI path: first prune WW directions that are already forced by
        // InducedSI, then let MonoSAT select the remaining WW directions and
        // generate predicate PR_WR/PR_RW frontiers.
        System.err.printf("Mode: SI, pruning forced WW then checking induced SI graph with MonoSAT predicate frontiers\n");

        profiler.startTick("SI_GEN_CONSTRAINTS");
        var constraints = generateConstraintsSI(history, graph);
        profiler.endTick("SI_GEN_CONSTRAINTS");
        System.err.printf("Unresolved WW choices: %d\nConditional dependency implications: %d\n", constraints.size(),
                constraints.stream().map(c -> c.getEdges1().size() + c.getEdges2().size()).reduce(0, Integer::sum));

        if (compareDerivedPredicateEdges) {
            profiler.startTick("SI_DERIVED_PREDICATE_COMPARE");
            var derivedPredicateGraph = new KnownGraph<>(history);
            injectPredicateEdgesSI(history, derivedPredicateGraph);
            profiler.endTick("SI_DERIVED_PREDICATE_COMPARE");
            System.err.printf(
                    "[SI] Derived predicate-edge compare: PR_WR=%d, PR_RW=%d (debug-only, not used by MonoSAT solver)\n",
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
        var solver = new SISolverInduced<>(history, graph, constraints);

        boolean accepted = solver.solve();
        profiler.endTick("ONESHOT_SOLVE");

        if (!accepted) {
            emitRejectDiagnostics(graph, constraints, solver.getConflicts());
        }

        return accepted;
    }

    private void emitRejectDiagnostics(
            KnownGraph<KeyType, ValueType> graph,
            Collection<SIConstraint<KeyType, ValueType>> constraints,
            Pair<Collection<Pair<com.google.common.graph.EndpointPair<Transaction<KeyType, ValueType>>, Collection<Edge<KeyType>>>>,
                    Collection<SIConstraint<KeyType, ValueType>>> conflicts) {
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
                    Collection<SIConstraint<KeyType, ValueType>>> conflicts) {
        var txns = new HashSet<Transaction<KeyType, ValueType>>();
        conflicts.getLeft().forEach(e -> {
            txns.add(e.getLeft().source());
            txns.add(e.getLeft().target());
        });
        conflicts.getRight().forEach(c -> {
            txns.add(c.getWriteTransaction1());
            txns.add(c.getWriteTransaction2());
            var addEdges = ((Consumer<Collection<SIEdge<KeyType, ValueType>>>) edges -> edges.forEach(e -> {
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
            Collection<SIConstraint<KeyType, ValueType>> constraints,
            Pair<Collection<Pair<com.google.common.graph.EndpointPair<Transaction<KeyType, ValueType>>, Collection<Edge<KeyType>>>>,
                    Collection<SIConstraint<KeyType, ValueType>>> conflicts,
            Optional<List<CycleEdge<KeyType, ValueType>>> cycleWitness) {
        int knownEdges = graph.getKnownGraphA().edges().size() + graph.getKnownGraphB().edges().size();
        int conditionalEdges = constraints.stream()
                .map(c -> c.getEdges1().size() + c.getEdges2().size())
                .reduce(0, Integer::sum);
        System.err.println("[SI] Reject reason: induced SI graph is cyclic or MonoSAT WW/predicate-frontier constraints are unsatisfiable.");
        System.err.printf(
                "[SI] Diagnostic counts: knownEdges=%d, unresolvedWWChoices=%d, conditionalDependencyImplications=%d, predicateReads=%d\n",
                knownEdges, constraints.size(), conditionalEdges, graph.getPredicateObservations().size());
        cycleWitness.ifPresentOrElse(
                cycle -> System.err.printf("[SI] Cycle witness: %d edges explain the contradiction.\n", cycle.size()),
                () -> System.err.println("[SI] Cycle witness: not available from current mandatory/forced edges."));

        if (conflicts.getLeft().isEmpty() && conflicts.getRight().isEmpty()) {
            System.err.println("[SI] No compact conflict core was extracted; the contradiction may come from induced edges or predicate visibility.");
        } else {
            System.err.printf("[SI] Conflict core: knownEdges=%d, wwChoices=%d\n",
                    conflicts.getLeft().size(), conflicts.getRight().size());
        }
    }

    private Optional<List<CycleEdge<KeyType, ValueType>>> buildCycleWitness(
            KnownGraph<KeyType, ValueType> graph,
            Collection<SIConstraint<KeyType, ValueType>> constraints,
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
            Collection<SIConstraint<KeyType, ValueType>> constraints,
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
            Collection<SIEdge<KeyType, ValueType>> edges,
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

    static final class InducedGraph {
        private InducedGraph() {
        }

        static <KeyType, ValueType> boolean canAddAll(
                History<KeyType, ValueType> history,
                KnownGraph<KeyType, ValueType> graph,
                Collection<SIEdge<KeyType, ValueType>> edges,
                boolean requireResolvedPredicates) {
            var trial = copyOf(history, graph);
            for (var edge : edges) {
                if (edge.getFrom().equals(edge.getTo()) || isBottomTxn(edge.getTo())) {
                    return false;
                }
                trial.putEdge(edge.getFrom(), edge.getTo(), new Edge<>(edge.getType(), edge.getKey()));
            }
            return !inducedGraph(trial).hasLoops();
        }

        private static <KeyType, ValueType> KnownGraph<KeyType, ValueType> copyOf(
                History<KeyType, ValueType> history,
                KnownGraph<KeyType, ValueType> graph) {
            var copy = new KnownGraph<>(history);
            copyGraphEdges(graph.getKnownGraphA(), copy);
            copyGraphEdges(graph.getKnownGraphB(), copy);
            return copy;
        }

        private static <KeyType, ValueType> void copyGraphEdges(
                ValueGraph<Transaction<KeyType, ValueType>, Collection<Edge<KeyType>>> source,
                KnownGraph<KeyType, ValueType> target) {
            for (var ep : source.edges()) {
                for (var edge : source.edgeValue(ep).orElse(List.of())) {
                    target.putEdge(ep.source(), ep.target(), edge);
                }
            }
        }

        static <KeyType, ValueType> MatrixGraph<Transaction<KeyType, ValueType>> depReachability(
                KnownGraph<KeyType, ValueType> graph) {
            return depGraph(graph).reachability();
        }

        static <KeyType, ValueType> boolean reaches(
                MatrixGraph<Transaction<KeyType, ValueType>> reachability,
                Transaction<KeyType, ValueType> from,
                Transaction<KeyType, ValueType> to) {
            return !from.equals(to) && reachability.hasEdgeConnecting(from, to);
        }

        static <KeyType, ValueType> Collection<Pair<com.google.common.graph.EndpointPair<Transaction<KeyType, ValueType>>, Collection<Edge<KeyType>>>>
        extractCycleEdges(KnownGraph<KeyType, ValueType> graph) {
            var cycleNodes = findCycle(inducedGraph(graph));
            if (cycleNodes.isEmpty()) {
                return Collections.emptyList();
            }

            var result = new ArrayList<Pair<com.google.common.graph.EndpointPair<Transaction<KeyType, ValueType>>, Collection<Edge<KeyType>>>>();
            for (int i = 0; i + 1 < cycleNodes.size(); i++) {
                var from = cycleNodes.get(i);
                var to = cycleNodes.get(i + 1);
                var edges = new ArrayList<Edge<KeyType>>();
                edges.addAll(graph.getKnownGraphA().edgeValue(from, to).orElse(List.of()));
                edges.addAll(graph.getKnownGraphB().edgeValue(from, to).orElse(List.of()));
                result.add(Pair.of(com.google.common.graph.EndpointPair.ordered(from, to), edges));
            }
            return result;
        }

        private static <KeyType, ValueType> MatrixGraph<Transaction<KeyType, ValueType>> inducedGraph(
                KnownGraph<KeyType, ValueType> graph) {
            var dep = depGraph(graph);
            var anti = antiDepGraph(graph, dep);
            return dep.union(dep.composition(anti));
        }

        private static <KeyType, ValueType> MatrixGraph<Transaction<KeyType, ValueType>> depGraph(
                KnownGraph<KeyType, ValueType> graph) {
            return new MatrixGraph<>(toSimpleGraph(graph.getKnownGraphA()));
        }

        private static <KeyType, ValueType> MatrixGraph<Transaction<KeyType, ValueType>> antiDepGraph(
                KnownGraph<KeyType, ValueType> graph,
                MatrixGraph<Transaction<KeyType, ValueType>> depGraph) {
            return new MatrixGraph<>(toSimpleGraph(graph.getKnownGraphB()), depGraph.getNodeMap());
        }

        private static <KeyType, ValueType> MutableGraph<Transaction<KeyType, ValueType>> toSimpleGraph(
                ValueGraph<Transaction<KeyType, ValueType>, Collection<Edge<KeyType>>> source) {
            var graph = GraphBuilder.directed()
                    .allowsSelfLoops(true)
                    .<Transaction<KeyType, ValueType>>build();
            source.nodes().forEach(graph::addNode);
            for (var ep : source.edges()) {
                graph.putEdge(ep.source(), ep.target());
            }
            return graph;
        }

        private static <KeyType, ValueType> List<Transaction<KeyType, ValueType>> findCycle(
                MatrixGraph<Transaction<KeyType, ValueType>> graph) {
            var color = new HashMap<Transaction<KeyType, ValueType>, Integer>();
            var stack = new ArrayList<Transaction<KeyType, ValueType>>();
            var stackIndex = new HashMap<Transaction<KeyType, ValueType>, Integer>();

            for (var node : graph.nodes()) {
                if (color.getOrDefault(node, 0) != 0) {
                    continue;
                }
                var cycle = dfsCycle(node, graph, color, stack, stackIndex);
                if (!cycle.isEmpty()) {
                    return cycle;
                }
            }
            return Collections.emptyList();
        }

        private static <KeyType, ValueType> List<Transaction<KeyType, ValueType>> dfsCycle(
                Transaction<KeyType, ValueType> node,
                MatrixGraph<Transaction<KeyType, ValueType>> graph,
                Map<Transaction<KeyType, ValueType>, Integer> color,
                List<Transaction<KeyType, ValueType>> stack,
                Map<Transaction<KeyType, ValueType>, Integer> stackIndex) {
            color.put(node, 1);
            stackIndex.put(node, stack.size());
            stack.add(node);

            for (var succ : graph.successors(node)) {
                int succColor = color.getOrDefault(succ, 0);
                if (succColor == 0) {
                    var cycle = dfsCycle(succ, graph, color, stack, stackIndex);
                    if (!cycle.isEmpty()) {
                        return cycle;
                    }
                } else if (succColor == 1) {
                    var cycle = new ArrayList<>(stack.subList(stackIndex.get(succ), stack.size()));
                    cycle.add(succ);
                    return cycle;
                }
            }

            stack.remove(stack.size() - 1);
            stackIndex.remove(node);
            color.put(node, 2);
            return Collections.emptyList();
        }
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

    private static <KeyType, ValueType> Collection<SIConstraint<KeyType, ValueType>> generateConstraintsCoalesce(
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

        var constraintEdges = new HashMap<Pair<Transaction<KeyType, ValueType>, Transaction<KeyType, ValueType>>, Collection<SIEdge<KeyType, ValueType>>>();
        forEachWriteSameKey.accept((a, c, key) -> {
            var addEdge = ((BiConsumer<Transaction<KeyType, ValueType>, Transaction<KeyType, ValueType>>) (m, n) -> {
                constraintEdges.computeIfAbsent(Pair.of(m, n), p -> new ArrayList<>())
                        .add(new SIEdge<>(m, n, EdgeType.WW, key));
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

                        constraintEdges.get(Pair.of(a, c)).add(new SIEdge<>(b, c, EdgeType.RW, edge.getKey()));
                    }
                }
            }
        }

        var constraints = new HashSet<SIConstraint<KeyType, ValueType>>();
        var addedPairs = new HashSet<Pair<Transaction<KeyType, ValueType>, Transaction<KeyType, ValueType>>>();
        AtomicInteger constraintId = new AtomicInteger();
        forEachWriteSameKey.accept((a, c, key) -> {
            if (addedPairs.contains(Pair.of(a, c)) || addedPairs.contains(Pair.of(c, a))) {
                return;
            }
            addedPairs.add(Pair.of(a, c));
            constraints.add(new SIConstraint<>(constraintEdges.get(Pair.of(a, c)), constraintEdges.get(Pair.of(c, a)),
                    a, c, constraintId.getAndIncrement()));
        });

        return constraints;
    }

    private static <KeyType, ValueType> Collection<SIConstraint<KeyType, ValueType>> generateConstraintsNoCoalesce(
            History<KeyType, ValueType> history, KnownGraph<KeyType, ValueType> graph) {
        var readFrom = graph.getReadFrom();
        var writes = new HashMap<KeyType, Set<Transaction<KeyType, ValueType>>>();

        history.getEvents().stream().filter(e -> e.getType() == Event.EventType.WRITE).forEach(ev -> {
            writes.computeIfAbsent(ev.getKey(), k -> new HashSet<>()).add(ev.getTransaction());
        });

        var constraints = new HashSet<SIConstraint<KeyType, ValueType>>();
        var constraintId = 0;
        for (var a : history.getTransactions()) {
            for (var b : readFrom.successors(a)) {
                for (var edge : readFrom.edgeValue(a, b).get()) {
                    for (var c : writes.get(edge.getKey())) {
                        if (a == c || b == c) {
                            continue;
                        }

                        constraints.add(new SIConstraint<>(
                                List.of(new SIEdge<>(a, c, EdgeType.WW, edge.getKey()),
                                        new SIEdge<>(b, c, EdgeType.RW, edge.getKey())),
                                List.of(new SIEdge<>(c, a, EdgeType.WW, edge.getKey())), a, c, constraintId++));
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
                    constraints.add(new SIConstraint<>(List.of(new SIEdge<>(a, c, EdgeType.WW, write.getKey())),
                            List.of(new SIEdge<>(c, a, EdgeType.WW, write.getKey())), a, c, constraintId++));
                }
            }
        }

        return constraints;
    }

    private static <KeyType, ValueType> Collection<SIConstraint<KeyType, ValueType>> generateConstraints(
            History<KeyType, ValueType> history, KnownGraph<KeyType, ValueType> graph) {
        if (coalesceConstraints) {
            return generateConstraintsCoalesce(history, graph);
        }
        return generateConstraintsNoCoalesce(history, graph);
    }

    /**
     * SI direct-edge constraint generation.
     *
     * The generated WW and RW edges are direct SI dependency candidates and
     * will be placed directly into:
     *   A = {SO, WR, WW, PR_WR}
     *   B = {RW, PR_RW}
     */
    static <KeyType, ValueType> Collection<SIConstraint<KeyType, ValueType>> generateConstraintsSI(
            History<KeyType, ValueType> history, KnownGraph<KeyType, ValueType> graph) {
        return generateConstraints(history, graph);
    }

    /**
     * Diagnostic predicate-edge derivation.
     *
     * <p>The main SI path encodes predicate frontiers directly in
     * {@link SISolverInduced} and does not consume these materialized
     * PR_WR / PR_RW graph edges. This helper is kept only for explicit
     * compare/debug runs and tests of the derived-edge layer.</p>
     */
    static <KeyType, ValueType> void injectPredicateEdgesSI(
            History<KeyType, ValueType> history,
            KnownGraph<KeyType, ValueType> graph) {
        refreshDerivedPredicateEdges(history, graph);
        injectConservativePredicateCandidatesSI(graph);
    }

    /* ================================================================
     * PR_WR / PR_RW derivation — debug-only materialized-edge layer
     *
     * PR_WR and PR_RW cannot be trusted as main-path KnownGraph edges because
     * they depend on the per-key total write ordering, which is ultimately
     * selected by MonoSAT. The production SI solver encodes this dependency
     * directly instead of consuming this materialized graph.
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
     * PR_WR / PR_RW derivation — latest-visible frontier variant (debug only)
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

    private static <KeyType, ValueType> long countEdgesOfType(
            com.google.common.graph.ValueGraph<Transaction<KeyType, ValueType>, Collection<Edge<KeyType>>> graph,
            EdgeType type) {
        return graph.edges().stream()
                .flatMap(ep -> graph.edgeValue(ep).orElse(List.of()).stream())
                .filter(edge -> edge.getType() == type)
                .count();
    }

    private static <KeyType, ValueType> void injectConservativePredicateCandidatesSI(
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
                    "[SI] Recorded conservative predicate candidates: PR_WR=%d, PR_RW=%d%n",
                    conservativePrWr, conservativePrRw);
        }
    }

    /**
     * Clear and rebuild all derived PR_WR / PR_RW edges.
     *
     * Algorithm:
     * 1. For each predicate observation S ⊢ PR(P,M,x):
     *    Resolve T = max snapshot-visible write for key x.
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

        var emittedPrWr = new HashSet<Triple<Transaction<KeyType, ValueType>,
                Transaction<KeyType, ValueType>, KeyType>>();
        var emittedPrRw = new HashSet<Triple<Transaction<KeyType, ValueType>,
                Transaction<KeyType, ValueType>, KeyType>>();

        // Predicate result sources are SI snapshot frontier dependencies. They
        // do not require a pre-existing per-key WW total order.
        for (var obs : observations) {
            var reader = obs.getTxn();
            var pr = obs.getPredicateReadEvent();
            if (pr.getPredicate() == null) continue;

            for (var ts : obs.getTupleSources()) {
                var source = ts.getSourceWrite();
                var sourceTxn = source.getTxn();
                var key = ts.getKey();
                if (!sourceTxn.equals(reader)
                        && emittedPrWr.add(Triple.of(sourceTxn, reader, key))) {
                    graph.putEdge(sourceTxn, reader, new Edge<>(EdgeType.PR_WR, key));
                }
            }
        }

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
        var depReachability = InducedGraph.depReachability(graph);

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

                int obsIdx;
                if (keyInResult) {
                    obsIdx = orderedWrites.indexOf(resultSourceByKey.get(key));
                    if (obsIdx < 0) continue;
                } else {
                    obsIdx = latestVisibleWriteIndex(b, pr, orderedWrites, obs, depReachability);
                }

                KnownGraph.WriteRef<KeyType, ValueType> frontier =
                        obsIdx == OBS_INITIAL_STATE ? null : orderedWrites.get(obsIdx);
                Transaction<KeyType, ValueType> frontierTxn =
                        frontier == null ? null : frontier.getTxn();

                // ---- Phase 1: PR_WR derivation ----
                // T = max snapshot-visible write for key x.
                if (frontierTxn != null
                        && !frontierTxn.equals(b)
                        && emittedPrWr.add(Triple.of(frontierTxn, b, key))) {
                    graph.putEdge(frontierTxn, b, new Edge<>(EdgeType.PR_WR, key));
                }

                // ---- Phase 2: PR_RW derivation ----
                // For each later writer U where frontier WW(x)→U:
                //   If Δ(frontier,b,U,x) holds, emit PR_RW(b→U, x).
                int firstLater = obsIdx == OBS_INITIAL_STATE ? 0 : obsIdx + 1;
                for (int uIdx = firstLater; uIdx < orderedWrites.size(); uIdx++) {
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

    private static <KeyType, ValueType> int latestVisibleWriteIndex(
            Transaction<KeyType, ValueType> reader,
            Event<KeyType, ValueType> predicateRead,
            List<KnownGraph.WriteRef<KeyType, ValueType>> orderedWrites,
            KnownGraph.PredicateObservation<KeyType, ValueType> observation,
            MatrixGraph<Transaction<KeyType, ValueType>> depReachability) {
        for (int i = orderedWrites.size() - 1; i >= 0; i--) {
            if (visibleToSnapshot(reader, predicateRead, orderedWrites.get(i), observation, depReachability)) {
                return i;
            }
        }
        return OBS_INITIAL_STATE;
    }

    private static <KeyType, ValueType> boolean visibleToSnapshot(
            Transaction<KeyType, ValueType> reader,
            Event<KeyType, ValueType> predicateRead,
            KnownGraph.WriteRef<KeyType, ValueType> write,
            KnownGraph.PredicateObservation<KeyType, ValueType> observation,
            MatrixGraph<Transaction<KeyType, ValueType>> depReachability) {
        if (write.getTxn().equals(reader)) {
            return write.getIndex() < observation.getEventIndex()
                    && observation.getPredicateReadEvent() == predicateRead;
        }
        return InducedGraph.reaches(depReachability, write.getTxn(), reader);
    }

    /* ---------- helper: PredicateResult transition checks ---------- */

    private static <KeyType, ValueType> boolean predicateTransitionDelta(
            KnownGraph.WriteRef<KeyType, ValueType> source,
            KnownGraph.WriteRef<KeyType, ValueType> later,
            Event<KeyType, ValueType> predicateReadEvent) {
        return writeChangesPredicateResultSet(later, source, predicateReadEvent);
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
