import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryOnlyCatalogTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path HISTORIES_ROOT = Path.of("..", "PolySIHistories");

    @Test
    void historyOnlyDeepMulticycleCasesHaveCatalogManifestAndCycleDesignCoverage() throws Exception {
        var catalog = HISTORIES_ROOT.resolve(
                "deep_prhist_multicycle_suite/deep_prhist_multicycle_suite/catalog.json");

        assertHistoryOnlyCase(catalog, "deep_reject_overlapping_cycles_32x32", "REJECT",
                "cycle_design.json", List.of("RW_cycle", "PR_RW_cycle"), List.of(6, 5));
        assertHistoryOnlyCase(catalog, "deep_reject_multicore_dense_32x32", "REJECT",
                "cycle_design.json", List.of("RW_cycle", "PR_RW_cycle", "mixed_RW_PRRW_cycle"),
                List.of(6, 8, 12));
        assertHistoryOnlyCase(catalog, "deep_reject_backedge_cycle_len16_32x32", "REJECT",
                "cycle_design.json", List.of("RW_cycle"), List.of(16));
    }

    @Test
    void historyOnlyDenseAndHardScaleCasesHaveDesignCoverage() throws Exception {
        var denseCatalog = HISTORIES_ROOT.resolve(
                "dense_prhist_comprehensive_suite/dense_prhist_comprehensive_suite/catalog.json");
        assertHistoryOnlyCase(denseCatalog, "dense_accept_serial_48x32_scale", "ACCEPT",
                "hidden_core_design.json", List.of("ACCEPT_DENSE_SERIAL"), List.of());
        assertHistoryOnlyCase(denseCatalog, "dense_reject_multicore_overlap_32x32", "REJECT",
                "hidden_core_design.json", List.of("PR_RW_cycle", "mixed_RW_PRRW_cycle", "RW_cycle"),
                List.of(96, 128, 160));

        var hardCatalog = HISTORIES_ROOT.resolve(
                "hard_hidden_prhist_batch_v2/hard_hidden_prhist_batch_v2/catalog.json");
        assertHistoryOnlyCase(hardCatalog, "hard_reject_multicore_v2_32x32", "REJECT",
                "hidden_core_design.json", List.of("PR_RW_long_cycle", "mixed_RW_PRRW_long_cycle",
                        "RW_long_cycle"), List.of(36, 48, 64));
    }

    private static void assertHistoryOnlyCase(Path catalogPath, String caseName, String expectedVerdict,
            String designFile, List<String> expectedTypes, List<Integer> expectedLengths) throws Exception {
        var catalogRoot = catalogPath.getParent();
        var catalogEntry = findCatalogEntry(catalogPath, caseName);
        var histDir = resolveHistDir(catalogRoot, catalogEntry, caseName);
        assertTrue(Files.isDirectory(histDir), () -> "missing history directory: " + histDir);

        var manifest = readJson(histDir.resolve("manifest.json"));
        assertEquals(caseName, textOrNull(manifest, "dataset_name"));
        assertEquals(expectedVerdict, textOrNull(manifest, "expected_verdict"));
        assertEquals(expectedVerdict, textOrNull(catalogEntry, "expected_verdict"));

        var stats = scanHistory(histDir);
        assertOptionalCount(catalogEntry, "transactions", stats.transactions);
        assertOptionalCount(catalogEntry, "operations", stats.operations);
        assertOptionalCount(catalogEntry, "point_reads", stats.pointReads);
        assertOptionalCount(catalogEntry, "predicate_reads", stats.predicateReads);
        assertOptionalCount(catalogEntry, "writes", stats.writes);
        assertOptionalCount(catalogEntry, "predicate_result_rows", stats.predicateResultRows);
        assertOptionalCount(catalogEntry, "initial_keys", stats.initialKeys);

        assertDesign(histDir.resolve(designFile), expectedTypes, expectedLengths);
    }

    private static JsonNode findCatalogEntry(Path catalogPath, String caseName) throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(catalogPath), () -> "missing catalog: " + catalogPath);
        var catalog = readJson(catalogPath);
        var cases = catalog.isArray() ? catalog : catalog.path("cases");
        assertTrue(cases.isArray(), () -> catalogPath + " must contain a cases array");
        for (var entry : cases) {
            if (caseName.equals(textOrNull(entry, "case"))
                    || caseName.equals(textOrNull(entry, "dataset_name"))) {
                return entry;
            }
        }
        throw new AssertionError("missing catalog case " + caseName + " in " + catalogPath);
    }

    private static Path resolveHistDir(Path catalogRoot, JsonNode entry, String caseName) {
        var path = textOrNull(entry, "path");
        if (path != null) {
            return catalogRoot.resolve(path);
        }
        return catalogRoot.resolve(caseName).resolve("hist-00000");
    }

    private static HistoryStats scanHistory(Path histDir) throws Exception {
        var stats = new HistoryStats();
        var initialState = readJson(histDir.resolve("initial_state.json"));
        assertTrue(initialState.isArray(), () -> histDir + "/initial_state.json must be an array");
        stats.initialKeys = initialState.size();

        var historyFile = histDir.resolve("history.prhist.jsonl");
        assertTrue(Files.isRegularFile(historyFile), () -> "missing history file: " + historyFile);
        for (var line : Files.readAllLines(historyFile)) {
            if (line.isBlank()) {
                continue;
            }
            stats.transactions++;
            var txn = MAPPER.readTree(line);
            assertEquals("commit", textOrNull(txn, "status"));
            var ops = txn.path("ops");
            assertTrue(ops.isArray(), () -> "ops must be an array in " + historyFile);
            for (var op : ops) {
                stats.operations++;
                var type = textOrNull(op, "type");
                if ("r".equals(type)) {
                    stats.pointReads++;
                } else if ("pr".equals(type)) {
                    stats.predicateReads++;
                    var inputs = op.has("result")
                            ? op.path("result").path("inputs")
                            : op.path("results");
                    assertTrue(inputs.isArray(), () -> "predicate inputs/results must be an array in " + historyFile);
                    stats.predicateResultRows += inputs.size();
                } else if ("w".equals(type)) {
                    stats.writes++;
                } else {
                    throw new AssertionError("unknown op type: " + type);
                }
            }
        }
        return stats;
    }

    private static void assertDesign(Path designPath, List<String> expectedTypes,
            List<Integer> expectedLengths) throws Exception {
        assertTrue(Files.isRegularFile(designPath), () -> "missing design file: " + designPath);
        var design = readJson(designPath);
        assertTrue(design.isArray(), () -> designPath + " must be an array");

        var actualTypes = new LinkedHashSet<String>();
        var actualLengths = new ArrayList<Integer>();
        for (var item : design) {
            var type = textOrNull(item, "type");
            assertNotNull(type, () -> designPath + " item missing type");
            actualTypes.add(type);

            if (item.has("length")) {
                var length = item.path("length").asInt();
                actualLengths.add(length);
                var nodes = item.path("nodes");
                assertTrue(nodes.isArray(), () -> designPath + " item nodes must be an array");
                assertEquals(length, nodes.size(), () -> designPath + " node count must match length");
            }
        }

        assertEquals(new LinkedHashSet<>(expectedTypes), actualTypes);
        assertEquals(expectedLengths, actualLengths);
    }

    private static void assertOptionalCount(JsonNode entry, String fieldName, long actual) {
        if (entry.has(fieldName)) {
            assertEquals(entry.path(fieldName).asLong(), actual, fieldName);
        }
    }

    private static JsonNode readJson(Path path) throws Exception {
        assertTrue(Files.isRegularFile(path), () -> "missing JSON file: " + path);
        return MAPPER.readTree(path.toFile());
    }

    private static String textOrNull(JsonNode node, String fieldName) {
        var value = node.get(fieldName);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static class HistoryStats {
        long transactions;
        long operations;
        long pointReads;
        long predicateReads;
        long predicateResultRows;
        long writes;
        long initialKeys;
    }
}
