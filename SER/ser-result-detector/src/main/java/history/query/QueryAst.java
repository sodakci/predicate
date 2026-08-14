package history.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

/** Extensible relational and scalar AST used by {@link QueryPlan}. */
public final class QueryAst {
    private QueryAst() {
    }

    public interface RelationalNode<KeyType, ValueType> {
        List<BindingRow<KeyType, ValueType>> execute(
                QueryExecutionContext<KeyType, ValueType> context);
    }

    public interface Expression<KeyType, ValueType> {
        QueryValue evaluate(BindingRow<KeyType, ValueType> row,
                QueryExecutionContext<KeyType, ValueType> context);

        default void validateAliases(Set<String> aliases) {
        }
    }

    public static final class ScanNode<KeyType, ValueType>
            implements RelationalNode<KeyType, ValueType> {
        private final String relation;
        private final String alias;

        public ScanNode(String relation, String alias) {
            this.relation = requireName(relation, "relation");
            this.alias = requireName(alias, "alias");
        }

        @Override
        public List<BindingRow<KeyType, ValueType>> execute(
                QueryExecutionContext<KeyType, ValueType> context) {
            var result = new ArrayList<BindingRow<KeyType, ValueType>>();
            for (var row : context.state().rows(relation)) {
                result.add(BindingRow.of(alias, row));
            }
            return result;
        }

        public String relation() {
            return relation;
        }

        public String alias() {
            return alias;
        }

        @Override
        public String toString() {
            return "SCAN(" + relation + " AS " + alias + ")";
        }
    }

    public static final class InnerJoinNode<KeyType, ValueType>
            implements RelationalNode<KeyType, ValueType> {
        private final RelationalNode<KeyType, ValueType> left;
        private final RelationalNode<KeyType, ValueType> right;
        private final Expression<KeyType, ValueType> condition;

        public InnerJoinNode(RelationalNode<KeyType, ValueType> left,
                RelationalNode<KeyType, ValueType> right,
                Expression<KeyType, ValueType> condition) {
            this.left = Objects.requireNonNull(left, "left");
            this.right = Objects.requireNonNull(right, "right");
            this.condition = Objects.requireNonNull(condition, "condition");
        }

        @Override
        public List<BindingRow<KeyType, ValueType>> execute(
                QueryExecutionContext<KeyType, ValueType> context) {
            var result = new ArrayList<BindingRow<KeyType, ValueType>>();
            var leftRows = left.execute(context);
            var rightRows = right.execute(context);
            for (var leftRow : leftRows) {
                for (var rightRow : rightRows) {
                    var joined = leftRow.merging(rightRow);
                    if (condition.evaluate(joined, context).asBoolean()) {
                        result.add(joined);
                    }
                }
            }
            return result;
        }

        @Override
        public String toString() {
            return "INNER_JOIN(" + left + "," + right + ",ON=" + condition + ")";
        }
    }

    public static final class FilterNode<KeyType, ValueType>
            implements RelationalNode<KeyType, ValueType> {
        private final RelationalNode<KeyType, ValueType> input;
        private final Expression<KeyType, ValueType> predicate;

        public FilterNode(RelationalNode<KeyType, ValueType> input,
                Expression<KeyType, ValueType> predicate) {
            this.input = Objects.requireNonNull(input, "input");
            this.predicate = Objects.requireNonNull(predicate, "predicate");
        }

        @Override
        public List<BindingRow<KeyType, ValueType>> execute(
                QueryExecutionContext<KeyType, ValueType> context) {
            return input.execute(context).stream()
                    .filter(row -> predicate.evaluate(row, context).asBoolean())
                    .collect(Collectors.toList());
        }

        @Override
        public String toString() {
            return "FILTER(" + predicate + "," + input + ")";
        }
    }

    public static final class LiteralExpression<KeyType, ValueType>
            implements Expression<KeyType, ValueType> {
        private final QueryValue value;

        public LiteralExpression(QueryValue value) {
            this.value = Objects.requireNonNull(value, "value");
        }

        @Override
        public QueryValue evaluate(BindingRow<KeyType, ValueType> row,
                QueryExecutionContext<KeyType, ValueType> context) {
            return value;
        }

        @Override
        public String toString() {
            return value.toString();
        }
    }

