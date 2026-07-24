package history.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Canonical business results and physical source versions produced by a query. */
public final class QueryEvaluation<KeyType, ValueType> {
    private final List<QueryValue> values;
    private final Map<KeyType, ValueType> inputs;
    private final Map<QueryValue, Integer> valueMultiset;
    private final Map<KeyType, QueryValue> canonicalInputs;

    public QueryEvaluation(List<QueryValue> values, Map<KeyType, ValueType> inputs,
            ValueAdapter<ValueType> valueAdapter) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(valueAdapter, "valueAdapter");
        this.values = Collections.unmodifiableList(new ArrayList<>(values));
        this.inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
        this.valueMultiset = multiset(this.values);
        this.canonicalInputs = canonicalInputs(this.inputs, valueAdapter);
    }

    public List<QueryValue> values() {
        return values;
    }

    public List<QueryValue> getValues() {
        return values;
    }

    public Map<KeyType, ValueType> inputs() {
        return inputs;
    }

    public Map<KeyType, ValueType> getInputs() {
        return inputs;
    }

    public Map<QueryValue, Integer> valueMultiset() {
        return valueMultiset;
    }

    public Map<KeyType, QueryValue> canonicalInputs() {
        return canonicalInputs;
    }

    public boolean canonicalEquals(QueryEvaluation<KeyType, ValueType> other) {
        return other != null
                && valueMultiset.equals(other.valueMultiset)
                && canonicalInputs.equals(other.canonicalInputs);
    }

    public boolean canonicalEquals(RecordedQueryResult<KeyType, ValueType> recorded) {
        return recorded != null
                && valueMultiset.equals(recorded.valueMultiset())
                && canonicalInputs.equals(recorded.canonicalInputs());
    }

    static Map<QueryValue, Integer> multiset(List<QueryValue> values) {
        var result = new LinkedHashMap<QueryValue, Integer>();
        for (var value : values) {
            result.merge(Objects.requireNonNull(value, "query result value"), 1, Integer::sum);
        }
        return Collections.unmodifiableMap(result);
    }

    static <KeyType, ValueType> Map<KeyType, QueryValue> canonicalInputs(
            Map<KeyType, ValueType> inputs, ValueAdapter<ValueType> adapter) {
        var result = new LinkedHashMap<KeyType, QueryValue>();
        inputs.forEach((key, value) -> result.put(
                Objects.requireNonNull(key, "input key"),
                Objects.requireNonNull(adapter.toQueryValue(value), "canonical input value")));
        return Collections.unmodifiableMap(result);
    }

    @Override
    public String toString() {
        return "QueryEvaluation{values=" + values + ", inputs=" + inputs + "}";
    }
}
