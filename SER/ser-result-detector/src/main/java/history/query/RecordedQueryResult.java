package history.query;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** PRHIST result.inputs/result.values in canonical comparison form. */
public interface RecordedQueryResult<KeyType, ValueType> {
    Map<KeyType, ValueType> inputs();

    default Map<KeyType, ValueType> getInputs() {
        return inputs();
    }

    List<QueryValue> values();

    default List<QueryValue> getValues() {
        return values();
    }

    Map<QueryValue, Integer> valueMultiset();

    Map<KeyType, QueryValue> canonicalInputs();

    boolean canonicalEquals(QueryEvaluation<KeyType, ValueType> evaluation);

    default boolean isCompact() {
        return false;
    }

    static <KeyType, ValueType> RecordedQueryResult<KeyType, ValueType> general(
            Map<KeyType, ValueType> inputs, List<QueryValue> values,
            ValueAdapter<ValueType> valueAdapter) {
        return new GeneralRecordedQueryResult<>(inputs, values, valueAdapter);
    }

    static <KeyType, ValueType> RecordedQueryResult<KeyType, ValueType> rowLocal(
            Map<KeyType, ValueType> inputs, boolean recordedValuesMatchDerived,
            QueryPlan<KeyType, ValueType> plan,
            RelationResolver<KeyType> relationResolver,
            ValueAdapter<ValueType> valueAdapter) {
        return new RowLocalRecordedQueryResult<>(inputs, recordedValuesMatchDerived, plan,
                relationResolver, valueAdapter);
    }

    static <KeyType, ValueType> RecordedQueryResult<KeyType, ValueType> fromJsonValues(
            Map<KeyType, ValueType> inputs, Iterable<? extends JsonNode> values,
            ValueAdapter<ValueType> valueAdapter) {
        Objects.requireNonNull(values, "values");
        var canonicalValues = new ArrayList<QueryValue>();
        values.forEach(value -> canonicalValues.add(QueryValue.of(value)));
        return general(inputs, canonicalValues, valueAdapter);
    }
}
