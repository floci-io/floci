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
 * but Floci does not evaluate is refused rather than matching nothing.
 */
final class IotFleetIndexQuery {

    private static final Set<String> FIELDS = Set.of("thingName", "thingId", "thingTypeName", "thingGroupNames",
            "connectivity.connected", "connectivity.clientId", "connectivity.disconnectReason");
    private static final String ATTRIBUTES = "attributes.";
    private static final Set<String> OPERATORS = Set.of("AND", "OR", "NOT", "-", ")");
    private static final Pattern COMPARISON = Pattern.compile("[<>]");

    private final String queryString;
    private final ThingIndexing indexing;
    private final List<String> tokens;
    private int position;

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
        return query;
    }

    /** Words, quoted runs kept inside their word, parentheses, and a leading - or ! as negation. */
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
                while (i < queryString.length()) {
                    char next = queryString.charAt(i);
                    if (next == '\\') {
                        i++;
                    } else if (next == '"') {
                        quoted = !quoted;
                    } else if (!quoted && (Character.isWhitespace(next) || next == '(' || next == ')')) {
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
        if ("*".equals(token)) {
            return document -> true;
        }
        if (token.startsWith("+")) {
            throw parseError("unsupported operator - \"+\"");
        }
        int colon = token.indexOf(':');
        Matcher comparison = COMPARISON.matcher(token);
        if (comparison.find() && (colon < 0 || comparison.start() < colon)) {
            checkField(token.substring(0, comparison.start()));
            throw unsupported("comparisons");
        }
        if (colon < 0) {
            throw unsupported("free text terms");
        }
        String field = token.substring(0, colon);
        String value = token.substring(colon + 1);
        checkField(field);
        if (value.isEmpty()) {
            if (accept("(") && tokens.subList(position, tokens.size()).contains(")")) {
                throw unsupported("field grouping");
            }
            throw syntaxError();
        }
        Pattern pattern = valuePattern(value);
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
     * that indexing on, with AWS's error; Floci then refuses those it does not index. Anything else
     * is the invalid field name AWS reports.
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
            enabled = "REGISTRY_AND_SHADOW".equals(indexing.thingIndexingMode())
                    || "ON".equals(indexing.namedShadowIndexingMode());
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

    /** A quoted value matches literally; otherwise * and ? are wildcards and a backslash escapes. */
    private Pattern valuePattern(String value) {
        boolean quoted = value.startsWith("\"");
        if (quoted && (value.length() < 2 || !value.endsWith("\""))) {
            throw syntaxError();
        }
        if (!quoted) {
            switch (value.charAt(0)) {
                case '[', '{' -> throw unsupported("range queries");
                case '<', '>' -> throw syntaxError();
                case '/' -> throw parseError("unsupported query type - regular expression");
                default -> {
                }
            }
            if (value.contains("~")) {
                throw parseError("unsupported query type - fuzzy");
            }
            if (value.contains("^")) {
                throw parseError("unsupported query type - boost");
            }
        }
        String text = quoted ? value.substring(1, value.length() - 1) : value;
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                regex.append(Pattern.quote(String.valueOf(text.charAt(++i))));
            } else if (!quoted && c == '*') {
                regex.append(".*");
            } else if (!quoted && c == '?') {
                regex.append('.');
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
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

    private AwsException unsupported(String construct) {
        return new AwsException("InvalidQueryException",
                "Floci does not support " + construct + " in fleet index queries, query string: " + queryString, 400);
    }
}
