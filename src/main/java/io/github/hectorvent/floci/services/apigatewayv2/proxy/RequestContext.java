package io.github.hectorvent.floci.services.apigatewayv2.proxy;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
 *
 * <p>Path parameters appear twice for the same reason. {@code pathParams} holds the decoded values
 * that {@code $request.path.X} resolves against, and {@code rawPathParams} holds the same parameters
 * as the caller encoded them, which is what the target URL needs: decoding and re-encoding cannot
 * tell {@code %2F} from {@code /}. It may be null, lack a parameter that did not come from the
 * request path, or hold a value that does not decode to the parameter's, and
 * {@link #targetPathParams()} encodes the decoded value in those cases.
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
        Map<String, List<String>> multiValueQueryParams,
        Map<String, String> rawPathParams) {

    public RequestContext(String apiId, String stageName, String httpMethod, String path, String proxy,
                          String routeKey, String requestId, String sourceIp,
                          Map<String, String> requestHeaders, Map<String, String> queryParams,
                          Map<String, String> pathParams, byte[] body,
                          Map<String, Object> authorizerClaims, Map<String, Object> authorizerContext,
                          Map<String, List<String>> multiValueHeaders,
                          Map<String, List<String>> multiValueQueryParams) {
        this(apiId, stageName, httpMethod, path, proxy, routeKey, requestId, sourceIp,
                requestHeaders, queryParams, pathParams, body, authorizerClaims, authorizerContext,
                multiValueHeaders, multiValueQueryParams, null);
    }

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

    /** The path parameters as they go into the target URL, every value percent-encoded. */
    public Map<String, String> targetPathParams() {
        if (pathParams == null) {
            return null;
        }
        Map<String, String> target = new LinkedHashMap<>();
        pathParams.forEach((name, value) -> {
            String raw = rawPathParams == null ? null : rawPathParams.get(name);
            target.put(name, decodesTo(raw, value) ? raw : PathTemplateResolver.encodePathValue(value));
        });
        return target;
    }

    /**
     * Routes match on the decoded path, where an encoded slash splits a segment, so segments read off
     * the raw path can belong to a different parameter than the decoded value of the same name.
     */
    private static boolean decodesTo(String raw, String value) {
        if (raw == null) {
            return false;
        }
        try {
            return URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8).equals(value);
        } catch (IllegalArgumentException expected) {
            // Not valid percent-encoding, so it cannot stand for the decoded value: encode that instead.
            return false;
        }
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
