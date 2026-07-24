package history.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable, structurally comparable value used by the query evaluator. */
public final class QueryValue implements Comparable<QueryValue> {
    private final JsonNode value;

    private QueryValue(JsonNode value) {
        this.value = normalize(value == null ? NullNode.getInstance() : value);
    }

    public static QueryValue of(JsonNode value) {
        return new QueryValue(value);
    }

    public static QueryValue integer(long value) {
        return new QueryValue(JsonNodeFactory.instance.numberNode(value));
    }

    public static QueryValue text(String value) {
        return new QueryValue(JsonNodeFactory.instance.textNode(
                Objects.requireNonNull(value, "value")));
    }

    public static QueryValue bool(boolean value) {
        return new QueryValue(JsonNodeFactory.instance.booleanNode(value));
    }

    public static QueryValue nullValue() {
        return new QueryValue(NullNode.getInstance());
    }

    public static QueryValue object(Map<String, QueryValue> fields) {
        var object = JsonNodeFactory.instance.objectNode();
        fields.forEach((name, fieldValue) -> object.set(name,
                Objects.requireNonNull(fieldValue, "field value").rawJson()));
        return new QueryValue(object);
    }

    public JsonNode json() {
        return copy(value);
    }

    public boolean isIntegralNumber() {
        return value.isIntegralNumber() && value.canConvertToLong();
    }

    public boolean isTextual() {
        return value.isTextual();
    }

    public boolean isBoolean() {
        return value.isBoolean();
    }

    public boolean isObject() {
        return value.isObject();
    }

    public boolean isNull() {
        return value.isNull();
    }

    public long asLong() {
        if (!isIntegralNumber()) {
            throw new QueryException("expected an integer value, got " + value);
        }
        return value.asLong();
    }

    public boolean asBoolean() {
        if (!value.isBoolean()) {
            throw new QueryException("expected a boolean value, got " + value);
        }
        return value.asBoolean();
    }

    public String asText() {
        if (!value.isTextual()) {
            throw new QueryException("expected a string value, got " + value);
        }
        return value.asText();
    }

    public QueryValue field(List<String> path) {
        JsonNode current = value;
        for (var component : path) {
            if (!current.isObject()) {
                throw new QueryException("cannot access field '" + component + "' on " + current);
            }
            current = current.get(component);
            if (current == null) {
                throw new QueryException("missing field '" + component + "' in " + value);
            }
        }
        return new QueryValue(current);
    }

    @Override
    public int compareTo(QueryValue other) {
        Objects.requireNonNull(other, "other");
        if (isIntegralNumber() && other.isIntegralNumber()) {
            return Long.compare(asLong(), other.asLong());
        }
        if (isTextual() && other.isTextual()) {
            return asText().compareTo(other.asText());
        }
        if (isBoolean() && other.isBoolean()) {
            return Boolean.compare(asBoolean(), other.asBoolean());
        }
        throw new QueryException("values are not order-comparable: " + value + " and " + other.value);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof QueryValue
                && value.equals(((QueryValue) other).value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value.toString();
    }

    JsonNode rawJson() {
        return copy(value);
    }

    private static JsonNode copy(JsonNode node) {
        if (node instanceof ObjectNode || node.isArray()) {
            return node.deepCopy();
        }
        return node;
    }

    private static JsonNode normalize(JsonNode node) {
        if (node.isIntegralNumber()) {
            if (!node.canConvertToLong()) {
                throw new QueryException("integer is outside signed 64-bit range: " + node);
            }
            return JsonNodeFactory.instance.numberNode(node.asLong());
        }
        if (node.isObject()) {
            var normalized = JsonNodeFactory.instance.objectNode();
            node.fields().forEachRemaining(entry -> normalized.set(
                    entry.getKey(), normalize(entry.getValue())));
            return normalized;
        }
        if (node.isArray()) {
            ArrayNode normalized = JsonNodeFactory.instance.arrayNode();
            node.forEach(element -> normalized.add(normalize(element)));
            return normalized;
        }
        return node.deepCopy();
    }
}
