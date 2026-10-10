package io.github.hectorvent.floci.services.iot;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.iot.model.IotIndexingConfiguration.ThingIndexing;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The part of the AWS IoT fleet indexing query language Floci evaluates, parsed into a predicate
 * over a thing's index document. Precedence is the one measured on AWS: NOT binds tightest, then
 * AND, then OR, and whitespace is an AND looser than OR, so {@code a OR b c} is
 * {@code (a OR b) AND c}. Values match case-insensitively, field names exactly. Syntax AWS accepts
 * but Floci does not evaluate is refused rather than matching nothing. A query is checked in the
 * order measured on AWS: syntax, then the 12-term limit, then fields and values, the first such
 * error in the query winning.
 */
final class IotFleetIndexQuery {

    private static final Set<String> FIELDS = Set.of("thingName", "thingId", "thingTypeName", "thingGroupNames",
            "connectivity.connected", "connectivity.clientId", "connectivity.disconnectReason");
    private static final String ATTRIBUTES = "attributes.";
    private static final Set<String> OPERATORS = Set.of("AND", "OR", "NOT", "-", ")");
    private static final Pattern COMPARISON = Pattern.compile("[<>]");
    private static final String RANGE_BOUND = "(\"(?:\\\\.|[^\"\\\\])*\"|[^ \\]}]+)";
    private static final Pattern RANGE = Pattern.compile("[\\[{] *" + RANGE_BOUND + " +(?:TO +)?" + RANGE_BOUND
            + " *[\\]}]");
    private static final int MAX_TERMS = 12;
    private static final int MAX_WILDCARDS = 2;

    private final String queryString;
    private final ThingIndexing indexing;
    private final List<String> tokens;
    private int position;
    private int terms;
    private AwsException deferred;

    private IotFleetIndexQuery(String queryString, ThingIndexing indexing) {
        this.queryString = queryString;
        this.indexing = indexing;
        this.tokens = tokenize();
    }

    static Predicate<JsonNode> parse(String queryString, ThingIndexing indexing) {
        IotFleetIndexQuery parser = new IotFleetIndexQuery(queryString, indexing);
        Predicate<JsonNode> query = parser.clauses();
        if (parser.position < parser.tokens.size()) {
            throw parser.syntaxError();
        }
        if (parser.terms > MAX_TERMS) {
            throw parser.parseError("number of terms cannot exceed " + MAX_TERMS + ", terms found: " + parser.terms);
        }
        if (parser.deferred != null) {
            throw parser.deferred;
        }
        return query;
    }

