package history.query;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Alias-to-version bindings carried between relational AST nodes. */
public final class BindingRow<KeyType, ValueType> {
    private final Map<String, RowVersion<KeyType, ValueType>> bindings;

    private BindingRow(Map<String, RowVersion<KeyType, ValueType>> bindings) {
        this.bindings = Collections.unmodifiableMap(new LinkedHashMap<>(bindings));
    }

    public static <KeyType, ValueType> BindingRow<KeyType, ValueType> of(
            String alias, RowVersion<KeyType, ValueType> row) {
        var bindings = new LinkedHashMap<String, RowVersion<KeyType, ValueType>>();
        bindings.put(requireAlias(alias), Objects.requireNonNull(row, "row"));
        return new BindingRow<>(bindings);
    }

    public BindingRow<KeyType, ValueType> merging(BindingRow<KeyType, ValueType> other) {
        Objects.requireNonNull(other, "other");
        var merged = new LinkedHashMap<>(bindings);
        other.bindings.forEach((alias, row) -> {
            if (merged.putIfAbsent(alias, row) != null) {
                throw new QueryException("duplicate relation alias: " + alias);
            }
        });
        return new BindingRow<>(merged);
    }

    public RowVersion<KeyType, ValueType> get(String alias) {
        return bindings.get(alias);
    }

    public Set<String> aliases() {
        return bindings.keySet();
    }

    public Collection<RowVersion<KeyType, ValueType>> sources() {
        return bindings.values();
    }

    public QueryValue resolve(List<String> path, ValueAdapter<ValueType> adapter) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(adapter, "adapter");
        if (path.isEmpty()) {
            throw new QueryException("empty field reference");
        }

        RowVersion<KeyType, ValueType> source;
        int fieldOffset;
        if (bindings.containsKey(path.get(0))) {
            source = bindings.get(path.get(0));
            fieldOffset = 1;
        } else if (bindings.size() == 1) {
            source = bindings.values().iterator().next();
            fieldOffset = 0;
        } else {
            throw new QueryException("unqualified field in a multi-relation row: "
                    + String.join(".", path));
        }

        if (fieldOffset == path.size()) {
            return adapted(adapter, source.value());
        }

        var firstField = path.get(fieldOffset);
        if ("value".equalsIgnoreCase(firstField)) {
            return adapted(adapter, source.value()).field(
                    tail(path, fieldOffset + 1));
        }
        if ("k".equalsIgnoreCase(firstField) || "key".equalsIgnoreCase(firstField)) {
            ensureTerminal(path, fieldOffset, firstField);
            return QueryValue.text(localKey(source.key()));
        }
        if ("canonical_key".equalsIgnoreCase(firstField)) {
            ensureTerminal(path, fieldOffset, firstField);
            return QueryValue.text(String.valueOf(source.key()));
        }

        return adapted(adapter, source.value()).field(tail(path, fieldOffset));
    }

    private static <ValueType> QueryValue adapted(
            ValueAdapter<ValueType> adapter, ValueType value) {
        return Objects.requireNonNull(adapter.toQueryValue(value), "adapted value");
    }

    private static List<String> tail(List<String> path, int offset) {
        if (offset >= path.size()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(path.subList(offset, path.size()));
    }

    private static void ensureTerminal(List<String> path, int offset, String field) {
        if (offset + 1 != path.size()) {
            throw new QueryException("cannot dereference key field '" + field + "'");
        }
    }

    private static String localKey(Object key) {
        var canonical = String.valueOf(key);
        var separator = canonical.indexOf(':');
        return separator < 0 ? canonical : canonical.substring(separator + 1);
    }

    private static String requireAlias(String alias) {
        if (alias == null || alias.isBlank()) {
            throw new QueryException("relation alias must not be blank");
        }
        return alias;
    }
}
