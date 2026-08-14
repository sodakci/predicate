package history.query;

import java.util.Map;
import java.util.Objects;

/** Canonical result contributed by evaluating one physical row. */
public final class RowContribution<KeyType> {
    private final Map<QueryValue, Integer> valueMultiset;
    private final Map<KeyType, QueryValue> canonicalInputs;

    private RowContribution(Map<QueryValue, Integer> valueMultiset,
            Map<KeyType, QueryValue> canonicalInputs) {
        this.valueMultiset = Objects.requireNonNull(valueMultiset, "valueMultiset");
        this.canonicalInputs = Objects.requireNonNull(canonicalInputs, "canonicalInputs");
    }

    public static <KeyType, ValueType> RowContribution<KeyType> from(
            QueryEvaluation<KeyType, ValueType> evaluation) {
        Objects.requireNonNull(evaluation, "evaluation");
        return new RowContribution<>(evaluation.valueMultiset(),
                evaluation.canonicalInputs());
    }

    public boolean containsInput(KeyType key) {
        return canonicalInputs.containsKey(key);
    }

    public boolean inputsEmpty() {
        return canonicalInputs.isEmpty();
    }

    public boolean valuesEmpty() {
        return valueMultiset.isEmpty();
    }

    public boolean canonicalEquals(RowContribution<KeyType> other) {
        return other != null
                && valueMultiset.equals(other.valueMultiset)
                && canonicalInputs.equals(other.canonicalInputs);
    }
}
