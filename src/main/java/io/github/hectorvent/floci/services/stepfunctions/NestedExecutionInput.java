package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Encodes the child execution input for a nested {@code states:startExecution} Task, preserving the
 * provenance needed to distinguish a value produced by {@code States.JsonToString} (JSON <em>text</em>
 * that must become the child's object {@code $}) from an ordinary string value that merely looks like
 * JSON.
 *
 * <p>Extracted from {@link AslExecutor} for two reasons: (1) the intrinsic function-name parse is the
 * SAME one {@code evaluateIntrinsic} uses (see {@link #intrinsicFunctionName}), so the provenance check
 * and the evaluator cannot drift apart as parser tolerance changes; and (2) the encoding rules are then
 * unit-testable without constructing the Vert.x-bound {@code AslExecutor}.
 *
 * <p>AWS semantics: {@code StartExecution}'s wire input is a string that the child parses as JSON.
 * {@code States.JsonToString(x)} returns a string whose content is JSON text, so that content is used
 * verbatim as the wire input (the child gets {@code x} back as an object). Any other resolved value is
 * serialized as a JSON value: an object stays an object, a plain string stays a JSON string literal.
 */
public final class NestedExecutionInput {

    private NestedExecutionInput() {
    }

    /**
     * The function name of an intrinsic-call expression (e.g. {@code "States.JsonToString"}), or
     * {@code null} if {@code expr} is not a call. This is the exact parse {@code AslExecutor.evaluateIntrinsic}
     * performs, shared so provenance detection and evaluation stay in lockstep (tolerating the same
     * whitespace, e.g. {@code States.JsonToString ($.x)}).
     */
    public static String intrinsicFunctionName(String expr) {
        if (expr == null) {
            return null;
        }
        int paren = expr.indexOf('(');
        if (paren < 0) {
            return null;
        }
        return expr.substring(0, paren).trim();
    }

    /**
     * Whether the resolved {@code Input} came from a top-level {@code "Input.$": "States.JsonToString(...)"}
     * template. Provenance is intentionally top-level and syntactic: {@code States.JsonToString} nested
     * inside another intrinsic (e.g. {@code States.Format(...)}) follows the plain-value rule, which is
     * the safe direction. JSONata Tasks use {@code Arguments} (not {@code Parameters}) and are out of scope.
     */
    public static boolean isJsonToStringInput(JsonNode rawParameters) {
        if (rawParameters == null || !rawParameters.isObject()) {
            return false;
        }
        JsonNode inputDollar = rawParameters.get("Input.$");
        return inputDollar != null && inputDollar.isTextual()
                && "States.JsonToString".equals(intrinsicFunctionName(inputDollar.asText()));
    }

    /**
     * The wire input string passed to the child execution.
     *
     * <ul>
     *   <li>missing {@code Input} → {@code "{}"}</li>
     *   <li>{@code fromJsonToString} and textual → the value's content verbatim (it is already JSON text,
     *       so the child parses it back to an object)</li>
     *   <li>otherwise → the value serialized as a JSON value (object stays object; plain/JSON-looking
     *       string stays a JSON string literal)</li>
     * </ul>
     */
    public static String childInput(JsonNode inputNode, boolean fromJsonToString, ObjectMapper mapper)
            throws JsonProcessingException {
        if (inputNode == null || inputNode.isMissingNode()) {
            return "{}";
        }
        // Use the value verbatim only when it is genuinely JSON text (a States.JsonToString result always
        // is). This guards a malformed template with colliding Input / Input.$ keys, where the winning
        // resolved value may not be JSON despite the Input.$ provenance. Such a value is quoted instead
        // of sent verbatim (avoiding parseInput's {} fallback).
        if (fromJsonToString && inputNode.isTextual() && isJsonText(inputNode.asText(), mapper)) {
            return inputNode.asText();
        }
        return mapper.writeValueAsString(inputNode);
    }

    private static boolean isJsonText(String text, ObjectMapper mapper) {
        try {
            mapper.readTree(text);
            return true;
        } catch (JsonProcessingException e) {
            return false;
        }
    }
}
