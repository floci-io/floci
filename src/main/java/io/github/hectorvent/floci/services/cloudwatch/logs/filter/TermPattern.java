package io.github.hectorvent.floci.services.cloudwatch.logs.filter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Terms matched against an unstructured message: every plain term must be present (a
 * case-sensitive substring, as the API reference's examples show), no {@code -term} may be, and
 * {@code ?term}s make the pattern match when any of them is present, though only when no other
 * kind of term is given, since AWS ignores them otherwise. A quoted phrase is one term.
 * An unstructured {@code %regex%} must stand alone and is searched for.
 */
final class TermPattern extends FilterPattern {

    private enum Mode { INCLUDE, EXCLUDE, OPTIONAL }

    private record Term(Mode mode, String text, AwsRegex regex) {
        boolean occursIn(String message) {
            return regex != null ? regex.find(message) : message.contains(text);
        }
    }

    private final List<Term> includes = new ArrayList<>();
    private final List<Term> excludes = new ArrayList<>();
    private final List<Term> optionals = new ArrayList<>();
    private int regexes;

    static TermPattern of(String text) {
        TermPattern pattern = new TermPattern();
        PatternCursor cursor = new PatternCursor(text);
        if (cursor.peek() == '%') {
            pattern.add(new Term(Mode.INCLUDE, null, cursor.regex()));
            cursor.expectEnd();
            return pattern;
        }
        while (true) {
            cursor.skipWhitespace();
            if (cursor.atEnd()) {
                return pattern;
            }
            pattern.add(readTerm(cursor));
            if (!cursor.atEnd() && !Character.isWhitespace(cursor.peek())
                    && cursor.peek() != '-' && cursor.peek() != '?') {
                throw cursor.error("terms must be separated");
            }
        }
    }

    private static Term readTerm(PatternCursor cursor) {
        Mode mode = Mode.INCLUDE;
        if (cursor.peek() == '-' && !cursor.lookingAt("- ")) {
            cursor.next();
            mode = Mode.EXCLUDE;
        } else if (cursor.peek() == '?' && !cursor.lookingAt("? ")) {
            cursor.next();
            mode = Mode.OPTIONAL;
        }
        if (cursor.peek() == '"') {
            String value = cursor.quoted();
            if (value.isEmpty()) {
                throw cursor.error("empty quoted term");
            }
            return new Term(mode, value, null);
        }
        if (cursor.peek() == '%') {
            throw cursor.error("a regex must be the entire unstructured pattern");
        }
        String value = cursor.bareValue("-?");
        if (!value.matches("[\\p{L}\\p{N}_.]+")) {
            throw cursor.error("invalid unquoted term '" + value + "'");
        }
        return new Term(mode, value, null);
    }

    private void add(Term term) {
        if (term.regex() != null) {
            regexes++;
        }
        switch (term.mode()) {
            case INCLUDE -> includes.add(term);
            case EXCLUDE -> excludes.add(term);
            case OPTIONAL -> optionals.add(term);
        }
    }

    @Override
    public Kind kind() {
        return Kind.TERMS;
    }

    @Override
    public FilterMatch match(String message) {
        String text = message == null ? "" : message;
        boolean matched;
        if (includes.isEmpty() && excludes.isEmpty()) {
            matched = optionals.stream().anyMatch(term -> term.occursIn(text));
        } else {
            matched = includes.stream().allMatch(term -> term.occursIn(text))
                    && excludes.stream().noneMatch(term -> term.occursIn(text));
        }
        return matched ? FilterMatch.of(Map.of(), reference -> null) : FilterMatch.NONE;
    }

    @Override
    public boolean declaresField(String reference) {
        return false;
    }

    @Override
    public int regexCount() {
        return regexes;
    }
}
