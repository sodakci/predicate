package history.query;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
}
