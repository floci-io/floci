package io.github.hectorvent.floci.services.resourceexplorer2.query;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand-written character-by-character parser for Resource Explorer query syntax.
 *
 * <p>Supports: filter prefixes ({@code service:}, {@code region:}, etc.),
 * comma-OR, negation ({@code -}), wildcards ({@code *}), quoted strings,
 * backslash escaping, and free-form text keywords.
 *
 * <p>Escapes survive tokenization intact and are resolved only where the meaning of the
 * character is decided — splitting a filter's comma-separated values, finding the colon that
 * separates a filter prefix from its value, and recognising a trailing wildcard. That ordering
 * is what makes {@code tag.key:comma\,literal} one value rather than two, and
 * {@code "my\-key\-word"} a keyword rather than a negation.
 *
 * @see <a href="https://docs.aws.amazon.com/resource-explorer/latest/userguide/using-search-query-syntax.html">
 *     Search query syntax reference</a>
 */
public final class QueryParser {

    /**
     * Characters that carry meaning to the parser and therefore have to be neutralised when they
     * appear inside a quoted phrase, which AWS defines as literal text.
     */
    private static final String OPERATOR_CHARACTERS = "*\"-:=\\,";

    private QueryParser() {}

    public static ParsedQuery parse(String input) {
        if (input == null || input.isBlank()) {
            return new ParsedQuery(List.of(), List.of());
        }
        List<String> tokens = tokenize(input);
        return classify(tokens);
    }

    // Rejects free-form text — ListResources only accepts filter prefixes.
    public static ParsedQuery parseFilterOnly(String input) {
        ParsedQuery query = parse(input);
        if (!query.keywords().isEmpty()) {
            throw new IllegalArgumentException(
                    "ListResources FilterString does not support free-form text. " +
                    "Use only filter prefixes (e.g., service:ec2, region:us-east-1).");
        }
        return query;
    }

    static List<String> tokenize(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        // Tracks whether the current token has any content, including a quoted empty string.
        boolean hadContent = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (c == '\\' && i + 1 < input.length()) {
                current.append(c).append(input.charAt(i + 1));
                i++;
                hadContent = true;
                continue;
            }

            if (c == '"') {
                inQuotes = !inQuotes;
                hadContent = true;
                continue;
            }

            if (!inQuotes && Character.isWhitespace(c)) {
                if (hadContent) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    hadContent = false;
                }
                continue;
            }

            if (inQuotes && OPERATOR_CHARACTERS.indexOf(c) >= 0) {
                current.append('\\');
            }
            current.append(c);
            hadContent = true;
        }

        if (hadContent) {
            tokens.add(current.toString());
        }

        return tokens;
    }

    private static ParsedQuery classify(List<String> tokens) {
        List<ParsedQuery.Keyword> keywords = new ArrayList<>();
        List<ParsedQuery.Filter> filters = new ArrayList<>();

        for (String token : tokens) {
            boolean negated = false;
            String working = token;

            if (working.startsWith("-")) {
                negated = true;
                working = working.substring(1);
            }

            int colonIndex = indexOfUnescaped(working, ':');
            FilterAttribute attribute = colonIndex >= 0
                    ? FilterAttribute.fromPrefix(unescape(working.substring(0, colonIndex)))
                    : null;
            if (attribute != null) {
                String valuePortion = working.substring(colonIndex + 1);
                List<ParsedQuery.FilterValue> values = parseFilterValues(valuePortion);
                filters.add(new ParsedQuery.Filter(attribute, values, negated));
            } else {
                keywords.add(new ParsedQuery.Keyword(toKeywordValue(working), negated));
            }
        }

        return new ParsedQuery(keywords, filters);
    }

    /**
     * A trailing wildcard is dropped rather than recorded: keyword matching is a substring test,
     * so {@code ec2*} and {@code ec2} already select the same resources. Dropping it only where it
     * is unescaped keeps a literal {@code \*} searchable.
     */
    private static String toKeywordValue(String raw) {
        String value = endsWithUnescaped(raw, '*') ? raw.substring(0, raw.length() - 1) : raw;
        return unescape(value);
    }

    private static List<ParsedQuery.FilterValue> parseFilterValues(String valuePortion) {
        List<ParsedQuery.FilterValue> values = new ArrayList<>();
        for (String raw : splitUnescaped(valuePortion, ',')) {
            values.add(toFilterValue(raw));
        }
        return values;
    }

    private static ParsedQuery.FilterValue toFilterValue(String raw) {
        if (endsWithUnescaped(raw, '*')) {
            return new ParsedQuery.FilterValue(unescape(raw.substring(0, raw.length() - 1)), true);
        }
        return new ParsedQuery.FilterValue(unescape(raw), false);
    }

    private static int indexOfUnescaped(String value, char target) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == target) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> splitUnescaped(String value, char separator) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                current.append(c).append(value.charAt(i + 1));
                i++;
                continue;
            }
            if (c == separator) {
                parts.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        parts.add(current.toString());
        return parts;
    }

    private static boolean endsWithUnescaped(String value, char target) {
        if (value.isEmpty() || value.charAt(value.length() - 1) != target) {
            return false;
        }
        int backslashes = 0;
        for (int i = value.length() - 2; i >= 0 && value.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        return backslashes % 2 == 0;
    }

    /** Resolves escape sequences. A backslash with nothing after it is literal text, as AWS treats it. */
    private static String unescape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                out.append(value.charAt(i + 1));
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }
}
