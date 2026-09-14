package io.github.hectorvent.floci.services.apigatewayv2.proxy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bundles the inbound request data and authorizer-derived context that an
 * HTTP_PROXY integration may reference via $context.* / $request.* expressions.
 *
 * <p>Headers and query parameters appear twice, in two different shapes, because
 * the two consumers want different things. {@code requestHeaders} /
 * {@code queryParams} are the single-valued view that {@code $request.header.X}
 * and {@code $request.querystring.X} resolve against, matching AWS, where those
 * expressions yield one value. {@code multiValueHeaders} /
 * {@code multiValueQueryParams} are what actually gets forwarded to the backend,
 * and they keep repeated values separate: REST HTTP_PROXY passes the request
 * through, and {@code ?tag=a&tag=b} must not reach the backend as
 * {@code ?tag=a,b}, which is a different request to most servers.
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
        Map<String, Object> authorizerContext,
        Map<String, List<String>> multiValueHeaders,
        Map<String, List<String>> multiValueQueryParams) {

    /**
     * For callers whose headers and query parameters are single-valued by
     * construction, such as a non-proxy HTTP integration that forwards only the
     * parameters its {@code requestParameters} mapping named.
     */
    public RequestContext(String apiId, String stageName, String httpMethod, String path, String proxy,
                          String routeKey, String requestId, String sourceIp,
                          Map<String, String> requestHeaders, Map<String, String> queryParams,
                          Map<String, String> pathParams, byte[] body,
                          Map<String, Object> authorizerClaims, Map<String, Object> authorizerContext) {
        this(apiId, stageName, httpMethod, path, proxy, routeKey, requestId, sourceIp,
                requestHeaders, queryParams, pathParams, body, authorizerClaims, authorizerContext,
                expand(requestHeaders), expand(queryParams));
    }

    private static Map<String, List<String>> expand(Map<String, String> single) {
        if (single == null) {
            return null;
        }
        Map<String, List<String>> expanded = new LinkedHashMap<>();
        single.forEach((name, value) -> {
            if (value != null) {
                expanded.put(name, List.of(value));
            }
        });
        return expanded;
    }
}
