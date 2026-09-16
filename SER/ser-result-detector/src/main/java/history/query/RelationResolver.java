package history.query;

import java.util.Objects;
import java.util.Optional;

/** Resolves the logical relation containing a canonical history key. */
@FunctionalInterface
public interface RelationResolver<KeyType> {
    String relationOf(KeyType key);

    /** Stable value key for scope caches; empty when equivalence is unknown. */
    default Optional<Object> cacheKey() {
        return Optional.empty();
    }

    static RelationResolver<String> canonicalStringKeys() {
        return canonicalKeys();
    }

    /** The same physical-key convention for programmatic and loaded histories. */
    static <KeyType> RelationResolver<KeyType> canonicalKeys() {
        var cacheKey = (Object) java.util.List.of(
                RelationResolver.class.getName(), "canonicalStringKeys");
        return new RelationResolver<>() {
            @Override
            public String relationOf(KeyType key) {
                Objects.requireNonNull(key, "key");
                var canonical = String.valueOf(key);
                var separator = canonical.indexOf(':');
                if (separator <= 0) {
                    // Compact legacy predicate histories used unqualified keys and
                    // represented their only table as relation "kv".
                    return "kv";
                }
                return canonical.substring(0, separator);
            }

            @Override
            public Optional<Object> cacheKey() {
                return Optional.of(cacheKey);
            }
        };
    }

    static <KeyType> RelationResolver<KeyType> fixed(String relation) {
        Objects.requireNonNull(relation, "relation");
        var cacheKey = (Object) java.util.List.of(
                RelationResolver.class.getName(), "fixed", relation);
        return new RelationResolver<>() {
            @Override
            public String relationOf(KeyType key) {
                return relation;
            }

            @Override
            public Optional<Object> cacheKey() {
                return Optional.of(cacheKey);
            }
        };
    }
}
