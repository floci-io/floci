package io.floci.aws.services.cloudwatch.logs.filter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A pattern in square brackets over a space-delimited log event, one entry per field: a name, a
 * name with conditions ({@code status_code = 4*}, {@code w1=ERROR || w1=WARNING}), an empty entry
 * for a field without a name, or one {@code ...} standing for any number of fields. Text between
 * double quotes or square brackets is one field. Without an ellipsis the last entry takes the rest
 * of the line, which is why the reference asks for a blank indicator after the last term.
 *
 * <p>A match extracts every field: named ones as {@code $name}, the rest by position as
 * {@code $1}, {@code $2} and so on, the shape {@code TestMetricFilter} reports.
 */
final class SpaceDelimitedPattern extends FilterPattern {

    /** The bound fields of one event, by name and by position. */
    record Fields(List<String> values, Map<String, String> byName) {
        String value(String reference) {
            String key = extractReferenceKey(reference);
            if (key == null) {
                return null;
            }
            Integer position = parseOneBasedPosition(key);
            if (position != null) {
                return position >= 1 && position <= values.size() ? values.get(position - 1) : null;
            }
            return byName.get(key);
        }
    }

    private record Entry(String name, Condition<Fields> condition) {}

    private static final class ParseState {
        private final List<Entry> before = new ArrayList<>();
        private final List<Entry> after = new ArrayList<>();
        private final Set<String> declared = new HashSet<>();
        private final Set<String> referenced = new HashSet<>();
        private final int[] regexes = {0};
        private boolean ellipsis;

        private List<Entry> activeEntries() {
            return ellipsis ? after : before;
        }
    }

    private final List<Entry> before;
    private final List<Entry> after;
    private final boolean ellipsis;
    private final Set<String> names;

    private SpaceDelimitedPattern(List<Entry> before, List<Entry> after, boolean ellipsis, Set<String> names,
                                  int regexes) {
        super(regexes);
        this.before = before;
        this.after = after;
        this.ellipsis = ellipsis;
        this.names = names;
    }

    static SpaceDelimitedPattern of(String text) {
        PatternCursor cursor = new PatternCursor(text);
        cursor.expect('[');
        ParseState state = new ParseState();
        cursor.skipWhitespace();
        if (cursor.peek() == ']') {
            cursor.next();
            state.ellipsis = true;
        } else {
            parseEntries(cursor, state);
        }
        cursor.expectEnd();
        validateReferencedFields(cursor, state);
        if (!state.ellipsis && state.before.isEmpty()) {
            state.ellipsis = true;
        }
        return new SpaceDelimitedPattern(List.copyOf(state.before), List.copyOf(state.after), state.ellipsis,
                Set.copyOf(state.declared), state.regexes[0]);
    }

    private static void parseEntries(PatternCursor cursor, ParseState state) {
        while (true) {
            cursor.skipWhitespace();
            parseSingleEntry(cursor, state);
            cursor.skipWhitespace();
            if (cursor.consume("]")) {
                break;
            }
            cursor.expect(',');
        }
    }

    private static void parseSingleEntry(PatternCursor cursor, ParseState state) {
        if (cursor.lookingAt("...")) {
            cursor.consume("...");
            if (state.ellipsis) {
                throw cursor.error("only one '...' is allowed");
            }
            state.ellipsis = true;
            return;
        }
        if (cursor.peek() == ',' || cursor.peek() == ']') {
            state.activeEntries().add(new Entry(null, null));
            return;
        }
        Entry entry = readEntry(cursor, state.referenced, state.regexes);
        if (!state.declared.add(entry.name())) {
            throw cursor.error("field '" + entry.name() + "' is declared twice");
        }
        state.activeEntries().add(entry);
    }

    private static void validateReferencedFields(PatternCursor cursor, ParseState state) {
        state.referenced.removeAll(state.declared);
        if (!state.referenced.isEmpty()) {
            throw cursor.error("condition refers to an undeclared field " + state.referenced.iterator().next());
        }
    }

