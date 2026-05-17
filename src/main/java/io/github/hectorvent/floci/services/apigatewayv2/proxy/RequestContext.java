package io.github.hectorvent.floci.services.apigatewayv2.proxy;

import java.util.Map;

/**
 * Bundles the inbound request data and authorizer-derived context that an
 * HTTP_PROXY integration may reference via $context.* / $request.* expressions.
 */
public record RequestContext(
        String apiId,
        String stageName,
        String httpMethod,
        String path,
        String proxy,
        String routeKey,
        String requestId,
        String sourceIp,
        Map<String, String> requestHeaders,
        Map<String, String> queryParams,
        Map<String, String> pathParams,
        byte[] body,
        Map<String, Object> authorizerClaims,
        Map<String, Object> authorizerContext) {
}
