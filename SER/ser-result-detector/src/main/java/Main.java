import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.Callable;

import history.loaders.PredicateHistoryLoader;
import lombok.SneakyThrows;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ITypeConverter;
import util.Profiler;
import verifier.SERVerifier;

@Command(name = "ser-result-detector", mixinStandardHelpOptions = true,
        version = "ser-result-detector 0.1.0", subcommands = Audit.class)
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

enum WwPruningMode {
    NONE,
    REACHABILITY;

    SERVerifier.PruningMode asVerifierMode() {
        return this == NONE
                ? SERVerifier.PruningMode.NONE
                : SERVerifier.PruningMode.REACHABILITY;
    }
}

@Command(name = "audit", mixinStandardHelpOptions = true, description = "Verify a history")
class Audit implements Callable<Integer> {
    static final class SerPropagationModeConverter
            implements ITypeConverter<SERVerifier.SerPropagationMode> {
        @Override
        public SERVerifier.SerPropagationMode convert(String value) {
            return SERVerifier.SerPropagationMode.valueOf(
                    value.trim().toUpperCase().replace('-', '_'));
        }
    }

    @Option(names = { "--ww-pruning" },
            hidden = true,
            description = "[experimental] WW pruning: ${COMPLETION-CANDIDATES}")
    private WwPruningMode wwPruning = WwPruningMode.REACHABILITY;

    @Option(names = { "--solver-timeout-seconds" }, description = "SAT solver timeout in seconds measured from solve(); 0 disables backend timeout")
    private int solverTimeoutSeconds = 600;

    @Option(names = { "--solver-stats" },
            description = "print SAT backend and detailed predicate encoding statistics")
    private final Boolean solverStats = false;

    @Option(names = { "--predicate-encoding" },
            description = "predicate encoding: ${COMPLETION-CANDIDATES} (default: gmwr)")
    private SERVerifier.PredicateSolvingMode predicateEncoding;

    @Option(names = { "--ser-propagation-mode" },
            converter = SerPropagationModeConverter.class,
            hidden = true,
            description = "[experimental] internal GMWR propagation mode")
    private SERVerifier.SerPropagationMode serPropagationMode;

    @Option(names = { "--gmwr-prepropagation" }, negatable = true,
            hidden = true,
            description = "[experimental] override GMWR prepropagation")
    private Boolean gmwrPrepropagation;

    @Option(names = { "--predicate-witness-coalescing" }, negatable = true,
            hidden = true,
            description = "[experimental] override witness coalescing")
    private Boolean predicateWitnessCoalescing;

    @Option(names = { "--graph-edge-interning" }, negatable = true,
            hidden = true,
            description = "[experimental] override graph edge interning")
    private Boolean graphEdgeInterning;

    @Parameters(paramLabel = "HISTORY", description = "history path")
    private Path path;

    private final Profiler profiler = Profiler.getInstance();
    private SERVerifier.PredicateSolvingMode selectedPredicateEncoding =
            SERVerifier.PredicateSolvingMode.GMWR;

    @Override
    public Integer call() {
        try {
            return runAudit();
        } catch (CommandLine.ParameterException exception) {
            throw exception;
        } catch (Throwable exception) {
            System.err.printf("[SER] Error: %s%n",
                    exception.getMessage() == null
                            ? exception.getClass().getSimpleName()
                            : exception.getMessage());
            System.err.println("SER audit result: ERROR");
            return 1;
        }
    }

