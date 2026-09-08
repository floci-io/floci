package io.github.hectorvent.floci.services.cloudwatch.logs.filter;

/** A position in the pattern text for the recursive descent parsers, with the small reads they share. */
final class PatternCursor {

    private final String text;
    private int position;

    PatternCursor(String text) {
        this.text = text;
    }

    boolean atEnd() {
        return position >= text.length();
    }

    char peek() {
        return atEnd() ? '\0' : text.charAt(position);
    }

    boolean lookingAt(String token) {
        return text.startsWith(token, position);
    }

    char next() {
        if (atEnd()) {
            throw error("unexpected end of pattern");
        }
        return text.charAt(position++);
    }

    void skipWhitespace() {
        while (!atEnd() && Character.isWhitespace(peek())) {
            position++;
        }
    }

    void expect(char c) {
        skipWhitespace();
        if (peek() != c) {
            throw error("expected '" + c + "'");
        }
        position++;
    }

    /** Consumes {@code token} if it is next, reporting whether it was. */
    boolean consume(String token) {
        if (lookingAt(token)) {
            position += token.length();
            return true;
        }
        return false;
    }

    /** Reads a double-quoted string whose opening quote is next; {@code \"} and {@code \\} are escapes. */
    String quoted() {
        expect('"');
        StringBuilder value = new StringBuilder();
        while (true) {
            if (atEnd()) {
                throw error("unterminated quoted string");
            }
            char c = next();
            if (c == '"') {
                return value.toString();
            }
            if (c == '\\' && !atEnd() && (peek() == '"' || peek() == '\\')) {
                c = next();
            }
            value.append(c);
        }
    }

    /** Reads a {@code %regex%} whose opening percent sign is next, returning the compiled regex. */
    AwsRegex regex() {
        expect('%');
        int start = position;
        while (!atEnd() && peek() != '%') {
            position++;
        }
        if (atEnd()) {
            throw error("unterminated regular expression, expected a closing '%'");
        }
        String body = text.substring(start, position);
        position++;
        return AwsRegex.compile(body);
    }

    /** Reads an identifier: a letter or underscore followed by letters, digits and underscores. */
    String identifier() {
        skipWhitespace();
        int start = position;
        if (atEnd() || !(Character.isLetter(peek()) || peek() == '_')) {
            throw error("expected a field name");
        }
        while (!atEnd() && (Character.isLetterOrDigit(peek()) || peek() == '_')) {
            position++;
        }
        return text.substring(start, position);
    }

    /** Reads a bare value up to whitespace or one of the delimiters the enclosing syntax uses. */
    String bareValue(String delimiters) {
        int start = position;
        while (!atEnd() && !Character.isWhitespace(peek()) && delimiters.indexOf(peek()) < 0) {
            position++;
        }
        if (start == position) {
            throw error("expected a value");
        }
        return text.substring(start, position);
    }

    void expectEnd() {
        skipWhitespace();
        if (!atEnd()) {
            throw error("unexpected '" + text.substring(position) + "'");
        }
    }

    FilterPatternException error(String what) {
        return new FilterPatternException("Invalid filter pattern at position " + position + ": " + what);
    }
}
