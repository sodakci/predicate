package history.query;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Compiles the structured PRHIST query object into an executable {@link QueryPlan}. */
public final class StructuredQueryParser<KeyType, ValueType> {
    private static final Pattern COLUMN_ALIAS = Pattern.compile(
            "^(.+?)\\s+(?i:AS)\\s+([A-Za-z_][A-Za-z0-9_$]*)$");

    private final ValueAdapter<ValueType> valueAdapter;
    private final RelationResolver<KeyType> relationResolver;

    public StructuredQueryParser(ValueAdapter<ValueType> valueAdapter,
            RelationResolver<KeyType> relationResolver) {
        this.valueAdapter = Objects.requireNonNull(valueAdapter, "valueAdapter");
        this.relationResolver = Objects.requireNonNull(relationResolver, "relationResolver");
    }

    public static <ValueType> StructuredQueryParser<String, ValueType> forCanonicalStringKeys(
            ValueAdapter<ValueType> valueAdapter) {
        return new StructuredQueryParser<>(valueAdapter, RelationResolver.canonicalStringKeys());
    }

    public QueryPlan<KeyType, ValueType> parse(JsonNode query) {
        requireObject(query, "query");
        var aliases = new LinkedHashSet<String>();
        var relations = new LinkedHashSet<String>();

        var from = requiredObject(query, "from");
        var fromRelation = requiredText(from, "relation");
        var fromAlias = optionalText(from, "alias", fromRelation);
        addRelation(aliases, relations, fromAlias, fromRelation);

        QueryAst.RelationalNode<KeyType, ValueType> root =
                new QueryAst.ScanNode<>(fromRelation, fromAlias);

        var joins = query.get("joins");
        if (joins != null) {
            requireArray(joins, "query.joins");
            for (var join : joins) {
                requireObject(join, "join");
                var type = optionalText(join, "type", "INNER");
                if (!"INNER".equalsIgnoreCase(type)) {
                    throw new QueryException("unsupported join type: " + type);
                }
                var relation = requiredText(join, "relation");
                var alias = optionalText(join, "alias", relation);
                addRelation(aliases, relations, alias, relation);
                var condition = parseConditionArray(join, "on", aliases, true);
                root = new QueryAst.InnerJoinNode<>(root,
                        new QueryAst.ScanNode<>(relation, alias), condition);
            }
        }

        var where = parseConditionArray(query, "where", aliases, false);
        root = new QueryAst.FilterNode<>(root, where);

        var select = requiredObject(query, "select");
        var columnNodes = requiredArray(select, "columns");
        if (columnNodes.size() == 0) {
            throw new QueryException("query.select.columns must not be empty");
        }
        var columns = new ArrayList<QueryAst.ProjectedColumn<KeyType, ValueType>>();
        var outputNames = new LinkedHashSet<String>();
        for (var columnNode : columnNodes) {
            if (!columnNode.isTextual()) {
                throw new QueryException("SELECT columns must be strings");
            }
            var column = parseColumn(columnNode.asText(), aliases);
            if (!outputNames.add(column.outputName())) {
                throw new QueryException("duplicate SELECT output name: " + column.outputName()
                        + "; use AS to disambiguate it");
            }
            columns.add(column);
        }

        var distinctNode = select.get("distinct");
        var distinct = false;
        if (distinctNode != null) {
            if (!distinctNode.isBoolean()) {
                throw new QueryException("query.select.distinct must be boolean");
            }
            distinct = distinctNode.asBoolean();
        }

        return new QueryPlan<>(root, columns, distinct,
                QueryScope.forRelations(relations, relationResolver), valueAdapter);
    }

    private QueryAst.ProjectedColumn<KeyType, ValueType> parseColumn(
            String source, Set<String> aliases) {
        var trimmed = source == null ? "" : source.trim();
        if (trimmed.isEmpty()) {
            throw new QueryException("empty SELECT column");
        }
        var aliasMatcher = COLUMN_ALIAS.matcher(trimmed);
        String expressionSource;
        String outputName = null;
        if (aliasMatcher.matches()) {
            expressionSource = aliasMatcher.group(1).trim();
            outputName = aliasMatcher.group(2);
        } else {
            expressionSource = trimmed;
        }

        var expression = new ExpressionParser(expressionSource).parse();
        expression.validateAliases(aliases);
        if (outputName == null) {
            if (!(expression instanceof QueryAst.FieldExpression)) {
                throw new QueryException("computed SELECT expression requires AS: " + source);
            }
            @SuppressWarnings("unchecked")
            var field = (QueryAst.FieldExpression<KeyType, ValueType>) expression;
            outputName = field.defaultOutputName();
        }
        return new QueryAst.ProjectedColumn<>(outputName, expression);
    }

