package io.floci.aws.services.cloudwatch.logs.filter;

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

    private final List<Term> includes;
    private final List<Term> excludes;
    private final List<Term> optionals;

    private TermPattern(List<Term> includes, List<Term> excludes, List<Term> optionals, int regexes) {
        super(regexes);
        this.includes = includes;
        this.excludes = excludes;
        this.optionals = optionals;
    }

    static TermPattern of(String text) {
        List<Term> includes = new ArrayList<>();
        List<Term> excludes = new ArrayList<>();
        List<Term> optionals = new ArrayList<>();
        PatternCursor cursor = new PatternCursor(text);
        if (cursor.peek() == '%') {
            includes.add(new Term(Mode.INCLUDE, null, cursor.regex()));
            cursor.expectEnd();
            return new TermPattern(List.copyOf(includes), List.of(), List.of(), 1);
        }
        int regexCount = 0;
        while (true) {
            cursor.skipWhitespace();
            if (cursor.atEnd()) {
                return new TermPattern(List.copyOf(includes), List.copyOf(excludes), List.copyOf(optionals),
                        regexCount);
            }
            Term term = readTerm(cursor);
            if (term.regex() != null) {
                regexCount++;
            }
            addTerm(term, includes, excludes, optionals);
            if (isMissingTermSeparator(cursor)) {
                throw cursor.error("terms must be separated");
            }
        }
    }

    private static boolean isMissingTermSeparator(PatternCursor cursor) {
        if (cursor.atEnd()) {
            return false;
        }
        char nextChar = cursor.peek();
        return !Character.isWhitespace(nextChar) && nextChar != '-' && nextChar != '?';
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

    private static void addTerm(Term term, List<Term> includes, List<Term> excludes, List<Term> optionals) {
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
}
