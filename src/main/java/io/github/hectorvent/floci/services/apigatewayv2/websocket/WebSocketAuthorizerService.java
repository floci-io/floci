package io.github.hectorvent.floci.services.apigatewayv2.websocket;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.apigatewayv2.ApiGatewayV2Service;
import io.github.hectorvent.floci.services.apigatewayv2.AuthorizerPolicyEvaluator;
import io.github.hectorvent.floci.services.apigatewayv2.model.Authorizer;
import io.github.hectorvent.floci.services.lambda.LambdaArnUtils;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * Service responsible for invoking and evaluating Lambda REQUEST authorizers
 * for WebSocket $connect routes.
 *
 * Extracted from WebSocketHandler to keep the handler thin (controller responsibility)
 * and make authorizer logic independently testable.
 */
@ApplicationScoped
public class WebSocketAuthorizerService {

    private static final Logger LOG = Logger.getLogger(WebSocketAuthorizerService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ApiGatewayV2Service apiGatewayV2Service;
    private final LambdaService lambdaService;
    private final WebSocketProxyEventBuilder proxyEventBuilder;
    private final ObjectMapper objectMapper;
    private final AuthorizerPolicyEvaluator authorizerPolicyEvaluator;

    @Inject
    public WebSocketAuthorizerService(ApiGatewayV2Service apiGatewayV2Service,
                                      LambdaService lambdaService,
                                      WebSocketProxyEventBuilder proxyEventBuilder,
                                      ObjectMapper objectMapper,
                                      AuthorizerPolicyEvaluator authorizerPolicyEvaluator) {
        this.apiGatewayV2Service = apiGatewayV2Service;
        this.lambdaService = lambdaService;
        this.proxyEventBuilder = proxyEventBuilder;
        this.objectMapper = objectMapper;
        this.authorizerPolicyEvaluator = authorizerPolicyEvaluator;
    }

    /**
     * Result of authorizer evaluation.
     *
     * @param allowed   whether the connection is allowed
     * @param statusCode HTTP status code to return if denied (403 for Deny, 500 for errors, 401 for missing identity)
     * @param context   authorizer context map (may be null)
     */
    public record AuthorizerResult(boolean allowed, int statusCode, Map<String, Object> context) {

        public static AuthorizerResult allow(Map<String, Object> context) {
            return new AuthorizerResult(true, 200, context);
        }

        public static AuthorizerResult deny() {
            return new AuthorizerResult(false, 403, null);
        }

        public static AuthorizerResult unauthorized() {
            return new AuthorizerResult(false, 401, null);
        }

        public static AuthorizerResult error() {
            return new AuthorizerResult(false, 500, null);
        }
    }

    /**
     * Invoke the Lambda REQUEST authorizer for a $connect route.
     * Validates identity source values, invokes the authorizer Lambda,
     * and parses the policy document to determine Allow/Deny.
     *
     * @return AuthorizerResult indicating whether the connection is allowed
     */
    public AuthorizerResult invokeAndEvaluate(String region, String apiId, String stageName,
                                              String authorizerId, String connectionId, long connectedAt,
                                              Map<String, List<String>> headers,
                                              Map<String, List<String>> queryParams,
                                              String sourceIp, String userAgent,
                                              Map<String, String> stageVariables) {
        // Fetch the authorizer model
        Authorizer authorizer = apiGatewayV2Service.getAuthorizer(region, apiId, authorizerId);

        if (!"REQUEST".equals(authorizer.getAuthorizerType())) {
            // Only REQUEST type authorizers are supported for WebSocket APIs
            LOG.debugv("Authorizer {0} is not REQUEST type (is {1}), allowing by default",
                    authorizerId, authorizer.getAuthorizerType());
            return AuthorizerResult.allow(null);
        }

        // Validate identity source expressions
        List<String> identitySources = authorizer.getIdentitySource();
        if (identitySources != null && !identitySources.isEmpty()) {
            for (String expression : identitySources) {
                if (expression.startsWith("$request.header.")) {
                    String headerName = expression.substring("$request.header.".length());
                    if (!hasHeaderValue(headers, headerName)) {
                        LOG.debugv("Missing required identity source header: {0}", headerName);
                        return AuthorizerResult.unauthorized();
                    }
                } else if (expression.startsWith("$request.querystring.")) {
                    String paramName = expression.substring("$request.querystring.".length());
                    if (!hasQueryParamValue(queryParams, paramName)) {
                        LOG.debugv("Missing required identity source query param: {0}", paramName);
                        return AuthorizerResult.unauthorized();
                    }
                }
            }
        }

        // Build the authorizer event payload
        String authorizerEventJson = proxyEventBuilder.buildAuthorizerEvent(
                connectionId, apiId, stageName, region, connectedAt,
                headers, queryParams, sourceIp, userAgent, stageVariables);

        // Extract the Lambda function name from the authorizer URI
        String functionName = extractFunctionNameFromUri(authorizer.getAuthorizerUri());
        if (functionName == null) {
            LOG.warnv("Cannot extract function name from authorizer URI: {0}",
                    authorizer.getAuthorizerUri());
            return AuthorizerResult.error();
        }

        // Invoke the authorizer Lambda
        InvokeResult invokeResult;
        try {
            invokeResult = lambdaService.invoke(region, functionName,
                    authorizerEventJson.getBytes(), InvocationType.RequestResponse);
        } catch (Exception e) {
            LOG.warnv("Lambda authorizer invocation failed for API {0}: {1}", apiId, e.getMessage());
            return AuthorizerResult.error();
        }

        // Parse the policy document
        return parseAuthorizerResponse(invokeResult, apiId, proxyEventBuilder.buildMethodArn(region, apiId, stageName), sourceIp);
    }

    /**
     * Parse the authorizer Lambda response and extract the policy decision.
     */
    private AuthorizerResult parseAuthorizerResponse(InvokeResult invokeResult, String apiId, String methodArn, String sourceIp) {
        // Check for function error
        if (invokeResult.getFunctionError() != null) {
            LOG.warnv("Lambda authorizer returned function error for API {0}: {1}",
                    apiId, invokeResult.getFunctionError());
            return AuthorizerResult.error();
        }

        byte[] payload = invokeResult.getPayload();
        if (payload == null || payload.length == 0) {
            LOG.warnv("Lambda authorizer returned empty payload for API {0}", apiId);
            return AuthorizerResult.error();
        }

        try {
            JsonNode policy = objectMapper.readTree(payload);
            JsonNode policyDocument = policy.path("policyDocument");
            if (policyDocument.isMissingNode() || policyDocument.isNull()) {
                LOG.warnv("Authorizer response missing policyDocument for API {0}", apiId);
                return AuthorizerResult.error();
            }

            if (!authorizerPolicyEvaluator.permits(policyDocument, methodArn, sourceIp)) {
                return AuthorizerResult.deny();
            }

            // Extract context map if present
            Map<String, Object> context = null;
            JsonNode contextNode = policy.path("context");
            if (!contextNode.isMissingNode() && !contextNode.isNull() && contextNode.isObject()) {
                context = objectMapper.convertValue(contextNode, MAP_TYPE);
            }

            return AuthorizerResult.allow(context);
        } catch (Exception e) {
            LOG.warnv("Failed to parse authorizer policy for API {0}: {1}", apiId, e.getMessage());
            return AuthorizerResult.error();
        }
    }

    /**
     * Check if a header value is present (case-insensitive header name lookup).
     */
    private boolean hasHeaderValue(Map<String, List<String>> headers, String headerName) {
        if (headers == null) return false;
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(headerName)) {
                List<String> values = entry.getValue();
                return values != null && !values.isEmpty()
                        && values.get(0) != null && !values.get(0).isEmpty();
            }
        }
        return false;
    }

    /**
     * Check if a query parameter value is present.
     */
    private boolean hasQueryParamValue(Map<String, List<String>> queryParams, String paramName) {
        if (queryParams == null) return false;
        List<String> values = queryParams.get(paramName);
        return values != null && !values.isEmpty()
                && values.get(0) != null && !values.get(0).isEmpty();
    }

    /**
     * Extract the Lambda function name from an authorizer URI.
     * Delegates to {@link LambdaArnUtils#extractFunctionNameFromUri(String)}.
     */
    private String extractFunctionNameFromUri(String uri) {
        return LambdaArnUtils.extractFunctionNameFromUri(uri);
    }
}