    private QueryAst.Expression<KeyType, ValueType> parseConditionArray(
            JsonNode owner, String fieldName, Set<String> aliases, boolean required) {
        var array = owner.get(fieldName);
        if (array == null) {
            if (required) {
                throw new QueryException("missing array field: " + fieldName);
            }
            return new QueryAst.LiteralExpression<>(QueryValue.bool(true));
        }
        requireArray(array, fieldName);
        if (required && array.size() == 0) {
            throw new QueryException(fieldName + " must not be empty");
        }
        var expressions = new ArrayList<QueryAst.Expression<KeyType, ValueType>>();
        for (var expressionNode : array) {
            if (!expressionNode.isTextual()) {
                throw new QueryException(fieldName + " expressions must be strings");
            }
            var expression = new ExpressionParser(expressionNode.asText()).parse();
            expression.validateAliases(aliases);
            expressions.add(expression);
        }
        return QueryAst.and(expressions);
    }

    private static void addRelation(Set<String> aliases, Set<String> relations,
            String alias, String relation) {
        if (!aliases.add(alias)) {
            throw new QueryException("duplicate relation alias: " + alias);
        }
        relations.add(relation);
    }

    private static JsonNode requiredObject(JsonNode owner, String fieldName) {
        var value = owner.get(fieldName);
        requireObject(value, fieldName);
        return value;
    }

    private static JsonNode requiredArray(JsonNode owner, String fieldName) {
        var value = owner.get(fieldName);
        requireArray(value, fieldName);
        return value;
    }

    private static void requireObject(JsonNode value, String label) {
        if (value == null || !value.isObject()) {
            throw new QueryException(label + " must be an object");
        }
    }

    private static void requireArray(JsonNode value, String label) {
        if (value == null || !value.isArray()) {
            throw new QueryException(label + " must be an array");
        }
    }

