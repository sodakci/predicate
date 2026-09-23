import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.Callable;

import history.loaders.PredicateHistoryLoader;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import util.Profiler;
import verifier.SIVerifier;

@Command(name = "si-result-detector", mixinStandardHelpOptions = true,
        version = "si-result-detector 0.1.0", subcommands = Audit.class)
public class Main implements Callable<Integer> {
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
    @Option(names = { "--solver-stats" },
            description = "print SAT backend and detailed predicate encoding statistics")
    private final Boolean solverStats = false;

    @Option(names = { "--gmwr" }, negatable = true,
            description = "enable GMWR encoding and frontier pruning")
    private Boolean gmwr;

    @Option(names = { "--gmwr-prepropagation" }, negatable = true,
            description = "enable GMWR prepropagation (effective only with GMWR)")
    private Boolean gmwrPrepropagation;

    @Parameters(paramLabel = "HISTORY", description = "history path")
    private Path path;

    private final Profiler profiler = Profiler.getInstance();
    private SIVerifier.PredicateMode selectedPredicateEncoding =
            SIVerifier.PredicateMode.GMWR;

    @Override
    public Integer call() {
        try {
            return runAudit();
        } catch (CommandLine.ParameterException exception) {
            throw exception;
        } catch (Throwable exception) {
            System.err.printf("[SI] Error: %s%n",
                    exception.getMessage() == null
                            ? exception.getClass().getSimpleName()
                            : exception.getMessage());
            System.err.println("SI audit result: ERROR");
            return 1;
        }
    }

