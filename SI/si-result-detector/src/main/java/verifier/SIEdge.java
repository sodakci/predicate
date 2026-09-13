package verifier;

import graph.EdgeType;
import history.Transaction;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

class SIEdge<KeyType, ValueType> {
    private final Transaction<KeyType, ValueType> from;
    private final Transaction<KeyType, ValueType> to;
    private final EdgeType type;
    private final LinkedHashSet<KeyType> keys = new LinkedHashSet<>();

    SIEdge(Transaction<KeyType, ValueType> from,
            Transaction<KeyType, ValueType> to,
            EdgeType type,
            KeyType key) {
        this.from = from;
        this.to = to;
        this.type = type;
        if (key != null) {
            keys.add(key);
        }
    }

    Transaction<KeyType, ValueType> getFrom() {
        return from;
    }

    Transaction<KeyType, ValueType> getTo() {
        return to;
    }

    EdgeType getType() {
        return type;
    }

    KeyType getKey() {
        return keys.isEmpty() ? null : keys.iterator().next();
    }

    Set<KeyType> getKeys() {
        return Collections.unmodifiableSet(keys);
    }

    boolean addKey(KeyType key) {
        return key != null && keys.add(key);
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof SIEdge)) {
            return false;
        }
        var other = (SIEdge<?, ?>) object;
        return type == other.type
                && Objects.equals(from, other.from)
                && Objects.equals(to, other.to)
                && keys.equals(other.keys);
    }

    @Override
    public int hashCode() {
        return Objects.hash(from, to, type, keys);
    }

    @Override
    public String toString() {
        return String.format("(%s -> %s, %s, %s)", from, to, type, keys);
    }
}
