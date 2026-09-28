package io.github.hectorvent.floci.services.apigatewayv2.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.SsrfProtection;
import io.github.hectorvent.floci.services.apigateway.AwsServiceRouter;
import io.github.hectorvent.floci.services.apigateway.VtlTemplateEngine;
import io.github.hectorvent.floci.services.apigatewayv2.model.Integration;
import io.github.hectorvent.floci.services.apigatewayv2.proxy.PinnedHttpClient;
import io.github.hectorvent.floci.services.lambda.LambdaArnUtils;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves and invokes integration targets for WebSocket routes.
 * Supports AWS_PROXY (Lambda), AWS (Lambda with VTL templates), HTTP_PROXY, HTTP (with VTL templates), and MOCK integration types.
 */
@ApplicationScoped
public class WebSocketIntegrationInvoker {

    private static final Logger LOG = Logger.getLogger(WebSocketIntegrationInvoker.class);

    /**
     * Regex pattern matching ${stageVariables.variableName} references in URIs.
     */
    private static final Pattern STAGE_VAR_PATTERN =
            Pattern.compile("\\$\\{stageVariables\\.([^}]+)}");
    private static final String APPLICATION_JSON = "application/json";
    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    private static final String HTTP_SCHEME = "http";
    private static final Duration BACKEND_TIMEOUT = Duration.ofSeconds(10);

    private final LambdaService lambdaService;
    private final AwsServiceRouter serviceRouter;
    private final ObjectMapper objectMapper;
    private final VtlTemplateEngine vtlEngine;
    private final HttpClient httpClient;
    private final AddressResolver addressResolver;

    @Inject
    public WebSocketIntegrationInvoker(LambdaService lambdaService, AwsServiceRouter serviceRouter,
                                       ObjectMapper objectMapper, VtlTemplateEngine vtlEngine) {
        this(lambdaService, serviceRouter, objectMapper, vtlEngine, InetAddress::getAllByName);
    }

    WebSocketIntegrationInvoker(LambdaService lambdaService, AwsServiceRouter serviceRouter,
                                ObjectMapper objectMapper, VtlTemplateEngine vtlEngine,
                                AddressResolver addressResolver) {
        this.lambdaService = lambdaService;
        this.serviceRouter = serviceRouter;
        this.objectMapper = objectMapper;
        this.vtlEngine = vtlEngine;
        this.httpClient = HttpClient.newHttpClient();
        this.addressResolver = addressResolver;
    }

    @FunctionalInterface
    interface AddressResolver {
        InetAddress[] resolve(String host) throws IOException;
    }

    private record HttpBackendResponse(int statusCode, String body) {
    }

    @PreDestroy
    void shutdown() {
        // HttpClient does not have an explicit close in Java 11-20.
        // In Java 21+ HttpClient implements AutoCloseable. For forward compatibility,
        // we attempt to close if the method is available.
        try {
            httpClient.close();
        } catch (UnsupportedOperationException | NoSuchMethodError e) {
            // Pre-Java 21 — no-op, GC will handle cleanup
        }
    }

    /**
     * Result of an integration invocation.
     *
     * @param statusCode    HTTP-like status code from the integration response
     * @param body          response body (may be null)
     * @param functionError function error string if the Lambda reported an error (may be null)
     * @param headers       response headers from the integration (may be null)
     */
    public record IntegrationResult(int statusCode, String body, String functionError,
                                    Map<String, String> headers) {
        /** Convenience constructor without headers (backwards compatible). */
        public IntegrationResult(int statusCode, String body, String functionError) {
            this(statusCode, body, functionError, null);
        }
    }

    /**
     * Invoke the integration for a route.
     *
     * @param region            the AWS region
     * @param integration       the Integration model
     * @param eventJson         the serialized proxy event JSON
     * @param stageVariables    stage variables for URI substitution
     * @param requestTemplates  request templates (for MOCK integration)
     * @param responseTemplates response templates (for MOCK integration)
     * @return the integration result
     */
    public IntegrationResult invoke(String region, Integration integration, String eventJson,
                                    Map<String, String> stageVariables,
                                    Map<String, String> requestTemplates,
                                    Map<String, String> responseTemplates) {
        String integrationType = integration.getIntegrationType();
        if (integrationType == null) {
            integrationType = "AWS_PROXY";
        }

        return switch (integrationType) {
            case "AWS_PROXY" -> invokeAwsProxy(region, integration, eventJson, stageVariables);
            case "AWS" -> invokeAws(region, integration, eventJson, stageVariables, requestTemplates, responseTemplates);
            case "HTTP_PROXY" -> invokeHttpProxy(integration, eventJson, stageVariables);
            case "HTTP" -> invokeHttp(integration, eventJson, stageVariables, requestTemplates, responseTemplates);
            case "MOCK" -> invokeMock(integration, stageVariables, requestTemplates, responseTemplates);
            default -> {
                LOG.warnv("Unsupported integration type: {0}", integrationType);
                yield new IntegrationResult(500, null, "Unsupported integration type: " + integrationType);
            }
        };
    }

