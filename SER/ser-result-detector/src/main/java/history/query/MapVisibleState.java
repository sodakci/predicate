package history.query;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable map-backed visible state. */
public final class MapVisibleState<KeyType, ValueType>
        implements VisibleState<KeyType, ValueType> {
    private final Map<KeyType, ValueType> values;
    private final RelationResolver<KeyType> relationResolver;
    private final List<RowVersion<KeyType, ValueType>> rows;
    private final Map<String, List<RowVersion<KeyType, ValueType>>> rowsByRelation;

    public MapVisibleState(Map<KeyType, ValueType> values,
            RelationResolver<KeyType> relationResolver) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(values, "values")));
        this.relationResolver = Objects.requireNonNull(relationResolver, "relationResolver");

        var allRows = new ArrayList<RowVersion<KeyType, ValueType>>();
        var grouped = new LinkedHashMap<String, List<RowVersion<KeyType, ValueType>>>();
        this.values.forEach((key, value) -> {
            if (value == null) {
                throw new QueryException("visible state contains a null value for key " + key);
            }
            var relation = requireRelation(relationResolver.relationOf(key), key);
            var row = new RowVersion<>(key, relation, value);
            allRows.add(row);
            grouped.computeIfAbsent(relation, ignored -> new ArrayList<>()).add(row);
        });
        this.rows = Collections.unmodifiableList(allRows);

        var immutableGrouped = new LinkedHashMap<String, List<RowVersion<KeyType, ValueType>>>();
        grouped.forEach((relation, relationRows) -> immutableGrouped.put(
                relation, Collections.unmodifiableList(relationRows)));
        this.rowsByRelation = Collections.unmodifiableMap(immutableGrouped);
    }

    @Override
    public ValueType get(KeyType key) {
        return values.get(key);
    }

    @Override
    public Collection<RowVersion<KeyType, ValueType>> rows() {
        return rows;
    }

    @Override
    public Collection<RowVersion<KeyType, ValueType>> rows(String relation) {
        return rowsByRelation.getOrDefault(relation, Collections.emptyList());
    }

    @Override
    public VisibleState<KeyType, ValueType> replacing(KeyType key, ValueType value) {
        Objects.requireNonNull(key, "key");
        var replacement = new LinkedHashMap<>(values);
        if (value == null) {
            replacement.remove(key);
        } else {
            replacement.put(key, value);
        }
        return new MapVisibleState<>(replacement, relationResolver);
    }

    public Map<KeyType, ValueType> asMap() {
        return values;
    }

    private static String requireRelation(String relation, Object key) {
        if (relation == null || relation.isBlank()) {
            throw new QueryException("no relation for key " + key);
        }
        return relation;
    }
}
