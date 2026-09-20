package verifier;

import graph.KnownGraph;
import history.Event;
import history.History;
import history.HistoryLoader;
import history.Transaction;
import history.loaders.PredicateHistoryLoader;
import history.query.MapVisibleState;
import history.query.QueryScope;
import history.query.QueryValue;
import history.query.RecordedQueryResult;
import history.query.RelationResolver;
import history.query.StructuredQueryParser;
import history.query.ValueAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import util.Profiler;

/**
 * Semantic regressions runnable from JUnit or directly with main().
 * The independent serial oracle
 * executes complete transactions, not predicate constraints or frontiers.
 */
public final class PredicateSemanticsRegression {
    private static final String EMPTY = "[]";
    private final Path root;
    private final List<String> results = new ArrayList<>();
    private int cases;
    private int failures;

    public PredicateSemanticsRegression(Path root) {
        this.root = root;
        results.add("case\tencoding\tpruning\texpected\tactual");
    }

    public static void main(String[] args) throws Exception {
        var root = args.length == 0 ? Files.createTempDirectory("ser-semantics-") : Path.of(args[0]);
        runAll(root);
    }

    public static void runAll(Path root) throws Exception {
        Files.createDirectories(root);
        var suite = new PredicateSemanticsRegression(root);
        suite.targetedHistories();
        suite.customPhysicalMappings();
        suite.coverageAndAcceleration();
        suite.existingDifferentialFixtures();
        Files.write(root.resolve("results.tsv"), suite.results);
        var summary = "histories=" + suite.cases + ", comparisons=" + (suite.results.size() - 1)
                + ", mismatches=" + suite.failures;
        Files.writeString(root.resolve("summary.txt"), summary + "\n");
        System.out.println("Predicate semantics regression: " + summary);
        check(suite.failures == 0, summary + "; see " + root.resolve("results.tsv"));
    }

