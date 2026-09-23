package verifier;

import graph.KnownGraph;
import history.History;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** 测试侧显式衔接分析、剪枝与真实求解器，不恢复生产隐式准备入口。 */
final class SISolverTestSupport {
    private SISolverTestSupport() {
    }

    static <K, V> PredicatePruning.Result<K, V> prepare(
            History<K, V> history, KnownGraph<K, V> graph,
            SIReachabilityOracle<K, V> oracle, SIVerifier.SolverSettings settings) {
        var analysis = new PredicateAnalysis<>(graph, oracle);
        return new PredicatePruning<>(history, graph, oracle, settings, analysis).prune();
    }

    static <K, V> SISolverInduced<K, V> create(
            History<K, V> history, KnownGraph<K, V> graph,
            Collection<SIConstraint<K, V>> constraints) {
        return create(history, graph, constraints, true, false,
                SIVerifier.SolverSettings.defaults());
    }

    static <K, V> SISolverInduced<K, V> create(
            History<K, V> history, KnownGraph<K, V> graph,
            Collection<SIConstraint<K, V>> constraints,
            boolean collectConflicts, boolean collectMetrics,
            SIVerifier.SolverSettings settings) {
        return create(history, graph, constraints, collectConflicts, collectMetrics,
                settings, new SIReachabilityOracle<>(graph));
    }

    static <K, V> SISolverInduced<K, V> create(
            History<K, V> history, KnownGraph<K, V> graph,
            Collection<SIConstraint<K, V>> constraints,
            boolean collectConflicts, boolean collectMetrics,
            SIVerifier.SolverSettings settings, SIReachabilityOracle<K, V> oracle) {
        var prepared = prepare(history, graph, oracle, settings);
        assertFalse(prepared.hasConflict(),
                () -> "本例应进入真实 SAT 求解，前置冲突：" + prepared.conflictReasons());
        return new SISolverInduced<>(history, graph, constraints, collectConflicts,
                collectMetrics, settings, oracle, prepared);
    }

    static <K, V> boolean solve(
            History<K, V> history, KnownGraph<K, V> graph,
            Collection<SIConstraint<K, V>> constraints) {
        return solveStatus(history, graph, constraints, true, false,
                SIVerifier.SolverSettings.defaults()) == SolveStatus.SAT;
    }

    static <K, V> SolveStatus solveStatus(
            History<K, V> history, KnownGraph<K, V> graph,
            Collection<SIConstraint<K, V>> constraints,
            boolean collectConflicts, boolean collectMetrics,
            SIVerifier.SolverSettings settings) {
        return solveStatus(history, graph, constraints, collectConflicts,
                collectMetrics, settings, new SIReachabilityOracle<>(graph));
    }

    static <K, V> SolveStatus solveStatus(
            History<K, V> history, KnownGraph<K, V> graph,
            Collection<SIConstraint<K, V>> constraints,
            boolean collectConflicts, boolean collectMetrics,
            SIVerifier.SolverSettings settings, SIReachabilityOracle<K, V> oracle) {
        var prepared = prepare(history, graph, oracle, settings);
        if (prepared.hasConflict()) {
            assertFalse(prepared.conflictReasons().isEmpty(), "前置拒绝必须保留冲突理由");
            return SolveStatus.UNSAT;
        }
        return new SISolverInduced<>(history, graph, constraints, collectConflicts,
                collectMetrics, settings, oracle, prepared).solveStatus();
    }
}