    private Integer runAudit() {
        profiler.clear();
        var loader = new PredicateHistoryLoader(path);

        var selectedPruningMode = wwPruning.asVerifierMode();
        selectedPredicateEncoding = predicateEncoding != null
                ? predicateEncoding
                : SERVerifier.PredicateSolvingMode.GMWR;
        var selectedPropagationMode = serPropagationMode != null
                ? serPropagationMode
                : selectedPredicateEncoding == SERVerifier.PredicateSolvingMode.GMWR
                        ? SERVerifier.SerPropagationMode.WW_GMWR
                        : SERVerifier.SerPropagationMode.WW_ONLY;
        var settings = SERVerifier.SolverSettings.forModes(
                selectedPredicateEncoding, selectedPruningMode,
                selectedPropagationMode);
        if (gmwrPrepropagation != null) {
            settings.gmwrPrepropagation = gmwrPrepropagation;
        }
        if (predicateWitnessCoalescing != null) {
            settings.predicateWitnessCoalescing = predicateWitnessCoalescing;
        }
        if (graphEdgeInterning != null) {
            settings.graphEdgeInterning = graphEdgeInterning;
        }
        settings.solverTimeoutSeconds = solverTimeoutSeconds;
        settings.detailedPredicateMetrics = solverStats;
        settings.auditProgressListener = this::printCompletedSection;
        profiler.startTick("ENTIRE_EXPERIMENT");
        var verifier = new SERVerifier<>(loader, settings, solverStats);
        printHistorySummary(verifier);
        var result = verifier.audit();
        profiler.endTick("ENTIRE_EXPERIMENT");

        printTimingSummary();
        System.err.printf("Peak memory: %s%n%n",
                Utils.formatMemoryWithSpace(profiler.getMaxMemory()));
        if (solverStats) {
            for (var p : profiler.getDurations()) {
                System.err.printf("%s: %dms\n", p.getKey(), p.getValue());
            }
            for (var p : profiler.getCounts()) {
                System.err.printf("%s: %d\n", p.getKey(), p.getValue());
            }
            System.err.println("[solver-stats] backend=monosat");
            System.err.printf("[solver-stats] predicate-encoding=%s%n",
                    selectedPredicateEncoding.name().toLowerCase());
            System.err.printf("[solver-stats] ser-propagation-mode=%s%n",
                    selectedPropagationMode.name().toLowerCase().replace('_', '-'));
            System.err.printf("[solver-stats] gmwr-prepropagation=%s%n",
                    settings.gmwrPrepropagation);
            System.err.printf("[solver-stats] predicate-witness-coalescing=%s%n",
                    settings.predicateWitnessCoalescing);
            System.err.printf("[solver-stats] graph-edge-interning=%s%n",
                    settings.graphEdgeInterning);
            System.err.printf("[solver-stats] solver-timeout-seconds=%d%n",
                    settings.solverTimeoutSeconds);
            System.err.printf("Max memory: %s%n",
                    Utils.formatMemory(profiler.getMaxMemory()));
            System.err.println(result.marker);
        }
        System.err.printf("SER audit result: %s%n", result.name());
        return result.exitCode;
    }

    private void printHistorySummary(SERVerifier<?, ?> verifier) {
        System.err.println("History");
        System.err.printf(Locale.ROOT,
                "Transactions: %s | Events: %s | Predicates: %s%n%n",
                grouped(verifier.getTransactionCount()),
                grouped(verifier.getEventCount()),
                grouped(verifier.getPredicateObservationCount()));
        System.err.flush();
    }

    private void printCompletedSection(SERVerifier.AuditStage stage) {
        switch (stage) {
        case WW:
            printWwSummary();
            break;
        case GMWR:
            printGmwrSummary();
            break;
        case PREDICATE:
            printPredicateSummary();
            break;
        case SAT:
            printSatSummary();
            break;
        default:
            throw new IllegalArgumentException("Unknown audit stage: " + stage);
        }
        System.err.flush();
    }

    private void printWwSummary() {
        long initial = profiler.getCount("WW_INITIAL_CHOICES");
        long wwAfterReachability = profiler.getCount("WW_AFTER_REACHABILITY");
        long reachabilityForced = profiler.getCount("WW_REACHABILITY_FORCED");

        System.err.println("WW");
        if (selectedPredicateEncoding == SERVerifier.PredicateSolvingMode.GMWR) {
            System.err.printf(Locale.ROOT, "%s -> %s -> %s%n",
                    grouped(initial), grouped(wwAfterReachability),
                    grouped(profiler.getCount("WW_AFTER_GMWR")));
        } else {
            System.err.printf(Locale.ROOT, "%s -> %s%n",
                    grouped(initial), grouped(wwAfterReachability));
        }
        System.err.printf(Locale.ROOT, "Reachability forced: %s (%.1f%%)%n",
                grouped(reachabilityForced), percentage(reachabilityForced, initial));
        if (selectedPredicateEncoding == SERVerifier.PredicateSolvingMode.GMWR) {
            long gmwrReduced = Math.max(0L, wwAfterReachability
                    - profiler.getCount("WW_AFTER_GMWR"));
            System.err.printf(Locale.ROOT, "GMWR-WW reduced:      %s (%.1f%%)%n",
                    grouped(gmwrReduced), percentage(gmwrReduced, wwAfterReachability));
        }
        System.err.println();
    }

