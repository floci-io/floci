package io.github.hectorvent.floci.services.cloudwatch.logs.filter;

import java.util.ArrayList;
import java.util.List;

/**
 * A metric filter's {@code fieldSelectionCriteria}: which log events the filter processes, decided
 * from the system fields {@code @aws.account} and {@code @aws.region} rather than from the event's
 * own text. The Logs API documents the operators {@code =}, {@code !=}, {@code IN}, {@code NOT IN},
 * {@code AND} and {@code OR}, as in {@code @aws.region = "us-east-1"} or
 * {@code @aws.account IN ["123456789012", "987654321098"]}.
 *
 * <p>Both fields describe the batch rather than the individual event, so one evaluation decides a
 * whole ingested batch. {@code &&} and {@code ||} are accepted beside {@code AND} and {@code OR},
 * and parentheses group, because the criteria share their shape with the filter-pattern syntax.
 *
 * <p>Instances are immutable and safe to share between threads.
 */
public final class SystemFieldSelection {

    private static final String ACCOUNT = "@aws.account";
    private static final String REGION = "@aws.region";

    /** The system fields of one ingested batch. */
    private record Fields(String account, String region) {}

    private final Condition<Fields> condition;

    private SystemFieldSelection(Condition<Fields> condition) {
        this.condition = condition;
    }

    /**
     * Parses a criterion, throwing {@link FilterPatternException} when it does not follow the
     * syntax. A blank criterion selects everything, as an absent one does.
     */
    public static SystemFieldSelection parse(String expression) {
        String text = expression == null ? "" : expression.strip();
        if (text.isEmpty()) {
            return new SystemFieldSelection(fields -> true);
        }
        PatternCursor cursor = new PatternCursor(text);
        Condition<Fields> condition = Condition.parse(cursor, SystemFieldSelection::atom, true);
        cursor.expectEnd();
        return new SystemFieldSelection(condition);
    }

    /** Whether a batch written for {@code account} in {@code region} is one this filter processes. */
    public boolean test(String account, String region) {
        return condition.test(new Fields(account, region));
    }

    private static Condition<Fields> atom(PatternCursor cursor) {
        String field = field(cursor);
        cursor.skipWhitespace();
        if (cursor.consume("!=")) {
            String value = value(cursor);
            return fields -> !value.equals(valueOf(field, fields));
        }
        if (cursor.consume("=")) {
            if (cursor.peek() == '=') {
                throw cursor.error("'==' is not an operator, use '='");
            }
            String value = value(cursor);
            return fields -> value.equals(valueOf(field, fields));
        }
        boolean negated = cursor.consumeKeyword("NOT") || cursor.consumeKeyword("not");
        cursor.skipWhitespace();
        if (!cursor.consumeKeyword("IN") && !cursor.consumeKeyword("in")) {
            throw cursor.error("expected '=', '!=', IN or NOT IN after " + field);
        }
        List<String> values = list(cursor);
        return fields -> values.contains(valueOf(field, fields)) != negated;
    }

    private static String field(PatternCursor cursor) {
        cursor.skipWhitespace();
        if (cursor.consume(ACCOUNT)) {
            return ACCOUNT;
        }
        if (cursor.consume(REGION)) {
            return REGION;
        }
        throw cursor.error("expected " + ACCOUNT + " or " + REGION);
    }

    private static String value(PatternCursor cursor) {
        cursor.skipWhitespace();
        return cursor.peek() == '"' ? cursor.quoted() : cursor.bareValue(",]()=!<>|&");
    }

    private static List<String> list(PatternCursor cursor) {
        cursor.expect('[');
        List<String> values = new ArrayList<>();
        do {
            values.add(value(cursor));
            cursor.skipWhitespace();
        } while (cursor.consume(","));
        cursor.expect(']');
        return List.copyOf(values);
    }

    private static String valueOf(String field, Fields fields) {
        return ACCOUNT.equals(field) ? fields.account() : fields.region();
    }
}
