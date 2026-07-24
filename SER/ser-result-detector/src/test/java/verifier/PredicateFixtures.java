package verifier;

import com.fasterxml.jackson.databind.ObjectMapper;
import history.query.PredicateEvaluator;
import history.query.MapVisibleState;
import history.query.QueryEvaluation;
import history.query.QueryScope;
import history.query.QueryValue;
import history.query.ValueAdapter;
import history.query.VisibleState;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Test fixtures for programmatically constructed predicate histories. */
public final class PredicateFixtures {
    private static final ObjectMapper VALUE_MAPPER = new ObjectMapper();

    private PredicateFixtures() {
    }

    public static <KeyType, ValueType> boolean matches(
            PredicateEvaluator<KeyType, ValueType> predicate,
            KeyType key, ValueType value) {
        return predicate.evaluate(new MapVisibleState<>(
                Map.of(key, value), ignored -> "kv")).inputs().containsKey(key);
    }

    @FunctionalInterface
    public interface RowPredicate<KeyType, ValueType>
            extends PredicateEvaluator<KeyType, ValueType> {
        boolean test(KeyType key, ValueType value);

        default boolean covers(KeyType key) {
            return true;
        }

        @Override
        default QueryScope<KeyType> scope() {
            return new QueryScope<>() {
                @Override
                public boolean covers(KeyType key) {
                    return RowPredicate.this.covers(key);
                }

                @Override
                public Set<String> relations() {
                    return Set.of("kv");
                }
            };
        }

        @Override
        default QueryEvaluation<KeyType, ValueType> evaluate(
                VisibleState<KeyType, ValueType> state) {
            var inputs = new LinkedHashMap<KeyType, ValueType>();
            for (var row : state.rows()) {
                if (covers(row.key()) && test(row.key(), row.value())) {
                    inputs.put(row.key(), row.value());
                }
            }
            ValueAdapter<ValueType> adapter = ValueAdapter.fromJson(
                    value -> VALUE_MAPPER.valueToTree(value));
            return new QueryEvaluation<>(Collections.<QueryValue>emptyList(), inputs, adapter);
        }

        @Override
        default Object identity() {
            return this;
        }
    }
}
