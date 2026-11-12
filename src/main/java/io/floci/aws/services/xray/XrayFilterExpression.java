package io.floci.aws.services.xray;

import io.floci.aws.core.common.AwsException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * The subset of X-Ray's filter expression grammar that {@code GetTraceSummaries} honours.
 *
 * <p>AWS's grammar is large and much of it (insights, edge predicates over the service graph,
 * sampling) needs an index Floci does not keep. Rather than accept an expression and quietly ignore
 * the part it does not understand, Floci parses what it supports and refuses the rest with
 * {@code InvalidRequestException} naming the token it stopped at, which is the difference between a
 * caller trusting a filter and a caller getting a superset.
 *
 * <p>Supported: {@code service("name")}, the {@code ok}, {@code error}, {@code fault} and
 * {@code throttle} keywords,
 * {@code duration}, {@code responsetime}, {@code http.status}, {@code annotation.key},
 * comparisons ({@code = != < <= > >=}), {@code AND}, {@code OR}, {@code NOT} and parentheses.
 */
final class XrayFilterExpression {

    /** What a filter is evaluated against: one trace, as the summaries see it. */
    record Facts(Set<String> services, boolean hasError, boolean hasFault, boolean hasThrottle,
                 double duration, Integer httpStatus, Map<String, Object> annotations) {
    }

    private final String source;
    private final List<String> tokens;
    private int position;

    private XrayFilterExpression(String source, List<String> tokens) {
        this.source = source;
        this.tokens = tokens;
    }

    static Predicate<Facts> compile(String expression) {
        if (expression == null || expression.isBlank()) {
            return facts -> true;
        }
        XrayFilterExpression parser = new XrayFilterExpression(expression, tokenize(expression));
        Predicate<Facts> predicate = parser.parseOr();
        if (parser.position < parser.tokens.size()) {
            throw parser.invalid("unexpected '" + parser.peek() + "'");
        }
        return predicate;
    }

    // ---------------------------------------------------------------- grammar

    private Predicate<Facts> parseOr() {
        Predicate<Facts> left = parseAnd();
        while (isKeyword("OR")) {
            position++;
            Predicate<Facts> right = parseAnd();
            Predicate<Facts> previous = left;
            left = facts -> previous.test(facts) || right.test(facts);
        }
        return left;
    }

    private Predicate<Facts> parseAnd() {
        Predicate<Facts> left = parseUnary();
        while (isKeyword("AND")) {
            position++;
            Predicate<Facts> right = parseUnary();
            Predicate<Facts> previous = left;
            left = facts -> previous.test(facts) && right.test(facts);
        }
        return left;
    }

    private Predicate<Facts> parseUnary() {
        if (isKeyword("NOT") || "!".equals(peek())) {
            position++;
            Predicate<Facts> inner = parseUnary();
            return facts -> !inner.test(facts);
        }
        if ("(".equals(peek())) {
            position++;
            Predicate<Facts> inner = parseOr();
            expect(")");
            return inner;
        }
        return parsePredicate();
    }

    private Predicate<Facts> parsePredicate() {
        String name = next();
        if (name == null) {
            throw invalid("the expression ends where a predicate was expected");
        }
        switch (name) {
            // These four are the whole keyword set AWS documents. There are no has* spellings, so
            // accepting one here would let a filter pass locally and fail against AWS.
            case "error":
                return Facts::hasError;
            case "fault":
                return Facts::hasFault;
            case "throttle":
                return Facts::hasThrottle;
            case "ok":
                return facts -> !facts.hasError() && !facts.hasFault() && !facts.hasThrottle();
            case "service":
                expect("(");
                String service = literal();
                expect(")");
                return facts -> facts.services().contains(service);
            case "duration":
                return numberComparison(name, Facts::duration);
            case "responsetime":
                // AWS measures it in seconds, the same unit as duration; only the moment it
                // marks differs, and a trace summary carries one duration for both.
                return numberComparison(name, Facts::duration);
            case "http.status": {
                String operator = nextOperator();
                double expected = number();
                return facts -> compare(facts.httpStatus(), operator, expected);
            }
            default:
                if (name.startsWith("annotation.")) {
                    String key = name.substring("annotation.".length());
                    String operator = nextOperator();
                    String expected = literalOrNumber();
                    return facts -> {
                        Object stored = facts.annotations().get(key);
                        if (stored == null) {
                            return false;
                        }
                        String actual = String.valueOf(stored);
                        if ("=".equals(operator) || "!=".equals(operator)) {
                            boolean equal = actual.equals(expected);
                            return "=".equals(operator) == equal;
                        }
                        return compare(parseDouble(actual), operator, parseDouble(expected));
                    };
                }
                throw invalid("'" + name + "' is not a predicate Floci evaluates");
        }
    }

