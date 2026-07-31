package history.query;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Finite relation scope used to restrict candidate frontier construction. */
public interface QueryScope<KeyType> {
    boolean covers(KeyType key);

    Set<String> relations();

    static <KeyType> QueryScope<KeyType> forRelations(
            Set<String> relations, RelationResolver<KeyType> resolver) {
        return new RelationScope<>(relations, resolver);
    }

    final class RelationScope<KeyType> implements QueryScope<KeyType> {
        private final Set<String> relations;
        private final RelationResolver<KeyType> resolver;

        private RelationScope(Set<String> relations, RelationResolver<KeyType> resolver) {
            Objects.requireNonNull(relations, "relations");
            if (relations.isEmpty() || relations.stream().anyMatch(
                    relation -> relation == null || relation.isBlank())) {
                throw new QueryException("query scope must contain named relations");
            }
            this.relations = Collections.unmodifiableSet(new LinkedHashSet<>(relations));
            this.resolver = Objects.requireNonNull(resolver, "resolver");
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
        public String toString() {
            return relations.toString();
        }
    }
}
