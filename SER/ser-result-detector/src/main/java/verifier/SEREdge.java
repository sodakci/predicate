package verifier;

import graph.EdgeType;
import history.Transaction;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

class SEREdge<KeyType, ValueType> {
    private final Transaction<KeyType, ValueType> from;
    private final Transaction<KeyType, ValueType> to;
    private final EdgeType type;
    private KeyType singleKey;
    private LinkedHashSet<KeyType> multipleKeys;

    SEREdge(Transaction<KeyType, ValueType> from,
            Transaction<KeyType, ValueType> to,
            EdgeType type,
            KeyType key) {
        this.from = from;
        this.to = to;
        this.type = type;
        this.singleKey = key;
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
        return multipleKeys == null ? singleKey : multipleKeys.iterator().next();
    }

    Set<KeyType> getKeys() {
        if (multipleKeys != null) {
            return Collections.unmodifiableSet(multipleKeys);
        }
        return singleKey == null ? Collections.emptySet() : Collections.singleton(singleKey);
    }

    boolean addKey(KeyType key) {
        if (key == null) {
            return false;
        }
        if (multipleKeys != null) {
            return multipleKeys.add(key);
        }
        if (singleKey == null) {
            singleKey = key;
            return true;
        }
        if (Objects.equals(singleKey, key)) {
            return false;
        }
        multipleKeys = new LinkedHashSet<>();
        multipleKeys.add(singleKey);
        multipleKeys.add(key);
        singleKey = null;
        return true;
    }

    void addKeysTo(SEREdge<KeyType, ValueType> target) {
        if (multipleKeys == null) {
            target.addKey(singleKey);
            return;
        }
        for (var key : multipleKeys) {
            target.addKey(key);
        }
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof SEREdge)) {
            return false;
        }
        var other = (SEREdge<?, ?>) object;
        return type == other.type
                && Objects.equals(from, other.from)
                && Objects.equals(to, other.to)
                && keysEqual(other);
    }

    private boolean keysEqual(SEREdge<?, ?> other) {
        if (multipleKeys == null && other.multipleKeys == null) {
            return Objects.equals(singleKey, other.singleKey);
        }
        if (multipleKeys == null) {
            return other.multipleKeys.size() == 1
                    && other.multipleKeys.contains(singleKey);
        }
        if (other.multipleKeys == null) {
            return multipleKeys.size() == 1 && multipleKeys.contains(other.singleKey);
        }
        return multipleKeys.equals(other.multipleKeys);
    }

    @Override
    public int hashCode() {
        int keysHash = multipleKeys == null
                ? Objects.hashCode(singleKey)
                : multipleKeys.hashCode();
        return Objects.hash(from, to, type, keysHash);
    }

    @Override
    public String toString() {
        return String.format("(%s -> %s, %s, %s)", from, to, type, getKeys());
    }
}
