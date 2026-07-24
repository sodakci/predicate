package history.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class QueryPlanTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final RelationResolver<String> RELATIONS =
            RelationResolver.canonicalStringKeys();
    private static final StructuredQueryParser<String, JsonNode> PARSER =
            new StructuredQueryParser<>(ValueAdapter.jsonNodes(), RELATIONS);

    @Test
    void evaluatesArbitraryRelationsAndDeduplicatesSharedInput() {
        var plan = parse("{"
                + "\"from\":{\"relation\":\"purchases\",\"alias\":\"p\"},"
                + "\"joins\":[{\"relation\":\"inventory\",\"alias\":\"stock_row\","
                + "\"type\":\"INNER\","
                + "\"on\":[\"p.value.sku = stock_row.value.sku\"]}],"
                + "\"where\":[\"stock_row.value.stock > 0\"],"
                + "\"select\":{\"columns\":[\"p.value.buyer\","
                + "\"stock_row.value.stock\"],\"distinct\":false}}");

        var evaluation = plan.evaluate(state(
                "purchases:p0", "{\"sku\":\"s0\",\"buyer\":\"u0\"}",
                "purchases:p1", "{\"sku\":\"s0\",\"buyer\":\"u1\"}",
                "inventory:i0", "{\"sku\":\"s0\",\"stock\":5}",
                "audit:a0", "{\"sku\":\"s0\",\"stock\":99}"));

        assertEquals(Map.of(
                value("{\"buyer\":\"u0\",\"stock\":5}"), 1,
                value("{\"buyer\":\"u1\",\"stock\":5}"), 1),
                evaluation.valueMultiset());
        assertEquals(Set.of("purchases:p0", "purchases:p1", "inventory:i0"),
                evaluation.inputs().keySet());
        assertEquals(3, evaluation.inputs().size());
        assertEquals(Set.of("purchases", "inventory"), plan.scope().relations());
        assertTrue(plan.scope().covers("purchases:p0"));
        assertTrue(plan.scope().covers("inventory:i0"));
        assertFalse(plan.scope().covers("audit:a0"));
    }

    @Test
    void evaluatesThreeRelationsAndConjoinsAllWhereConditions() {
        var plan = parse("{"
                + "\"from\":{\"relation\":\"purchases\",\"alias\":\"p\"},"
                + "\"joins\":["
                + "{\"relation\":\"inventory\",\"alias\":\"i\",\"type\":\"INNER\","
                + "\"on\":[\"p.value.sku = i.value.sku\"]},"
                + "{\"relation\":\"depots\",\"alias\":\"d\",\"type\":\"INNER\","
                + "\"on\":[\"i.value.depot_key = d.value.depot_key\"]}],"
                + "\"where\":[\"i.value.stock > 0\",\"d.value.region = 'east'\","
                + "\"p.value.total > 10\"],"
                + "\"select\":{\"columns\":[\"p.value.purchase_id\","
                + "\"i.value.stock\",\"d.value.region\"],\"distinct\":false}}");

        var evaluation = plan.evaluate(state(
                "purchases:p0",
                "{\"purchase_id\":\"p0\",\"sku\":\"s0\",\"total\":25}",
                "inventory:i0",
                "{\"sku\":\"s0\",\"stock\":3,\"depot_key\":\"d0\"}",
                "depots:d0", "{\"depot_key\":\"d0\",\"region\":\"east\"}",
                "purchases:p1",
                "{\"purchase_id\":\"p1\",\"sku\":\"s1\",\"total\":5}",
                "inventory:i1",
                "{\"sku\":\"s1\",\"stock\":8,\"depot_key\":\"d1\"}",
                "depots:d1", "{\"depot_key\":\"d1\",\"region\":\"west\"}"));

        assertEquals(Map.of(
                value("{\"purchase_id\":\"p0\",\"stock\":3,\"region\":\"east\"}"),
                1), evaluation.valueMultiset());
        assertEquals(Set.of("purchases:p0", "inventory:i0", "depots:d0"),
                evaluation.inputs().keySet());
        assertEquals(Set.of("purchases", "inventory", "depots"),
                plan.scope().relations());
    }

    @Test
    void comparesValuesAsAnOrderIndependentMultisetAndUnionsDistinctProvenance() {
        var ordinary = parse(sharedProjectionQuery(false));
        var first = ordinary.evaluate(state(
                "purchases:p0", "{\"sku\":\"s0\"}",
                "purchases:p1", "{\"sku\":\"s0\"}",
                "inventory:i0", "{\"sku\":\"s0\",\"stock\":7}"));
        var reversed = ordinary.evaluate(state(
                "inventory:i0", "{\"sku\":\"s0\",\"stock\":7}",
                "purchases:p1", "{\"sku\":\"s0\"}",
                "purchases:p0", "{\"sku\":\"s0\"}"));

        assertEquals(Map.of(value("{\"stock\":7}"), 2), first.valueMultiset());
        assertTrue(first.canonicalEquals(reversed));

        var distinct = parse(sharedProjectionQuery(true)).evaluate(state(
                "purchases:p0", "{\"sku\":\"s0\"}",
                "purchases:p1", "{\"sku\":\"s0\"}",
                "inventory:i0", "{\"sku\":\"s0\",\"stock\":7}"));

        assertEquals(Map.of(value("{\"stock\":7}"), 1), distinct.valueMultiset());
        assertEquals(Set.of("purchases:p0", "purchases:p1", "inventory:i0"),
                distinct.inputs().keySet());
    }

    @Test
    void rejectsUnsupportedJoinTypeAndUnknownAlias() {
        assertThrows(QueryException.class, () -> parse("{"
                + "\"from\":{\"relation\":\"purchases\",\"alias\":\"p\"},"
                + "\"joins\":[{\"relation\":\"inventory\",\"alias\":\"i\","
                + "\"type\":\"LEFT\",\"on\":[\"p.value.sku = i.value.sku\"]}],"
                + "\"select\":{\"columns\":[\"p.value.sku\"]}}"));

        assertThrows(QueryException.class, () -> parse("{"
                + "\"from\":{\"relation\":\"purchases\",\"alias\":\"p\"},"
                + "\"joins\":[{\"relation\":\"inventory\",\"alias\":\"i\","
                + "\"type\":\"INNER\","
                + "\"on\":[\"missing.value.sku = i.value.sku\"]}],"
                + "\"select\":{\"columns\":[\"p.value.sku\"]}}"));
    }

    private static String sharedProjectionQuery(boolean distinct) {
        return "{"
                + "\"from\":{\"relation\":\"purchases\",\"alias\":\"p\"},"
                + "\"joins\":[{\"relation\":\"inventory\",\"alias\":\"i\","
                + "\"type\":\"INNER\",\"on\":[\"p.value.sku = i.value.sku\"]}],"
                + "\"select\":{\"columns\":[\"i.value.stock\"],\"distinct\":"
                + distinct + "}}";
    }

    private static QueryPlan<String, JsonNode> parse(String source) {
        return PARSER.parse(json(source));
    }

    private static MapVisibleState<String, JsonNode> state(String... entries) {
        if (entries.length % 2 != 0) {
            throw new IllegalArgumentException("state entries must be key/value pairs");
        }
        var values = new LinkedHashMap<String, JsonNode>();
        for (int index = 0; index < entries.length; index += 2) {
            values.put(entries[index], json(entries[index + 1]));
        }
        return new MapVisibleState<>(values, RELATIONS);
    }

    private static QueryValue value(String source) {
        return QueryValue.of(json(source));
    }

    private static JsonNode json(String source) {
        try {
            return MAPPER.readTree(source);
        } catch (JsonProcessingException exception) {
            throw new AssertionError("invalid test JSON: " + source, exception);
        }
    }
}
