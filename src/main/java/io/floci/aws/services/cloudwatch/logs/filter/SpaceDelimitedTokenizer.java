package io.floci.aws.services.cloudwatch.logs.filter;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a space-delimited CloudWatch Logs message into fields on whitespace, keeping text
 * enclosed in double quotes or square brackets together without the delimiters.
 */
final class SpaceDelimitedTokenizer {

    private SpaceDelimitedTokenizer() {
    }

    /**
     * Splits {@code message} into fields. When {@code limit} is positive, the field at that
     * one-based position takes the remainder of the line as written if non-blank text follows.
     */
    static List<String> split(String message, int limit) {
        List<String> fields = new ArrayList<>();
        int index = 0;
        int length = message.length();
        while (index < length) {
            index = skipWhitespace(message, index, length);
            if (index >= length) {
                break;
            }
            char openingChar = message.charAt(index);
            char closingDelimiter = closingDelimiterFor(openingChar);
            int closingIndex = closingDelimiter == '\0' ? -1 : message.indexOf(closingDelimiter, index + 1);
            boolean isDelimited = closingIndex >= 0;
            int end = isDelimited ? closingIndex + 1 : findUnquotedTokenEnd(message, index, length);

            boolean isAtLimitField = limit > 0 && fields.size() == limit - 1;
            if (isAtLimitField && hasTrailingContent(message, end, length)) {
                fields.add(message.substring(index));
                break;
            }
            fields.add(isDelimited ? message.substring(index + 1, end - 1) : message.substring(index, end));
            index = end;
        }
        return fields;
    }

    private static boolean hasTrailingContent(String message, int start, int length) {
        return skipWhitespace(message, start, length) < length;
    }

    private static int skipWhitespace(String message, int start, int length) {
        int index = start;
        while (index < length && Character.isWhitespace(message.charAt(index))) {
            index++;
        }
        return index;
    }

    private static char closingDelimiterFor(char openingChar) {
        if (openingChar == '"') {
            return '"';
        }
        if (openingChar == '[') {
            return ']';
        }
        return '\0';
    }

    private static int findUnquotedTokenEnd(String message, int start, int length) {
        int end = start;
        while (end < length && !Character.isWhitespace(message.charAt(end))) {
            end++;
        }
        return end;
    }
}
