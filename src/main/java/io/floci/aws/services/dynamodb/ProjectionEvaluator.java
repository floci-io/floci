package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.dynamodb.ExpressionEvaluator.Token;
import io.floci.aws.services.dynamodb.ExpressionEvaluator.TokenType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Applies a DynamoDB ProjectionExpression to a result item, returning a new
 * ObjectNode containing only the projected attributes.
 */
final class ProjectionEvaluator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProjectionEvaluator() {}

    static boolean hasProjection(String expression) {
        return expression != null && !expression.isBlank();
    }

    /**
     * Returns a new ObjectNode containing only the paths named in the expression.
     * Resolves #alias references via exprAttrNames. Handles dot-path and [n] list index segments.
     * Paths sharing a prefix merge into one reconstructed structure; projected list indices
     * compact to ascending source order, the way DynamoDB reconstructs projected items.
     */
    static ObjectNode project(JsonNode item, String projectionExpression, JsonNode exprAttrNames) {
        if (item == null || !hasProjection(projectionExpression)) {
            return (ObjectNode) item;
        }
        return projector(projectionExpression, exprAttrNames).apply(item);
    }

    /**
     * Validates and reads the expression once and returns a function that projects an item
     * as {@link #project} does, for a read that projects every item it returns.
     */
    static Function<JsonNode, ObjectNode> projector(String projectionExpression, JsonNode exprAttrNames) {
        if (!hasProjection(projectionExpression)) {
            return item -> (ObjectNode) item;
        }
        validateExpression(projectionExpression, exprAttrNames);
        PathTrie root = new PathTrie();
        for (List<Token> path : readPaths(projectionExpression)) {
            root.insert(resolvePath(path, exprAttrNames));
        }
        return item -> {
            if (item == null) {
                return null;
            }
            ObjectNode projected = projectMapEntries(item, root);
            return projected != null ? projected : MAPPER.createObjectNode();
        };
    }

    /**
     * Returns a new ObjectNode containing only the named top-level attributes.
     * Used by AttributesToGet (no alias resolution, no nested paths).
     */
    static ObjectNode trimToAttributes(ObjectNode item, Set<String> keep) {
        ObjectNode result = MAPPER.createObjectNode();
        item.fields().forEachRemaining(entry -> {
            if (keep.contains(entry.getKey())) {
                result.set(entry.getKey(), entry.getValue());
            }
        });
        return result;
    }

    static Set<String> topLevelAttributes(String projectionExpression, JsonNode exprAttrNames) {
        Set<String> attributes = new LinkedHashSet<>();
        for (List<Token> path : readPaths(projectionExpression)) {
            attributes.add(resolvePath(path, exprAttrNames).getFirst().name());
        }
        return Collections.unmodifiableSet(attributes);
    }

    static void validateExpression(String expression, JsonNode exprAttrNames) {
        validatePaths(expression, exprAttrNames);
    }

    /**
     * Rejects an undefined #alias, a reserved word, and a path that overlaps an earlier one.
     * DynamoDB checks each path in written order before the read, so a request that matches
     * nothing still fails.
     */
    static void validatePaths(String expression, JsonNode exprAttrNames) {
        if (expression == null || expression.isBlank()) {
            return;
        }
        List<List<PathSegment>> paths = new ArrayList<>();
        for (List<Token> path : readPaths(expression)) {
            List<PathSegment> segments = resolvePath(path, exprAttrNames, true);
            for (List<PathSegment> earlier : paths) {
                if (covers(earlier, segments) || covers(segments, earlier)) {
                    throw new AwsException("ValidationException",
                            "Invalid ProjectionExpression: Two document paths overlap with each other; "
                            + "must remove or rewrite one of these paths; path one: " + render(earlier)
                            + ", path two: " + render(segments), 400);
                }
            }
            paths.add(segments);
        }
    }

    private static boolean covers(List<PathSegment> outer, List<PathSegment> inner) {
        return outer.size() <= inner.size() && inner.subList(0, outer.size()).equals(outer);
    }

    // DynamoDB prints a document path as its elements inside brackets, with a list
    // index carrying brackets of its own: a.b is [a, b] and l[0] is [l, [0]].
    private static String render(List<PathSegment> segments) {
        StringBuilder rendered = new StringBuilder("[");
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0) {
                rendered.append(", ");
            }
            PathSegment segment = segments.get(i);
            rendered.append(segment.isIndex() ? "[" + segment.index() + "]" : segment.name());
        }
        return rendered.append(']').toString();
    }

    // ── Path reading ──

    /**
     * Reads the paths with the lexer of the other expressions: a name or #alias, then any
     * .name and [n] steps, the paths separated by commas. A path holds its name and NUMBER
     * tokens in order.
     */
    private static List<List<Token>> readPaths(String expression) {
        List<Token> tokens = ExpressionEvaluator.tokenize(expression);
        List<List<Token>> paths = new ArrayList<>();
        int pos = 0;
        while (true) {
            List<Token> path = new ArrayList<>();
            pos = readName(expression, tokens, pos, path);
            while (true) {
                TokenType type = tokens.get(pos).type();
                if (type == TokenType.DOT) {
                    pos = readName(expression, tokens, pos + 1, path);
                } else if (type == TokenType.LBRACKET) {
                    pos = expect(expression, tokens, pos + 1, TokenType.NUMBER);
                    path.add(tokens.get(pos - 1));
                    pos = expect(expression, tokens, pos, TokenType.RBRACKET);
                } else {
                    break;
                }
            }
            paths.add(path);
            if (tokens.get(pos).type() == TokenType.EOF) {
                return paths;
            }
            pos = expect(expression, tokens, pos, TokenType.COMMA);
        }
    }

    // Function names are plain names here: DynamoDB projects an attribute called size.
    private static int readName(String expression, List<Token> tokens, int pos, List<Token> path) {
        TokenType type = tokens.get(pos).type();
        if (type != TokenType.IDENTIFIER && type != TokenType.NAME_REF && type != TokenType.FUNCTION) {
            throw syntaxError(expression, tokens, pos);
        }
        path.add(tokens.get(pos));
        return pos + 1;
    }

    private static int expect(String expression, List<Token> tokens, int pos, TokenType type) {
        if (tokens.get(pos).type() != type) {
            throw syntaxError(expression, tokens, pos);
        }
        return pos + 1;
    }

    private static AwsException syntaxError(String expression, List<Token> tokens, int pos) {
        return new AwsException("ValidationException", "Invalid ProjectionExpression: Syntax error; "
                + ExpressionEvaluator.syntaxErrorDetail(expression, tokens, pos), 400);
    }

    // ── Path resolution ──

    /**
     * One step of a document path. The kind is fixed by the raw expression: a name token
     * (including a resolved #alias, whatever characters its value contains) addresses a map
     * key, and only a [n] suffix written in the expression itself addresses a list index.
     */
    private record PathSegment(String name, long index, boolean isIndex) {

        static PathSegment name(String name) {
            return new PathSegment(name, -1L, false);
        }

        static PathSegment index(long index) {
            return new PathSegment(null, index, true);
        }
    }

    private static List<PathSegment> resolvePath(List<Token> path, JsonNode exprAttrNames) {
        return resolvePath(path, exprAttrNames, false);
    }

    private static List<PathSegment> resolvePath(List<Token> path, JsonNode exprAttrNames,
                                                 boolean requireDefinedNames) {
        List<PathSegment> segments = new ArrayList<>();
        for (Token token : path) {
            if (token.type() == TokenType.NUMBER) {
                segments.add(PathSegment.index(listIndex(token.value())));
            } else {
                segments.add(PathSegment.name(resolveSegment(token.value(), exprAttrNames, requireDefinedNames)));
            }
        }
        return segments;
    }

    private static long listIndex(String digits) {
        if (digits.length() > 10 || Integer.MAX_VALUE < Long.parseLong(digits)) {
            throw new AwsException("ValidationException",
                    "Invalid ProjectionExpression: List index is not within the allowable range; "
                    + "index: [" + digits + "]", 400);
        }
        return Long.parseLong(digits);
    }

    private static String resolveSegment(String seg, JsonNode exprAttrNames, boolean requireDefinedNames) {
        if (!seg.startsWith("#")) {
            if (requireDefinedNames && DynamoDbReservedWords.isReserved(seg)) {
                throw DynamoDbReservedWords.reservedWord("ProjectionExpression", seg);
            }
            return seg;
        }
        JsonNode resolved = exprAttrNames != null ? exprAttrNames.get(seg) : null;
        if (resolved != null) {
            return resolved.asText();
        }
        if (requireDefinedNames) {
            throw ExpressionEvaluator.undefinedName("ProjectionExpression", seg);
        }
        return seg;
    }

    // ── Tree walking ──

    /**
     * Merged view of every projected path. Map-name children and list-index children are
     * kept separately; index children stay sorted so a projected list compacts to
     * ascending source order. A node marked terminal ends a path and copies the whole
     * subtree it points at.
     */
    private static final class PathTrie {
        private boolean terminal;
        private final Map<String, PathTrie> names = new LinkedHashMap<>();
        private final TreeMap<Long, PathTrie> indices = new TreeMap<>();

        void insert(List<PathSegment> segments) {
            PathTrie node = this;
            for (PathSegment seg : segments) {
                if (seg.isIndex()) {
                    node = node.indices.computeIfAbsent(seg.index(), k -> new PathTrie());
                } else {
                    node = node.names.computeIfAbsent(seg.name(), k -> new PathTrie());
                }
            }
            node.terminal = true;
        }
    }

    /**
     * Projects a typed attribute value through a trie node. Returns null when nothing
     * under this value matches, so the caller can drop the branch entirely.
     */
    private static JsonNode projectValue(JsonNode src, PathTrie node) {
        if (node.terminal) {
            return src.deepCopy();
        }
        if (!node.indices.isEmpty() && src.has("L")) {
            JsonNode list = src.get("L");
            ArrayNode projectedList = MAPPER.createArrayNode();
            for (Map.Entry<Long, PathTrie> entry : node.indices.entrySet()) {
                long idx = entry.getKey();
                if (idx >= list.size()) {
                    continue;
                }
                JsonNode projected = projectValue(list.get((int) idx), entry.getValue());
                if (projected != null) {
                    projectedList.add(projected);
                }
            }
            if (projectedList.isEmpty()) {
                return null;
            }
            ObjectNode wrapper = MAPPER.createObjectNode();
            wrapper.set("L", projectedList);
            return wrapper;
        }
        if (!node.names.isEmpty()) {
            if (src.has("M")) {
                JsonNode projectedMap = projectMapEntries(src.get("M"), node);
                if (projectedMap == null) {
                    return null;
                }
                ObjectNode wrapper = MAPPER.createObjectNode();
                wrapper.set("M", projectedMap);
                return wrapper;
            }
            // Defensive fallback for plain (non-DynamoDB-typed) nested objects.
            return projectMapEntries(src, node);
        }
        return null;
    }

    private static ObjectNode projectMapEntries(JsonNode map, PathTrie node) {
        ObjectNode projectedMap = MAPPER.createObjectNode();
        for (Map.Entry<String, PathTrie> entry : node.names.entrySet()) {
            JsonNode child = map.get(entry.getKey());
            if (child == null) {
                continue;
            }
            JsonNode projected = projectValue(child, entry.getValue());
            if (projected != null) {
                projectedMap.set(entry.getKey(), projected);
            }
        }
        return projectedMap.isEmpty() ? null : projectedMap;
    }
}