    public static final class FieldExpression<KeyType, ValueType>
            implements Expression<KeyType, ValueType> {
        private final List<String> path;

        public FieldExpression(List<String> path) {
            Objects.requireNonNull(path, "path");
            if (path.isEmpty() || path.stream().anyMatch(
                    component -> component == null || component.isBlank())) {
                throw new QueryException("field path must contain named components");
            }
            this.path = Collections.unmodifiableList(new ArrayList<>(path));
        }

        @Override
        public QueryValue evaluate(BindingRow<KeyType, ValueType> row,
                QueryExecutionContext<KeyType, ValueType> context) {
            return row.resolve(path, context.valueAdapter());
        }

        @Override
        public void validateAliases(Set<String> aliases) {
            if (aliases.size() > 1 && !aliases.contains(path.get(0))) {
                throw new QueryException("field must be alias-qualified in a multi-relation query: "
                        + String.join(".", path));
            }
        }

        public List<String> path() {
            return path;
        }

        public String defaultOutputName() {
            return path.get(path.size() - 1);
        }

        @Override
        public String toString() {
            return String.join(".", path);
        }
    }

    public enum ComparisonOperator {
        EQ("="),
        GT(">"),
        LT("<");

        private final String symbol;

        ComparisonOperator(String symbol) {
            this.symbol = symbol;
        }

        @Override
        public String toString() {
            return symbol;
        }
    }

    public static final class ComparisonExpression<KeyType, ValueType>
            implements Expression<KeyType, ValueType> {
        private final Expression<KeyType, ValueType> left;
        private final ComparisonOperator operator;
        private final Expression<KeyType, ValueType> right;

        public ComparisonExpression(Expression<KeyType, ValueType> left,
                ComparisonOperator operator,
                Expression<KeyType, ValueType> right) {
            this.left = Objects.requireNonNull(left, "left");
            this.operator = Objects.requireNonNull(operator, "operator");
            this.right = Objects.requireNonNull(right, "right");
        }

        @Override
        public QueryValue evaluate(BindingRow<KeyType, ValueType> row,
                QueryExecutionContext<KeyType, ValueType> context) {
            var leftValue = left.evaluate(row, context);
            var rightValue = right.evaluate(row, context);
            switch (operator) {
            case EQ:
                return QueryValue.bool(leftValue.equals(rightValue));
            case GT:
                return QueryValue.bool(leftValue.compareTo(rightValue) > 0);
            case LT:
                return QueryValue.bool(leftValue.compareTo(rightValue) < 0);
            default:
                throw new AssertionError(operator);
            }
        }

        @Override
        public void validateAliases(Set<String> aliases) {
            left.validateAliases(aliases);
            right.validateAliases(aliases);
        }

        @Override
        public String toString() {
            return "(" + left + operator.toString() + right + ")";
        }
    }

    public static final class ModuloExpression<KeyType, ValueType>
            implements Expression<KeyType, ValueType> {
        private final Expression<KeyType, ValueType> left;
        private final Expression<KeyType, ValueType> right;

        public ModuloExpression(Expression<KeyType, ValueType> left,
                Expression<KeyType, ValueType> right) {
            this.left = Objects.requireNonNull(left, "left");
            this.right = Objects.requireNonNull(right, "right");
        }

        @Override
        public QueryValue evaluate(BindingRow<KeyType, ValueType> row,
                QueryExecutionContext<KeyType, ValueType> context) {
            var dividend = left.evaluate(row, context).asLong();
            var divisor = right.evaluate(row, context).asLong();
            if (divisor == 0) {
                throw new QueryException("modulo divisor must not be zero");
            }
            return QueryValue.integer(Math.floorMod(dividend, divisor));
        }

        @Override
        public void validateAliases(Set<String> aliases) {
            left.validateAliases(aliases);
            right.validateAliases(aliases);
        }

        @Override
        public String toString() {
            return "(" + left + "%" + right + ")";
        }
    }

