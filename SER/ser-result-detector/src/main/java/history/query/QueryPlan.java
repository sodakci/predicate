package history.query;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

/** Executable query AST and the generic predicate evaluator implementation. */
public final class QueryPlan<KeyType, ValueType>
        implements PredicateEvaluator<KeyType, ValueType> {
    private final QueryAst.RelationalNode<KeyType, ValueType> root;
    private final List<QueryAst.ProjectedColumn<KeyType, ValueType>> columns;
    private final boolean distinct;
    private final QueryScope<KeyType> scope;
    private final ValueAdapter<ValueType> valueAdapter;
    private final String identity;
    private final Optional<BiPredicate<KeyType, ValueType>> compiledRowMatcher;
    private final CompactResultProjection compactResultProjection;

    public QueryPlan(QueryAst.RelationalNode<KeyType, ValueType> root,
            List<QueryAst.ProjectedColumn<KeyType, ValueType>> columns,
            boolean distinct, QueryScope<KeyType> scope,
            ValueAdapter<ValueType> valueAdapter) {
        this.root = Objects.requireNonNull(root, "root");
        Objects.requireNonNull(columns, "columns");
        if (columns.isEmpty()) {
            throw new QueryException("SELECT must contain at least one column");
        }
        this.columns = Collections.unmodifiableList(new ArrayList<>(columns));
        this.distinct = distinct;
        this.scope = Objects.requireNonNull(scope, "scope");
        this.valueAdapter = Objects.requireNonNull(valueAdapter, "valueAdapter");
        this.identity = buildIdentity();
        var rowAlias = QueryAst.rowLocalAlias(root);
        this.compiledRowMatcher = isRowLocal() && rowAlias != null
                ? QueryAst.compileRowMatcher(root, rowAlias, valueAdapter)
                        .map(matcher -> (key, value) -> scope.covers(key)
                                && matcher.test(key, value))
                : Optional.empty();
        this.compactResultProjection = buildCompactResultProjection(rowAlias);
    }

    @Override
    public QueryScope<KeyType> scope() {
        return scope;
    }

    @Override
    public QueryEvaluation<KeyType, ValueType> evaluate(
            VisibleState<KeyType, ValueType> state) {
        var context = new QueryExecutionContext<>(state, valueAdapter);
        var projectedRows = new ArrayList<ProjectedRow<KeyType, ValueType>>();
        for (var binding : root.execute(context)) {
            var object = JsonNodeFactory.instance.objectNode();
            for (var column : columns) {
                object.set(column.outputName(),
                        column.expression().evaluate(binding, context).json());
            }
            var sources = new LinkedHashMap<KeyType, ValueType>();
            for (var source : binding.sources()) {
                mergeInput(sources, source.key(), source.value());
            }
            projectedRows.add(new ProjectedRow<>(QueryValue.of(object), sources));
        }

        if (distinct) {
            projectedRows = distinct(projectedRows);
        }

        var values = new ArrayList<QueryValue>();
        var inputs = new LinkedHashMap<KeyType, ValueType>();
        for (var row : projectedRows) {
            values.add(row.value);
            row.sources.forEach((key, value) -> mergeInput(inputs, key, value));
        }
        return new QueryEvaluation<>(values, inputs, valueAdapter);
    }

    @Override
    public Object identity() {
        return identity;
    }

    public boolean distinct() {
        return distinct;
    }

    public List<QueryAst.ProjectedColumn<KeyType, ValueType>> columns() {
        return columns;
    }

    /** Allocation-light single-row membership evaluator compiled from the AST. */
    public Optional<BiPredicate<KeyType, ValueType>> compiledRowMatcher() {
        return compiledRowMatcher;
    }

    /** Canonical full contribution for non-compact row-local projections. */
    public RowContribution<KeyType> evaluateRowContribution(
            KeyType key, ValueType value, RelationResolver<KeyType> relationResolver) {
        if (!isRowLocal()) {
            throw new QueryException("row contribution requires a row-local query");
        }
        return RowContribution.from(evaluate(new MapVisibleState<>(
                Map.of(key, value), relationResolver)));
    }

    /**
     * Whether this plan is a bag union of independent single-row evaluations.
     * Joins, DISTINCT, and custom AST nodes deliberately fall back to the
     * general whole-snapshot evaluator.
     */
    public boolean isRowLocal() {
        return !distinct
                && QueryAst.isRowLocal(root)
                && columns.stream()
                        .allMatch(column -> QueryAst.isRowLocal(column.expression()));
    }

    /** Whether adding visible rows can only add projected rows to this query. */
    public boolean isMonotone() {
        return !distinct && QueryAst.isMonotone(root);
    }

    /**
     * Describes the compact result shape used by current KV histories. A null
     * result means the recorded values must retain the general representation.
     */
    public CompactResultProjection compactResultProjection() {
        return compactResultProjection;
    }

    private CompactResultProjection buildCompactResultProjection(String rowAlias) {
        if (!isRowLocal() || rowAlias == null || scope.relations().size() != 1
                || columns.size() != 2
                || !isProjectedField(columns.get(0), "k", rowAlias)
                || !isProjectedField(columns.get(1), "value", rowAlias)) {
            return null;
        }
        return new CompactResultProjection(scope.relations().iterator().next());
    }

    private boolean isProjectedField(
            QueryAst.ProjectedColumn<KeyType, ValueType> column,
            String field, String rowAlias) {
        if (!field.equals(column.outputName())
                || !(column.expression() instanceof QueryAst.FieldExpression)) {
            return false;
        }
        @SuppressWarnings("unchecked")
        var expression = (QueryAst.FieldExpression<KeyType, ValueType>) column.expression();
        var path = expression.path();
        return (path.size() == 1
                    && !rowAlias.equals(path.get(0))
                    && field.equalsIgnoreCase(path.get(0)))
                || (path.size() == 2
                    && rowAlias.equals(path.get(0))
                    && field.equalsIgnoreCase(path.get(1)));
    }

    private ArrayList<ProjectedRow<KeyType, ValueType>> distinct(
            List<ProjectedRow<KeyType, ValueType>> rows) {
        var byValue = new LinkedHashMap<QueryValue, ProjectedRow<KeyType, ValueType>>();
        for (var row : rows) {
            var existing = byValue.get(row.value);
            if (existing == null) {
                byValue.put(row.value, row);
            } else {
                row.sources.forEach((key, value) -> mergeInput(existing.sources, key, value));
            }
        }
        return new ArrayList<>(byValue.values());
    }

    private void mergeInput(Map<KeyType, ValueType> inputs, KeyType key, ValueType value) {
        var previous = inputs.putIfAbsent(key, value);
        if (previous != null && !valueAdapter.toQueryValue(previous)
                .equals(valueAdapter.toQueryValue(value))) {
            throw new QueryException("one visible state contains conflicting values for key " + key);
        }
    }

    private String buildIdentity() {
        return "QUERY[" + root + ";SELECT="
                + columns.stream().map(Object::toString).collect(Collectors.joining(","))
                + ";DISTINCT=" + distinct + "]";
    }

    @Override
    public String toString() {
        return identity;
    }

    private static final class ProjectedRow<KeyType, ValueType> {
        private final QueryValue value;
        private final LinkedHashMap<KeyType, ValueType> sources;

        private ProjectedRow(QueryValue value, Map<KeyType, ValueType> sources) {
            this.value = value;
            this.sources = new LinkedHashMap<>(sources);
        }
    }

    public static final class CompactResultProjection {
        private final String relation;

        private CompactResultProjection(String relation) {
            this.relation = relation;
        }

        public String relation() {
            return relation;
        }
    }
}
