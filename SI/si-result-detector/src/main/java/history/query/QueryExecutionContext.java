package history.query;

import java.util.Objects;

/** Runtime dependencies shared by all AST nodes. */
public final class QueryExecutionContext<KeyType, ValueType> {
    private final VisibleState<KeyType, ValueType> state;
    private final ValueAdapter<ValueType> valueAdapter;

    public QueryExecutionContext(VisibleState<KeyType, ValueType> state,
            ValueAdapter<ValueType> valueAdapter) {
        this.state = Objects.requireNonNull(state, "state");
        this.valueAdapter = Objects.requireNonNull(valueAdapter, "valueAdapter");
    }

    public VisibleState<KeyType, ValueType> state() {
        return state;
    }

    public ValueAdapter<ValueType> valueAdapter() {
        return valueAdapter;
    }
}