    private void targetedHistories() throws Exception {
        var a0 = row("A:x", 0);
        var a1 = row("A:x", 1);
        var b1 = row("B:y", 1);
        var joined = "[{\"left_key\":\"x\",\"right_key\":\"y\"}]";
        var join = joinQuery("A", "a", "B", "b");
        var renamed = joinQuery("A", "left_side", "B", "right_side");
        var before = read(join, EMPTY, EMPTY);
        var after = read(join, array(a1, b1), joined);
        history("repeated-join-wrong-empty", false, array(a0, b1),
                txn(0, 10, before, write("A:x", 1), before));
        history("repeated-join-correct", true, array(a0, b1),
                txn(0, 10, before, write("A:x", 1), after));
        history("renamed-join-wrong-empty", false, array(a0, b1),
                txn(0, 10, before, write("A:x", 1), read(renamed, EMPTY, EMPTY)));
        history("renamed-join-correct", true, array(a0, b1),
                txn(0, 10, before, write("A:x", 1), read(renamed, array(a1, b1), joined)));
        history("single-join-wrong-empty", false, array(a0, b1),
                txn(0, 10, write("A:x", 1), before));
        history("repeated-join-no-own-write-wrong", false, array(a1, b1),
                txn(0, 10, after, before));
        history("join-does-not-ignore-self-write", false, array(a0, b1),
                txn(0, 10, write("A:x", 2), after));
        history("join-all-local", true, array(a0, row("B:y", 0)),
                txn(0, 10, write("A:x", 1), write("B:y", 1), after));
        history("join-same-local-key-two-tables", true, array(row("A:x", 7), row("B:x", 7)),
                txn(0, 10, read(join, array(row("A:x", 7), row("B:x", 7)),
                        "[{\"left_key\":\"x\",\"right_key\":\"x\"}]")));

        var selfJoin = joinQuery("A", "a", "A", "b");
        var selfInputs = array(row("A:x", 7), row("A:y", 8));
        history("single-table-self-join", true, selfInputs,
                txn(0, 10, read(selfJoin, selfInputs,
                        "[{\"left_key\":\"x\",\"right_key\":\"x\"},"
                        + "{\"left_key\":\"y\",\"right_key\":\"y\"}]")));
        history("single-table-self-join-omitted", false, selfInputs,
                txn(0, 10, read(selfJoin, EMPTY, EMPTY)));

        var legacyJoin = joinQuery("kv", "a", "lookup", "b");
        var legacyInputs = array(row("k0", 7), row("lookup:x", 7));
        history("legacy-kv-key-in-multi-table-query", true, legacyInputs,
                txn(0, 10, read(legacyJoin, legacyInputs,
                        "[{\"left_key\":\"k0\",\"right_key\":\"x\"}]")));
        history("legacy-kv-key-in-multi-table-query-omitted", false, legacyInputs,
                txn(0, 10, read(legacyJoin, EMPTY, EMPTY)));

        var compact = rowQuery("kv", true);
        var projection = rowQuery("kv", false);
        var kv0 = row("kv:k0", 7);
        var kv1 = row("kv:k1", 7);
        var kvInputs = array(kv0, kv1);
        history("row-local-bag", true, kvInputs,
                txn(0, 10, read(projection, kvInputs, "[{\"value\":7},{\"value\":7}]")));
        history("row-local-bag-missing-duplicate", false, kvInputs,
                txn(0, 10, read(projection, kvInputs, "[{\"value\":7}]")));
        history("compact-correct", true, array(kv0),
                txn(0, 10, read(compact, array(kv0), "[{\"k\":\"k0\",\"value\":7}]")));
        history("compact-wrong-projection", false, array(kv0),
                txn(0, 10, read(compact, array(kv0), "[{\"k\":\"k0\",\"value\":99}]")));
        history("legacy-single-table", true, array(row("k0", 7)),
                txn(0, 10, read(compact, array(row("k0", 7)), "[{\"k\":\"k0\",\"value\":7}]")));
        history("row-local-repeat-self-write", true, array(row("kv:k0", 0)),
                txn(0, 10, read(compact, EMPTY, EMPTY), write("kv:k0", 7),
                        read(compact, array(kv0), "[{\"k\":\"k0\",\"value\":7}]")));
        history("row-local-repeat-self-write-omitted", false, array(row("kv:k0", 0)),
                txn(0, 10, read(compact, EMPTY, EMPTY), write("kv:k0", 7),
                        read(compact, EMPTY, EMPTY)));

        var qa = rowQuery("A", true);
        var qb = rowQuery("B", true);
        var readA1 = read(qa, array(a1), "[{\"k\":\"x\",\"value\":1}]");
        var readB0 = read(qb, array(row("B:y", 0)), "[{\"k\":\"y\",\"value\":0}]");
        var readB1 = read(qb, array(b1), "[{\"k\":\"y\",\"value\":1}]");
        // Use < 2 for this pair so the old B version contributes as well.
        readA1 = readA1.replace("value > 0", "value < 2");
        readB0 = readB0.replace("value > 0", "value < 2");
        readB1 = readB1.replace("value > 0", "value < 2");
        history("cross-table-fractured-read", false, array(a0, row("B:y", 0)),
                txn(1, 11, write("A:x", 1), write("B:y", 1)), txn(2, 12, readA1, readB0));
        history("cross-table-common-serialization", true, array(a0, row("B:y", 0)),
                txn(1, 11, write("A:x", 1), write("B:y", 1)), txn(2, 12, readA1, readB1));
        history("join-can-order-writer-after-reader", true, array(a0, b1),
                txn(1, 11, write("A:x", 1)), txn(2, 12, before));
        history("join-session-order-forces-writer-before-reader", false, array(a0, b1),
                txn(1, 11, write("A:x", 1)), txn(1, 12, before));
    }

