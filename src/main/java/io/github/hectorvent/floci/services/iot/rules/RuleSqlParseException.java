package io.github.hectorvent.floci.services.iot.rules;

/**
 * Raised when a topic rule statement falls outside the SQL subset Floci evaluates. The
 * offending token and its zero-based offset in the statement are carried so callers can
 * name them in a warning or in an AWS {@code SqlParseException}.
 */
public class RuleSqlParseException extends RuntimeException {

    private final String token;
    private final int position;

    public RuleSqlParseException(String reason, String token, int position) {
        super(reason + ": '" + token + "' at position " + position);
        this.token = token;
        this.position = position;
    }

    public String token() {
        return token;
    }

    public int position() {
        return position;
    }
}
