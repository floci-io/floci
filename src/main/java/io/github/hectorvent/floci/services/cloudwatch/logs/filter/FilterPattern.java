package io.github.hectorvent.floci.services.cloudwatch.logs.filter;

import java.util.Map;

/**
 * A CloudWatch Logs filter pattern, parsed once and matched against many log event messages. The
 * four syntaxes AWS documents are told apart by the first character: {@code {} is a JSON pattern,
 * {@code [} a space-delimited one, a blank pattern matches everything, and anything else is a list
 * of terms.
 *
 * <p>Instances are immutable and safe to share between threads.
 */
public abstract class FilterPattern {

    public enum Kind { MATCH_ALL, TERMS, JSON, SPACE_DELIMITED }

    /** AWS's quota on regular expressions in one pattern. */
    static final int MAX_REGEXES = 2;

    private static final FilterPattern MATCH_ALL = new FilterPattern() {
        @Override
        public Kind kind() {
            return Kind.MATCH_ALL;
        }

        @Override
        public FilterMatch match(String message) {
            return FilterMatch.of(Map.of(), reference -> null);
        }

        @Override
        public boolean declaresField(String reference) {
            return false;
        }
    };

    /** Parses a pattern, throwing {@link FilterPatternException} when it does not follow the syntax. */
    public static FilterPattern parse(String pattern) {
        String text = pattern == null ? "" : pattern.strip();
        FilterPattern parsed;
        if (text.isEmpty()) {
            return MATCH_ALL;
        } else if (text.startsWith("{")) {
            parsed = JsonPattern.of(text);
        } else if (text.startsWith("[")) {
            parsed = SpaceDelimitedPattern.of(text);
        } else {
            parsed = TermPattern.of(text);
        }
        if (parsed.regexCount() > MAX_REGEXES) {
            throw new FilterPatternException("Invalid filter pattern: at most " + MAX_REGEXES
                    + " regular expressions are allowed in a filter pattern");
        }
        return parsed;
    }

    public abstract Kind kind();

    /** Matches one message; never throws, an event the pattern cannot read simply does not match. */
    public abstract FilterMatch match(String message);

    /**
     * Whether a metric value or dimension reference can be read off this pattern's matches: any
     * {@code $.path} for a JSON pattern, a declared {@code $name} or a position {@code $3} for a
     * space-delimited one, nothing for the others.
     */
    public abstract boolean declaresField(String reference);

    /** How many {@code %regex%} the pattern holds, for the quota. */
    int regexCount() {
        return 0;
    }
}
