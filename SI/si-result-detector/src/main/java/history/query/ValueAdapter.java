package history.query;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import java.util.function.Function;

/** Converts the detector's value type into the evaluator's canonical value. */
@FunctionalInterface
public interface ValueAdapter<ValueType> {
    QueryValue toQueryValue(ValueType value);

    static ValueAdapter<JsonNode> jsonNodes() {
        return QueryValue::of;
    }

    static <ValueType> ValueAdapter<ValueType> of(
            Function<? super ValueType, QueryValue> adapter) {
        Objects.requireNonNull(adapter, "adapter");
        return value -> Objects.requireNonNull(adapter.apply(value), "adapted value");
    }

    static <ValueType> ValueAdapter<ValueType> fromJson(
            Function<? super ValueType, ? extends JsonNode> adapter) {
        Objects.requireNonNull(adapter, "adapter");
        return value -> QueryValue.of(adapter.apply(value));
    }
}
