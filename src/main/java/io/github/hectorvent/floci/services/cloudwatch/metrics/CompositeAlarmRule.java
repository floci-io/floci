package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.SmithyValidationErrors;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

/** CloudWatch's boolean alarm language, parsed completely before any alarm is stored. */
final class CompositeAlarmRule {
    private final String input;
    private final Set<String> references = new HashSet<>();
    private int offset;
    private int elements;

    private CompositeAlarmRule(String input) {
        this.input = input;
    }

    static Rule parse(String input) {
        String violation = input == null ? SmithyValidationErrors.notNull("alarmRule")
                : SmithyValidationErrors.lengthOutOfRange("alarmRule", input, 1, 10240);
        if (violation == null && input.isBlank()) {
            violation = SmithyValidationErrors.lengthTooShort("alarmRule", input, 1);
        }
        if (violation != null) {
            throw SmithyValidationErrors.validationError(violation);
        }
        CompositeAlarmRule parser = new CompositeAlarmRule(input);
        Expression expression = parser.or();
        parser.whitespace();
        if (parser.offset != input.length()) {
            throw invalid("Invalid AlarmRule");
        }
        return new Rule(expression, Set.copyOf(parser.references));
    }

    private Expression or() {
        Expression left = and();
        while (word("OR")) {
            Expression prior = left;
            Expression right = and();
            left = states -> prior.evaluate(states) || right.evaluate(states);
        }
        return left;
    }

    private Expression and() {
        Expression left = atom();
        while (word("AND")) {
            Expression prior = left;
            Expression right = atom();
            left = states -> prior.evaluate(states) && right.evaluate(states);
        }
        return left;
    }

    private Expression atom() {
        boolean negate = false;
        while (word("NOT")) {
            negate = !negate;
        }
        Expression operand = primary();
        return negate ? states -> !operand.evaluate(states) : operand;
    }

    private Expression primary() {
        element();
        if (character('(')) {
            Expression nested = or();
            require(')');
            element();
            return nested;
        }
        if (word("TRUE")) {
            return states -> true;
        }
        if (word("FALSE")) {
            return states -> false;
        }
        String state;
        if (word("ALARM")) {
            state = "ALARM";
        } else if (word("OK")) {
            state = "OK";
        } else if (word("INSUFFICIENT_DATA")) {
            state = "INSUFFICIENT_DATA";
        } else {
            throw invalid("Expected an alarm state, boolean, NOT, or parenthesis");
        }
        require('(');
        whitespace();
        String name;
        if (character('"')) {
            StringBuilder quoted = new StringBuilder();
            boolean closed = false;
            while (offset < input.length()) {
                char next = input.charAt(offset++);
                if (next == '"') {
                    closed = true;
                    break;
                }
                if (next == '\\') {
                    if (offset == input.length()) {
                        throw invalid("Unterminated quoted alarm name");
                    }
                    next = input.charAt(offset++);
                    if (next != '\\' && next != '"') {
                        throw invalid("Invalid quoted alarm name escape");
                    }
                }
                quoted.append(next);
            }
            if (!closed) {
                throw invalid("Unterminated quoted alarm name");
            }
            name = quoted.toString();
        } else {
            int start = offset;
            while (offset < input.length() && input.charAt(offset) != ')') {
                offset++;
            }
            name = input.substring(start, offset).trim();
        }
        if (name.isEmpty()) {
            throw invalid("An alarm name or ARN is required");
        }
        require(')');
        references.add(name);
        return states -> state.equals(states.apply(name));
    }

    private void element() {
        if (++elements > 500) {
            throw invalid("AlarmRule exceeds 500 child alarms, booleans, and parentheses");
        }
    }

    private boolean word(String word) {
        whitespace();
        if (!input.startsWith(word, offset)) {
            return false;
        }
        int end = offset + word.length();
        if (end < input.length() && (Character.isLetterOrDigit(input.charAt(end)) || input.charAt(end) == '_')) {
            return false;
        }
        offset = end;
        return true;
    }

    private boolean character(char expected) {
        whitespace();
        if (offset < input.length() && input.charAt(offset) == expected) {
            offset++;
            return true;
        }
        return false;
    }

    private void require(char expected) {
        if (!character(expected)) {
            throw invalid("Expected '" + expected + "' in AlarmRule");
        }
    }

    private void whitespace() {
        while (offset < input.length() && Character.isWhitespace(input.charAt(offset))) {
            offset++;
        }
    }

    private static AwsException invalid(String message) {
        return new AwsException("ValidationError", message, 400);
    }

    record Rule(Expression expression, Set<String> references) {
        boolean evaluate(Function<String, String> states) {
            return expression.evaluate(states);
        }
    }

    interface Expression {
        boolean evaluate(Function<String, String> states);
    }
}
