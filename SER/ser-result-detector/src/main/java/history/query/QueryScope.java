package history.query;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Finite relation scope used to restrict candidate frontier construction. */
public interface QueryScope<KeyType> {
    boolean covers(KeyType key);

    Set<String> relations();

    /**
     * Stable immutable value key for reusing scope-derived data. Implementations
     * must return empty when equivalent coverage cannot be expressed safely.
     */
    default Optional<Object> cacheKey() {
        return Optional.empty();
    }

    static <KeyType> QueryScope<KeyType> forRelations(
            Set<String> relations, RelationResolver<KeyType> resolver) {
        return new RelationScope<>(relations, resolver);
    }

    final class RelationScope<KeyType> implements QueryScope<KeyType> {
        private final Set<String> relations;
        private final RelationResolver<KeyType> resolver;
        private final Optional<Object> cacheKey;

        private RelationScope(Set<String> relations, RelationResolver<KeyType> resolver) {
            Objects.requireNonNull(relations, "relations");
            if (relations.isEmpty() || relations.stream().anyMatch(
                    relation -> relation == null || relation.isBlank())) {
                throw new QueryException("query scope must contain named relations");
            }
            this.relations = Collections.unmodifiableSet(new LinkedHashSet<>(relations));
            this.resolver = Objects.requireNonNull(resolver, "resolver");
            this.cacheKey = resolver.cacheKey().map(
                    resolverKey -> new RelationScopeCacheKey(this.relations, resolverKey));
        }

        @Override
        public boolean covers(KeyType key) {
            return key != null && relations.contains(resolver.relationOf(key));
        }

        @Override
        public Set<String> relations() {
            return relations;
        }

        @Override
        public Optional<Object> cacheKey() {
            return cacheKey;
        }

        @Override
        public String toString() {
            return relations.toString();
        }
    }
}

final class RelationScopeCacheKey {
    private final Set<String> relations;
    private final Object resolverKey;

    RelationScopeCacheKey(Set<String> relations, Object resolverKey) {
        this.relations = relations;
        this.resolverKey = resolverKey;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RelationScopeCacheKey)) {
            return false;
        }
        var that = (RelationScopeCacheKey) other;
        return relations.equals(that.relations) && resolverKey.equals(that.resolverKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(relations, resolverKey);
    }
}
