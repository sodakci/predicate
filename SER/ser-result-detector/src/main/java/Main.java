import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import history.Event;
import history.Event.EventType;
import history.History;
import history.HistoryLoader;
import history.Transaction;
import history.loaders.PredicateHistoryLoader;
import lombok.SneakyThrows;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ITypeConverter;
import util.Profiler;
import verifier.SERVerifier;

@Command(name = "ser-result-detector", mixinStandardHelpOptions = true, version = "ser-result-detector 0.1.0", subcommands = { Audit.class,
        ConstraintStat.class, Stat.class, Dump.class })
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

@Command(name = "constraint-stat", mixinStandardHelpOptions = true,
        description = "Count SER constraints before and after pruning without solving")
class ConstraintStat implements Callable<Integer> {
    @Option(names = { "-t", "--type" }, description = "history type: ${COMPLETION-CANDIDATES}")
    private final HistoryType type = HistoryType.PRHIST;

    @Option(names = { "--ww-pruning" },
            description = "WW pruning: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE})")
    private WwPruningMode wwPruning = WwPruningMode.REACHABILITY;

    @Parameters(description = "history path")
    private Path path;

    @Override
    public Integer call() {
        var loader = Utils.getLoader(type, path);
        var verifier = new SERVerifier<>(loader, false,
                SERVerifier.PredicateSolvingMode.EAGER, wwPruning.asVerifierMode());
        var stats = verifier.analyzeConstraintsOnly();
        System.out.printf(
                "CONSTRAINT_STATS ww_pruning=%s constraints_before=%d constraints_after=%d "
                        + "implications_before=%d implications_after=%d "
                        + "internally_consistent=%s pruning_inconsistent=%s%n",
                wwPruning.name(),
                stats.constraintsBefore,
                stats.constraintsAfter,
                stats.implicationsBefore,
                stats.implicationsAfter,
                stats.internallyConsistent,
                stats.pruningInconsistent);
        return stats.internallyConsistent ? 0 : 2;
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

    @Option(names = { "-t", "--type" }, description = "history type: ${COMPLETION-CANDIDATES}")
    private final HistoryType type = HistoryType.PRHIST;

    @Option(names = { "--ww-pruning" },
            description = "WW pruning: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE})")
    private WwPruningMode wwPruning = WwPruningMode.REACHABILITY;

    @Option(names = { "--no-coalescing" }, description = "disable coalescing")
    private final Boolean noCoalescing = false;

    @Option(names = { "--dot-output" }, description = "print conflicts in dot format")
    private final Boolean dotOutput = false;

    @Option(names = { "--compare-derived-predicate-edges" }, description = "derive PR_* graph edges for diagnostics only")
    private final Boolean compareDerivedPredicateEdges = false;

    @Option(names = { "--solver" }, description = "SAT solver backend; only monosat is supported")
    private String solverKind = "monosat";

    @Option(names = { "--solver-timeout-seconds" }, description = "SAT solver timeout in seconds measured from solve(); 0 disables backend timeout")
    private int solverTimeoutSeconds = 600;

    @Option(names = { "--solver-stats" },
            description = "print SAT backend and detailed predicate encoding statistics")
    private final Boolean solverStats = false;

    @Option(names = { "--predicate-mode" },
            description = "predicate solving mode: ${COMPLETION-CANDIDATES}")
    private SERVerifier.PredicateSolvingMode predicateSolvingMode =
            SERVerifier.PredicateSolvingMode.EAGER;

    @Option(names = { "--ser-propagation-mode" },
            converter = SerPropagationModeConverter.class,
            description = "SER propagation mode: ww-only, ww-gmwr-oneway, or ww-gmwr")
    private SERVerifier.SerPropagationMode serPropagationMode;

    @Option(names = { "--gmwr-prepropagation" }, negatable = true,
            description = "run GMWR simplification before SAT; default follows --predicate-mode. Does not control obligation construction")
    private Boolean gmwrPrepropagation;

    @Option(names = { "--predicate-witness-coalescing" }, negatable = true,
            description = "coalesce same-endpoint predicate witnesses; default on")
    private Boolean predicateWitnessCoalescing;

    @Option(names = { "--graph-edge-interning" }, negatable = true,
            description = "intern one MonoSAT theory-edge per (from,to); default on (E2 baseline)")
    private Boolean graphEdgeInterning;

    @Option(names = { "--verify-incremental-propagation" },
            description = "compare incremental GMWR-WW notification against a full residual scan")
    private final Boolean verifyIncrementalPropagation = false;

    @Parameters(description = "history path")
    private Path path;

    private final Profiler profiler = Profiler.getInstance();

    @Override
    public Integer call() {
        var loader = Utils.getLoader(type, path);

        var selectedPruningMode = wwPruning.asVerifierMode();
        var selectedPropagationMode = serPropagationMode != null
                ? serPropagationMode
                : predicateSolvingMode == SERVerifier.PredicateSolvingMode.GMWR
                        ? SERVerifier.SerPropagationMode.WW_GMWR
                        : SERVerifier.SerPropagationMode.WW_ONLY;
        var settings = SERVerifier.SolverSettings.forModes(
                predicateSolvingMode, selectedPruningMode, selectedPropagationMode);
        if (gmwrPrepropagation != null) {
            settings.gmwrPrepropagation = gmwrPrepropagation;
        }
        if (predicateWitnessCoalescing != null) {
            settings.predicateWitnessCoalescing = predicateWitnessCoalescing;
        }
        if (graphEdgeInterning != null) {
            settings.graphEdgeInterning = graphEdgeInterning;
        }
        settings.verifyIncrementalPropagation = Boolean.TRUE.equals(
                verifyIncrementalPropagation);
        settings.solverTimeoutSeconds = solverTimeoutSeconds;
        settings.detailedPredicateMetrics = solverStats;
        SERVerifier.setCoalesceConstraints(!noCoalescing);
        SERVerifier.setDotOutput(dotOutput);
        SERVerifier.setCompareDerivedPredicateEdges(compareDerivedPredicateEdges);
        if (!"monosat".equalsIgnoreCase(solverKind)) {
            throw new CommandLine.ParameterException(
                    new CommandLine(this),
                    "Invalid value for --solver: only monosat is supported");
        }

        profiler.startTick("ENTIRE_EXPERIMENT");
        var verifier = new SERVerifier<>(loader, settings, solverStats);
        var result = verifier.audit();
        profiler.endTick("ENTIRE_EXPERIMENT");

        for (var p : profiler.getDurations()) {
            System.err.printf("%s: %dms\n", p.getKey(), p.getValue());
        }
        for (var p : profiler.getCounts()) {
            System.err.printf("%s: %d\n", p.getKey(), p.getValue());
        }
        if (solverStats) {
            System.err.println("[solver-stats] backend=monosat");
            System.err.printf("[solver-stats] predicate-mode=%s%n",
                    predicateSolvingMode.name().toLowerCase());
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
        }
        System.err.printf("Max memory: %s\n", Utils.formatMemory(profiler.getMaxMemory()));

        System.err.println(result.marker);
        return result.exitCode;
    }
}

@Command(name = "stat", mixinStandardHelpOptions = true, description = "Print some statistics of a history")
class Stat implements Callable<Integer> {
    @Option(names = { "-t", "--type" }, description = "history type: ${COMPLETION-CANDIDATES}")
    private final HistoryType type = HistoryType.PRHIST;