    private void customPhysicalMappings() throws Exception {
        check(RelationResolver.canonicalStringKeys().relationOf("k0").equals("kv"), "legacy key mapping");
        check(RelationResolver.canonicalStringKeys().relationOf("A:x").equals("A"), "qualified key mapping");
        var fixed = RelationResolver.<String>fixed("alpha");
        var scope = QueryScope.forRelations(java.util.Set.of("alpha"), fixed);
        check(scope.relationResolver() == fixed, "scope must expose its actual resolver");
        programmatic("fixed-resolver-not-key-prefix", fixed,
                rowQuery("alpha", true), Map.of("physical:x", 7));
        RelationResolver<String> mapped = key -> key.equals("x") ? "A" : "B";
        programmatic("custom-mapping-two-tables", mapped,
                joinQuery("A", "a", "B", "b"), Map.of("x", 7, "y", 7));
    }

    private void programmatic(String name, RelationResolver<String> resolver,
            String query, Map<String, Integer> initial) throws Exception {
        ValueAdapter<Integer> adapter = QueryValue::integer;
        var parser = new StructuredQueryParser<String, Integer>(adapter, resolver);
        var plan = parser.parse(new ObjectMapper().readTree(query));
        var evaluation = plan.evaluate(new MapVisibleState<>(initial, resolver));
        check(!evaluation.inputs().isEmpty(), "custom mapping fixture must produce a result");
        Supplier<History<String, Integer>> factory = () -> {
            var h = new History<String, Integer>();
            var bottom = h.addTransaction(h.addSession(-1L), -1L);
            initial.forEach((key, value) -> h.addEvent(bottom, Event.EventType.WRITE, key, value));
            bottom.setStatus(Transaction.TransactionStatus.COMMIT);
            var reader = h.addTransaction(h.addSession(0L), 10L);
            var sources = new ArrayList<Event.PredResult<String, Integer>>();
            evaluation.inputs().forEach((key, value) -> sources.add(new Event.PredResult<>(key, value)));
            h.addPredicateReadEvent(reader, plan, sources,
                    RecordedQueryResult.general(evaluation.inputs(), evaluation.values(), adapter));
            reader.setStatus(Transaction.TransactionStatus.COMMIT);
            return h;
        };
        compare(name, factory, true, true);
    }

    private void coverageAndAcceleration() {
        var joined = new PredicateHistoryLoader(root.resolve("repeated-join-correct")).loadHistory();
        var graph = new KnownGraph<>(joined, false);
        var second = graph.getPredicateObservations().get(1);
        check(second.getPredicateReadType("A:x") == KnownGraph.PredicateReadType.INTERNAL,
                "earlier self write remains INTERNAL");
        check(second.getPredicateReadType("B:y") == KnownGraph.PredicateReadType.EXTERNAL,
                "a non-contributing JOIN row must retain an external frontier");
        for (var mode : SERVerifier.PredicateSolvingMode.values()) {
            var profiler = Profiler.getInstance();
            profiler.clear();
            var verifier = new SERVerifier<>(new PredicateHistoryLoader(root.resolve("compact-correct")), true, mode);
            check(verifier.audit() == SERVerifier.AuditResult.ACCEPT, "compact acceleration acceptance");
            check(profiler.getCount("SER_PRED_ROW_LOCAL_ENCODED_COUNT") > 0,
                    "single-table acceleration must remain active in " + mode);
        }
    }

