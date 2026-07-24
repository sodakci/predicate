package history.query;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Existing PRHIST result.inputs/result.values in canonical comparison form. */
public final class RecordedQueryResult<KeyType, ValueType> {
    private final Map<KeyType, ValueType> inputs;
    private final List<QueryValue> values;
    private final Map<QueryValue, Integer> valueMultiset;
    private final Map<KeyType, QueryValue> canonicalInputs;

    public RecordedQueryResult(Map<KeyType, ValueType> inputs,
            List<QueryValue> values, ValueAdapter<ValueType> valueAdapter) {
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(valueAdapter, "valueAdapter");
        this.inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
        this.values = Collections.unmodifiableList(new ArrayList<>(values));
        this.valueMultiset = QueryEvaluation.multiset(this.values);
        this.canonicalInputs = QueryEvaluation.canonicalInputs(this.inputs, valueAdapter);
    }

    public static <KeyType, ValueType> RecordedQueryResult<KeyType, ValueType> fromJsonValues(
            Map<KeyType, ValueType> inputs, Iterable<? extends JsonNode> values,
            ValueAdapter<ValueType> valueAdapter) {
        Objects.requireNonNull(values, "values");
        var canonicalValues = new ArrayList<QueryValue>();
        values.forEach(value -> canonicalValues.add(QueryValue.of(value)));
        return new RecordedQueryResult<>(inputs, canonicalValues, valueAdapter);
    }

    public Map<KeyType, ValueType> inputs() {
        return inputs;
    }

    public Map<KeyType, ValueType> getInputs() {
        return inputs;
    }

    public List<QueryValue> values() {
        return values;
    }

    public List<QueryValue> getValues() {
        return values;
    }

    public Map<QueryValue, Integer> valueMultiset() {
        return valueMultiset;
    }

    public Map<KeyType, QueryValue> canonicalInputs() {
        return canonicalInputs;
    }

    @Override
    public String toString() {
        return "RecordedQueryResult{values=" + values + ", inputs=" + inputs + "}";
    }
}