    /**
     * Invoke a Lambda function via AWS_PROXY integration.
     */
    private IntegrationResult invokeAwsProxy(String region, Integration integration,
                                             String eventJson, Map<String, String> stageVariables) {
        String uri = integration.getIntegrationUri();
        uri = substituteStageVariables(uri, stageVariables);

        String functionName = extractFunctionName(uri);
        if (functionName == null || functionName.isEmpty()) {
            LOG.warnv("Could not extract function name from integration URI: {0}", uri);
            return new IntegrationResult(500, null, "Invalid integration URI");
        }

        LOG.debugv("Invoking Lambda function {0} for AWS_PROXY integration", functionName);

        InvokeResult result = lambdaService.invoke(region, functionName,
                eventJson.getBytes(StandardCharsets.UTF_8), InvocationType.RequestResponse);

        int statusCode = 200;
        String body = null;
        String functionError = result.getFunctionError();
        Map<String, String> responseHeaders = null;

        byte[] payload = result.getPayload();
        if (payload != null && payload.length > 0) {
            String payloadStr = new String(payload, StandardCharsets.UTF_8);
            try {
                JsonNode responseNode = objectMapper.readTree(payloadStr);
                if (responseNode.has("statusCode")) {
                    statusCode = responseNode.get("statusCode").asInt(200);
                }
                if (responseNode.has("body")) {
                    body = responseNode.get("body").asText();
                }
                // Extract response headers (used by $connect to propagate to upgrade response)
                if (responseNode.has("headers") && responseNode.get("headers").isObject()) {
                    responseHeaders = new HashMap<>();
                    JsonNode headersNode = responseNode.get("headers");
                    Iterator<Map.Entry<String, JsonNode>> fields = headersNode.fields();
                    while (fields.hasNext()) {
                        Map.Entry<String, JsonNode> field = fields.next();
                        responseHeaders.put(field.getKey(), field.getValue().asText());
                    }
                }
            } catch (Exception e) {
                LOG.debugv("Failed to parse Lambda response as JSON: {0}", e.getMessage());
                body = payloadStr;
            }
        }

        return new IntegrationResult(statusCode, body, functionError, responseHeaders);
    }

    /**
     * Invoke a Lambda function via AWS integration with VTL template transformation.
     * Applies requestTemplates before invocation and responseTemplates after.
     * Uses templates from the method parameters first, falling back to the integration model's templates.
     */
    private IntegrationResult invokeAws(String region, Integration integration, String eventJson,
                                        Map<String, String> stageVariables,
                                        Map<String, String> requestTemplates,
                                        Map<String, String> responseTemplates) {
        String uri = integration.getIntegrationUri();
        uri = substituteStageVariables(uri, stageVariables);

        String functionName = extractFunctionName(uri);
        if (functionName == null || functionName.isEmpty()) {
            LOG.warnv("Could not extract function name from integration URI: {0}", uri);
            return new IntegrationResult(500, null, "Invalid integration URI");
        }

        // Use passed templates, falling back to integration model's templates
        Map<String, String> effectiveRequestTemplates = (requestTemplates != null && !requestTemplates.isEmpty())
                ? requestTemplates
                : integration.getRequestTemplates();
        Map<String, String> effectiveResponseTemplates = (responseTemplates != null && !responseTemplates.isEmpty())
                ? responseTemplates
                : integration.getResponseTemplates();

        // Apply request template transformation
        String transformedPayload = applyRequestTemplate(eventJson, effectiveRequestTemplates,
                integration.getTemplateSelectionExpression(), stageVariables);

        LOG.debugv("Invoking Lambda function {0} for AWS integration", functionName);

        InvokeResult result = lambdaService.invoke(region, functionName,
                transformedPayload.getBytes(StandardCharsets.UTF_8), InvocationType.RequestResponse);

        int statusCode = 200;
        String body = null;
        String functionError = result.getFunctionError();

        byte[] payload = result.getPayload();
        if (payload != null && payload.length > 0) {
            body = new String(payload, StandardCharsets.UTF_8);
        }

        // Apply response template transformation
        if (body != null) {
            body = applyResponseTemplate(body, effectiveResponseTemplates,
                    integration.getTemplateSelectionExpression(), stageVariables, statusCode);
        }

        return new IntegrationResult(statusCode, body, functionError);
    }

