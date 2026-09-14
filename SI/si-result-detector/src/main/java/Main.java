import java.nio.file.Path;
import java.util.concurrent.Callable;

import history.loaders.PredicateHistoryLoader;
import lombok.SneakyThrows;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import util.Profiler;
import verifier.SIVerifier;

@Command(name = "si-result-detector", mixinStandardHelpOptions = true,
        version = "si-result-detector 0.1.0", subcommands = Audit.class)
public class Main implements Callable<Integer> {
    @SneakyThrows
    public static void main(String[] args) {
        var cmd = new CommandLine(new Main());
        cmd.setCaseInsensitiveEnumValuesAllowed(true);
        System.exit(cmd.execute(args));
    }

    @Override
    public Integer call() {
        CommandLine.usage(this, System.err);
        return -1;
    }
}

@Command(name = "audit", mixinStandardHelpOptions = true, description = "Verify a history")
class Audit implements Callable<Integer> {
    @Option(names = { "--ww-pruning" }, hidden = true,
            description = "[experimental] WW pruning: ${COMPLETION-CANDIDATES}")
    private SIVerifier.PruningMode wwPruning =
            SIVerifier.PruningMode.REACHABILITY;

    @Option(names = { "--solver-timeout-seconds" }, description = "SAT solver timeout in seconds; 0 disables backend timeout")
    private int solverTimeoutSeconds = 600;

    @Option(names = { "--solver-stats" }, description = "print SAT backend and CNF statistics")
    private final Boolean solverStats = false;

    @Option(names = { "--predicate-witness-coalescing" }, negatable = true,
            hidden = true,
            description = "[experimental] override predicate witness coalescing")
    private Boolean predicateWitnessCoalescing;

    @Option(names = { "--graph-edge-interning" }, negatable = true,
            hidden = true,
            description = "[experimental] override graph edge interning")
    private Boolean graphEdgeInterning;

    @Parameters(paramLabel = "HISTORY", description = "history path")
    private Path path;

    private final Profiler profiler = Profiler.getInstance();

    @Override
    public Integer call() {
        var loader = new PredicateHistoryLoader(path);

        var settings = SIVerifier.SolverSettings.defaults(wwPruning);
        settings.solverTimeoutSeconds = solverTimeoutSeconds;
        settings.detailedPredicateMetrics = solverStats;
        if (predicateWitnessCoalescing != null) {
            settings.predicateWitnessCoalescing = predicateWitnessCoalescing;
        }
        if (graphEdgeInterning != null) {
            settings.graphEdgeInterning = graphEdgeInterning;
        }

        profiler.startTick("ENTIRE_EXPERIMENT");
        var verifier = new SIVerifier<>(loader, settings, solverStats);
        var result = verifier.auditResult();
        profiler.endTick("ENTIRE_EXPERIMENT");

        for (var p : profiler.getDurations()) {
            System.err.printf("%s: %dms\n", p.getKey(), p.getValue());
        }
        for (var p : profiler.getCounts()) {
            System.err.printf("%s: %d\n", p.getKey(), p.getValue());
        }
        if (solverStats) {
            System.err.println("[solver-stats] backend=monosat");
            System.err.printf("[solver-stats] predicate-witness-coalescing=%s%n",
                    settings.predicateWitnessCoalescing);
            System.err.printf("[solver-stats] graph-edge-interning=%s%n",
                    settings.graphEdgeInterning);
            System.err.printf("[solver-stats] solver-timeout-seconds=%d%n",
                    settings.solverTimeoutSeconds);
        }
        System.err.printf("Max memory: %s\n", Utils.formatMemory(profiler.getMaxMemory()));

        System.err.println(result.marker);
        return result.exitCode;
    }
}

class Utils {
    static String formatMemory(Long memoryBytes) {
        double[] scale = { 1, 1024, 1024 * 1024, 1024 * 1024 * 1024 };
        String[] unit = { "B", "KB", "MB", "GB" };

        for (int i = scale.length - 1; i >= 0; i--) {
            if (i == 0 || memoryBytes >= scale[i]) {
                return String.format("%.1f%s", memoryBytes / scale[i], unit[i]);
            }
        }
        throw new Error("should not be here");
    }
}
