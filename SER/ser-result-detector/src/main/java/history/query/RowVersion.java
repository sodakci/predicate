package history.query;

import java.util.Objects;

/** One visible physical key/value version and its logical relation. */
public final class RowVersion<KeyType, ValueType> {
    private final KeyType key;
    private final String relation;
    private final ValueType value;

    public RowVersion(KeyType key, String relation, ValueType value) {
        this.key = Objects.requireNonNull(key, "key");
        this.relation = requireName(relation, "relation");
        this.value = Objects.requireNonNull(value, "value");
    }

    public KeyType key() {
        return key;
    }

    public KeyType getKey() {
        return key;
    }

    public String relation() {
        return relation;
    }

    public String getRelation() {
        return relation;
    }

    public ValueType value() {
        return value;
    }

    public ValueType getValue() {
        return value;
    }

    private static String requireName(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank()) {
            throw new QueryException(label + " must not be blank");
        }
        return value;
    }
}
