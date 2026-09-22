package history.query;

import java.util.List;
import java.util.Map;

/** PRHIST result.inputs/result.values in canonical comparison form. */
public interface RecordedQueryResult<KeyType, ValueType> {
    Map<KeyType, ValueType> inputs();

    List<QueryValue> values();

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
}
