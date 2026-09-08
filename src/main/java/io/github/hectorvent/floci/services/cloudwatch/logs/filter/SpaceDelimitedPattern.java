package io.github.hectorvent.floci.services.cloudwatch.logs.filter;

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
    record Fields(List<String> values, Map<String, String> byName) {}

    private record Entry(String name, Condition<Fields> condition) {}

    private final List<Entry> before;
    private final List<Entry> after;
    private final boolean ellipsis;
    private final Set<String> names;
    private final int regexes;

    private SpaceDelimitedPattern(List<Entry> before, List<Entry> after, boolean ellipsis, Set<String> names,
                                  int regexes) {
        this.before = before;
        this.after = after;
        this.ellipsis = ellipsis;
        this.names = names;
        this.regexes = regexes;
    }

    static SpaceDelimitedPattern of(String text) {
        PatternCursor cursor = new PatternCursor(text);
        cursor.expect('[');
        List<Entry> before = new ArrayList<>();
        List<Entry> after = new ArrayList<>();
        boolean ellipsis = false;
        Set<String> declared = new HashSet<>();
        Set<String> referenced = new HashSet<>();
        int[] regexes = {0};
        cursor.skipWhitespace();
        if (cursor.peek() != ']') {
            while (true) {
                cursor.skipWhitespace();
                if (cursor.lookingAt("...")) {
                    cursor.consume("...");
                    if (ellipsis) {
                        throw cursor.error("only one '...' is allowed");
                    }
                    ellipsis = true;
                } else if (cursor.peek() == ',' || cursor.peek() == ']') {
                    (ellipsis ? after : before).add(new Entry(null, null));
                } else {
                    Entry entry = readEntry(cursor, referenced, regexes);
                    if (!declared.add(entry.name())) {
                        throw cursor.error("field '" + entry.name() + "' is declared twice");
                    }
                    (ellipsis ? after : before).add(entry);
                }
                cursor.skipWhitespace();
                if (cursor.consume("]")) {
                    break;
                }
                cursor.expect(',');
            }
        } else {
            cursor.next();
            ellipsis = true;
        }
        cursor.expectEnd();
        referenced.removeAll(declared);
        if (!referenced.isEmpty()) {
            throw cursor.error("condition refers to an undeclared field " + referenced.iterator().next());
        }
        if (!ellipsis && before.isEmpty()) {
            ellipsis = true;
        }
        return new SpaceDelimitedPattern(List.copyOf(before), List.copyOf(after), ellipsis, Set.copyOf(declared),
                regexes[0]);
    }

    /** Reads one entry; the first field name in it is the field the entry declares. */
    private static Entry readEntry(PatternCursor cursor, Set<String> referenced, int[] regexes) {
        String[] declared = {null};
        Condition<Fields> condition = Condition.parse(cursor, c -> {
            String name = c.identifier();
            if (declared[0] == null) {
                declared[0] = name;
            }
            referenced.add(name);
            c.skipWhitespace();
            if ("=!<>".indexOf(c.peek()) < 0) {
                return fields -> true;
            }
            Literal.Operator op = Literal.Operator.read(c);
            Literal literal = Literal.read(c, ",]&|)");
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
        int fixed = before.size() + after.size();
        if (values.size() < fixed || (!ellipsis && values.size() != fixed)) {
            return FilterMatch.NONE;
        }
        Map<String, String> byName = new HashMap<>();
        Map<String, String> extracted = new LinkedHashMap<>();
        for (int position = 0; position < values.size(); position++) {
            Entry entry = null;
            if (position < before.size()) {
                entry = before.get(position);
            } else if (position >= values.size() - after.size()) {
                entry = after.get(position - (values.size() - after.size()));
            }
            String value = values.get(position);
            if (entry != null && entry.name() != null) {
                byName.put(entry.name(), value);
                extracted.put("$" + entry.name(), value);
            } else {
                extracted.put("$" + (position + 1), value);
            }
        }
        Fields fields = new Fields(values, byName);
        for (List<Entry> entries : List.of(before, after)) {
            for (Entry entry : entries) {
                if (entry.condition() != null && !entry.condition().test(fields)) {
                    return FilterMatch.NONE;
                }
            }
        }
        return FilterMatch.of(extracted, reference -> value(fields, reference));
    }

    private static String value(Fields fields, String reference) {
        if (reference == null || reference.length() < 2 || reference.charAt(0) != '$') {
            return null;
        }
        String key = reference.substring(1);
        Integer position = position(key);
        if (position != null) {
            return position >= 1 && position <= fields.values().size() ? fields.values().get(position - 1) : null;
        }
        return fields.byName().get(key);
    }

    private static Integer position(String key) {
        if (key.isEmpty() || !key.chars().allMatch(Character::isDigit)) {
            return null;
        }
        try {
            return Integer.parseInt(key);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Splits a message into fields on whitespace, keeping text between double quotes or square
     * brackets together without the delimiters. With {@code limit} above zero, the field at that
     * position takes the rest of the line as written.
     */
    static List<String> split(String message, int limit) {
        List<String> fields = new ArrayList<>();
        int i = 0;
        int length = message.length();
        while (i < length) {
            while (i < length && Character.isWhitespace(message.charAt(i))) {
                i++;
            }
            if (i >= length) {
                break;
            }
            if (limit > 0 && fields.size() == limit - 1) {
                fields.add(message.substring(i).strip());
                break;
            }
            char c = message.charAt(i);
            char closing = c == '"' ? '"' : c == '[' ? ']' : '\0';
            if (closing != '\0') {
                int end = message.indexOf(closing, i + 1);
                if (end < 0) {
                    fields.add(message.substring(i + 1));
                    break;
                }
                fields.add(message.substring(i + 1, end));
                i = end + 1;
            } else {
                int end = i;
                while (end < length && !Character.isWhitespace(message.charAt(end))) {
                    end++;
                }
                fields.add(message.substring(i, end));
                i = end;
            }
        }
        return fields;
    }

    @Override
    public boolean declaresField(String reference) {
        if (reference == null || reference.length() < 2 || reference.charAt(0) != '$') {
            return false;
        }
        String key = reference.substring(1);
        Integer position = position(key);
        return position != null ? position >= 1 : names.contains(key);
    }

    @Override
    int regexCount() {
        return regexes;
    }
}
