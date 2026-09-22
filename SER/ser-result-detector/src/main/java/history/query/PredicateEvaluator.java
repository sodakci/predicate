package history.query;

/** Query-level predicate interface consumed by the predicate SAT constraints. */
public interface PredicateEvaluator<KeyType, ValueType> {
    QueryScope<KeyType> scope();

    /**
     * True only when each physical row contributes independently to the result.
     * This permits per-key acceleration and repeated-read coverage reuse; it
     * does not change the latest-visible snapshot or result equality contract.
     * Whole-snapshot evaluators are the conservative default.
     */
    default boolean isRowLocal() {
        return false;
    }

    QueryEvaluation<KeyType, ValueType> evaluate(
            VisibleState<KeyType, ValueType> state);

    Object identity();
}