    private Integer runAudit() {
        profiler.clear();
        var loader = new PredicateHistoryLoader(path);

        boolean gmwrEnabled = gmwr == null || gmwr;
        boolean prepropagationEnabled = gmwrPrepropagation == null
                || gmwrPrepropagation;
        selectedPredicateEncoding = gmwrEnabled
                ? SIVerifier.PredicateMode.GMWR
                : SIVerifier.PredicateMode.EAGER;
        var settings = SIVerifier.SolverSettings.defaults();
        settings.predicateMode = selectedPredicateEncoding;
        settings.gmwrPrepropagation = gmwrEnabled && prepropagationEnabled;
        settings.predicateWitnessCoalescing = true;
        settings.graphEdgeInterning = true;
        settings.detailedPredicateMetrics = solverStats;
        settings.auditProgressListener = this::printCompletedSection;
        profiler.startTick("ENTIRE_EXPERIMENT");
        var verifier = new SIVerifier<>(loader, settings, solverStats);
        printHistorySummary(verifier);
        var result = verifier.auditResult();
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
            System.err.printf("[solver-stats] gmwr=%s%n", gmwrEnabled);
            System.err.printf("[solver-stats] predicate-encoding=%s%n",
                    selectedPredicateEncoding.name().toLowerCase());
            System.err.printf("[solver-stats] gmwr-prepropagation=%s%n",
                    settings.gmwrPrepropagation);
            System.err.printf("[solver-stats] predicate-witness-coalescing=%s%n",
                    settings.predicateWitnessCoalescing);
            System.err.printf("[solver-stats] graph-edge-interning=%s%n",
                    settings.graphEdgeInterning);
            System.err.printf("Max memory: %s%n",
                    Utils.formatMemory(profiler.getMaxMemory()));
            System.err.println(result.marker);
        }
        System.err.printf("SI audit result: %s%n", result.name());
        return result.exitCode;
    }

    private void printHistorySummary(SIVerifier<?, ?> verifier) {
        System.err.println("History");
        System.err.printf(Locale.ROOT,
                "Transactions: %s | Events: %s | Predicates: %s%n%n",
                grouped(verifier.getTransactionCount()),
                grouped(verifier.getEventCount()),
                grouped(verifier.getPredicateObservationCount()));
        System.err.flush();
    }

    private void printCompletedSection(SIVerifier.AuditStage stage) {
        switch (stage) {
        case WW:
            printWwSummary();
            break;
        case GMWR:
            printGmwrSummary();
            break;
        case PREDICATE:
            if (solverStats) {
                printPredicateSummary();
            }
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
        System.err.printf(Locale.ROOT, "%s -> %s%n",
                grouped(initial), grouped(wwAfterReachability));
        System.err.printf(Locale.ROOT, "Reachability forced: %s (%.1f%%)%n",
                grouped(reachabilityForced), percentage(reachabilityForced, initial));
        System.err.println();
    }

    private void printGmwrSummary() {
        long wwInitial = profiler.getCount("WW_INITIAL_CHOICES");
        long wwAfterReachability = profiler.getCount("WW_AFTER_REACHABILITY");
        long gmwrInitial = profiler.getCount("GMWR_INITIAL_CONSTRAINTS");
        long gmwrResidual = profiler.getCount("GMWR_RESIDUAL_CONSTRAINTS");
        long gmwrMs = profiler.getTime("GMWR_BUILD_MS")
                + profiler.getTime("GMWR_PRUNING_MS")
                + profiler.getTime("GMWR_REDUCTION_MS");
        System.err.println("GMWR");
        System.err.printf(Locale.ROOT, "WW feedback: %s -> %s (%s forced, %dms)%n",
                grouped(wwAfterReachability), grouped(profiler.getCount("WW_AFTER_GMWR_FEEDBACK")),
                grouped(profiler.getCount("WW_GMWR_FEEDBACK_FORCED")),
                profiler.getTime("WW_GMWR_FEEDBACK_MS"));
        printSummaryTransition("Constraints:", gmwrInitial, gmwrResidual);
        long sources = profiler.getCount("SI_PRED_PR_WR_INITIAL_CONSTRAINTS_COUNT");
        long remaining = profiler.getCount("SI_PRED_PR_WR_RESIDUAL_CONSTRAINTS_COUNT");
        long forced = profiler.getCount("SI_PRED_PR_WR_FORCED_CONSTRAINTS_COUNT");
        System.err.printf(Locale.ROOT, "PR_WR constraints: %s -> %s%n",
                grouped(sources), grouped(remaining));
        System.err.printf(Locale.ROOT, "Forced PR_WR constraints: %s (%.1f%%)%n",
                grouped(forced), percentage(forced, sources));
        System.err.printf(Locale.ROOT,
                "PR_WR candidates: %s -> %s remaining (%s pruned, %s fixed)%n",
                grouped(profiler.getCount("SI_PRED_PR_WR_INITIAL_CANDIDATES_COUNT")),
                grouped(profiler.getCount("SI_PRED_PR_WR_RESIDUAL_CANDIDATES_COUNT")),
                grouped(profiler.getCount("SI_PRED_PR_WR_PRUNED_CANDIDATES_COUNT")),
                grouped(profiler.getCount("SI_PRED_PR_WR_FIXED_CANDIDATES_COUNT")));
        System.err.printf(Locale.ROOT,
                "PRUNING_COMPARISON_STATS ww_original=%d ww_residual=%d ww_reduced=%d "
                        + "ww_time_ms=%d gmwr_obligations_original=%d "
                        + "gmwr_obligations_residual=%d gmwr_obligations_reduced=%d "
                        + "gmwr_time_ms=%d%n",
                wwInitial, wwAfterReachability,
                Math.max(0L, wwInitial - wwAfterReachability),
                profiler.getTime("WW_REACHABILITY_PRUNE_MS"),
                gmwrInitial, gmwrResidual, Math.max(0L, gmwrInitial - gmwrResidual),
                gmwrMs);
        System.err.println();
    }

    private void printPredicateSummary() {
        long attempts = profiler.getCount("SI_PRED_DEPENDENCY_ATTEMPTS_COUNT");
        long candidates = profiler.getCount("SI_PRED_DEPENDENCY_CANDIDATES_COUNT");
        long fixedCandidates = profiler.getCount(
                "SI_PRED_DEPENDENCY_FIXED_CANDIDATES_COUNT");
        long generatedCandidates = Math.max(0L, candidates - fixedCandidates);
        long physicalEdges = profiler.getCount("SI_PRED_DEPENDENCY_PHYSICAL_EDGES_COUNT");
        System.err.println("Predicate");
        System.err.printf(Locale.ROOT,
                "Generated PR_WR/PR_RW: %s attempts -> %s remaining%n",
                grouped(attempts), grouped(generatedCandidates));
        System.err.printf(Locale.ROOT, "Fixed PR_WR/PR_RW:     %s%n",
                grouped(fixedCandidates));
        System.err.printf(Locale.ROOT,
                "All logical PR edges:  %s -> %s physical edges%n",
                grouped(candidates), grouped(physicalEdges));
        System.err.printf(Locale.ROOT, "Skipped: %s%n%n",
                grouped(profiler.getCount("SI_PRED_DEPENDENCY_SKIPPED_COUNT")));
    }

    private void printSatSummary() {
        System.err.println("SAT");
        System.err.printf(Locale.ROOT, "Variables: %s | Constraints: %s%n%n",
                grouped(profiler.getCount("SI_PROP_RESIDUAL_SAT_VARIABLES_COUNT")),
                grouped(profiler.getCount("SI_PROP_RESIDUAL_SAT_CONSTRAINTS_COUNT")));
    }

    private void printTimingSummary() {
        System.err.println("Timing");
        if (selectedPredicateEncoding == SIVerifier.PredicateMode.GMWR) {
            System.err.printf(Locale.ROOT,
                    "WW: %.3fs | GMWR build: %.3fs | Predicate pruning: %.3fs | Prepropagation: %.3fs%n",
                    seconds("WW_REACHABILITY_PRUNE_MS"), seconds("GMWR_BUILD_MS"),
                    seconds("GMWR_PRUNING_MS"), seconds("GMWR_REDUCTION_MS"));
        } else {
            System.err.printf(Locale.ROOT, "WW: %.3fs | Predicate pruning: %.3fs%n",
                    seconds("WW_REACHABILITY_PRUNE_MS"), seconds("SI_PRED_PRUNING_MS"));
        }
        System.err.printf(Locale.ROOT, "Predicate: %.3fs%n",
                seconds("SI_GRAPH_ENCODE_PREDICATE"));
        System.err.printf(Locale.ROOT,
                "MonoSAT: %.3fs | Verify: %.3fs | Total: %.3fs%n%n",
                seconds("SI_MONOSAT_SOLVE"), seconds("SI_VERIFY_INT"),
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
