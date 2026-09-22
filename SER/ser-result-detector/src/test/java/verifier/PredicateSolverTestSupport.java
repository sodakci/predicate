package verifier;

import graph.KnownGraph;
import history.History;
import history.Transaction;
import java.util.Collection;

/** 直接编码测试显式准备谓词剪枝输入；生产求解器不再隐藏执行剪枝。 */
final class PredicateSolverTestSupport {
    private PredicateSolverTestSupport() { }

    static <K, V> SERSolverAR<K, V> preparedSolver(History<K, V> history, KnownGraph<K, V> graph,
                                                Collection<SERConstraint<K, V>> constraints) {
        return preparedSolver(history, graph, constraints, true, false);
    }

    static <K, V> SERSolverAR<K, V> preparedSolver(History<K, V> history, KnownGraph<K, V> graph,
                                                Collection<SERConstraint<K, V>> constraints,
                                                boolean conflicts, boolean metrics) {
        return preparedSolver(history, graph, constraints, conflicts, metrics,
                SERVerifier.PredicateSolvingMode.GMWR);
    }

    static <K, V> SERSolverAR<K, V> preparedSolver(History<K, V> history, KnownGraph<K, V> graph,
                                                Collection<SERConstraint<K, V>> constraints,
                                                boolean conflicts, boolean metrics,
                                                SERVerifier.PredicateSolvingMode mode) {
        return preparedSolver(history, graph, constraints, conflicts, metrics,
                SERVerifier.SolverSettings.forModes(mode, SERVerifier.PruningMode.REACHABILITY));
    }

    static <K, V> SERSolverAR<K, V> preparedSolver(History<K, V> history, KnownGraph<K, V> graph,
                                                Collection<SERConstraint<K, V>> constraints,
                                                boolean conflicts, boolean metrics,
                                                SERVerifier.SolverSettings settings) {
        return preparedSolver(history, graph, constraints, conflicts, metrics, settings,
                SERVerifier.createPrecedenceOracle(history));
    }

    static <K, V> SERSolverAR<K, V> preparedSolver(History<K, V> history, KnownGraph<K, V> graph,
                                                Collection<SERConstraint<K, V>> constraints,
                                                boolean conflicts, boolean metrics,
                                                SERVerifier.SolverSettings settings,
                                                PrecedenceOracle<Transaction<K, V>> oracle) {
        var result = new PredicatePruning<>(history, graph, oracle, settings,
                new PredicateAnalysis<>(graph, oracle)).prune();
        return new SERSolverAR<>(history, graph, constraints, conflicts, metrics, settings, oracle, result);
    }

    static <K, V> SolveStatus solve(History<K, V> history, KnownGraph<K, V> graph,
                                  Collection<SERConstraint<K, V>> constraints,
                                  SERVerifier.SolverSettings settings) {
        var oracle = SERVerifier.createPrecedenceOracle(history);
        var result = new PredicatePruning<>(history, graph, oracle, settings,
                new PredicateAnalysis<>(graph, oracle)).prune();
        if (result.hasConflict()) {
            return SolveStatus.UNSAT;
        }
        return new SERSolverAR<>(history, graph, constraints, true, false, settings, oracle, result).solve();
    }
}