    /**
     * Invoke an HTTP endpoint via HTTP_PROXY integration (passthrough, no VTL transformation).
     * Forwards the event JSON as an HTTP POST to the resolved integration URI and returns
     * the HTTP response status code and body as the IntegrationResult.
     */
    private IntegrationResult invokeHttpProxy(Integration integration, String eventJson,
                                              Map<String, String> stageVariables) {
        String uri = integration.getIntegrationUri();
        uri = substituteStageVariables(uri, stageVariables);

        if (uri == null || uri.isEmpty()) {
            LOG.warnv("HTTP_PROXY integration URI is null or empty");
            return new IntegrationResult(500, null, "Invalid integration URI");
        }

        LOG.debugv("Forwarding event to HTTP_PROXY endpoint: {0}", uri);

        try {
            URI target = URI.create(uri);
            HttpBackendResponse response = postToHttpEndpoint(target, eventJson);
            return new IntegrationResult(response.statusCode(), response.body(), null);
        } catch (Exception e) {
            LOG.warnv("HTTP_PROXY integration call failed: {0}", e.getMessage());
            return new IntegrationResult(500, null, "HTTP_PROXY integration error: " + e.getMessage());
        }
    }

    /**
     * Invoke an HTTP endpoint via HTTP integration with VTL template transformation.
     * Applies requestTemplates to transform the event before forwarding, and
     * responseTemplates to transform the HTTP response before returning.
     * Stage variable substitution is applied to the integrationUri (Requirement 3.3).
     */
    private IntegrationResult invokeHttp(Integration integration, String eventJson,
                                         Map<String, String> stageVariables,
                                         Map<String, String> requestTemplates,
                                         Map<String, String> responseTemplates) {
        String uri = integration.getIntegrationUri();
        uri = substituteStageVariables(uri, stageVariables);

        if (uri == null || uri.isEmpty()) {
            LOG.warnv("HTTP integration URI is null or empty");
            return new IntegrationResult(500, null, "Invalid integration URI");
        }

        // Use passed templates, falling back to integration model's templates
        Map<String, String> effectiveRequestTemplates = (requestTemplates != null && !requestTemplates.isEmpty())
                ? requestTemplates
                : integration.getRequestTemplates();
        Map<String, String> effectiveResponseTemplates = (responseTemplates != null && !responseTemplates.isEmpty())
                ? responseTemplates
                : integration.getResponseTemplates();

        // Apply request template transformation (Requirement 3.1)
        String transformedPayload = applyRequestTemplate(eventJson, effectiveRequestTemplates,
                integration.getTemplateSelectionExpression(), stageVariables);

        LOG.debugv("Forwarding transformed event to HTTP endpoint: {0}", uri);

        try {
            URI target = URI.create(uri);
            HttpBackendResponse response = postToHttpEndpoint(target, transformedPayload);
            int statusCode = response.statusCode();
            String body = response.body();

            // Apply response template transformation (Requirement 3.2)
            if (body != null) {
                body = applyResponseTemplate(body, effectiveResponseTemplates,
                        integration.getTemplateSelectionExpression(), stageVariables, statusCode);
            }

            return new IntegrationResult(statusCode, body, null);
        } catch (Exception e) {
            LOG.warnv("HTTP integration call failed: {0}", e.getMessage());
            return new IntegrationResult(500, null, "HTTP integration error: " + e.getMessage());
        }
    }

    private HttpBackendResponse postToHttpEndpoint(URI target, String payload)
            throws IOException, InterruptedException {
        if (HTTP_SCHEME.equalsIgnoreCase(target.getScheme())) {
            return postToPinnedHttpEndpoint(target, payload);
        }

        resolveNonMetadataTarget(target.getHost());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(target)
                .header(CONTENT_TYPE_HEADER, APPLICATION_JSON)
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return new HttpBackendResponse(response.statusCode(), response.body());
    }

