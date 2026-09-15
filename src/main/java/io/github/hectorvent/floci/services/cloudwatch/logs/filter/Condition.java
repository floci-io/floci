package io.github.hectorvent.floci.services.cloudwatch.logs.filter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * A boolean expression over one log event, shared by the JSON and space-delimited syntaxes: atoms
 * joined with {@code &&} and {@code ||}, grouped with parentheses, {@code &&} binding tighter.
 * {@code C} is what an atom is evaluated against, a parsed JSON document or the event's fields.
 */
@FunctionalInterface
interface Condition<C> {

    boolean test(C context);

    static <C> Condition<C> and(List<Condition<C>> operands) {
        return context -> operands.stream().allMatch(operand -> operand.test(context));
    }

    static <C> Condition<C> or(List<Condition<C>> operands) {
        return context -> operands.stream().anyMatch(operand -> operand.test(context));
    }

    /**
     * Parses {@code or := and ('||' and)*; and := unary ('&&' unary)*; unary := '(' or ')' | atom}
     * at the cursor, reading atoms through {@code atom}.
     */
    static <C> Condition<C> parse(PatternCursor cursor, Function<PatternCursor, Condition<C>> atom) {
        List<Condition<C>> alternatives = new ArrayList<>();
        do {
            alternatives.add(parseAnd(cursor, atom));
            cursor.skipWhitespace();
        } while (cursor.consume("||"));
        return alternatives.size() == 1 ? alternatives.getFirst() : or(alternatives);
    }

    private static <C> Condition<C> parseAnd(PatternCursor cursor, Function<PatternCursor, Condition<C>> atom) {
        List<Condition<C>> operands = new ArrayList<>();
        do {
            operands.add(parseUnary(cursor, atom));
            cursor.skipWhitespace();
        } while (cursor.consume("&&"));
        return operands.size() == 1 ? operands.getFirst() : and(operands);
    }

    private static <C> Condition<C> parseUnary(PatternCursor cursor, Function<PatternCursor, Condition<C>> atom) {
        cursor.skipWhitespace();
        if (cursor.consume("(")) {
            Condition<C> inner = parse(cursor, atom);
            cursor.expect(')');
            return inner;
        }
        return atom.apply(cursor);
    }
}