    private void printGmwrSummary() {
        System.err.println("GMWR");
        printSummaryTransition("Constraints:",
                profiler.getCount("GMWR_INITIAL_CONSTRAINTS"),
                profiler.getCount("GMWR_RESIDUAL_CONSTRAINTS"));
        printSummaryTransition("Bundles:",
                profiler.getCount("SER_GMWR_BUNDLES_COUNT"),
                profiler.getCount("SER_GMWR_RESIDUAL_BUNDLES_COUNT"));
        System.err.println();
    }

    private void printPredicateSummary() {
        System.err.println("Predicate");
        System.err.printf(Locale.ROOT,
                "Candidates: %s | Physical edges: %s | Skipped: %s%n%n",
                grouped(profiler.getCount("SER_PRED_DEPENDENCY_CANDIDATES_COUNT")),
                grouped(profiler.getCount("SER_PRED_DEPENDENCY_PHYSICAL_EDGES_COUNT")),
                grouped(profiler.getCount("SER_PRED_DEPENDENCY_SKIPPED_COUNT")));
    }

    private void printSatSummary() {
        System.err.println("SAT");
        System.err.printf(Locale.ROOT, "Variables: %s | Constraints: %s%n%n",
                grouped(profiler.getCount("SER_PROP_RESIDUAL_SAT_VARIABLES_COUNT")),
                grouped(profiler.getCount("SER_PROP_RESIDUAL_SAT_CONSTRAINTS_COUNT")));
    }

    private void printTimingSummary() {
        long gmwrMs = profiler.getTime("GMWR_BUILD_MS")
                + profiler.getTime("GMWR_REDUCTION_MS")
                + profiler.getTime("GMWR_WW_BRIDGE_MS");
        System.err.println("Timing");
        if (selectedPredicateEncoding == SERVerifier.PredicateSolvingMode.GMWR) {
            System.err.printf(Locale.ROOT,
                    "WW: %.3fs | GMWR: %.3fs | Predicate: %.3fs%n",
                    seconds("WW_REACHABILITY_PRUNE_MS"), gmwrMs / 1000.0,
                    seconds("SER_AR_ENCODE_PREDICATE"));
        } else {
            System.err.printf(Locale.ROOT, "WW: %.3fs | Predicate: %.3fs%n",
                    seconds("WW_REACHABILITY_PRUNE_MS"),
                    seconds("SER_AR_ENCODE_PREDICATE"));
        }
        System.err.printf(Locale.ROOT,
                "MonoSAT: %.3fs | Verify: %.3fs | Total: %.3fs%n%n",
                seconds("SER_MONOSAT_SOLVE"), seconds("SER_VERIFY_INT"),
                seconds("ENTIRE_EXPERIMENT"));
        System.err.flush();
    }

    private static void printSummaryTransition(
            String label, long initial, long residual) {
        System.err.printf(Locale.ROOT, "%-12s %9s -> %s%n",
                label, grouped(initial), grouped(residual));
    }

    private double seconds(String metric) {
        return profiler.getTime(metric) / 1000.0;
    }

    private static String grouped(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private static double percentage(long value, long denominator) {
        return denominator == 0L ? 0.0 : value * 100.0 / denominator;
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

    static String formatMemoryWithSpace(Long memoryBytes) {
        return formatMemory(memoryBytes).replaceFirst("(?<=\\d)(?=[A-Z])", " ");
    }
}