    public static final class AndExpression<KeyType, ValueType>
            implements Expression<KeyType, ValueType> {
        private final List<Expression<KeyType, ValueType>> expressions;

        public AndExpression(List<Expression<KeyType, ValueType>> expressions) {
            Objects.requireNonNull(expressions, "expressions");
            if (expressions.isEmpty()) {
                throw new QueryException("AND requires at least one expression");
            }
            this.expressions = Collections.unmodifiableList(new ArrayList<>(expressions));
        }

        @Override
        public QueryValue evaluate(BindingRow<KeyType, ValueType> row,
                QueryExecutionContext<KeyType, ValueType> context) {
            for (var expression : expressions) {
                if (!expression.evaluate(row, context).asBoolean()) {
                    return QueryValue.bool(false);
                }
            }
            return QueryValue.bool(true);
        }

        @Override
        public void validateAliases(Set<String> aliases) {
            expressions.forEach(expression -> expression.validateAliases(aliases));
        }

        @Override
        public String toString() {
            return expressions.stream().map(Object::toString)
                    .collect(Collectors.joining(" AND ", "(", ")"));
        }
    }

    public static final class ProjectedColumn<KeyType, ValueType> {
        private final String outputName;
        private final Expression<KeyType, ValueType> expression;

        public ProjectedColumn(String outputName,
                Expression<KeyType, ValueType> expression) {
            this.outputName = requireName(outputName, "output name");
            this.expression = Objects.requireNonNull(expression, "expression");
        }

        public String outputName() {
            return outputName;
        }

        public Expression<KeyType, ValueType> expression() {
            return expression;
        }

        @Override
        public String toString() {
            return expression + " AS " + outputName;
        }
    }

    static <KeyType, ValueType> Expression<KeyType, ValueType> and(
            List<Expression<KeyType, ValueType>> expressions) {
        if (expressions.isEmpty()) {
            return new LiteralExpression<>(QueryValue.bool(true));
        }
        if (expressions.size() == 1) {
            return expressions.get(0);
        }
        return new AndExpression<>(expressions);
    }

    static Set<String> aliases(String... aliases) {
        var result = new LinkedHashSet<String>();
        Collections.addAll(result, aliases);
        return result;
    }

    static boolean isRowLocal(RelationalNode<?, ?> node) {
        if (node instanceof ScanNode) {
            return true;
        }
        if (node instanceof FilterNode) {
            var filter = (FilterNode<?, ?>) node;
            return isRowLocal(filter.input) && isRowLocal(filter.predicate);
        }
        return false;
    }

    static String rowLocalAlias(RelationalNode<?, ?> node) {
        if (node instanceof ScanNode) {
            return ((ScanNode<?, ?>) node).alias;
        }
        if (node instanceof FilterNode) {
            return rowLocalAlias(((FilterNode<?, ?>) node).input);
        }
        return null;
    }

    static boolean isRowLocal(Expression<?, ?> expression) {
        if (expression instanceof LiteralExpression
                || expression instanceof FieldExpression) {
            return true;
        }
        if (expression instanceof ComparisonExpression) {
            var comparison = (ComparisonExpression<?, ?>) expression;
            return isRowLocal(comparison.left) && isRowLocal(comparison.right);
        }
        if (expression instanceof ModuloExpression) {
            var modulo = (ModuloExpression<?, ?>) expression;
            return isRowLocal(modulo.left) && isRowLocal(modulo.right);
        }
        if (expression instanceof AndExpression) {
            var conjunction = (AndExpression<?, ?>) expression;
            return conjunction.expressions.stream().allMatch(QueryAst::isRowLocal);
        }
        return false;
    }