    /**
     * Words, quoted runs kept inside their word, parentheses, and a leading - or ! as negation. A
     * range opened right after a field's colon, or at the start of a word, runs to its closing
     * bracket as one word.
     */
    private List<String> tokenize() {
        List<String> found = new ArrayList<>();
        int i = 0;
        while (i < queryString.length()) {
            char c = queryString.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '(' || c == ')') {
                found.add(String.valueOf(c));
                i++;
            } else if (c == '-' || c == '!') {
                found.add("-");
                i++;
            } else {
                int start = i;
                boolean quoted = false;
                boolean range = false;
                while (i < queryString.length()) {
                    char next = queryString.charAt(i);
                    if (next == '\\') {
                        i++;
                    } else if (next == '"') {
                        quoted = !quoted;
                    } else if (!quoted && range && (next == ']' || next == '}')) {
                        i++;
                        break;
                    } else if (!quoted && !range && (next == '[' || next == '{')
                            && (i == start || queryString.charAt(i - 1) == ':')) {
                        range = true;
                    } else if (!quoted && !range && (Character.isWhitespace(next) || next == '(' || next == ')')) {
                        break;
                    }
                    i++;
                }
                if (quoted) {
                    throw syntaxError();
                }
                String word = queryString.substring(start, Math.min(i, queryString.length()));
                found.add(switch (word) {
                    case "&&" -> "AND";
                    case "||" -> "OR";
                    default -> word;
                });
            }
        }
        return found;
    }

    /** Clauses joined by whitespace, which AWS treats as an AND binding looser than OR. */
    private Predicate<JsonNode> clauses() {
        Predicate<JsonNode> query = or();
        while (position < tokens.size() && !")".equals(tokens.get(position))) {
            query = query.and(or());
        }
        return query;
    }

    private Predicate<JsonNode> or() {
        Predicate<JsonNode> query = and();
        while (accept("OR")) {
            query = query.or(and());
        }
        return query;
    }

    private Predicate<JsonNode> and() {
        Predicate<JsonNode> query = unary();
        while (accept("AND")) {
            query = query.and(unary());
        }
        return query;
    }

    private Predicate<JsonNode> unary() {
        if (accept("NOT") || accept("-")) {
            return primary().negate();
        }
        return primary();
    }

    private Predicate<JsonNode> primary() {
        if (position >= tokens.size()) {
            throw syntaxError();
        }
        String token = tokens.get(position++);
        if ("(".equals(token)) {
            Predicate<JsonNode> group = clauses();
            if (!accept(")")) {
                throw syntaxError();
            }
            return group;
        }
        if (OPERATORS.contains(token)) {
            throw syntaxError();
        }
        return term(token);
    }

    private Predicate<JsonNode> term(String token) {
        int colon = token.indexOf(':');
        String value = colon < 0 ? "" : token.substring(colon + 1);
        if (colon >= 0 && value.isEmpty()) {
            if (hasBracket(token) || !accept("(")) {
                throw syntaxError();
            }
            // AWS counts each value of a field group as a term, but not the field, and checks the field first.
            Predicate<JsonNode> refused;
            try {
                checkField(token.substring(0, colon));
                refused = defer(unsupported("field grouping"));
            } catch (AwsException e) {
                refused = defer(e);
            }
            clauses();
            if (!accept(")")) {
                throw syntaxError();
            }
            return refused;
        }
        terms++;
        boolean quoted = value.startsWith("\"");
        String rangeText = colon < 0 ? token : value;
        boolean range = rangeText.startsWith("[") || rangeText.startsWith("{");
        if ((quoted && (value.length() < 2 || !value.endsWith("\"")))
                || (!quoted && (value.startsWith("<") || value.startsWith(">")))
                || hasBracket(range ? token.substring(0, token.length() - rangeText.length()) : token)
                || (range && !isRange(rangeText))) {
            throw syntaxError();
        }
        try {
            return match(token, colon, value);
        } catch (AwsException e) {
            return defer(e);
        }
    }

    private Predicate<JsonNode> match(String token, int colon, String value) {
        if ("*".equals(token)) {
            return document -> true;
        }
        if (token.startsWith("+")) {
            throw parseError("unsupported operator - \"+\"");
        }
        Matcher comparison = COMPARISON.matcher(token);
        if (comparison.find() && (colon < 0 || comparison.start() < colon)) {
            checkField(token.substring(0, comparison.start()));
            throw unsupported("comparisons");
        }
        if (colon < 0) {
            throw unsupported("free text terms");
        }
        String field = token.substring(0, colon);
        checkField(field);
        Pattern pattern = valuePattern(field, value);
        return document -> {
            int dot = field.indexOf('.');
            JsonNode node = dot < 0 ? document.path(field)
                    : document.path(field.substring(0, dot)).path(field.substring(dot + 1));
            Iterable<JsonNode> values = node.isArray() ? node : node.isMissingNode() ? List.of() : List.of(node);
            for (JsonNode candidate : values) {
                if (pattern.matcher(candidate.asText()).matches()) {
                    return true;
                }
            }
            return false;
        };
    }

    /**
     * A field Floci indexes passes. One under connectivity, shadow or Device Defender first needs
     * that indexing on, with AWS's error: a classic shadow field needs REGISTRY_AND_SHADOW, a named
     * shadow field needs named shadow indexing and a shadow in its filter (otherwise an invalid field
     * name, as on AWS). Floci then refuses those it does not index. Anything else is the invalid
     * field name AWS reports.
     */
    private void checkField(String field) {
        if (field.startsWith(ATTRIBUTES) && field.length() > ATTRIBUTES.length()) {
            return;
        }
        String attribute;
        boolean enabled;
        if (field.startsWith("connectivity.")) {
            attribute = "Connectivity";
            enabled = "STATUS".equals(indexing.thingConnectivityIndexingMode());
        } else if (field.startsWith("shadow.")) {
            attribute = "Shadow";
            boolean named = "shadow.name".equals(field) || field.startsWith("shadow.name.");
            enabled = "REGISTRY_AND_SHADOW".equals(indexing.thingIndexingMode())
                    || named && "ON".equals(indexing.namedShadowIndexingMode());
            if (enabled && named) {
                // shadow.name.<shadow>.<field> exists only for a shadow in the named shadow filter.
                String[] parts = field.split("\\.", 4);
                if (parts.length < 4 || !"ON".equals(indexing.namedShadowIndexingMode())
                        || !indexing.namedShadowNames().contains(parts[2])) {
                    throw parseError("invalid field name, field name: " + field);
                }
            }
        } else if (field.startsWith("deviceDefender.")) {
            attribute = "Devicedefender";
            enabled = "VIOLATIONS".equals(indexing.deviceDefenderIndexingMode());
        } else if (FIELDS.contains(field)) {
            return;
        } else {
            throw parseError("invalid field name, field name: " + field);
        }
        if (!enabled) {
            throw new AwsException("InvalidRequestException", "Query includes one or more constraints for "
                    + attribute + " attribute, but " + attribute + " indexing is not enabled for AWS_Things index", 400);
        }
        if (!FIELDS.contains(field)) {
            throw unsupported("the field " + field);
        }
    }

    /**
     * A quoted value matches literally; otherwise * and ? are wildcards and a backslash escapes.
     * As on AWS an unquoted value takes at most two unescaped * and cannot start with an unescaped ?.
     */
    private Pattern valuePattern(String field, String value) {
        boolean quoted = value.startsWith("\"");
        if (!quoted) {
            switch (value.charAt(0)) {
                case '[', '{' -> throw unsupported("range queries");
                case '/' -> throw parseError("unsupported query type - regular expression");
                case '?' -> throw invalidValue("wildcard character (?) is not allowed at the start of the value",
                        field, value);
                default -> {
                }
            }
        }
        String text = quoted ? value.substring(1, value.length() - 1) : value;
        StringBuilder regex = new StringBuilder();
        int wildcards = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                regex.append(Pattern.quote(String.valueOf(text.charAt(++i))));
            } else if (!quoted && c == '*') {
                wildcards++;
                regex.append(".*");
            } else if (!quoted && c == '?') {
                regex.append('.');
            } else if (!quoted && c == '~') {
                throw parseError("unsupported query type - fuzzy");
            } else if (!quoted && c == '^') {
                throw parseError("unsupported query type - boost");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        if (wildcards > MAX_WILDCARDS) {
            throw invalidValue("too many wildcards (*). The maximum allowed per term is " + MAX_WILDCARDS,
                    field, value);
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
    }

    /** A [, ], { or } that is neither escaped nor quoted, which AWS's parser rejects outside a range. */
    private static boolean hasBracket(String text) {
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && "[]{}".indexOf(c) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** Two bounds, optionally joined by TO, in [ or { and ] or }, where TO itself is no bound. */
    private static boolean isRange(String text) {
        Matcher range = RANGE.matcher(text);
        return range.matches() && !"TO".equals(range.group(1)) && !"TO".equals(range.group(2));
    }

    /** Records the first error AWS reports only once the query parses and its terms are counted. */
    private Predicate<JsonNode> defer(AwsException error) {
        if (deferred == null) {
            deferred = error;
        }
        return document -> false;
    }

    private boolean accept(String token) {
        if (position < tokens.size() && token.equals(tokens.get(position))) {
            position++;
            return true;
        }
        return false;
    }

    private AwsException syntaxError() {
        return parseError("invalid syntax");
    }

    private AwsException parseError(String reason) {
        return new AwsException("InvalidQueryException",
                "Unable to parse query, " + reason + ", query string: " + queryString, 400);
    }

    private AwsException invalidValue(String reason, String field, String value) {
        return parseError("invalid value - " + reason + ", field name: " + field + ", value: " + value);
    }

    private AwsException unsupported(String construct) {
        return new AwsException("InvalidQueryException",
                "Floci does not support " + construct + " in fleet index queries, query string: " + queryString, 400);
    }
}
