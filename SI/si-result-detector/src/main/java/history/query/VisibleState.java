package history.query;

import java.util.Collection;

/** Read-only candidate snapshot selected by the existing SAT/frontier logic. */
public interface VisibleState<KeyType, ValueType> {
    ValueType get(KeyType key);

    Collection<RowVersion<KeyType, ValueType>> rows();

    Collection<RowVersion<KeyType, ValueType>> rows(String relation);

    /** A null replacement removes the key, allowing deletion candidates. */
    VisibleState<KeyType, ValueType> replacing(KeyType key, ValueType value);
}
