package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.SmithyValidationErrors;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;

/** CloudWatch's boolean alarm language, parsed completely before any alarm is stored. */
final class CompositeAlarmRule {
    private final String input;
    private final Set<String> references = new LinkedHashSet<>();
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
        return new Rule(expression, Collections.unmodifiableSet(parser.references));
    }

    private Expression or() {
        Expression left = and();
        while (word("OR")) {
            left = new Or(left, and());
        }
        return left;
    }

    private Expression and() {
        Expression left = atom();
        while (word("AND")) {
            left = new And(left, atom());
        }
        return left;
    }

    private Expression atom() {
        boolean negate = false;
        while (word("NOT")) {
            negate = !negate;
        }
        Expression operand = primary();
        return negate ? new Not(operand) : operand;
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
            return new Constant(true);
        }
        if (word("FALSE")) {
            return new Constant(false);
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
        return new StateOf(state, name);
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

    /** A parsed rule; {@code references} holds each alarm name or ARN once, in the order the rule first names it. */
    record Rule(Expression expression, Set<String> references) {
        boolean evaluate(Function<String, String> states) {
            return expression.evaluate(states);
        }

        /**
         * The references whose state test agrees with {@code result}: under an odd number of NOTs a test
         * agrees when it is false. CloudWatch names these, among a composite alarm's children, as the
         * alarms that triggered its change to that result. In rule order.
         */
        Set<String> agreeing(Function<String, String> states, boolean result) {
            Set<String> agreeing = new LinkedHashSet<>();
            expression.collectAgreeing(states, result, agreeing);
            return agreeing;
        }
    }

    sealed interface Expression permits Constant, StateOf, Not, And, Or {
        boolean evaluate(Function<String, String> states);

        void collectAgreeing(Function<String, String> states, boolean result, Set<String> agreeing);
    }

    record Constant(boolean value) implements Expression {
        @Override
        public boolean evaluate(Function<String, String> states) {
            return value;
        }

        @Override
        public void collectAgreeing(Function<String, String> states, boolean result, Set<String> agreeing) {
            // TRUE and FALSE name no alarm.
        }
    }

    record StateOf(String state, String reference) implements Expression {
        @Override
        public boolean evaluate(Function<String, String> states) {
            return state.equals(states.apply(reference));
        }

        @Override
        public void collectAgreeing(Function<String, String> states, boolean result, Set<String> agreeing) {
            if (evaluate(states) == result) {
                agreeing.add(reference);
            }
        }
    }

    record Not(Expression operand) implements Expression {
        @Override
        public boolean evaluate(Function<String, String> states) {
            return !operand.evaluate(states);
        }

        @Override
        public void collectAgreeing(Function<String, String> states, boolean result, Set<String> agreeing) {
            operand.collectAgreeing(states, !result, agreeing);
        }
    }

    record And(Expression left, Expression right) implements Expression {
        @Override
        public boolean evaluate(Function<String, String> states) {
            return left.evaluate(states) && right.evaluate(states);
        }

        @Override
        public void collectAgreeing(Function<String, String> states, boolean result, Set<String> agreeing) {
            left.collectAgreeing(states, result, agreeing);
            right.collectAgreeing(states, result, agreeing);
        }
    }

    record Or(Expression left, Expression right) implements Expression {
        @Override
        public boolean evaluate(Function<String, String> states) {
            return left.evaluate(states) || right.evaluate(states);
        }

        @Override
        public void collectAgreeing(Function<String, String> states, boolean result, Set<String> agreeing) {
            left.collectAgreeing(states, result, agreeing);
            right.collectAgreeing(states, result, agreeing);
        }
    }
}
