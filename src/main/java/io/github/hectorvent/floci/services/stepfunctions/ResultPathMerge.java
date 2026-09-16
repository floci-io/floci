package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies a state's JSONPath {@code ResultPath}, merging the state result into the effective input.
 * Extracted from {@link AslExecutor} so the merge rules (including the AWS
 * {@code States.ResultPathMatchFailure} case) are unit-testable without the Vert.x-bound executor.
 *
 * <p>AWS: {@code ResultPath} names where the result is inserted into the state's raw input. A
 * {@code ResultPath} is a Reference Path, and a dotted field ({@code $.a}), a bracket index
 * ({@code $[1]}, {@code $.a[0].b}, {@code $[0][1]}), and a single-quoted bracket field name
 * ({@code $.abc.['def ghi']}, AWS's documented form for a field name a dotted segment cannot carry)
 * are all legal segments; the ASL spec excludes only the {@code @}, {@code ..}, {@code ,},
 * {@code :}, and {@code ?} operators from a reference path, not {@code [n]} or a quoted field name.
 * When the input shape does not match the path, for example a {@code $.field}
 * path against a non-object input, or a {@code [n]} index that an existing array does not have, the
 * path cannot apply and the interpreter fails with {@code States.ResultPathMatchFailure}. It does
 * NOT silently discard the input.
 *
 * @see <a href="https://states-language.net/spec.html#filters">ASL ResultPath</a>
 */
public final class ResultPathMerge {

    private ResultPathMerge() {
    }

    /** Raised when a {@code ResultPath} cannot be applied to the given input; the caller maps it to {@code States.ResultPathMatchFailure}. */
    public static final class ResultPathMatchException extends RuntimeException {
        public ResultPathMatchException(String message) {
            super(message);
        }
    }

    /**
     * Merge {@code result} into {@code input} at {@code resultPath}.
     *
     * <ul>
     *   <li>{@code null} / literal {@code "null"} ResultPath keeps {@code input} (result discarded, per AWS)</li>
     *   <li>{@code "$"} replaces {@code input} with {@code result}</li>
     *   <li>a leading field segment ({@code $.a}) against a non-object input, or a leading index
     *       segment ({@code $[n]}) against a non-array input, raises {@link ResultPathMatchException}</li>
     *   <li>an index segment addressing a position an existing array does not have (missing array,
     *       wrong type, or out of bounds) raises {@link ResultPathMatchException} rather than
     *       fabricating array elements</li>
     *   <li>otherwise a deep copy of {@code input} is returned with {@code result} set at the path,
     *       creating missing intermediate objects along dotted segments the way {@code $.a.b} always has</li>
     * </ul>
     *
     * <p>Known residual (unchanged, pre-existing behavior): a {@code $.a.b} path whose {@code $.a} is
     * a scalar is silently overwritten with a new object rather than raising
     * {@code States.ResultPathMatchFailure}; the same applies to a bracket-addressed element that is
     * a scalar where the path expects an object or array.
     */
    public static JsonNode merge(JsonNode input, String resultPath, JsonNode result, ObjectMapper mapper) {
        if (resultPath == null || resultPath.equals("null")) {
            return input;
        }
        if ("$".equals(resultPath)) {
            return result;
        }
        List<Object> segments = parseReferencePath(resultPath);
        boolean rootIsIndexed = segments.get(0) instanceof Integer;
        if (rootIsIndexed && !input.isArray()) {
            throw new ResultPathMatchException(
                    "Failed to apply ResultPath '" + resultPath + "': the state input is not a JSON array");
        }
        if (!rootIsIndexed && !input.isObject()) {
            throw new ResultPathMatchException(
                    "Failed to apply ResultPath '" + resultPath + "': the state input is not a JSON object");
        }
        JsonNode merged = input.deepCopy();
        setPath(merged, segments, result, mapper, resultPath);
        return merged;
    }

    /**
     * Tokenizes a Reference Path (everything after the leading {@code $}) into an ordered list of
     * {@link String} field segments and {@link Integer} bracket-index segments. For example
     * {@code $[1].payload} becomes {@code [1, "payload"]}, {@code $.a[0].b} becomes
     * {@code ["a", 0, "b"]}, and a single-quoted bracket field name such as
     * {@code $.abc.['def ghi']}, AWS's own documented form for a field name with characters a
     * dotted segment cannot carry, becomes {@code ["abc", "def ghi"]}. A dot immediately followed
     * by a bracket, as in that example, is a separator with no field content of its own.
     */
    private static List<Object> parseReferencePath(String path) {
        List<Object> segments = new ArrayList<>();
        int index = 1;
        int length = path.length();
        while (index < length) {
            char current = path.charAt(index);
            if (current == '.') {
                index++;
                if (index < length && path.charAt(index) == '[') {
                    continue;
                }
                int start = index;
                while (index < length && path.charAt(index) != '.' && path.charAt(index) != '[') {
                    index++;
                }
                if (index == start) {
                    throw new ResultPathMatchException(
                            "Failed to apply ResultPath '" + path + "': empty field segment");
                }
                segments.add(path.substring(start, index));
            } else if (current == '[') {
                int start = ++index;
                if (start < length && path.charAt(start) == '\'') {
                    int contentStart = start + 1;
                    int closingQuote = path.indexOf('\'', contentStart);
                    if (closingQuote < 0 || closingQuote + 1 >= length || path.charAt(closingQuote + 1) != ']') {
                        throw new ResultPathMatchException(
                                "Failed to apply ResultPath '" + path + "': unterminated quoted bracket segment in '" + path.substring(start) + "'");
                    }
                    segments.add(path.substring(contentStart, closingQuote));
                    index = closingQuote + 2;
                    continue;
                }
                while (index < length && path.charAt(index) != ']') {
                    index++;
                }
                if (index >= length) {
                    throw new ResultPathMatchException(
                            "Failed to apply ResultPath '" + path + "': unterminated '['");
                }
                String token = path.substring(start, index);
                index++;
                if (token.isEmpty() || !token.chars().allMatch(Character::isDigit)) {
                    throw new ResultPathMatchException(
                            "Failed to apply ResultPath '" + path + "': only a numeric array index or a single-quoted field name is supported in '[" + token + "]'");
                }
                segments.add(Integer.valueOf(token));
            } else {
                throw new ResultPathMatchException(
                        "Failed to apply ResultPath '" + path + "': unsupported reference path syntax");
            }
        }
        if (segments.isEmpty()) {
            throw new ResultPathMatchException("Failed to apply ResultPath '" + path + "': empty reference path");
        }
        return segments;
    }

    private static void setPath(JsonNode root, List<Object> segments, JsonNode value, ObjectMapper mapper, String fullPath) {
        JsonNode current = root;
        for (int i = 0; i < segments.size() - 1; i++) {
            current = descend(current, segments.get(i), segments.get(i + 1), mapper, fullPath);
        }
        setChild(current, segments.get(segments.size() - 1), value, fullPath);
    }

    /**
     * Moves from {@code current} into the child addressed by {@code segment}, creating that child
     * (as an object or array, matching what {@code nextSegment} needs) when it is missing or of the
     * wrong type. Field segments may always create a missing child, mirroring the pre-existing
     * dotted-path behavior; index segments require {@code current} to already be an array containing
     * that index.
     */
    private static JsonNode descend(JsonNode current, Object segment, Object nextSegment, ObjectMapper mapper, String fullPath) {
        boolean nextNeedsArray = nextSegment instanceof Integer;
        if (segment instanceof Integer index) {
            ArrayNode array = requireArrayIndex(current, index, fullPath);
            JsonNode child = array.get(index);
            if (nextNeedsArray ? child.isArray() : child.isObject()) {
                return child;
            }
            JsonNode created = nextNeedsArray ? mapper.createArrayNode() : mapper.createObjectNode();
            array.set(index, created);
            return created;
        }
        ObjectNode object = requireObject(current, fullPath);
        String field = (String) segment;
        JsonNode child = object.path(field);
        if (nextNeedsArray ? child.isArray() : child.isObject()) {
            return child;
        }
        JsonNode created = nextNeedsArray ? mapper.createArrayNode() : mapper.createObjectNode();
        object.set(field, created);
        return created;
    }

    private static void setChild(JsonNode current, Object segment, JsonNode value, String fullPath) {
        if (segment instanceof Integer index) {
            requireArrayIndex(current, index, fullPath).set(index, value);
        } else {
            requireObject(current, fullPath).set((String) segment, value);
        }
    }

    private static ArrayNode requireArrayIndex(JsonNode current, int index, String fullPath) {
        if (!current.isArray() || index < 0 || index >= current.size()) {
            throw new ResultPathMatchException(
                    "Failed to apply ResultPath '" + fullPath + "': array index [" + index
                            + "] does not exist in the addressed input");
        }
        return (ArrayNode) current;
    }

    private static ObjectNode requireObject(JsonNode current, String fullPath) {
        if (!current.isObject()) {
            throw new ResultPathMatchException(
                    "Failed to apply ResultPath '" + fullPath + "': expected a JSON object at this position");
        }
        return (ObjectNode) current;
    }
}
