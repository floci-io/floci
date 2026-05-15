package io.github.hectorvent.floci.services.apigatewayv2.proxy;

import java.util.Map;

/**
 * Result of an HTTP_PROXY integration invocation. Carries the backend's
 * response status, headers (combined when multi-valued), and body bytes
 * back to the API Gateway dispatcher for relay to the original caller.
 */
public record ProxyResult(int statusCode, Map<String, String> headers, byte[] body) {}
