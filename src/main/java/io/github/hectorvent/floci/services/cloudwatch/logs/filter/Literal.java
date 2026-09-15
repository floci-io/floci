package io.github.hectorvent.floci.services.cloudwatch.logs.filter;

import java.util.regex.Pattern;

/**
 * The right-hand side of a JSON or space-delimited condition, and how it compares with a value from
 * a log event. A quoted or bare word compares as text, a word with an asterisk as a wildcard, a
 * number by value (a numeric string in the event counts as a number, as a quoted number in the
 * pattern still compares as text), and a {@code %regex%} by search. The ordering operators compare
 * numbers only.
 */
final class Literal {

    enum Operator {
        EQ("="), NE("!="), LT("<"), LE("<="), GT(">"), GE(">=");

        private final String token;

        Operator(String token) {
            this.token = token;
        }

        /** Reads the longest operator at the cursor, or throws. */
        static Operator read(PatternCursor cursor) {
            cursor.skipWhitespace();
            for (Operator op : new Operator[] {NE, LE, GE, EQ, LT, GT}) {
                if (cursor.consume(op.token)) {
                    if (op == EQ && cursor.peek() == '=') {
                        throw cursor.error("'==' is not an operator, use '='");
                    }
                    return op;
                }
            }
            throw cursor.error("expected a comparison operator");
        }
    }

    private static final Pattern NUMBER = Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?");

    private final String text;
    private final Double number;
    private final Pattern glob;
    private final AwsRegex regex;

    private Literal(String text, Double number, Pattern glob, AwsRegex regex) {
        this.text = text;
        this.number = number;
        this.glob = glob;
        this.regex = regex;
    }

    /** A quoted value: text, or a wildcard when it holds an asterisk. */
    static Literal quoted(String text) {
        return new Literal(text, null, globOf(text), null);
    }

    /** A bare value: a number, a wildcard, or a word. */
    static Literal bare(String text) {
        if (NUMBER.matcher(text).matches()) {
            return new Literal(text, Double.parseDouble(text), null, null);
        }
        return new Literal(text, null, globOf(text), null);
    }

    static Literal regex(AwsRegex regex) {
        return new Literal(null, null, null, regex);
    }

    /** Reads a quoted string, a {@code %regex%} or a bare value from the cursor. */
    static Literal read(PatternCursor cursor, String bareDelimiters) {
        cursor.skipWhitespace();
        if (cursor.peek() == '"') {
            return quoted(cursor.quoted());
        }
        if (cursor.peek() == '%') {
            return regex(cursor.regex());
        }
        return bare(cursor.bareValue(bareDelimiters));
    }

    private static Pattern globOf(String text) {
        if (text.indexOf('*') < 0) {
            return null;
        }
        StringBuilder regex = new StringBuilder();
        for (String piece : text.split("\\*", -1)) {
            if (!regex.isEmpty()) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(piece));
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }

    boolean isRegex() {
        return regex != null;
    }

    /** Whether the value from the event satisfies {@code op} against this literal. */
    boolean test(Operator op, String value) {
        return switch (op) {
            case EQ -> equalTo(value);
            case NE -> !equalTo(value);
            case LT, LE, GT, GE -> orders(op, value);
        };
    }

    private boolean equalTo(String value) {
        if (regex != null) {
            return regex.find(value);
        }
        if (number != null) {
            Double actual = parse(value);
            return actual != null && actual.doubleValue() == number.doubleValue();
        }
        if (glob != null) {
            return glob.matcher(value).matches();
        }
        return text.equals(value);
    }

    private boolean orders(Operator op, String value) {
        Double actual = number == null ? null : parse(value);
        if (actual == null) {
            return false;
        }
        int comparison = Double.compare(actual, number);
        return switch (op) {
            case LT -> comparison < 0;
            case LE -> comparison <= 0;
            case GT -> comparison > 0;
            case GE -> comparison >= 0;
            default -> false;
        };
    }

    private static Double parse(String value) {
        if (!NUMBER.matcher(value).matches()) {
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
