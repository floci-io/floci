package io.floci.aws.services.apigatewayv2.proxy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.apigatewayv2.model.IntegrationSubtype;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps an HTTP API request onto the input of an {@link IntegrationSubtype}'s AWS service action,
 * and the action's result onto the response API Gateway returns.
 *
 * <p>A parameter value is a static string, a whole source expression such as
 * {@code $request.body}, or a string with source expressions embedded as {@code ${request.header.X}}.
 * Expressions resolve through {@link ContextValueResolver}. An embedded expression that resolves to
 * nothing is replaced by the empty string.
 */
public class AwsServiceIntegrationMapper {

    private static final Pattern EMBEDDED_EXPRESSION = Pattern.compile("\\$\\{([^}]+)}");
    private static final String BODY = "$request.body";
    private static final String STAGE_VARIABLES = "$stageVariables.";
    private static final String CONTEXT = "$context.";
    private static final List<String> SOURCE_PREFIXES = List.of("$request.header.", "$request.querystring.",
            "$request.path.", BODY + ".", CONTEXT, STAGE_VARIABLES);
    private static final ObjectMapper TEMPLATE_READER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final ObjectMapper objectMapper;
    private final ContextValueResolver contextValueResolver;

    public AwsServiceIntegrationMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.contextValueResolver = new ContextValueResolver(objectMapper);
    }

    /**
     * Resolves every parameter value, keeping null for a source that is absent. A JSON parameter
     * whose value is a JSON object is parsed before anything is resolved, and expressions are
     * resolved only inside its string values, so a request value cannot add or change members. A
     * JSON parameter that is neither a whole source expression nor a JSON object is an
     * {@code InvalidParameterValue} error.
     */
    public Map<String, String> resolveParameters(IntegrationSubtype subtype, Map<String, String> parameters,
                                                 RequestContext ctx) {
        Map<String, String> resolved = new LinkedHashMap<>();
        if (parameters == null) {
            return resolved;
        }
        parameters.forEach((name, value) -> {
            if (!subtype.isJsonParameter(name) || value == null || isWholeExpression(value)) {
                resolved.put(name, resolveValue(value, ctx));
                return;
            }
            JsonNode template = jsonTemplate(value);
            if (template == null) {
                throw new AwsException("InvalidParameterValue",
                        "Value for parameter " + name + " is invalid. Reason: Must be a JSON object.", 400);
            }
            resolved.put(name, resolveLeaves(template, ctx).toString());
        });
        return resolved;
    }

    /**
     * Whether a JSON parameter value can be mapped: absent, a whole source expression such as
     * {@code $request.body.attributes} or {@code ${request.body.attributes}}, or a JSON object.
     */
    public static boolean isValidJsonParameter(String value) {
        return value == null || isWholeExpression(value) || jsonTemplate(value) != null;
    }

    /**
     * The first source expression in a parameter value that names no source the mapper reads, or null when there is
     * none. A value starting with {@code $} is one whole expression, {@code ${...}} marks an embedded one, and a
     * {@code $} anywhere else is static text. For a JSON parameter holding a JSON object, the expressions are those in
     * its string values.
     */
    public static String unsupportedExpression(String value, boolean jsonParameter) {
        if (value == null) {
            return null;
        }
        if (jsonParameter && !isWholeExpression(value)) {
            JsonNode template = jsonTemplate(value);
            return template == null ? null : unsupportedLeafExpression(template);
        }
        if (value.startsWith("$") && !value.startsWith("${")) {
            return isSupportedSource(value) ? null : value;
        }
        Matcher m = EMBEDDED_EXPRESSION.matcher(value);
        while (m.find()) {
            String expression = "$" + m.group(1);
            if (!isSupportedSource(expression)) {
                return expression;
            }
        }
        return null;
    }

    private static String unsupportedLeafExpression(JsonNode node) {
        if (node.isTextual()) {
            return unsupportedExpression(node.asText(), false);
        }
        for (JsonNode child : node) {
            String unsupported = unsupportedLeafExpression(child);
            if (unsupported != null) {
                return unsupported;
            }
        }
        return null;
    }

    private static boolean isSupportedSource(String expression) {
        if (expression.equals(BODY)) {
            return true;
        }
        for (String prefix : SOURCE_PREFIXES) {
            if (expression.startsWith(prefix) && expression.length() > prefix.length()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWholeExpression(String value) {
        return (value.startsWith("$") && !value.startsWith("${"))
                || EMBEDDED_EXPRESSION.matcher(value).matches();
    }

    private static JsonNode jsonTemplate(String value) {
        try {
            JsonNode node = TEMPLATE_READER.readTree(value);
            return node != null && node.isObject() ? node : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private JsonNode resolveLeaves(JsonNode node, RequestContext ctx) {
        if (node.isTextual()) {
            String resolved = resolveValue(node.asText(), ctx);
            return TextNode.valueOf(resolved != null ? resolved : "");
        }
        if (node.isObject()) {
            ObjectNode copy = objectMapper.createObjectNode();
            node.properties().forEach(e -> copy.set(e.getKey(), resolveLeaves(e.getValue(), ctx)));
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = objectMapper.createArrayNode();
            node.forEach(element -> copy.add(resolveLeaves(element, ctx)));
            return copy;
        }
        return node;
    }

    private String resolveValue(String value, RequestContext ctx) {
        if (value == null) {
            return null;
        }
        if (value.startsWith("$") && !value.startsWith("${")) {
            return contextValueResolver.resolve(value, ctx);
        }
        Matcher m = EMBEDDED_EXPRESSION.matcher(value);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String resolved = contextValueResolver.resolve("$" + m.group(1), ctx);
            m.appendReplacement(out, Matcher.quoteReplacement(resolved != null ? resolved : ""));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * The action's JSON input from the resolved parameters. A required parameter that resolved to
     * nothing is a {@code MissingParameter} error, an optional one is left out, and {@code Region}
     * is not an input member. Integer members are sent as numbers when they read as one, and JSON
     * members must hold a JSON object.
     */
    public ObjectNode actionRequest(IntegrationSubtype subtype, Map<String, String> resolved) {
        for (String required : subtype.requiredParameters()) {
            String value = resolved.get(required);
            if (value == null || value.isEmpty()) {
                throw new AwsException("MissingParameter",
                        "The request must contain the parameter " + required + ".", 400);
            }
        }
        ObjectNode request = objectMapper.createObjectNode();
        resolved.forEach((name, value) -> {
            if (value == null || value.isEmpty() || IntegrationSubtype.REGION_PARAMETER.equals(name)) {
                return;
            }
            if (subtype.isIntegerParameter(name)) {
                putInteger(request, name, value);
            } else if (subtype.isJsonParameter(name)) {
                request.set(name, jsonObject(name, value));
            } else {
                request.put(name, value);
            }
        });
        return request;
    }

    private static void putInteger(ObjectNode request, String name, String value) {
        try {
            request.put(name, Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            request.put(name, value);
        }
    }

    private static JsonNode jsonObject(String name, String value) {
        JsonNode node = jsonTemplate(value);
        if (node == null) {
            throw new AwsException("InvalidParameterValue",
                    "Value for parameter " + name + " is invalid. Reason: Must be a JSON object.", 400);
        }
        return node;
    }

    /**
     * The action's result in the response envelope API Gateway returns for it:
     * {@code {"<Action>Response":{"ResponseMetadata":{"RequestId":...},"<Action>Result":{...}}}},
     * every result member present and null when the action did not return it.
     */
    public ObjectNode actionResponse(IntegrationSubtype subtype, JsonNode result, String requestId) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode response = root.putObject(subtype.action() + "Response");
        response.putObject("ResponseMetadata").put("RequestId", requestId);
        ObjectNode members = response.putObject(subtype.action() + "Result");
        for (String member : subtype.resultMembers()) {
            JsonNode value = result == null ? null : result.get(member);
            if (value == null || value.isNull()) {
                members.putNull(member);
            } else {
                members.set(member, value);
            }
        }
        return root;
    }

    /** An action error in the shape API Gateway relays it. */
    public ObjectNode actionError(String code, String message, int status, String requestId) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode error = root.putObject("Error");
        error.put("Code", code);
        error.put("Message", message);
        error.put("Type", status >= 500 ? "Receiver" : "Sender");
        root.put("RequestId", requestId);
        return root;
    }
}