    private Predicate<Facts> numberComparison(String name, ToDoubleFunction<Facts> value) {
        String operator = nextOperator();
        double expected = number();
        return facts -> compare(value.applyAsDouble(facts), operator, expected);
    }

    // ---------------------------------------------------------------- helpers

    private static boolean compare(Integer actual, String operator, double expected) {
        return actual != null && compare(actual.doubleValue(), operator, expected);
    }

    private static boolean compare(double actual, String operator, double expected) {
        return switch (operator) {
            case "=" -> actual == expected;
            case "!=" -> actual != expected;
            case "<" -> actual < expected;
            case "<=" -> actual <= expected;
            case ">" -> actual > expected;
            case ">=" -> actual >= expected;
            default -> false;
        };
    }

    private String nextOperator() {
        String token = next();
        if (token == null || !Set.of("=", "!=", "<", "<=", ">", ">=").contains(token)) {
            throw invalid("expected a comparison after the field, found '" + token + "'");
        }
        return token;
    }

    private boolean isKeyword(String keyword) {
        return keyword.equalsIgnoreCase(peek());
    }

    private String peek() {
        return position < tokens.size() ? tokens.get(position) : null;
    }

    private String next() {
        if (position >= tokens.size()) {
            return null;
        }
        return tokens.get(position++);
    }

    private void expect(String token) {
        String actual = next();
        if (!token.equals(actual)) {
            throw invalid("expected '" + token + "', found '" + actual + "'");
        }
    }

    private String literal() {
        String token = next();
        if (token == null || token.length() < 2 || !token.startsWith("\"") || !token.endsWith("\"")) {
            throw invalid("expected a quoted string, found '" + token + "'");
        }
        return token.substring(1, token.length() - 1);
    }

    private String literalOrNumber() {
        String token = peek();
        if (token == null) {
            throw invalid("expected a value");
        }
        return token.startsWith("\"") ? literal() : next();
    }

    private double number() {
        String token = next();
        try {
            return Double.parseDouble(token);
        } catch (RuntimeException e) {
            throw invalid("expected a number, found '" + token + "'");
        }
    }

    private static double parseDouble(String value) {
        try {
            return Double.parseDouble(value);
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    private AwsException invalid(String reason) {
        return new AwsException("InvalidRequestException",
                "The filter expression '" + source + "' is not supported: " + reason + ".", 400);
    }

    /** Splits an expression into identifiers, quoted strings, numbers and operators. */
    private static List<String> tokenize(String expression) {
        List<String> tokens = new ArrayList<>();
        int i = 0;
        while (i < expression.length()) {
            char c = expression.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '"') {
                int end = expression.indexOf('"', i + 1);
                if (end < 0) {
                    throw new AwsException("InvalidRequestException",
                            "The filter expression has an unterminated string.", 400);
                }
                tokens.add(expression.substring(i, end + 1));
                i = end + 1;
                continue;
            }
            if (c == '(' || c == ')' || c == ',') {
                tokens.add(String.valueOf(c));
                i++;
                continue;
            }
            if (c == '!') {
                if (i + 1 < expression.length() && expression.charAt(i + 1) == '=') {
                    tokens.add("!=");
                    i += 2;
                } else {
                    // A lone '!' is the negation operator; parseUnary consumes it. It is emitted
                    // as its own token rather than left as a delimiter with no branch, which would
                    // spin here forever on no progress.
                    tokens.add("!");
                    i++;
                }
                continue;
            }
            if (c == '<' || c == '>' || c == '=') {
                if (i + 1 < expression.length() && expression.charAt(i + 1) == '=') {
                    tokens.add(expression.substring(i, i + 2));
                    i += 2;
                } else {
                    tokens.add(String.valueOf(c));
                    i++;
                }
                continue;
            }
            int start = i;
            while (i < expression.length()) {
                char current = expression.charAt(i);
                if (Character.isWhitespace(current) || "()\",<>=!".indexOf(current) >= 0) {
                    break;
                }
                i++;
            }
            if (start == i) {
                throw new AwsException("InvalidRequestException",
                        "The filter expression has an unexpected '" + c + "'.", 400);
            }
            tokens.add(expression.substring(start, i));
        }
        return tokens;
    }
}
