package io.github.hectorvent.floci.services.cloudwatch.logs.filter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Terms matched against an unstructured message: every plain term must be present (a
 * case-sensitive substring, as the API reference's examples show), no {@code -term} may be, and
 * {@code ?term}s make the pattern match when any of them is present, though only when no other
 * kind of term is given, since AWS ignores them otherwise. A quoted phrase is one term, and a
 * {@code %regex%} term is searched for.
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
        while (true) {
            cursor.skipWhitespace();
            if (cursor.atEnd()) {
                return pattern;
            }
            pattern.add(readTerm(cursor));
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
            return new Term(mode, cursor.quoted(), null);
        }
        if (cursor.peek() == '%') {
            return new Term(mode, null, cursor.regex());
        }
        return new Term(mode, cursor.bareValue(""), null);
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
    int regexCount() {
        return regexes;
    }
}
