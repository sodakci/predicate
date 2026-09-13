package history.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Complete recorded representation used by JOIN, DISTINCT, and general queries. */
public final class GeneralRecordedQueryResult<KeyType, ValueType>
        implements RecordedQueryResult<KeyType, ValueType> {
    private final Map<KeyType, ValueType> inputs;
    private final List<QueryValue> values;
    private final Map<QueryValue, Integer> valueMultiset;
    private final Map<KeyType, QueryValue> canonicalInputs;

    public GeneralRecordedQueryResult(Map<KeyType, ValueType> inputs,
            List<QueryValue> values, ValueAdapter<ValueType> valueAdapter) {
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(valueAdapter, "valueAdapter");
        this.inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
        this.values = Collections.unmodifiableList(new ArrayList<>(values));
        this.valueMultiset = QueryEvaluation.multiset(this.values);
        this.canonicalInputs = QueryEvaluation.canonicalInputs(this.inputs, valueAdapter);
    }

    @Override
    public Map<KeyType, ValueType> inputs() {
        return inputs;
    }

    @Override
    public List<QueryValue> values() {
        return values;
    }

    @Override
    public Map<QueryValue, Integer> valueMultiset() {
        return valueMultiset;
    }

    @Override
    public Map<KeyType, QueryValue> canonicalInputs() {
        return canonicalInputs;
    }

    @Override
    public boolean canonicalEquals(QueryEvaluation<KeyType, ValueType> evaluation) {
        return evaluation != null
                && valueMultiset.equals(evaluation.valueMultiset())
                && canonicalInputs.equals(evaluation.canonicalInputs());
    }

    @Override
    public String toString() {
        return "GeneralRecordedQueryResult{values=" + values + ", inputs=" + inputs + "}";
    }
}
