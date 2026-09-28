package io.github.hectorvent.floci.services.cloudwatch.logs.filter;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * The right-hand side of a JSON or space-delimited condition, and how it compares with a value from
 * a log event. A quoted or bare word compares as text, a word with an asterisk as a wildcard, a
 * number by value, and a {@code %regex%} by search. JSON strings compare with the literal's text,
 * never by numeric coercion. Space-delimited numeric fields compare by value. Ordering requires
 * a numeric literal and, for JSON events, a numeric node.
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
    private final BigDecimal number;
    private final Pattern glob;
    private final AwsRegex regex;

    private Literal(String text, BigDecimal number, Pattern glob, AwsRegex regex) {
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
            try {
                int exponent = Math.max(text.indexOf('e'), text.indexOf('E'));
                if (exponent >= 0) {
                    Integer.parseInt(text.substring(exponent + 1));
                }
                return new Literal(text, new BigDecimal(text), null, null);
            } catch (NumberFormatException e) {
                throw new FilterPatternException("Invalid filter pattern: numeric literal is out of range");
            }
        }
        if (text.equals("null") || !text.matches("[\\p{L}\\p{N}_.*-]+")) {
            throw new FilterPatternException("Invalid filter pattern: invalid unquoted value '" + text + "'");
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

    void validateOperator(Operator op) {
        if (op != Operator.EQ && op != Operator.NE && number == null) {
            throw new FilterPatternException("Invalid filter pattern: ordering requires a numeric literal");
        }
    }

    boolean test(Operator op, JsonNode node) {
        if (!node.isTextual() && !node.isNumber()) {
            return false;
        }
        if (number != null) {
            if (node.isTextual()) {
                return switch (op) {
                    case EQ -> text.equals(node.textValue());
                    case NE -> !text.equals(node.textValue());
                    default -> false;
                };
            }
            if (!Double.isFinite(node.doubleValue())) {
                return false;
            }
            return compare(op, node.decimalValue().compareTo(number));
        }
        return test(op, node.asText());
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
            BigDecimal actual = parse(value);
            return actual != null && actual.compareTo(number) == 0;
        }
        if (glob != null) {
            return glob.matcher(value).matches();
        }
        return text.equals(value);
    }

    private boolean orders(Operator op, String value) {
        BigDecimal actual = number == null ? null : parse(value);
        if (actual == null) {
            return false;
        }
        return compare(op, actual.compareTo(number));
    }

    private static boolean compare(Operator op, int comparison) {
        return switch (op) {
            case EQ -> comparison == 0;
            case NE -> comparison != 0;
            case LT -> comparison < 0;
            case LE -> comparison <= 0;
            case GT -> comparison > 0;
            case GE -> comparison >= 0;
        };
    }

    private static BigDecimal parse(String value) {
        if (!NUMBER.matcher(value).matches()) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