    static <KeyType, ValueType> Optional<BiPredicate<KeyType, ValueType>>
            compileRowMatcher(RelationalNode<KeyType, ValueType> node,
                    String alias, ValueAdapter<ValueType> valueAdapter) {
        Objects.requireNonNull(alias, "alias");
        Objects.requireNonNull(valueAdapter, "valueAdapter");
        if (node instanceof ScanNode) {
            return Optional.of((key, value) -> true);
        }
        if (!(node instanceof FilterNode)) {
            return Optional.empty();
        }
        @SuppressWarnings("unchecked")
        var filter = (FilterNode<KeyType, ValueType>) node;
        var input = compileRowMatcher(filter.input, alias, valueAdapter);
        var predicate = compileScalar(filter.predicate, alias, valueAdapter);
        if (input.isEmpty() || predicate.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of((key, value) -> input.get().test(key, value)
                && predicate.get().evaluate(key, value).asBoolean());
    }

    private static <KeyType, ValueType> Optional<RowScalar<KeyType, ValueType>>
            compileScalar(Expression<KeyType, ValueType> expression,
                    String alias, ValueAdapter<ValueType> valueAdapter) {
        if (expression instanceof LiteralExpression) {
            @SuppressWarnings("unchecked")
            var literal = (LiteralExpression<KeyType, ValueType>) expression;
            return Optional.of((key, value) -> literal.value);
        }
        if (expression instanceof FieldExpression) {
            @SuppressWarnings("unchecked")
            var field = (FieldExpression<KeyType, ValueType>) expression;
            return Optional.of(compileField(field.path, alias, valueAdapter));
        }
        if (expression instanceof ComparisonExpression) {
            @SuppressWarnings("unchecked")
            var comparison = (ComparisonExpression<KeyType, ValueType>) expression;
            var left = compileScalar(comparison.left, alias, valueAdapter);
            var right = compileScalar(comparison.right, alias, valueAdapter);
            if (left.isEmpty() || right.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of((key, value) -> {
                var leftValue = left.get().evaluate(key, value);
                var rightValue = right.get().evaluate(key, value);
                switch (comparison.operator) {
                case EQ:
                    return QueryValue.bool(leftValue.equals(rightValue));
                case GT:
                    return QueryValue.bool(leftValue.compareTo(rightValue) > 0);
                case LT:
                    return QueryValue.bool(leftValue.compareTo(rightValue) < 0);
                default:
                    throw new AssertionError(comparison.operator);
                }
            });
        }
        if (expression instanceof ModuloExpression) {
            @SuppressWarnings("unchecked")
            var modulo = (ModuloExpression<KeyType, ValueType>) expression;
            var left = compileScalar(modulo.left, alias, valueAdapter);
            var right = compileScalar(modulo.right, alias, valueAdapter);
            if (left.isEmpty() || right.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of((key, value) -> {
                var dividend = left.get().evaluate(key, value).asLong();
                var divisor = right.get().evaluate(key, value).asLong();
                if (divisor == 0) {
                    throw new QueryException("modulo divisor must not be zero");
                }
                return QueryValue.integer(Math.floorMod(dividend, divisor));
            });
        }
        if (expression instanceof AndExpression) {
            @SuppressWarnings("unchecked")
            var conjunction = (AndExpression<KeyType, ValueType>) expression;
            var compiled = new ArrayList<RowScalar<KeyType, ValueType>>();
            for (var part : conjunction.expressions) {
                var scalar = compileScalar(part, alias, valueAdapter);
                if (scalar.isEmpty()) {
                    return Optional.empty();
                }
                compiled.add(scalar.get());
            }
            return Optional.of((key, value) -> {
                for (var part : compiled) {
                    if (!part.evaluate(key, value).asBoolean()) {
                        return QueryValue.bool(false);
                    }
                }
                return QueryValue.bool(true);
            });
        }
        return Optional.empty();
    }

    private static <KeyType, ValueType> RowScalar<KeyType, ValueType> compileField(
            List<String> path, String alias, ValueAdapter<ValueType> valueAdapter) {
        return (key, value) -> {
            int offset = !path.isEmpty() && alias.equals(path.get(0)) ? 1 : 0;
            if (offset == path.size()) {
                return adaptValue(value, valueAdapter);
            }
            var firstField = path.get(offset);
            if ("value".equalsIgnoreCase(firstField)) {
                var adapted = adaptValue(value, valueAdapter);
                return offset + 1 == path.size()
                        ? adapted
                        : adapted.field(path.subList(offset + 1, path.size()));
            }
            if ("k".equalsIgnoreCase(firstField)
                    || "key".equalsIgnoreCase(firstField)) {
                ensureTerminal(path, offset, firstField);
                return QueryValue.text(localKey(key));
            }
            if ("canonical_key".equalsIgnoreCase(firstField)) {
                ensureTerminal(path, offset, firstField);
                return QueryValue.text(String.valueOf(key));
            }
            return adaptValue(value, valueAdapter).field(path.subList(offset, path.size()));
        };
    }

    private static <ValueType> QueryValue adaptValue(
            ValueType value, ValueAdapter<ValueType> valueAdapter) {
        return Objects.requireNonNull(
                valueAdapter.toQueryValue(value), "adapted value");
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

    @FunctionalInterface
    private interface RowScalar<KeyType, ValueType> {
        QueryValue evaluate(KeyType key, ValueType value);
    }

    private static String requireName(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new QueryException(label + " must not be blank");
        }
        return value;
    }
}