    private InetAddress[] resolveNonMetadataTarget(String host) throws IOException {
        if (host == null || host.isBlank()) {
            throw new IOException("integration URI has no host");
        }
        return SsrfProtection.rejectMetadataAddresses(addressResolver.resolve(host), host);
    }

    /**
     * Sends the payload to the address the host resolved to, instead of letting a client resolve
     * the name a second time. The shared {@link PinnedHttpClient} owns the request writing and the
     * bounded response parsing, so HTTP_PROXY and the WebSocket integrations keep the same limits.
     */
    private HttpBackendResponse postToPinnedHttpEndpoint(URI target, String payload) throws IOException {
        InetAddress[] targets = resolveNonMetadataTarget(target.getHost());
        PinnedHttpClient.Response response = PinnedHttpClient.send(
                targets[0], target, "POST", null,
                Map.of(CONTENT_TYPE_HEADER, List.of(APPLICATION_JSON)),
                payload.getBytes(StandardCharsets.UTF_8), BACKEND_TIMEOUT);
        return new HttpBackendResponse(
                response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
    }

    /**
     * Handle a MOCK integration — no backend invocation.
     * Evaluates templateSelectionExpression against requestTemplates to determine status code,
     * then selects matching responseTemplate.
     * Stage variable substitution is applied to the integrationUri and templateSelectionExpression
     * per Requirement 8.4 (all integration types).
     *
     * If no request templates are provided but templateSelectionExpression is a numeric value,
     * it is used directly as the status code. This allows MOCK integrations to return non-200
     * status codes without requiring template configuration.
     */
    private IntegrationResult invokeMock(Integration integration,
                                         Map<String, String> stageVariables,
                                         Map<String, String> requestTemplates,
                                         Map<String, String> responseTemplates) {
        // Apply stage variable substitution to integrationUri (Requirement 8.4)
        String uri = integration.getIntegrationUri();
        if (uri != null) {
            substituteStageVariables(uri, stageVariables);
        }

        int statusCode = 200;
        String body = null;

        String templateSelectionExpression = integration.getTemplateSelectionExpression();
        // Apply stage variable substitution to templateSelectionExpression
        if (templateSelectionExpression != null) {
            templateSelectionExpression = substituteStageVariables(templateSelectionExpression, stageVariables);
        }

        // Evaluate request templates to determine status code
        if (templateSelectionExpression != null && requestTemplates != null && !requestTemplates.isEmpty()) {
            // The templateSelectionExpression for mock integrations typically evaluates to a status code
            // Try to find a matching request template key
            String matchedKey = null;
            for (String key : requestTemplates.keySet()) {
                if (templateSelectionExpression.contains(key) || key.equals(templateSelectionExpression)) {
                    matchedKey = key;
                    break;
                }
            }
            if (matchedKey != null) {
                try {
                    statusCode = Integer.parseInt(matchedKey);
                } catch (NumberFormatException e) {
                    // Key is not a numeric status code, keep default 200
                }
            }
        } else if (templateSelectionExpression != null && (requestTemplates == null || requestTemplates.isEmpty())) {
            // No request templates provided — try to parse templateSelectionExpression directly as a status code.
            // This supports MOCK integrations configured with a numeric templateSelectionExpression
            // to return a specific status code without requiring template maps.
            try {
                statusCode = Integer.parseInt(templateSelectionExpression);
            } catch (NumberFormatException e) {
                // Not a numeric value, keep default 200
            }
        }

        // Select matching response template
        if (responseTemplates != null && !responseTemplates.isEmpty()) {
            String statusStr = String.valueOf(statusCode);
            body = responseTemplates.get(statusStr);
            if (body == null) {
                // Try to find a default or first available template
                body = responseTemplates.values().stream().findFirst().orElse(null);
            }
        }

        return new IntegrationResult(statusCode, body, null);
    }

    /**
     * Apply a request template (VTL) to transform the event payload.
     * Uses templateSelectionExpression to select which template to apply.
     * Falls back to the first available template if no match is found.
     *
     * @param eventJson                    the original event JSON
     * @param requestTemplates             the request templates map (keyed by content type or selection key)
     * @param templateSelectionExpression   expression to select which template to use
     * @param stageVariables               stage variables for VTL context
     * @return the transformed payload, or the original eventJson if no template applies
     */
    private String applyRequestTemplate(String eventJson, Map<String, String> requestTemplates,
                                        String templateSelectionExpression,
                                        Map<String, String> stageVariables) {
        if (requestTemplates == null || requestTemplates.isEmpty()) {
            return eventJson;
        }

        String template = selectTemplate(requestTemplates, templateSelectionExpression);
        if (template == null || template.isEmpty()) {
            return eventJson;
        }

        VtlTemplateEngine.VtlContext vtlCtx = new VtlTemplateEngine.VtlContext(
                eventJson, Map.of(), Map.of(), Map.of(), "", "", "", "", "000000000000",
                stageVariables != null ? stageVariables : Map.of(), null);

        VtlTemplateEngine.EvaluateResult result = vtlEngine.evaluate(template, vtlCtx);
        return result.body();
    }

    /**
     * Apply a response template (VTL) to transform the Lambda response.
     * Uses templateSelectionExpression to select which template to apply.
     * Falls back to matching by status code, then the first available template.
     *
     * @param responseBody                 the Lambda response body
     * @param responseTemplates            the response templates map (keyed by status code or selection key)
     * @param templateSelectionExpression   expression to select which template to use
     * @param stageVariables               stage variables for VTL context
     * @param statusCode                   the response status code
     * @return the transformed response body, or the original if no template applies
     */
    private String applyResponseTemplate(String responseBody, Map<String, String> responseTemplates,
                                         String templateSelectionExpression,
                                         Map<String, String> stageVariables, int statusCode) {
        if (responseTemplates == null || responseTemplates.isEmpty()) {
            return responseBody;
        }

        // Try to select template by templateSelectionExpression first, then by status code
        String template = selectTemplate(responseTemplates, templateSelectionExpression);
        if (template == null) {
            template = responseTemplates.get(String.valueOf(statusCode));
        }
        if (template == null) {
            // Fall back to first available template
            template = responseTemplates.values().stream().findFirst().orElse(null);
        }
        if (template == null || template.isEmpty()) {
            return responseBody;
        }

        VtlTemplateEngine.VtlContext vtlCtx = new VtlTemplateEngine.VtlContext(
                responseBody, Map.of(), Map.of(), Map.of(), "", "", "", "", "000000000000",
                stageVariables != null ? stageVariables : Map.of(), null);

        VtlTemplateEngine.EvaluateResult result = vtlEngine.evaluate(template, vtlCtx);
        return result.body();
    }

    /**
     * Select a template from the templates map using the templateSelectionExpression.
     * The expression is matched against template keys.
     *
     * @param templates                    the templates map
     * @param templateSelectionExpression   the selection expression
     * @return the selected template value, or null if no match
     */
    private String selectTemplate(Map<String, String> templates, String templateSelectionExpression) {
        if (templates == null || templates.isEmpty()) {
            return null;
        }

        if (templateSelectionExpression != null && !templateSelectionExpression.isEmpty()) {
            // Try exact match with the expression value
            String template = templates.get(templateSelectionExpression);
            if (template != null) {
                return template;
            }
            // Try to find a key that the expression contains or matches
            for (Map.Entry<String, String> entry : templates.entrySet()) {
                if (templateSelectionExpression.contains(entry.getKey())
                        || entry.getKey().equals(templateSelectionExpression)) {
                    return entry.getValue();
                }
            }
        }

        // Fall back to first available template
        return templates.values().stream().findFirst().orElse(null);
    }

    /**
     * Extract Lambda function name from an integration URI.
     * Delegates to {@link LambdaArnUtils#extractFunctionNameFromUri(String)}.
     *
     * @param uri the integration URI (Lambda ARN or function name)
     * @return the extracted function name, or null if not parseable
     */
    String extractFunctionName(String uri) {
        return LambdaArnUtils.extractFunctionNameFromUri(uri);
    }

    /**
     * Perform stage variable substitution on a URI.
     * Replaces all ${stageVariables.variableName} occurrences with the corresponding
     * value from the stageVariables map. Undefined variables are replaced with empty string.
     *
     * @param uri            the URI containing stage variable references
     * @param stageVariables the stage variables map (may be null or empty)
     * @return the URI with all stage variable references substituted
     */
    String substituteStageVariables(String uri, Map<String, String> stageVariables) {
        if (uri == null) {
            return null;
        }
        if (stageVariables == null) {
            stageVariables = Collections.emptyMap();
        }

        Matcher matcher = STAGE_VAR_PATTERN.matcher(uri);
        StringBuilder result = new StringBuilder();
        Map<String, String> vars = stageVariables;

        while (matcher.find()) {
            String variableName = matcher.group(1);
            String replacement = vars.getOrDefault(variableName, "");
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        return result.toString();
    }
}
