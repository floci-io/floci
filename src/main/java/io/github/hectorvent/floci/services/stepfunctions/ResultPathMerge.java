package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Applies a state's JSONPath {@code ResultPath}, merging the state result into the effective input.
 * Extracted from {@link AslExecutor} so the merge rules (including the AWS
 * {@code States.ResultPathMatchFailure} case) are unit-testable without the Vert.x-bound executor.
 *
 * <p>AWS: {@code ResultPath} names where the result is inserted into the state's raw input. When the
 * input is not a JSON object but {@code ResultPath} addresses an object member (a {@code $.field} path),
 * the path cannot apply and the interpreter fails with {@code States.ResultPathMatchFailure}. It does
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
     *   <li>{@code null} / literal {@code "null"} ResultPath → keep {@code input} (result discarded, per AWS)</li>
     *   <li>{@code "$"} → replace with {@code result}</li>
     *   <li>a {@code $.field} path into a non-object input → {@link ResultPathMatchException} (was silently
     *       discarding the input)</li>
     *   <li>object input → a deep copy with {@code result} set at the path</li>
     * </ul>
     *
     * <p>Known residuals (unchanged, pre-existing {@link #setPath} limitations): a {@code $[...]}-style
     * ResultPath is not applied (returns the result for a non-object input, and is a no-op merge for an
     * object input), and a {@code $.a.b} path whose {@code $.a} is a scalar is silently overwritten rather
     * than raising {@code States.ResultPathMatchFailure}.
     */
    public static JsonNode merge(JsonNode input, String resultPath, JsonNode result, ObjectMapper mapper) {
        if (resultPath == null || resultPath.equals("null")) {
            return input;
        }
        if ("$".equals(resultPath)) {
            return result;
        }
        if (!input.isObject()) {
            if (resultPath.startsWith("$.")) {
                throw new ResultPathMatchException(
                        "Failed to apply ResultPath '" + resultPath + "': the state input is not a JSON object");
            }
            return result; // non-$. paths ($[...]) are a documented residual: unchanged behavior
        }
        ObjectNode merged = input.deepCopy();
        setPath(merged, resultPath, result, mapper);
        return merged;
    }

    private static void setPath(ObjectNode root, String path, JsonNode value, ObjectMapper mapper) {
        if (!path.startsWith("$.") && !"$".equals(path)) {
            return;
        }
        if ("$".equals(path)) {
            return;
        }
        String[] parts = path.substring(2).split("\\.");
        ObjectNode current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            JsonNode next = current.path(parts[i]);
            if (!next.isObject()) {
                ObjectNode newNode = mapper.createObjectNode();
                current.set(parts[i], newNode);
                current = newNode;
            } else {
                current = (ObjectNode) next;
            }
        }
        current.set(parts[parts.length - 1], value);
    }
}