    @Parameters(description = "history path")
    private Path path;

    @Override
    public Integer call() {
        var loader = Utils.getLoader(type, path);
        var history = loader.loadHistory();

        var txns = history.getClientTransactions();
        var events = history.getEvents();
        var writeFreq = events.stream()
                .collect(Collectors.toMap(ev -> ev.getKey(), ev -> ev.getType().equals(EventType.WRITE) ? 1 : 0,
                        Integer::sum))
                .entrySet().stream().collect(Collectors.toMap(w -> w.getValue(), w -> 1, Integer::sum)).entrySet()
                .stream().sorted((p, q) -> Integer.compare(p.getKey(), q.getKey()))
                .collect(Collectors.toCollection(ArrayList::new));

        System.out.printf(
                "Sessions: %d\n" + "Transactions: %d, read-only: %d, write-only: %d, read-modify-write: %d\n"
                        + "Events: total %d, read %d, write %d\n" + "Variables: %d\n",
                history.getClientSessions().size(), txns.size(),
                txns.stream().filter(txn -> txn.getEvents().stream().allMatch(ev -> ev.getType() == EventType.READ))
                        .count(),
                txns.stream().filter(txn -> txn.getEvents().stream().allMatch(ev -> ev.getType() == EventType.WRITE))
                        .count(),
                txns.stream().filter(Stat::isReadModifyWriteTxn).count(), events.size(),
                events.stream().filter(e -> e.getType() == Event.EventType.READ).count(),
                events.stream().filter(e -> e.getType() == Event.EventType.WRITE).count(),
                events.stream().map(e -> e.getKey()).distinct().count());

        System.out.println("(writes, #keys):");
        int min = writeFreq.get(0).getKey(), max = writeFreq.get(writeFreq.size() - 1).getKey();
        int step = Math.max((max - min) / 8, 1), lowerBound;

        if (writeFreq.get(0).getKey() == 1) {
            System.out.printf("1: %d\n", writeFreq.get(0).getValue());
            lowerBound = 2;
        } else {
            lowerBound = 1;
        }
        for (; lowerBound <= max; lowerBound += step) {
            int x = lowerBound;
            int count = writeFreq.stream().filter(w -> x <= w.getKey() && w.getKey() < x + step)
                    .mapToInt(w -> w.getValue()).sum();
            System.out.printf("%d...%d: %d\n", lowerBound, lowerBound + step - 1, count);
        }

        return 0;
    }

    private static boolean isReadModifyWriteTxn(Transaction<?, ?> txn) {
        var readKeys = new HashSet<Object>();
        for (var ev : txn.getEvents()) {
            if (ev.getType().equals(EventType.READ)) {
                readKeys.add(ev.getKey());
            } else if (readKeys.contains(ev.getKey())) {
                return true;
            }
        }

        return false;
    }
}

@Command(name = "dump", mixinStandardHelpOptions = true, description = "Print a history to stdout")
class Dump implements Callable<Integer> {
    @Option(names = { "-t", "--type" }, description = "history type: ${COMPLETION-CANDIDATES}")
    private final HistoryType type = HistoryType.PRHIST;

    @Parameters(description = "history path")
    private Path path;

    @Override
    public Integer call() {
        var loader = Utils.getLoader(type, path);
        var history = loader.loadHistory();

        for (var session : history.getSessions()) {
            for (var txn : session.getTransactions()) {
                var events = txn.getEvents();
                System.out.printf("Transaction %s\n", txn);
                for (var j = 0; j < events.size(); j++) {
                    var ev = events.get(j);
                    System.out.printf("%s\n", ev);
                }
                System.out.println();
            }
        }

        return 0;
    }

}

class Utils {
    static HistoryLoader<?, ?> getLoader(HistoryType type, Path path) {
        switch (type) {
        case PRHIST:
            return new PredicateHistoryLoader(path);
        default:
            throw new IllegalArgumentException("Unsupported history type: " + type);
        }

    }

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

enum HistoryType {
    PRHIST
}
