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
 * @see <a href="https://docs.aws.amazon.com/resource-explorer/latest/userguide/using-search-query-syntax.html">
 *     Search query syntax reference</a>
 */
public final class QueryParser {

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
        // Track whether the current token has any content (including quoted empty strings)
        boolean hadContent = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (inQuotes) {
                if (c == '\\' && i + 1 < input.length() && input.charAt(i + 1) == '"') {
                    // Escaped quote inside quoted string
                    current.append('"');
                    i++;
                    hadContent = true;
                } else if (c == '"') {
                    // Closing quote — mark that we had content even if the string is empty
                    inQuotes = false;
                    hadContent = true;
                } else {
                    current.append(c);
                    hadContent = true;
                }
                continue;
            }

            // Unquoted context
            if (c == '"') {
                inQuotes = true;
                continue;
            }

            if (Character.isWhitespace(c)) {
                if (hadContent || !current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    hadContent = false;
                }
                continue;
            }

            current.append(c);
            hadContent = true;
        }

        if (hadContent || !current.isEmpty()) {
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

            int colonIndex = working.indexOf(':');
            FilterAttribute attribute = colonIndex >= 0
                    ? FilterAttribute.fromPrefix(working.substring(0, colonIndex))
                    : null;
            if (attribute != null) {
                String valuePortion = working.substring(colonIndex + 1);
                List<ParsedQuery.FilterValue> values = parseFilterValues(valuePortion);
                filters.add(new ParsedQuery.Filter(attribute, values, negated));
            } else {
                keywords.add(new ParsedQuery.Keyword(working, negated));
            }
        }

        return new ParsedQuery(keywords, filters);
    }

    private static List<ParsedQuery.FilterValue> parseFilterValues(String valuePortion) {
        List<ParsedQuery.FilterValue> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean escaped = false;

        for (int i = 0; i < valuePortion.length(); i++) {
            char c = valuePortion.charAt(i);

            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }

            if (c == '\\') {
                if (i == valuePortion.length() - 1) {
                    // Dangling backslash — treat literally
                    current.append(c);
                } else {
                    escaped = true;
                }
                continue;
            }

            if (c == ',') {
                values.add(toFilterValue(current.toString()));
                current.setLength(0);
                continue;
            }

            current.append(c);
        }

        values.add(toFilterValue(current.toString()));
        return values;
    }

    private static ParsedQuery.FilterValue toFilterValue(String raw) {
        if (raw.endsWith("*")) {
            return new ParsedQuery.FilterValue(raw.substring(0, raw.length() - 1), true);
        }
        return new ParsedQuery.FilterValue(raw, false);
    }
}
