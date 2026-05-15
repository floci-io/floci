package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Applies a DynamoDB ProjectionExpression to a result item, returning a new
 * ObjectNode containing only the projected attributes.
 */
final class ProjectionEvaluator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProjectionEvaluator() {}

    /**
     * Returns a new ObjectNode containing only the paths named in the expression.
     * Resolves #alias references via exprAttrNames. Handles dot-path and [n] list index segments.
     */
    static ObjectNode project(JsonNode item, String projectionExpression, JsonNode exprAttrNames) {
        if (item == null || projectionExpression == null || projectionExpression.isBlank()) {
            return (ObjectNode) item;
        }
        validateProjectionExpression(projectionExpression);
        DynamoDbReservedWords.check(projectionExpression, "ProjectionExpression");
        ObjectNode result = MAPPER.createObjectNode();
        for (String rawPath : splitProjectionPaths(projectionExpression)) {
            List<String> segments = resolvePath(rawPath.trim(), exprAttrNames);
            if (segments.isEmpty()) continue;
            copyPath(item, result, segments, 0);
        }
        return result;
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

    static void validateSyntax(String expression, String expressionType) {
        if (expression == null || expression.isBlank()) return;
        char first = expression.charAt(0);
        if (!Character.isLetterOrDigit(first) && first != '#' && first != '_') {
            String token = String.valueOf(first);
            String near = expression.length() > 2 ? expression.substring(1, 3) : expression.substring(1);
            throw new AwsException("ValidationException",
                    "Invalid " + expressionType + ": Syntax error; token: \"" + token + "\", near: \"" + near + "\"", 400);
        }
    }

    private static void validateProjectionExpression(String expression) {
        validateSyntax(expression, "ProjectionExpression");
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

    private static List<String> resolvePath(String path, JsonNode exprAttrNames) {
        List<String> segments = new ArrayList<>();
        // Tokenize on dots, preserving [n] bracket indices
        String[] parts = path.split("\\.");
        for (String part : parts) {
            // A part may end with [n] index(es) like "list[0]" or "[0]"
            int bracketIdx = part.indexOf('[');
            if (bracketIdx >= 0) {
                String name = part.substring(0, bracketIdx);
                if (!name.isEmpty()) {
                    segments.add(resolveSegment(name, exprAttrNames));
                }
                // Parse each [n] suffix
                String rest = part.substring(bracketIdx);
                int i = 0;
                while (i < rest.length() && rest.charAt(i) == '[') {
                    int close = rest.indexOf(']', i);
                    if (close < 0) break;
                    segments.add(rest.substring(i, close + 1)); // e.g. "[0]"
                    i = close + 1;
                }
            } else {
                segments.add(resolveSegment(part, exprAttrNames));
            }
        }
        return segments;
    }

    private static String resolveSegment(String seg, JsonNode exprAttrNames) {
        if (seg.startsWith("#") && exprAttrNames != null) {
            JsonNode resolved = exprAttrNames.get(seg);
            return resolved != null ? resolved.asText() : seg;
        }
        return seg;
    }

    // ── Tree walking ──

    private static void copyPath(JsonNode src, ObjectNode dest, List<String> segments, int idx) {
        if (idx >= segments.size()) return;
        String seg = segments.get(idx);

        if (seg.matches("\\[\\d+\\]")) {
            // List index at root level — handled by the caller
            return;
        }

        JsonNode child = src.get(seg);
        if (child == null) return;

        if (idx == segments.size() - 1) {
            // Leaf: copy the whole attribute node
            dest.set(seg, child);
        } else {
            String nextSeg = segments.get(idx + 1);
            if (nextSeg.matches("\\[\\d+\\]")) {
                // Next is a list index — extract just that element
                int listIdx = Integer.parseInt(nextSeg.substring(1, nextSeg.length() - 1));
                JsonNode listNode = child.has("L") ? child.get("L") : child;
                if (listNode.isArray() && listIdx < listNode.size()) {
                    JsonNode element = listNode.get(listIdx);
                    // Build {"L": [element]} wrapper
                    ObjectNode wrapper = MAPPER.createObjectNode();
                    ArrayNode newList = MAPPER.createArrayNode();
                    newList.add(element);
                    wrapper.set("L", newList);
                    dest.set(seg, wrapper);
                }
            } else if (child.has("M")) {
                // Nested map — recurse
                ObjectNode nestedDest = MAPPER.createObjectNode();
                copyPath(child.get("M"), nestedDest, segments, idx + 1);
                ObjectNode wrapper = MAPPER.createObjectNode();
                wrapper.set("M", nestedDest);
                dest.set(seg, wrapper);
            } else if (child.isObject()) {
                ObjectNode nestedDest = MAPPER.createObjectNode();
                copyPath(child, nestedDest, segments, idx + 1);
                dest.set(seg, nestedDest);
            } else {
                dest.set(seg, child);
            }
        }
    }
}