    /** Reads one entry; the first field name in it is the field the entry declares. */
    private static Entry readEntry(PatternCursor cursor, Set<String> referenced, int[] regexes) {
        String[] declared = {null};
        Condition<Fields> condition = Condition.parse(cursor, c -> {
            String name = c.identifier();
            if (declared[0] == null) {
                declared[0] = name;
            } else if (!declared[0].equals(name)) {
                throw c.error("a field condition cannot reference another field");
            }
            referenced.add(name);
            c.skipWhitespace();
            if ("=!<>".indexOf(c.peek()) < 0) {
                return fields -> true;
            }
            Literal.Operator op = Literal.Operator.read(c);
            Literal literal = Literal.read(c, ",]&|)");
            literal.validateOperator(op);
            if (literal.isRegex()) {
                regexes[0]++;
            }
            return fields -> {
                String value = fields.byName().get(name);
                return value != null && literal.test(op, value);
            };
        });
        return new Entry(declared[0], condition);
    }

    @Override
    public Kind kind() {
        return Kind.SPACE_DELIMITED;
    }

    @Override
    public FilterMatch match(String message) {
        List<String> values = split(message == null ? "" : message, ellipsis ? -1 : before.size());
        if (!hasCompatibleFieldCount(values.size())) {
            return FilterMatch.NONE;
        }
        Map<String, String> extracted = new LinkedHashMap<>();
        Fields fields = bindFields(values, extracted);
        if (!matchesAllConditions(fields)) {
            return FilterMatch.NONE;
        }
        return FilterMatch.of(extracted, fields::value);
    }

    private boolean hasCompatibleFieldCount(int valueCount) {
        int fixedEntryCount = before.size() + after.size();
        return valueCount >= fixedEntryCount && (ellipsis || valueCount == fixedEntryCount);
    }

    private Fields bindFields(List<String> values, Map<String, String> extracted) {
        Map<String, String> byName = new HashMap<>();
        int totalValues = values.size();
        for (int position = 0; position < totalValues; position++) {
            Entry entry = entryAt(position, totalValues);
            String value = values.get(position);
            if (entry != null && entry.name() != null) {
                byName.put(entry.name(), value);
                extracted.put("$" + entry.name(), value);
            } else {
                extracted.put("$" + (position + 1), value);
            }
        }
        return new Fields(values, byName);
    }

    private Entry entryAt(int position, int totalValues) {
        if (position < before.size()) {
            return before.get(position);
        }
        int afterStartPosition = totalValues - after.size();
        if (position >= afterStartPosition) {
            return after.get(position - afterStartPosition);
        }
        return null;
    }

    private boolean matchesAllConditions(Fields fields) {
        for (Entry entry : before) {
            if (entry.condition() != null && !entry.condition().test(fields)) {
                return false;
            }
        }
        for (Entry entry : after) {
            if (entry.condition() != null && !entry.condition().test(fields)) {
                return false;
            }
        }
        return true;
    }

    private static String extractReferenceKey(String reference) {
        if (reference == null || reference.length() < 2 || reference.charAt(0) != '$') {
            return null;
        }
        return reference.substring(1);
    }

    private static Integer parseOneBasedPosition(String key) {
        if (key.isEmpty() || !key.chars().allMatch(Character::isDigit)) {
            return null;
        }
        try {
            return Integer.parseInt(key);
        } catch (NumberFormatException expected) {
            // Digits exceed Integer.MAX_VALUE; treat as an invalid positional reference.
            return null;
        }
    }

    /**
     * Splits a message into fields on whitespace, keeping text between double quotes or square
     * brackets together without the delimiters. With {@code limit} above zero, the field at that
     * position takes the rest of the line as written.
     */
    static List<String> split(String message, int limit) {
        return SpaceDelimitedTokenizer.split(message, limit);
    }

    @Override
    public boolean declaresField(String reference) {
        String key = extractReferenceKey(reference);
        if (key == null) {
            return false;
        }
        Integer position = parseOneBasedPosition(key);
        return position != null ? position >= 1 : names.contains(key);
    }
}
