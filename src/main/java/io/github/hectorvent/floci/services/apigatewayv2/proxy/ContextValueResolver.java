package io.github.hectorvent.floci.services.apigatewayv2.proxy;

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
 *   <li>{@code $request.header.<name>} — case-insensitive header lookup</li>
 *   <li>{@code $request.querystring.<name>} — query parameter</li>
 *   <li>{@code $request.path.<name>} — captured path parameter</li>
 *   <li>literal strings — returned unchanged</li>
 * </ul>
 */
public class ContextValueResolver {

    public String resolve(String expression, RequestContext ctx) {
        if (expression == null) return null;
        if (!expression.startsWith("$")) return expression;

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

    private static String getCaseInsensitive(Map<String, String> headers, String name) {
        if (headers == null) return null;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }
}
