package history.query;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compact representation for a bag union of independent row contributions.
 * Projected values are validated once and subsequently derived from inputs and
 * the plan instead of being retained as a second complete result.
 */
public final class RowLocalRecordedQueryResult<KeyType, ValueType>
        implements RecordedQueryResult<KeyType, ValueType> {
    private final Map<KeyType, ValueType> inputs;
    private final QueryPlan<KeyType, ValueType> plan;
    private final RelationResolver<KeyType> relationResolver;
    private final ValueAdapter<ValueType> valueAdapter;
    private final boolean recordedValuesMatchDerived;

    public RowLocalRecordedQueryResult(Map<KeyType, ValueType> inputs,
            boolean recordedValuesMatchDerived,
            QueryPlan<KeyType, ValueType> plan,
            RelationResolver<KeyType> relationResolver,
            ValueAdapter<ValueType> valueAdapter) {
        Objects.requireNonNull(inputs, "inputs");
        this.plan = Objects.requireNonNull(plan, "plan");
        if (plan.compactResultProjection() == null) {
            throw new IllegalArgumentException(
                    "compact result requires a supported row-local projection");
        }
        this.relationResolver = Objects.requireNonNull(relationResolver, "relationResolver");
        this.valueAdapter = Objects.requireNonNull(valueAdapter, "valueAdapter");
        this.inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
        this.recordedValuesMatchDerived = recordedValuesMatchDerived;
    }

    @Override
    public Map<KeyType, ValueType> inputs() {
        return inputs;
    }

    @Override
    public List<QueryValue> values() {
        return derive().values();
    }

    @Override
    public Map<QueryValue, Integer> valueMultiset() {
        return derive().valueMultiset();
    }

    @Override
    public Map<KeyType, QueryValue> canonicalInputs() {
        return QueryEvaluation.canonicalInputs(inputs, valueAdapter);
    }

    @Override
    public boolean canonicalEquals(QueryEvaluation<KeyType, ValueType> evaluation) {
        if (!recordedValuesMatchDerived || evaluation == null
                || evaluation.canonicalInputs().size() != inputs.size()) {
            return false;
        }
        var actualInputs = evaluation.canonicalInputs();
        for (var entry : inputs.entrySet()) {
            if (!Objects.equals(actualInputs.get(entry.getKey()),
                    valueAdapter.toQueryValue(entry.getValue()))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isCompact() {
        return true;
    }

    private QueryEvaluation<KeyType, ValueType> derive() {
        return plan.evaluate(new MapVisibleState<>(inputs, relationResolver));
    }

    @Override
    public String toString() {
        return "RowLocalRecordedQueryResult{values=<derived>, inputs=" + inputs + "}";
    }
}
