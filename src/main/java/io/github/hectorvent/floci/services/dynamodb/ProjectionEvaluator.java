package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Applies a DynamoDB ProjectionExpression to a result item, returning a new
 * ObjectNode containing only the projected attributes.
 */
final class ProjectionEvaluator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // The highest list index real DynamoDB accepts in a document path.
    private static final long MAX_LIST_INDEX = 4_294_967_294L;

    private ProjectionEvaluator() {}

    /**
     * Returns a new ObjectNode containing only the paths named in the expression.
     * Resolves #alias references via exprAttrNames. Handles dot-path and [n] list index segments.
     * Paths sharing a prefix merge into one reconstructed structure; projected list indices
     * compact to ascending source order, the way DynamoDB reconstructs projected items.
     */
    static ObjectNode project(JsonNode item, String projectionExpression, JsonNode exprAttrNames) {
        if (item == null || projectionExpression == null || projectionExpression.isBlank()) {
            return (ObjectNode) item;
        }
        validateExpression(projectionExpression, exprAttrNames);
        PathTrie root = new PathTrie();
        for (String rawPath : splitProjectionPaths(projectionExpression)) {
            List<PathSegment> segments = resolvePath(rawPath.trim(), exprAttrNames);
            if (segments.isEmpty()) {
                continue;
            }
            root.insert(segments);
        }
        ObjectNode projected = projectMapEntries(item, root);
        return projected != null ? projected : MAPPER.createObjectNode();
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
        var attributes = new java.util.HashSet<String>();
        for (String rawPath : splitProjectionPaths(projectionExpression)) {
            List<PathSegment> segments = resolvePath(rawPath.trim(), exprAttrNames);
            if (!segments.isEmpty()) {
                attributes.add(segments.getFirst().name());
            }
        }
        return Set.copyOf(attributes);
    }

    static void validateSyntax(String expression, String expressionType) {
        if (expression == null || expression.isBlank()) return;
        char first = expression.charAt(0);
        if (!Character.isLetterOrDigit(first) && first != '#' && first != '_') {
            String near = expression.length() > 2 ? expression.substring(1, 3) : expression.substring(1);
            throw syntaxError(expressionType, String.valueOf(first), near);
        }
    }

    private static AwsException syntaxError(String expressionType, String token, String near) {
        return new AwsException("ValidationException",
                "Invalid " + expressionType + ": Syntax error; token: \"" + token + "\", near: \"" + near + "\"", 400);
    }

    static void validateExpression(String expression, JsonNode exprAttrNames) {
        validateSyntax(expression, "ProjectionExpression");
        DynamoDbReservedWords.check(expression, "ProjectionExpression");
        validatePaths(expression, exprAttrNames);
    }

    /**
     * Rejects an undefined #alias, and any two paths where one covers the other.
     * DynamoDB applies both before the read, so a request that matches nothing still fails.
     */
    static void validatePaths(String expression, JsonNode exprAttrNames) {
        if (expression == null || expression.isBlank()) {
            return;
        }
        List<List<PathSegment>> paths = new ArrayList<>();
        for (String rawPath : splitProjectionPaths(expression)) {
            List<PathSegment> segments = resolvePath(rawPath.trim(), exprAttrNames, true);
            if (!segments.isEmpty()) {
                paths.add(segments);
            }
        }
        for (int i = 0; i < paths.size(); i++) {
            List<PathSegment> first = paths.get(i);
            for (int j = i + 1; j < paths.size(); j++) {
                List<PathSegment> second = paths.get(j);
                if (covers(first, second) || covers(second, first)) {
                    throw new AwsException("ValidationException",
                            "Invalid ProjectionExpression: Two document paths overlap with each other; "
                            + "must remove or rewrite one of these paths; path one: " + render(first)
                            + ", path two: " + render(second), 400);
                }
            }
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

    // ── Path splitting ──

    private static List<String> splitProjectionPaths(String expression) {
        List<String> paths = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) {
                paths.add(expression.substring(start, i).trim());
                start = i + 1;
            }
        }
        if (start < expression.length()) {
            paths.add(expression.substring(start).trim());
        }
        return paths;
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

    private static List<PathSegment> resolvePath(String path, JsonNode exprAttrNames) {
        return resolvePath(path, exprAttrNames, false);
    }

    private static List<PathSegment> resolvePath(String path, JsonNode exprAttrNames,
                                                 boolean requireDefinedNames) {
        List<PathSegment> segments = new ArrayList<>();
        // Tokenize on dots, preserving [n] bracket indices
        String[] parts = path.split("\\.");
        for (String part : parts) {
            // A part may end with [n] index(es) like "list[0]" or "[0]"
            int bracketIdx = part.indexOf('[');
            if (bracketIdx >= 0) {
                String name = part.substring(0, bracketIdx);
                if (!name.isEmpty()) {
                    segments.add(PathSegment.name(resolveSegment(name, exprAttrNames, requireDefinedNames)));
                }
                // Parse each [n] suffix
                String rest = part.substring(bracketIdx);
                int i = 0;
                while (i < rest.length() && rest.charAt(i) == '[') {
                    int close = rest.indexOf(']', i);
                    if (close < 0) break;
                    String content = rest.substring(i + 1, close);
                    validateListIndex(content);
                    segments.add(PathSegment.index(Long.parseLong(content)));
                    i = close + 1;
                }
            } else {
                segments.add(PathSegment.name(resolveSegment(part, exprAttrNames, requireDefinedNames)));
            }
        }
        return segments;
    }

    // DynamoDB rejects a non-numeric bracket segment and a leading zero as syntax
    // errors, and a numeric index above 4294967294 as out of the allowable range,
    // all at expression level. Verified on real DynamoDB (us-east-1, 2026-09-05).
    private static void validateListIndex(String content) {
        if (content.isEmpty()) {
            throw syntaxError("ProjectionExpression", "]", "[]");
        }
        final var contentLength = content.length();
        for (var i = 0; i < contentLength; i++) {
            var c = content.charAt(i);
            if (c < '0' || '9' < c) {
                var near = "[" + content + "]";
                throw syntaxError("ProjectionExpression", String.valueOf(c),
                        near.substring(0, Math.min(3, near.length())));
            }
        }
        if (contentLength > 1 && content.charAt(0) == '0') {
            throw syntaxError("ProjectionExpression", "0",
                    content.substring(0, Math.min(3, contentLength)));
        }
        if (contentLength > 10 || Long.parseLong(content) > MAX_LIST_INDEX) {
            throw new AwsException("ValidationException",
                    "Invalid ProjectionExpression: List index is not within the allowable range; "
                    + "index: [" + content + "]", 400);
        }
    }

    private static String resolveSegment(String seg, JsonNode exprAttrNames, boolean requireDefinedNames) {
        if (!seg.startsWith("#")) {
            return seg;
        }
        JsonNode resolved = exprAttrNames != null ? exprAttrNames.get(seg) : null;
        if (resolved != null) {
            return resolved.asText();
        }
        if (requireDefinedNames) {
            throw new AwsException("ValidationException",
                    "Invalid ProjectionExpression: An expression attribute name used in the document path "
                    + "is not defined; attribute name: " + seg, 400);
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
        // Long keys: the allowable index range (up to 4294967294) exceeds Integer.MAX_VALUE.
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