    private static String requiredText(JsonNode owner, String fieldName) {
        var value = owner.get(fieldName);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new QueryException(fieldName + " must be a non-blank string");
        }
        return value.asText();
    }

    private static String optionalText(JsonNode owner, String fieldName, String fallback) {
        var value = owner.get(fieldName);
        if (value == null) {
            return fallback;
        }
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new QueryException(fieldName + " must be a non-blank string");
        }
        return value.asText();
    }

    private enum TokenType {
        IDENTIFIER,
        INTEGER,
        STRING,
        TRUE,
        FALSE,
        NULL,
        AND,
        DOT,
        EQ,
        GT,
        LT,
        MOD,
        LPAREN,
        RPAREN,
        EOF
    }

    private static final class Token {
        private final TokenType type;
        private final String text;
        private final int offset;

        private Token(TokenType type, String text, int offset) {
            this.type = type;
            this.text = text;
            this.offset = offset;
        }
    }

    private final class ExpressionParser {
        private final String source;
        private final List<Token> tokens;
        private int current;

        private ExpressionParser(String source) {
            this.source = source == null ? "" : source.trim();
            if (this.source.isEmpty()) {
                throw new QueryException("empty expression");
            }
            this.tokens = lex(this.source);
        }

        private QueryAst.Expression<KeyType, ValueType> parse() {
            var expression = parseAnd();
            expect(TokenType.EOF);
            return expression;
        }

        private QueryAst.Expression<KeyType, ValueType> parseAnd() {
            var expressions = new ArrayList<QueryAst.Expression<KeyType, ValueType>>();
            expressions.add(parseComparison());
            while (match(TokenType.AND)) {
                expressions.add(parseComparison());
            }
            return QueryAst.and(expressions);
        }

        private QueryAst.Expression<KeyType, ValueType> parseComparison() {
            var left = parseModulo();
            if (match(TokenType.EQ)) {
                return new QueryAst.ComparisonExpression<>(left,
                        QueryAst.ComparisonOperator.EQ, parseModulo());
            }
            if (match(TokenType.GT)) {
                return new QueryAst.ComparisonExpression<>(left,
                        QueryAst.ComparisonOperator.GT, parseModulo());
            }
            if (match(TokenType.LT)) {
                return new QueryAst.ComparisonExpression<>(left,
                        QueryAst.ComparisonOperator.LT, parseModulo());
            }
            return left;
        }

        private QueryAst.Expression<KeyType, ValueType> parseModulo() {
            var expression = parsePrimary();
            while (match(TokenType.MOD)) {
                expression = new QueryAst.ModuloExpression<>(expression, parsePrimary());
            }
            return expression;
        }

        private QueryAst.Expression<KeyType, ValueType> parsePrimary() {
            if (match(TokenType.LPAREN)) {
                var expression = parseAnd();
                expect(TokenType.RPAREN);
                return expression;
            }
            if (match(TokenType.INTEGER)) {
                var token = previous();
                try {
                    return new QueryAst.LiteralExpression<>(
                            QueryValue.integer(Long.parseLong(token.text)));
                } catch (NumberFormatException exception) {
                    throw error(token, "integer is outside signed 64-bit range", exception);
                }
            }
            if (match(TokenType.STRING)) {
                return new QueryAst.LiteralExpression<>(QueryValue.text(previous().text));
            }
            if (match(TokenType.TRUE)) {
                return new QueryAst.LiteralExpression<>(QueryValue.bool(true));
            }
            if (match(TokenType.FALSE)) {
                return new QueryAst.LiteralExpression<>(QueryValue.bool(false));
            }
            if (match(TokenType.NULL)) {
                return new QueryAst.LiteralExpression<>(QueryValue.nullValue());
            }
            if (match(TokenType.IDENTIFIER)) {
                var path = new ArrayList<String>();
                path.add(previous().text);
                while (match(TokenType.DOT)) {
                    path.add(expect(TokenType.IDENTIFIER).text);
                }
                return new QueryAst.FieldExpression<>(path);
            }
            throw error(peek(), "expected a field, literal, or parenthesized expression", null);
        }

        private boolean match(TokenType type) {
            if (peek().type != type) {
                return false;
            }
            current++;
            return true;
        }

        private Token expect(TokenType type) {
            var token = peek();
            if (token.type != type) {
                throw error(token, "expected " + type + " but found " + token.type, null);
            }
            current++;
            return token;
        }

        private Token peek() {
            return tokens.get(current);
        }

        private Token previous() {
            return tokens.get(current - 1);
        }

        private QueryException error(Token token, String message, Throwable cause) {
            var detail = message + " at offset " + token.offset + " in expression: " + source;
            return cause == null ? new QueryException(detail) : new QueryException(detail, cause);
        }
    }

    private static List<Token> lex(String source) {
        var result = new ArrayList<Token>();
        int offset = 0;
        while (offset < source.length()) {
            var character = source.charAt(offset);
            if (Character.isWhitespace(character)) {
                offset++;
                continue;
            }
            switch (character) {
            case '.':
                result.add(new Token(TokenType.DOT, ".", offset++));
                continue;
            case '=':
                result.add(new Token(TokenType.EQ, "=", offset++));
                continue;
            case '>':
                result.add(new Token(TokenType.GT, ">", offset++));
                continue;
            case '<':
                result.add(new Token(TokenType.LT, "<", offset++));
                continue;
            case '%':
                result.add(new Token(TokenType.MOD, "%", offset++));
                continue;
            case '(':
                result.add(new Token(TokenType.LPAREN, "(", offset++));
                continue;
            case ')':
                result.add(new Token(TokenType.RPAREN, ")", offset++));
                continue;
            case '\'':
            case '"': {
                var quoted = readQuoted(source, offset);
                result.add(new Token(TokenType.STRING, quoted.text, offset));
                offset = quoted.nextOffset;
                continue;
            }
            default:
                break;
            }

            if (Character.isDigit(character)
                    || character == '-' && offset + 1 < source.length()
                    && Character.isDigit(source.charAt(offset + 1))) {
                var start = offset++;
                while (offset < source.length() && Character.isDigit(source.charAt(offset))) {
                    offset++;
                }
                result.add(new Token(TokenType.INTEGER, source.substring(start, offset), start));
                continue;
            }

            if (isIdentifierStart(character)) {
                var start = offset++;
                while (offset < source.length() && isIdentifierPart(source.charAt(offset))) {
                    offset++;
                }
                var text = source.substring(start, offset);
                var keyword = keyword(text);
                result.add(new Token(keyword, text, start));
                continue;
            }
            throw new QueryException("unexpected character '" + character
                    + "' at offset " + offset + " in expression: " + source);
        }
        result.add(new Token(TokenType.EOF, "", source.length()));
        return result;
    }

    private static QuotedToken readQuoted(String source, int start) {
        var quote = source.charAt(start);
        var text = new StringBuilder();
        int offset = start + 1;
        while (offset < source.length()) {
            var character = source.charAt(offset++);
            if (character == quote) {
                return new QuotedToken(text.toString(), offset);
            }
            if (character != '\\') {
                text.append(character);
                continue;
            }
            if (offset >= source.length()) {
                break;
            }
            var escaped = source.charAt(offset++);
            switch (escaped) {
            case '\\':
            case '\'':
            case '"':
                text.append(escaped);
                break;
            case 'n':
                text.append('\n');
                break;
            case 'r':
                text.append('\r');
                break;
            case 't':
                text.append('\t');
                break;
            default:
                throw new QueryException("unsupported string escape \\" + escaped
                        + " at offset " + (offset - 2));
            }
        }
        throw new QueryException("unterminated string literal at offset " + start);
    }

    private static TokenType keyword(String identifier) {
        switch (identifier.toUpperCase(Locale.ROOT)) {
        case "TRUE":
            return TokenType.TRUE;
        case "FALSE":
            return TokenType.FALSE;
        case "NULL":
            return TokenType.NULL;
        case "AND":
            return TokenType.AND;
        default:
            return TokenType.IDENTIFIER;
        }
    }

    private static boolean isIdentifierStart(char character) {
        return Character.isLetter(character) || character == '_' || character == '$';
    }

    private static boolean isIdentifierPart(char character) {
        return Character.isLetterOrDigit(character) || character == '_' || character == '$';
    }

    private static final class QuotedToken {
        private final String text;
        private final int nextOffset;

        private QuotedToken(String text, int nextOffset) {
            this.text = text;
            this.nextOffset = nextOffset;
        }
    }
}
