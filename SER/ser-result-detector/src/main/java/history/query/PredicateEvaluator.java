package history.query;

/** Query-level predicate interface consumed by the predicate SAT constraints. */
public interface PredicateEvaluator<KeyType, ValueType> {
    QueryScope<KeyType> scope();

    QueryEvaluation<KeyType, ValueType> evaluate(
            VisibleState<KeyType, ValueType> state);

    default boolean matches(VisibleState<KeyType, ValueType> state,
            RecordedQueryResult<KeyType, ValueType> recorded) {
        return evaluate(state).canonicalEquals(recorded);
    }

    default boolean changesResult(VisibleState<KeyType, ValueType> before,
            VisibleState<KeyType, ValueType> after) {
        return !evaluate(before).canonicalEquals(evaluate(after));
    }

    Object identity();
}
