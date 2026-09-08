package io.github.hectorvent.floci.services.cloudwatch.logs.filter;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * A property selector of a JSON pattern: {@code $} followed by {@code .name}, {@code [index]},
 * the wildcard {@code .*} or {@code [*]} for every field or element, and {@code ['name.with.dots']}
 * for a property a dot could not name. Resolving it against a document yields the nodes it points
 * at, several when a wildcard is used.
 */
final class JsonSelector {

    private static final String SELECTOR_END = " \t\r\n=!<>)}&|,";

    private sealed interface Segment permits Name, Index, Wildcard {}

    private record Name(String name) implements Segment {}

    private record Index(int index) implements Segment {}

    private record Wildcard() implements Segment {}

    private final String text;
    private final List<Segment> segments;

    private JsonSelector(String text, List<Segment> segments) {
        this.text = text;
        this.segments = segments;
    }

    /** Reads the selector at the cursor, which must start with {@code $}. */
    static JsonSelector read(PatternCursor cursor) {
        cursor.skipWhitespace();
        if (cursor.peek() != '$') {
            throw cursor.error("expected a property selector starting with '$'");
        }
        StringBuilder text = new StringBuilder("$");
        cursor.next();
        List<Segment> segments = new ArrayList<>();
        while (!cursor.atEnd() && SELECTOR_END.indexOf(cursor.peek()) < 0) {
            char c = cursor.next();
            text.append(c);
            if (c == '.') {
                if (cursor.peek() == '[') {
                    continue;
                }
                segments.add(readName(cursor, text));
            } else if (c == '[') {
                segments.add(readBracket(cursor, text));
            } else {
                throw cursor.error("unexpected '" + c + "' in a property selector");
            }
        }
        return new JsonSelector(text.toString(), List.copyOf(segments));
    }

    /** Parses a whole reference such as {@code $.latency}, or returns null when it is not a selector. */
    static JsonSelector parse(String reference) {
        if (reference == null || !reference.startsWith("$.") && !reference.startsWith("$[")) {
            return null;
        }
        try {
            PatternCursor cursor = new PatternCursor(reference);
            JsonSelector selector = read(cursor);
            return cursor.atEnd() ? selector : null;
        } catch (FilterPatternException e) {
            return null;
        }
    }

    private static Segment readName(PatternCursor cursor, StringBuilder text) {
        if (cursor.peek() == '*') {
            text.append(cursor.next());
            return new Wildcard();
        }
        StringBuilder name = new StringBuilder();
        while (!cursor.atEnd() && (Character.isLetterOrDigit(cursor.peek()) || cursor.peek() == '_'
                || cursor.peek() == '-')) {
            name.append(cursor.next());
        }
        if (name.isEmpty()) {
            throw cursor.error("expected a property name after '.'");
        }
        text.append(name);
        return new Name(name.toString());
    }

    private static Segment readBracket(PatternCursor cursor, StringBuilder text) {
        Segment segment;
        if (cursor.peek() == '\'' || cursor.peek() == '"') {
            char quote = cursor.next();
            StringBuilder name = new StringBuilder();
            while (!cursor.atEnd() && cursor.peek() != quote) {
                name.append(cursor.next());
            }
            if (cursor.atEnd()) {
                throw cursor.error("unterminated quoted property name");
            }
            cursor.next();
            text.append(quote).append(name).append(quote);
            segment = new Name(name.toString());
        } else if (cursor.peek() == '*') {
            text.append(cursor.next());
            segment = new Wildcard();
        } else {
            StringBuilder digits = new StringBuilder();
            while (!cursor.atEnd() && Character.isDigit(cursor.peek())) {
                digits.append(cursor.next());
            }
            if (digits.isEmpty()) {
                throw cursor.error("expected an array index, '*' or a quoted property name inside '[ ]'");
            }
            text.append(digits);
            segment = new Index(Integer.parseInt(digits.toString()));
        }
        if (cursor.peek() != ']') {
            throw cursor.error("expected ']'");
        }
        text.append(cursor.next());
        return segment;
    }

    String text() {
        return text;
    }

    /** The nodes the selector points at in {@code root}; empty when the path is absent. */
    List<JsonNode> resolve(JsonNode root) {
        List<JsonNode> current = List.of(root);
        for (Segment segment : segments) {
            List<JsonNode> next = new ArrayList<>();
            for (JsonNode node : current) {
                step(node, segment, next);
            }
            if (next.isEmpty()) {
                return next;
            }
            current = next;
        }
        return current;
    }

    private static void step(JsonNode node, Segment segment, List<JsonNode> into) {
        switch (segment) {
            case Name name -> {
                JsonNode child = node.isObject() ? node.get(name.name()) : null;
                if (child != null) {
                    into.add(child);
                }
            }
            case Index index -> {
                if (node.isArray() && index.index() < node.size()) {
                    into.add(node.get(index.index()));
                }
            }
            case Wildcard wildcard -> {
                if (node.isObject() || node.isArray()) {
                    node.forEach(into::add);
                }
            }
        }
    }

    /** The text of the first scalar node the selector points at, or null. */
    String firstScalar(JsonNode root) {
        for (JsonNode node : resolve(root)) {
            if (node.isValueNode() && !node.isNull()) {
                return node.asText();
            }
        }
        return null;
    }
}
