package io.floci.aws.services.apigatewayv2.proxy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.JsonPathException;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.apigatewayv2.model.IntegrationSubtype;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps an HTTP API request onto the input of an {@link IntegrationSubtype}'s AWS service action,
 * and the action's result onto the response API Gateway returns.
 *
 * <p>A parameter value is a static string, a whole source expression such as
 * {@code $request.body}, or a string with source expressions embedded as {@code ${request.header.X}}.
 * Beyond the sources {@link ContextValueResolver} reads, it resolves {@code $request.body},
 * {@code $request.body.<JSONPath>} and {@code $stageVariables.<name>}. An embedded expression
 * that resolves to nothing is replaced by the empty string.
 */
public class AwsServiceIntegrationMapper {

    private static final Pattern EMBEDDED_EXPRESSION = Pattern.compile("\\$\\{([^}]+)}");
    private static final String BODY = "$request.body";
    private static final String STAGE_VARIABLES = "$stageVariables.";

    private final ObjectMapper objectMapper;
    private final Configuration jsonPathConfiguration;
    private final ContextValueResolver contextValueResolver = new ContextValueResolver();

    public AwsServiceIntegrationMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.jsonPathConfiguration = Configuration.builder()
                .jsonProvider(new JacksonJsonNodeJsonProvider(objectMapper))
                .mappingProvider(new JacksonMappingProvider(objectMapper))
                .build();
    }

    /** Resolves every parameter value, keeping null for a source that is absent. */
    public Map<String, String> resolveParameters(Map<String, String> parameters, RequestContext ctx,
                                                 Map<String, String> stageVariables) {
        Map<String, String> resolved = new LinkedHashMap<>();
        if (parameters == null) {
            return resolved;
        }
        parameters.forEach((name, value) -> resolved.put(name, resolveValue(value, ctx, stageVariables)));
        return resolved;
    }

    private String resolveValue(String value, RequestContext ctx, Map<String, String> stageVariables) {
        if (value == null) {
            return null;
        }
        if (value.startsWith("$") && !value.startsWith("${")) {
            return resolveExpression(value, ctx, stageVariables);
        }
        Matcher m = EMBEDDED_EXPRESSION.matcher(value);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String resolved = resolveExpression("$" + m.group(1), ctx, stageVariables);
            m.appendReplacement(out, Matcher.quoteReplacement(resolved != null ? resolved : ""));
        }
        m.appendTail(out);
        return out.toString();
    }

    private String resolveExpression(String expression, RequestContext ctx, Map<String, String> stageVariables) {
        if (expression.equals(BODY)) {
            return bodyText(ctx);
        }
        if (expression.startsWith(BODY + ".")) {
            return bodyPath(ctx, expression.substring(BODY.length() + 1));
        }
        if (expression.startsWith(STAGE_VARIABLES)) {
            return stageVariables == null ? null : stageVariables.get(expression.substring(STAGE_VARIABLES.length()));
        }
        return contextValueResolver.resolve(expression, ctx);
    }

    private static String bodyText(RequestContext ctx) {
        return ctx.body() == null ? "" : new String(ctx.body(), StandardCharsets.UTF_8);
    }

    private String bodyPath(RequestContext ctx, String path) {
        String body = bodyText(ctx);
        if (body.isBlank()) {
            return null;
        }
        JsonNode value;
        try {
            value = JsonPath.using(jsonPathConfiguration).parse(body).read("$." + path);
        } catch (JsonPathException | IllegalArgumentException e) {
            return null;
        }
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        return value.isValueNode() ? value.asText() : value.toString();
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

    private JsonNode jsonObject(String name, String value) {
        JsonNode node;
        try {
            node = objectMapper.readTree(value);
        } catch (JsonProcessingException e) {
            node = null;
        }
        if (node == null || !node.isObject()) {
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
