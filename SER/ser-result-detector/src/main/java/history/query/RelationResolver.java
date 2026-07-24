package history.query;

import java.util.Objects;

/** Resolves the logical relation containing a canonical history key. */
@FunctionalInterface
public interface RelationResolver<KeyType> {
    String relationOf(KeyType key);

    static RelationResolver<String> canonicalStringKeys() {
        return key -> {
            Objects.requireNonNull(key, "key");
            var separator = key.indexOf(':');
            if (separator <= 0) {
                // Compact legacy predicate histories used unqualified keys and
                // represented their only table as relation "kv".
                return "kv";
            }
            return key.substring(0, separator);
        };
    }

    static <KeyType> RelationResolver<KeyType> fixed(String relation) {
        Objects.requireNonNull(relation, "relation");
        return key -> relation;
    }
}
