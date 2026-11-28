package io.floci.aws.services.cloudwatch.logs.filter;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The regular expression subset CloudWatch Logs accepts between percent signs: letters, digits, a
 * short list of symbols, the operators {@code ^ $ ? [ ] { } | \ * + .} and the escapes {@code \d \D
 * \s \S \w \W \xhh}. Parentheses, other symbols and characters outside ASCII are rejected, as AWS
 * rejects them. What passes is Java regex syntax with the same meaning, so it compiles as is.
 */
final class AwsRegex {

    private static final String SYMBOLS = ":_#=@/;,- ";
    private static final String OPERATORS = "^$?[]{}|\\*+.";
    private static final String ESCAPES = "dDsSwW";

    private final Pattern pattern;

    private AwsRegex(Pattern pattern) {
        this.pattern = pattern;
    }

    static AwsRegex compile(String body) {
        if (body.isEmpty()) {
            throw new FilterPatternException("Invalid filter pattern: empty regular expression");
        }
        validateBody(body);
        try {
            return new AwsRegex(Pattern.compile(body));
        } catch (PatternSyntaxException e) {
            throw new FilterPatternException("Invalid filter pattern: bad regular expression '" + body + "': "
                    + e.getDescription());
        }
    }

    private static void validateBody(String body) {
        boolean inCharacterClass = false;
        boolean previousWasQuantifier = false;
        for (int index = 0; index < body.length(); index++) {
            char currentChar = body.charAt(index);
            if (currentChar == '\\') {
                index = checkEscape(body, index);
                previousWasQuantifier = false;
                continue;
            }
            validateCharacter(currentChar);
            if (currentChar == '[') {
                inCharacterClass = true;
            } else if (currentChar == ']') {
                inCharacterClass = false;
            }
            boolean isPossessiveQuantifier = !inCharacterClass && currentChar == '+' && previousWasQuantifier;
            if (isPossessiveQuantifier) {
                throw new FilterPatternException("Invalid filter pattern: possessive quantifiers are not supported");
            }
            previousWasQuantifier = !inCharacterClass && isQuantifierCharacter(currentChar);
        }
    }

    private static void validateCharacter(char currentChar) {
        if (currentChar > 127) {
            throw new FilterPatternException(
                    "Invalid filter pattern: regular expressions support ASCII only, found '" + currentChar + "'");
        }
        if (!isAllowedCharacter(currentChar)) {
            throw new FilterPatternException(
                    "Invalid filter pattern: '" + currentChar + "' is not supported in a regular expression");
        }
    }

    private static boolean isAllowedCharacter(char currentChar) {
        return Character.isLetterOrDigit(currentChar)
                || SYMBOLS.indexOf(currentChar) >= 0
                || OPERATORS.indexOf(currentChar) >= 0;
    }

    private static boolean isQuantifierCharacter(char currentChar) {
        return currentChar == '*' || currentChar == '+' || currentChar == '?' || currentChar == '}';
    }

    /** Validates the escape starting at {@code index} and returns the index of its last character. */
    private static int checkEscape(String body, int index) {
        if (index + 1 >= body.length()) {
            throw new FilterPatternException("Invalid filter pattern: regular expression ends with a backslash");
        }
        char escaped = body.charAt(index + 1);
        if (escaped == 'x') {
            if (hasTwoHexDigitsAfter(body, index + 1)) {
                return index + 3;
            }
            throw new FilterPatternException("Invalid filter pattern: \\x needs two hexadecimal digits");
        }
        if (Character.isLetterOrDigit(escaped)) {
            if (ESCAPES.indexOf(escaped) < 0) {
                throw new FilterPatternException("Invalid filter pattern: '\\" + escaped + "' is not a supported escape");
            }
            return index + 1;
        }
        // A backslash gives an operator its literal meaning; it does not admit characters AWS
        // rejects, so an escaped parenthesis or exclamation mark is still rejected.
        if (OPERATORS.indexOf(escaped) < 0 && SYMBOLS.indexOf(escaped) < 0) {
            throw new FilterPatternException("Invalid filter pattern: '" + escaped
                    + "' is not supported in a regular expression, escaped or not");
        }
        return index + 1;
    }

    private static boolean hasTwoHexDigitsAfter(String body, int xIndex) {
        return xIndex + 2 < body.length()
                && isHex(body.charAt(xIndex + 1))
                && isHex(body.charAt(xIndex + 2));
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    boolean find(String text) {
        return pattern.matcher(text).find();
    }
}