    /** Reuses the uploaded suite's generators and serial-order oracle, not its JUnit runner. */
    private void existingDifferentialFixtures() throws Exception {
        var type = Class.forName("verifier.SERSolverARDifferentialTest");
        var random = type.getDeclaredMethod("randomHistory", int.class);
        var oracle = type.getDeclaredMethod("exhaustiveOracle", History.class);
        random.setAccessible(true);
        oracle.setAccessible(true);
        for (int seed = 0; seed < 160; seed++) {
            final int current = seed;
            @SuppressWarnings("unchecked")
            var history = (History<String, Integer>) random.invoke(null, current);
            var expected = (Boolean) oracle.invoke(null, history);
            compare("existing-memory-" + seed, () -> invokeHistory(random, null, current), expected, false);
        }
        var constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        var instance = constructor.newInstance();
        var temp = type.getDeclaredField("tempDir");
        temp.setAccessible(true);
        temp.set(instance, root);
        var json = type.getDeclaredMethod("writeRandomPrhist", int.class);
        json.setAccessible(true);
        for (int seed = 0; seed < 60; seed++) {
            var path = (Path) json.invoke(instance, seed);
            var history = new PredicateHistoryLoader(path).loadHistory();
            var expected = (Boolean) oracle.invoke(null, history);
            compare("existing-prhist-" + seed,
                    () -> new PredicateHistoryLoader(path).loadHistory(), expected, false);
        }
        var predicateType = Class.forName(type.getName() + "$MatrixPredicateType");
        var writerType = Class.forName(type.getName() + "$MatrixWriterPattern");
        var matrix = type.getDeclaredMethod("predicateMatrixCase", predicateType, writerType);
        matrix.setAccessible(true);
        for (var predicate : predicateType.getEnumConstants()) {
            for (var writer : writerType.getEnumConstants()) {
                var fixture = matrix.invoke(null, predicate, writer);
                var historyField = fixture.getClass().getDeclaredField("history");
                historyField.setAccessible(true);
                @SuppressWarnings("unchecked")
                var h = (History<String, Integer>) historyField.get(fixture);
                var expected = (Boolean) oracle.invoke(null, h);
                compare("existing-matrix-" + predicate + "-" + writer, () -> {
                    try {
                        @SuppressWarnings("unchecked")
                        var fresh = (History<String, Integer>) historyField.get(matrix.invoke(null, predicate, writer));
                        return fresh;
                    } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
                }, expected, false);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static History<String, Integer> invokeHistory(java.lang.reflect.Method method, Object receiver, int seed) {
        try { return (History<String, Integer>) method.invoke(receiver, seed); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private void history(String name, boolean expected, String initial, String... transactions) throws Exception {
        var path = Files.createDirectories(root.resolve(name));
        Files.writeString(path.resolve("initial_state.json"), initial);
        Files.write(path.resolve("history.prhist.jsonl"), List.of(transactions));
        compare(name, () -> new PredicateHistoryLoader(path).loadHistory(), expected, true);
    }

    private <V> void compare(String name, Supplier<History<String, V>> factory,
            boolean expected, boolean targeted) {
        cases++;
        check(serialOracle(factory.get()) == expected, "fixture/oracle disagreement: " + name);
        for (var mode : SERVerifier.PredicateSolvingMode.values()) {
            var pruningModes = targeted
                    ? List.of(SERVerifier.PruningMode.NONE, SERVerifier.PruningMode.REACHABILITY)
                    : List.of(SERVerifier.PruningMode.REACHABILITY);
            for (var pruning : pruningModes) {
                var h = factory.get();
                Profiler.getInstance().clear();
                HistoryLoader<String, V> loader = () -> h;
                var actual = new SERVerifier<>(loader, false, mode, pruning).audit();
                results.add(name + "\t" + mode + "\t" + pruning
                        + "\t" + (expected ? "ACCEPT" : "REJECT") + "\t" + actual);
                if (actual != (expected ? SERVerifier.AuditResult.ACCEPT : SERVerifier.AuditResult.REJECT)) {
                    failures++;
                    System.err.println("MISMATCH " + results.get(results.size() - 1));
                }
            }
        }
    }

    private static <V> boolean serialOracle(History<String, V> history) {
        var clients = new ArrayList<>(history.getClientTransactions());
        check(clients.size() <= 8, "serial oracle is limited to eight client transactions");
        return permute(history, clients, 0);
    }

    private static <V> boolean permute(History<String, V> h, List<Transaction<String, V>> order, int index) {
        if (index == order.size()) { return executeOrder(h, order); }
        for (int i = index; i < order.size(); i++) {
            Collections.swap(order, index, i);
            boolean valid = permute(h, order, index + 1);
            Collections.swap(order, index, i);
            if (valid) { return true; }
        }
        return false;
    }

    private static <V> boolean executeOrder(History<String, V> h, List<Transaction<String, V>> order) {
        var rank = new HashMap<Transaction<String, V>, Integer>();
        for (int i = 0; i < order.size(); i++) { rank.put(order.get(i), i); }
        for (var session : h.getClientSessions()) {
            int previous = -1;
            for (var txn : session.getTransactions()) {
                int current = rank.get(txn);
                if (current <= previous) { return false; }
                previous = current;
            }
        }
        var state = new LinkedHashMap<String, V>();
        var bottom = h.getTransaction(-1L);
        if (bottom != null) {
            for (var e : bottom.getEvents()) {
                if (e.getType() == Event.EventType.WRITE && e.getValue() != null) {
                    state.put(e.getKey(), e.getValue());
                }
            }
        }
        for (var txn : order) {
            for (var e : txn.getEvents()) {
                switch (e.getType()) {
                case WRITE:
                    if (e.getValue() == null) { state.remove(e.getKey()); }
                    else { state.put(e.getKey(), e.getValue()); }
                    break;
                case READ:
                    if (!Objects.equals(e.getValue(), state.get(e.getKey()))) { return false; }
                    break;
                case PREDICATE_READ:
                    var actual = e.getPredicate().evaluate(new MapVisibleState<>(
                            state, e.getPredicate().scope().relationResolver()));
                    var expectedInputs = new LinkedHashMap<String, V>();
                    for (var input : e.getPredResults()) {
                        if (expectedInputs.put(input.getKey(), input.getValue()) != null) { return false; }
                    }
                    if (!actual.inputs().equals(expectedInputs)) { return false; }
                    var recorded = e.getRecordedPredicateResult();
                    if (recorded != null && !recorded.canonicalEquals(actual)) { return false; }
                    break;
                default: throw new AssertionError(e.getType());
                }
            }
        }
        return true;
    }

    private static String row(String key, int value) {
        return "{\"key\":\"" + key + "\",\"value\":" + value + "}";
    }
    private static String write(String key, int value) {
        return "{\"type\":\"w\",\"key\":\"" + key + "\",\"value\":" + value + "}";
    }
    private static String array(String... values) { return "[" + String.join(",", values) + "]"; }
    private static String txn(long session, long id, String... operations) {
        return "{\"session\":" + session + ",\"session_seq\":" + id + ",\"txn\":" + id
                + ",\"status\":\"commit\",\"ops\":" + array(operations) + "}";
    }
    private static String read(String query, String inputs, String values) {
        return "{\"type\":\"pr\",\"query\":" + query + ",\"result\":{\"inputs\":"
                + inputs + ",\"values\":" + values + "}}";
    }
    private static String rowQuery(String table, boolean compact) {
        return "{\"from\":{\"relation\":\"" + table + "\"},\"select\":{\"columns\":"
                + (compact ? "[\"k\",\"value\"]" : "[\"value\"]") + ",\"distinct\":false"
                + "},\"where\":[\"value > 0\"]}";
    }
    private static String joinQuery(String left, String leftAlias, String right, String rightAlias) {
        return "{\"from\":{\"relation\":\"" + left + "\",\"alias\":\"" + leftAlias
                + "\"},\"joins\":[{\"relation\":\"" + right + "\",\"alias\":\"" + rightAlias
                + "\",\"type\":\"INNER\",\"on\":[\"" + leftAlias + ".value = " + rightAlias
                + ".value\"]}],\"select\":{\"columns\":[\"" + leftAlias + ".k AS left_key\",\""
                + rightAlias + ".k AS right_key\"],\"distinct\":false}}";
    }
    private static void check(boolean condition, String description) {
        if (!condition) { throw new AssertionError(description); }
    }
}
