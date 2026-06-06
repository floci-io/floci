package io.github.hectorvent.floci.services.glue;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.glue.model.Column;
import io.github.hectorvent.floci.services.glue.model.Partition;
import io.github.hectorvent.floci.services.glue.model.Table;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class GluePartitionExpressionEvaluator {
    private GluePartitionExpressionEvaluator() {}

    static List<Partition> filter(Table table, List<Partition> partitions, String expression) {
        if (expression == null || expression.isBlank()) {
            return partitions;
        }

        Expression parsed = new Parser(expression).parse();
        List<String> partitionColumns = table.getPartitionKeys() == null
                ? List.of()
                : table.getPartitionKeys().stream()
                        .map(Column::getName)
                        .toList();
        return partitions.stream()
                .filter(partition -> parsed.matches(partitionValues(partitionColumns, partition.getValues())))
                .toList();
    }

    private static Map<String, String> partitionValues(List<String> partitionColumns, List<String> values) {
        Map<String, String> result = new HashMap<>();
        for (int i = 0; i < partitionColumns.size() && i < values.size(); i++) {
            result.put(partitionColumns.get(i), values.get(i));
        }
        return result;
    }

    private sealed interface Expression permits AndExpression, OrExpression, ComparisonExpression, InExpression {
        boolean matches(Map<String, String> values);
    }

    private record AndExpression(Expression left, Expression right) implements Expression {
        @Override
        public boolean matches(Map<String, String> values) {
            return left.matches(values) && right.matches(values);
        }
    }

    private record OrExpression(Expression left, Expression right) implements Expression {
        @Override
        public boolean matches(Map<String, String> values) {
            return left.matches(values) || right.matches(values);
        }
    }

    private record ComparisonExpression(String column, String operator, Literal literal) implements Expression {
        @Override
        public boolean matches(Map<String, String> values) {
            String value = values.get(column);
            if (value == null) {
                return false;
            }
            int comparison = literal.compare(value);
            return switch (operator) {
                case "=" -> comparison == 0;
                case "<>" -> comparison != 0;
                case "<" -> comparison < 0;
                case "<=" -> comparison <= 0;
                case ">" -> comparison > 0;
                case ">=" -> comparison >= 0;
                default -> throw invalidExpression("Unsupported comparison operator: " + operator);
            };
        }
    }

    private record InExpression(String column, List<Literal> literals) implements Expression {
        @Override
        public boolean matches(Map<String, String> values) {
            String value = values.get(column);
            return value != null && literals.stream().anyMatch(literal -> literal.compare(value) == 0);
        }
    }

    private record Literal(String value, boolean quoted) {
        int compare(String partitionValue) {
            if (quoted) {
                return partitionValue.compareTo(value);
            }
            return new BigDecimal(partitionValue).compareTo(new BigDecimal(value));
        }
    }

    private static final class Parser {
        private final List<Token> tokens;
        private int position;

        private Parser(String expression) {
            this.tokens = tokenize(expression);
        }

        private Expression parse() {
            Expression expression = parseOr();
            if (!peek(TokenType.END)) {
                throw invalidExpression("Unexpected token: " + current().text());
            }
            return expression;
        }

        private Expression parseOr() {
            Expression expression = parseAnd();
            while (matchKeyword("OR")) {
                expression = new OrExpression(expression, parseAnd());
            }
            return expression;
        }

        private Expression parseAnd() {
            Expression expression = parsePrimary();
            while (matchKeyword("AND")) {
                expression = new AndExpression(expression, parsePrimary());
            }
            return expression;
        }

        private Expression parsePrimary() {
            if (match(TokenType.LEFT_PAREN)) {
                Expression expression = parseOr();
                expect(TokenType.RIGHT_PAREN);
                return expression;
            }
            return parsePredicate();
        }

        private Expression parsePredicate() {
            String column = expect(TokenType.IDENTIFIER).text();
            if (matchKeyword("IN")) {
                expect(TokenType.LEFT_PAREN);
                List<Literal> literals = new ArrayList<>();
                literals.add(parseLiteral());
                while (match(TokenType.COMMA)) {
                    literals.add(parseLiteral());
                }
                expect(TokenType.RIGHT_PAREN);
                return new InExpression(column, literals);
            }

            String operator = expect(TokenType.OPERATOR).text();
            return new ComparisonExpression(column, operator, parseLiteral());
        }

        private Literal parseLiteral() {
            Token token = current();
            if (match(TokenType.STRING)) {
                return new Literal(token.text(), true);
            }
            if (match(TokenType.NUMBER)) {
                return new Literal(token.text(), false);
            }
            throw invalidExpression("Expected literal");
        }

        private boolean matchKeyword(String keyword) {
            if (peek(TokenType.IDENTIFIER) && current().text().equalsIgnoreCase(keyword)) {
                position++;
                return true;
            }
            return false;
        }

        private boolean match(TokenType type) {
            if (peek(type)) {
                position++;
                return true;
            }
            return false;
        }

        private Token expect(TokenType type) {
            if (!peek(type)) {
                throw invalidExpression("Expected " + type);
            }
            return tokens.get(position++);
        }

        private boolean peek(TokenType type) {
            return current().type() == type;
        }

        private Token current() {
            return tokens.get(position);
        }
    }

    private static List<Token> tokenize(String expression) {
        List<Token> tokens = new ArrayList<>();
        int index = 0;
        while (index < expression.length()) {
            char ch = expression.charAt(index);
            if (Character.isWhitespace(ch)) {
                index++;
            }
            else if (ch == '(') {
                tokens.add(new Token(TokenType.LEFT_PAREN, "("));
                index++;
            }
            else if (ch == ')') {
                tokens.add(new Token(TokenType.RIGHT_PAREN, ")"));
                index++;
            }
            else if (ch == ',') {
                tokens.add(new Token(TokenType.COMMA, ","));
                index++;
            }
            else if (ch == '\'') {
                int end = expression.indexOf('\'', index + 1);
                if (end < 0) {
                    throw invalidExpression("Unterminated string literal");
                }
                tokens.add(new Token(TokenType.STRING, expression.substring(index + 1, end)));
                index = end + 1;
            }
            else if (isOperatorStart(ch)) {
                int end = index + 1;
                if (end < expression.length() && (expression.charAt(end) == '=' || (ch == '<' && expression.charAt(end) == '>'))) {
                    end++;
                }
                String operator = expression.substring(index, end);
                if (!List.of("=", "<>", "<", "<=", ">", ">=").contains(operator)) {
                    throw invalidExpression("Unsupported operator: " + operator);
                }
                tokens.add(new Token(TokenType.OPERATOR, operator));
                index = end;
            }
            else if (Character.isDigit(ch) || ch == '-' || ch == '.') {
                int end = index + 1;
                while (end < expression.length() && (Character.isDigit(expression.charAt(end)) || expression.charAt(end) == '.')) {
                    end++;
                }
                tokens.add(new Token(TokenType.NUMBER, expression.substring(index, end)));
                index = end;
            }
            else if (Character.isLetter(ch) || ch == '_') {
                int end = index + 1;
                while (end < expression.length()) {
                    char c = expression.charAt(end);
                    if (!Character.isLetterOrDigit(c) && c != '_' && c != '-') {
                        break;
                    }
                    end++;
                }
                tokens.add(new Token(TokenType.IDENTIFIER, expression.substring(index, end)));
                index = end;
            }
            else {
                throw invalidExpression("Unexpected character: " + ch);
            }
        }
        tokens.add(new Token(TokenType.END, ""));
        return tokens;
    }

    private static boolean isOperatorStart(char ch) {
        return ch == '=' || ch == '<' || ch == '>';
    }

    private static AwsException invalidExpression(String message) {
        return new AwsException("InvalidInputException", "Invalid partition expression: " + message, 400);
    }

    private record Token(TokenType type, String text) {}

    private enum TokenType {
        IDENTIFIER,
        NUMBER,
        STRING,
        OPERATOR,
        LEFT_PAREN,
        RIGHT_PAREN,
        COMMA,
        END
    }
}
