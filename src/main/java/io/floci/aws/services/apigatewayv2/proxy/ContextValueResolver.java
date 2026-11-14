package io.floci.aws.services.apigatewayv2.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.JsonPathException;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Resolves source value expressions used by HTTP API v2 RequestParameters.
 * Returns the literal string for non-{@code $} prefixed values, looks up
 * the appropriate field on {@link RequestContext} for $-prefixed expressions,
 * and returns {@code null} for missing or unknown references.
 *
 * <p>Supported expressions:
 * <ul>
 *   <li>{@code $context.authorizer.claims.<name>} — JWT claim from authorizer</li>
 *   <li>{@code $context.authorizer.<name>} — authorizer context value</li>
 *   <li>{@code $context.requestId} — UUID assigned per request</li>
 *   <li>{@code $context.identity.sourceIp} — client IP</li>
 *   <li>{@code $context.<name>} — any other context variable the request carries</li>
 *   <li>{@code $request.header.<name>} — case-insensitive header lookup</li>
 *   <li>{@code $request.querystring.<name>} — query parameter</li>
 *   <li>{@code $request.path.<name>} — captured path parameter</li>
 *   <li>{@code $request.body} — the request body as text, null when it is empty</li>
 *   <li>{@code $request.body.<JSONPath>} — a value selected from a JSON request body</li>
 *   <li>{@code $stageVariables.<name>} — stage variable</li>
 *   <li>literal strings — returned unchanged</li>
 * </ul>
 */
public class ContextValueResolver {

    private static final String BODY = "$request.body";
    private static final String STAGE_VARIABLES = "$stageVariables.";
    private static final String CONTEXT = "$context.";

    private final ObjectMapper objectMapper;
    private final Configuration jsonPathConfiguration;

    public ContextValueResolver() {
        this(new ObjectMapper());
    }

    public ContextValueResolver(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.jsonPathConfiguration = Configuration.builder()
                .jsonProvider(new JacksonJsonNodeJsonProvider(objectMapper))
                .mappingProvider(new JacksonMappingProvider(objectMapper))
                .build();
    }

    public String resolve(String expression, RequestContext ctx) {
        if (expression == null) return null;
        if (!expression.startsWith("$")) return expression;

        if (expression.equals(BODY)) {
            return bodyText(ctx);
        }
        if (expression.startsWith(BODY + ".")) {
            return bodyPath(ctx, expression.substring(BODY.length() + 1));
        }
        if (expression.startsWith(STAGE_VARIABLES)) {
            Map<String, String> stageVariables = ctx.stageVariables();
            return stageVariables == null ? null : stageVariables.get(expression.substring(STAGE_VARIABLES.length()));
        }
        if (expression.startsWith("$context.authorizer.claims.")) {
            String claim = expression.substring("$context.authorizer.claims.".length());
            Object v = ctx.authorizerClaims() == null ? null : ctx.authorizerClaims().get(claim);
            return v == null ? null : v.toString();
        }
        if (expression.startsWith("$context.authorizer.")) {
            String key = expression.substring("$context.authorizer.".length());
            Object v = ctx.authorizerContext() == null ? null : ctx.authorizerContext().get(key);
            return v == null ? null : v.toString();
        }
        if (expression.equals("$context.requestId")) return ctx.requestId();
        if (expression.equals("$context.identity.sourceIp")) return ctx.sourceIp();
        if (expression.startsWith(CONTEXT)) {
            Map<String, String> contextVariables = ctx.contextVariables();
            return contextVariables == null ? null : contextVariables.get(expression.substring(CONTEXT.length()));
        }
        if (expression.startsWith("$request.header.")) {
            String name = expression.substring("$request.header.".length());
            return getCaseInsensitive(ctx.requestHeaders(), name);
        }
        if (expression.startsWith("$request.querystring.")) {
            String name = expression.substring("$request.querystring.".length());
            return ctx.queryParams() == null ? null : ctx.queryParams().get(name);
        }
        if (expression.startsWith("$request.path.")) {
            String name = expression.substring("$request.path.".length());
            return ctx.pathParams() == null ? null : ctx.pathParams().get(name);
        }
        return null;
    }

    private static String bodyText(RequestContext ctx) {
        return ctx.body() == null || ctx.body().length == 0 ? null : new String(ctx.body(), StandardCharsets.UTF_8);
    }

    private String bodyPath(RequestContext ctx, String path) {
        String body = bodyText(ctx);
        if (body == null || body.isBlank()) {
            return null;
        }
        JsonNode value;
        try {
            Object read = JsonPath.using(jsonPathConfiguration).parse(body).read("$." + path);
            value = read instanceof JsonNode node ? node : objectMapper.valueToTree(read);
        } catch (JsonPathException | IllegalArgumentException | ClassCastException e) {
            return null;
        }
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        return value.isValueNode() ? value.asText() : value.toString();
    }

    private static String getCaseInsensitive(Map<String, String> headers, String name) {
        if (headers == null) return null;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }
}
