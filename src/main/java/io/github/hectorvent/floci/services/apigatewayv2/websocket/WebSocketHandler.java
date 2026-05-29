package io.github.hectorvent.floci.services.apigatewayv2.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.apigatewayv2.ApiGatewayV2Service;
import io.github.hectorvent.floci.services.apigatewayv2.model.Api;
import io.github.hectorvent.floci.services.apigatewayv2.model.Integration;
import io.github.hectorvent.floci.services.apigatewayv2.model.Route;
import io.github.hectorvent.floci.services.apigatewayv2.model.Stage;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Vert.x-based WebSocket handler for API Gateway v2 WebSocket APIs.
 *
 * Registers a route on the Quarkus HTTP server via {@code @Observes Router} to handle
 * WebSocket upgrade requests at {@code /ws/{apiId}/{stageName}}.
 *
 * This handler validates the API and stage, generates a unique connectionId,
 * and delegates to the $connect/$disconnect/message lifecycle (implemented in Tasks 3-5).
 */
@ApplicationScoped
public class WebSocketHandler {

    private static final Logger LOG = Logger.getLogger(WebSocketHandler.class);

    private final ApiGatewayV2Service apiGatewayV2Service;
    private final WebSocketConnectionManager connectionManager;
    private final RouteSelectionEvaluator routeSelectionEvaluator;
    private final WebSocketProxyEventBuilder proxyEventBuilder;
    private final WebSocketIntegrationInvoker integrationInvoker;
    private final WebSocketAuthorizerService authorizerService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;
    private final io.vertx.core.Vertx vertx;

    @Inject
    public WebSocketHandler(ApiGatewayV2Service apiGatewayV2Service,
                            WebSocketConnectionManager connectionManager,
                            RouteSelectionEvaluator routeSelectionEvaluator,
                            WebSocketProxyEventBuilder proxyEventBuilder,
                            WebSocketIntegrationInvoker integrationInvoker,
                            WebSocketAuthorizerService authorizerService,
                            RegionResolver regionResolver,
                            ObjectMapper objectMapper,
                            io.vertx.core.Vertx vertx) {
        this.apiGatewayV2Service = apiGatewayV2Service;
        this.connectionManager = connectionManager;
        this.routeSelectionEvaluator = routeSelectionEvaluator;
        this.proxyEventBuilder = proxyEventBuilder;
        this.integrationInvoker = integrationInvoker;
        this.authorizerService = authorizerService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
        this.vertx = vertx;
    }

    /**
     * Register the WebSocket route handler on the Vert.x Router.
     * This runs before JAX-RS routing and intercepts WebSocket upgrade requests.
     */
    void init(@Observes Router router) {
        router.route("/ws/*").handler(this::handleWebSocketUpgrade);
        LOG.debug("Registered WebSocket handler on /ws/*");
    }

    /**
     * Handle a WebSocket upgrade request.
     * Parses apiId and stageName from the path, validates the API and stage,
     * generates a unique connectionId, and proceeds with the $connect lifecycle.
     */
    private void handleWebSocketUpgrade(RoutingContext ctx) {
        String path = ctx.request().path();

        // Strip the /ws/ prefix and parse apiId/stageName
        String pathAfterPrefix = path.substring("/ws/".length());
        String[] segments = pathAfterPrefix.split("/", 2);

        if (segments.length < 2 || segments[0].isEmpty() || segments[1].isEmpty()) {
            ctx.response().setStatusCode(403).end();
            return;
        }

        String apiId = segments[0];
        // stageName may contain additional path segments — take only the first segment
        String stageName = segments[1].contains("/") ? segments[1].split("/")[0] : segments[1];

        // Resolve region from the request Authorization header
        String region = resolveRegionFromVertxRequest(ctx);

        // Validate the API exists and is a WEBSOCKET protocol API
        Api api;
        try {
            api = apiGatewayV2Service.getApi(region, apiId);
        } catch (AwsException e) {
            LOG.debugv("WebSocket upgrade rejected: API {0} not found in region {1}", apiId, region);
            ctx.response().setStatusCode(403).end();
            return;
        }

        if (!"WEBSOCKET".equals(api.getProtocolType())) {
            LOG.debugv("WebSocket upgrade rejected: API {0} is not WEBSOCKET protocol (is {1})",
                    apiId, api.getProtocolType());
            ctx.response().setStatusCode(403).end();
            return;
        }

        // Validate the stage exists
        Stage stage;
        try {
            stage = apiGatewayV2Service.getStage(region, apiId, stageName);
        } catch (AwsException e) {
            LOG.debugv("WebSocket upgrade rejected: stage {0} not found on API {1}", stageName, apiId);
            ctx.response().setStatusCode(403).end();
            return;
        }

        // Generate a unique connectionId (AWS format: base64-encoded random bytes, ~10-14 chars)
        String connectionId = generateConnectionId();
        long connectedAt = System.currentTimeMillis();

        // Extract source IP and user agent from the upgrade request
        String sourceIp = ctx.request().remoteAddress() != null
                ? ctx.request().remoteAddress().host() : "127.0.0.1";
        String userAgent = ctx.request().getHeader("User-Agent");

        // Load stage variables (default to empty map if null)
        Map<String, String> stageVariables = stage.getStageVariables() != null
                ? stage.getStageVariables() : Collections.emptyMap();

        // Look up the $connect route
        Route connectRoute = apiGatewayV2Service.findRouteByKey(region, apiId, "$connect");

        if (connectRoute != null && connectRoute.getTarget() != null) {
            // Check if the $connect route has a Lambda REQUEST authorizer configured
            if ("CUSTOM".equals(connectRoute.getAuthorizationType())
                    && connectRoute.getAuthorizerId() != null
                    && !connectRoute.getAuthorizerId().isEmpty()) {

                // Build headers and query params from the upgrade request (needed for both authorizer and integration)
                Map<String, List<String>> headers = extractHeaders(ctx);
                Map<String, List<String>> queryParams = extractQueryParams(ctx);

                // Invoke the authorizer on a worker thread (blocking Lambda call)
                final Map<String, String> finalStageVariables = stageVariables;
                final String finalConnectionId = connectionId;
                final long finalConnectedAt = connectedAt;
                final String finalSourceIp = sourceIp;
                final String finalUserAgent = userAgent;

                vertx.<WebSocketAuthorizerService.AuthorizerResult>executeBlocking(() -> {
                    return authorizerService.invokeAndEvaluate(region, apiId, stageName,
                            connectRoute.getAuthorizerId(), finalConnectionId, finalConnectedAt,
                            headers, queryParams, finalSourceIp, finalUserAgent, finalStageVariables);
                }).onSuccess(authResult -> {
                    if (!authResult.allowed()) {
                        ctx.response().setStatusCode(authResult.statusCode()).end();
                        return;
                    }
                    // Authorizer allowed — proceed with $connect integration
                    proceedWithConnectIntegration(ctx, connectRoute, region, apiId, stageName,
                            finalConnectionId, finalConnectedAt, finalSourceIp, finalUserAgent,
                            finalStageVariables, headers, queryParams, authResult.context());
                }).onFailure(e -> {
                    LOG.warnv("Lambda authorizer invocation failed for API {0}: {1}",
                            apiId, e.getMessage());
                    ctx.response().setStatusCode(500).end();
                });
            } else {
                // No authorizer configured — proceed directly with $connect integration
                Map<String, List<String>> headers = extractHeaders(ctx);
                Map<String, List<String>> queryParams = extractQueryParams(ctx);
                proceedWithConnectIntegration(ctx, connectRoute, region, apiId, stageName,
                        connectionId, connectedAt, sourceIp, userAgent,
                        stageVariables, headers, queryParams, null);
            }
        } else {
            // No $connect route — complete the upgrade directly
            completeUpgrade(ctx, connectionId, apiId, stageName, region,
                    connectedAt, sourceIp, userAgent);
        }
    }

    /**
     * Proceed with the $connect integration invocation after authorizer check.
     * The authorizerContext parameter holds the extracted authorizer context (may be null if no authorizer).
     */
    private void proceedWithConnectIntegration(RoutingContext ctx, Route connectRoute,
                                               String region, String apiId, String stageName,
                                               String connectionId, long connectedAt,
                                               String sourceIp, String userAgent,
                                               Map<String, String> stageVariables,
                                               Map<String, List<String>> headers,
                                               Map<String, List<String>> queryParams,
                                               Map<String, Object> authorizerContext) {
        // Resolve the $connect integration
        String integrationId = parseIntegrationId(connectRoute.getTarget());
        Integration integration;
        try {
            integration = apiGatewayV2Service.getIntegration(region, apiId, integrationId);
        } catch (AwsException e) {
            LOG.warnv("$connect integration {0} not found for API {1}", integrationId, apiId);
            ctx.response().setStatusCode(500).end();
            return;
        }

        // Build the CONNECT proxy event with authorizer context
        String eventJson = proxyEventBuilder.buildConnectEvent(
                connectionId, apiId, stageName, region, connectedAt,
                headers, queryParams, sourceIp, userAgent, stageVariables, authorizerContext);

        // Execute the blocking Lambda invocation on a worker thread
        final Integration finalIntegration = integration;
        final Map<String, String> finalStageVariables = stageVariables;
        vertx.<WebSocketIntegrationInvoker.IntegrationResult>executeBlocking(() -> {
            return integrationInvoker.invoke(region, finalIntegration, eventJson,
                    finalStageVariables, Collections.emptyMap(), Collections.emptyMap());
        }).onSuccess(result -> {
            // Check for function error (Lambda invocation error)
            if (result.functionError() != null) {
                LOG.debugv("$connect Lambda returned function error: {0}, rejecting upgrade for API {1}",
                        result.functionError(), apiId);
                ctx.response().setStatusCode(500).end();
                return;
            }
            // Check the response status code
            if (result.statusCode() >= 200 && result.statusCode() <= 299) {
                // 2xx — complete the WebSocket upgrade with response headers from Lambda
                completeUpgradeWithHeaders(ctx, connectionId, apiId, stageName, region,
                        connectedAt, sourceIp, userAgent, result.headers());
            } else {
                // Non-2xx — reject the upgrade
                LOG.debugv("$connect route returned status {0}, rejecting upgrade for API {1}",
                        result.statusCode(), apiId);
                ctx.response().setStatusCode(403).end();
            }
        }).onFailure(e -> {
            LOG.warnv("$connect integration invocation failed for API {0}: {1}",
                    apiId, e.getMessage());
            ctx.response().setStatusCode(500).end();
        });
    }

    /** Maximum WebSocket frame payload size in bytes (128 KB, matching AWS limit). */
    private static final int MAX_FRAME_PAYLOAD_BYTES = 128 * 1024;

    /** Idle timeout in milliseconds (10 minutes, matching AWS default). */
    private static final long IDLE_TIMEOUT_MS = 10 * 60 * 1000L;

    /** Maximum connection duration in milliseconds (2 hours, matching AWS limit). */
    private static final long MAX_CONNECTION_DURATION_MS = 2 * 60 * 60 * 1000L;

    /**
     * Complete the WebSocket upgrade, register the connection, and attach message/close handlers.
     */
    private void completeUpgrade(RoutingContext ctx, String connectionId, String apiId,
                                 String stageName, String region, long connectedAt,
                                 String sourceIp, String userAgent) {
        ctx.request().toWebSocket().onSuccess(ws -> {
            // Register the connection
            ConnectionInfo connectionInfo = new ConnectionInfo(
                    connectionId, apiId, stageName, region,
                    connectedAt, connectedAt, sourceIp, userAgent);
            connectionManager.register(connectionId, connectionInfo, ws);

            LOG.debugv("WebSocket connection {0} established for API {1}/{2}",
                    connectionId, apiId, stageName);

            // Attach text message handler
            ws.textMessageHandler(msg -> {
                // Enforce payload size limit (AWS: 128 KB for WebSocket frames)
                if (msg.length() > MAX_FRAME_PAYLOAD_BYTES) {
                    LOG.debugv("Message exceeds 128 KB limit on connection {0} ({1} bytes)",
                            connectionId, msg.length());
                    safeWriteTextMessage(ws, "{\"message\":\"Message too long\",\"connectionId\":\""
                            + connectionId + "\"}", connectionId);
                    return;
                }
                onMessage(ws, connectionId, apiId, stageName, region, msg, false);
            });

            // Attach binary message handler (AWS supports binary frames with isBase64Encoded=true)
            ws.binaryMessageHandler(buffer -> {
                byte[] data = buffer.getBytes();
                // Enforce payload size limit
                if (data.length > MAX_FRAME_PAYLOAD_BYTES) {
                    LOG.debugv("Binary message exceeds 128 KB limit on connection {0} ({1} bytes)",
                            connectionId, data.length);
                    safeWriteTextMessage(ws, "{\"message\":\"Message too long\",\"connectionId\":\""
                            + connectionId + "\"}", connectionId);
                    return;
                }
                onBinaryMessage(ws, connectionId, apiId, stageName, region, data);
            });

            // Attach close handler
            ws.closeHandler(v -> onClose(ws, connectionId, apiId, stageName, region));

            // Schedule idle timeout check
            scheduleIdleTimeout(connectionId, connectedAt);
        }).onFailure(err -> {
            LOG.warnv("WebSocket upgrade failed for connection {0}: {1}",
                    connectionId, err.getMessage());
        });
    }

    /**
     * Complete the WebSocket upgrade with custom response headers from the $connect Lambda.
     * AWS allows the $connect Lambda to return headers that are included in the upgrade response.
     */
    private void completeUpgradeWithHeaders(RoutingContext ctx, String connectionId, String apiId,
                                            String stageName, String region, long connectedAt,
                                            String sourceIp, String userAgent,
                                            Map<String, String> responseHeaders) {
        // Add custom headers to the upgrade response before completing the WebSocket handshake
        if (responseHeaders != null && !responseHeaders.isEmpty()) {
            for (Map.Entry<String, String> header : responseHeaders.entrySet()) {
                // Skip hop-by-hop headers that shouldn't be propagated
                String key = header.getKey().toLowerCase();
                if (key.equals("connection") || key.equals("upgrade") || key.equals("sec-websocket-accept")
                        || key.equals("sec-websocket-key") || key.equals("sec-websocket-version")) {
                    continue;
                }
                ctx.response().putHeader(header.getKey(), header.getValue());
            }
        }
        completeUpgrade(ctx, connectionId, apiId, stageName, region, connectedAt, sourceIp, userAgent);
    }

    /**
     * Schedule periodic idle timeout and max duration checks for a connection.
     * AWS enforces a 10-minute idle timeout and 2-hour maximum connection duration.
     */
    private void scheduleIdleTimeout(String connectionId, long connectedAt) {
        // Check every 60 seconds
        vertx.setPeriodic(60_000L, timerId -> {
            ConnectionInfo info = connectionManager.getConnectionInfo(connectionId);
            if (info == null) {
                // Connection already closed — cancel the timer
                vertx.cancelTimer(timerId);
                return;
            }

            long now = System.currentTimeMillis();

            // Check max connection duration (2 hours)
            if (now - connectedAt > MAX_CONNECTION_DURATION_MS) {
                LOG.debugv("Connection {0} exceeded max duration (2h), closing", connectionId);
                vertx.cancelTimer(timerId);
                connectionManager.closeConnection(connectionId);
                return;
            }

            // Check idle timeout (10 minutes since last activity)
            if (now - info.getLastActiveAt() > IDLE_TIMEOUT_MS) {
                LOG.debugv("Connection {0} idle timeout (10m), closing", connectionId);
                vertx.cancelTimer(timerId);
                connectionManager.closeConnection(connectionId);
            }
        });
    }

    /**
     * Handle an incoming WebSocket binary message.
     * Encodes the binary data as base64 and routes it like a text message with isBase64Encoded=true.
     */
    private void onBinaryMessage(ServerWebSocket ws, String connectionId, String apiId,
                                 String stageName, String region, byte[] data) {
        LOG.debugv("Received binary message on connection {0}: {1} bytes", connectionId, data.length);

        // Update lastActiveAt timestamp
        connectionManager.updateLastActiveAt(connectionId);

        // Base64-encode the binary data for the proxy event body
        String base64Body = java.util.Base64.getEncoder().encodeToString(data);

        // Route using the base64 body (route selection won't match JSON fields in binary messages,
        // so it will fall through to $default)
        onMessage(ws, connectionId, apiId, stageName, region, base64Body, true);
    }

    /**
     * Handle an incoming WebSocket text message.
     * Routes the message to the appropriate integration based on the API's routeSelectionExpression.
     *
     * @param isBinary if true, the body is base64-encoded binary data and isBase64Encoded should be set in the event
     */
    private void onMessage(ServerWebSocket ws, String connectionId, String apiId,
                           String stageName, String region, String message, boolean isBinary) {
        LOG.debugv("Received message on connection {0}: {1}", connectionId, message);

        // Update lastActiveAt timestamp (Requirement 9.1)
        connectionManager.updateLastActiveAt(connectionId);

        // Load the API to get routeSelectionExpression
        Api api;
        try {
            api = apiGatewayV2Service.getApi(region, apiId);
        } catch (AwsException e) {
            LOG.warnv("Failed to load API {0} for message routing: {1}", apiId, e.getMessage());
            return;
        }

        String routeSelectionExpression = api.getRouteSelectionExpression();

        // Evaluate routeSelectionExpression against the message
        String routeKey = routeSelectionEvaluator.evaluate(routeSelectionExpression, message);

        // Look up matching route
        Route route = null;
        if (routeKey != null) {
            route = apiGatewayV2Service.findRouteByKey(region, apiId, routeKey);
        }

        // Fall back to $default if no match
        if (route == null) {
            route = apiGatewayV2Service.findRouteByKey(region, apiId, "$default");
        }

        // If no route found (no match and no $default)
        if (route == null) {
            String requestId = UUID.randomUUID().toString();
            if (routeKey == null) {
                // Message is non-JSON or field not found, and no $default
                String errorFrame = String.format(
                        "{\"message\":\"Could not route message\",\"connectionId\":\"%s\",\"requestId\":\"%s\"}",
                        connectionId, requestId);
                safeWriteTextMessage(ws, errorFrame, connectionId);
            } else {
                // Route key extracted but no matching route and no $default
                String errorFrame = String.format(
                        "{\"message\":\"No route found\",\"connectionId\":\"%s\",\"requestId\":\"%s\"}",
                        connectionId, requestId);
                safeWriteTextMessage(ws, errorFrame, connectionId);
            }
            return;
        }

        // Determine the effective route key for the proxy event
        String effectiveRouteKey = routeKey != null ? routeKey : "$default";

        // Resolve integration
        if (route.getTarget() == null) {
            LOG.debugv("Route {0} has no target integration", route.getRouteKey());
            return;
        }

        String integrationId = parseIntegrationId(route.getTarget());
        Integration integration;
        try {
            integration = apiGatewayV2Service.getIntegration(region, apiId, integrationId);
        } catch (AwsException e) {
            LOG.warnv("Integration {0} not found for route {1}: {2}",
                    integrationId, route.getRouteKey(), e.getMessage());
            String requestId = UUID.randomUUID().toString();
            String errorFrame = String.format(
                    "{\"message\":\"Internal server error\",\"connectionId\":\"%s\",\"requestId\":\"%s\"}",
                    connectionId, requestId);
            safeWriteTextMessage(ws, errorFrame, connectionId);
            return;
        }

        // Load stage variables
        Map<String, String> stageVariables = Collections.emptyMap();
        try {
            Stage stage = apiGatewayV2Service.getStage(region, apiId, stageName);
            if (stage.getStageVariables() != null) {
                stageVariables = stage.getStageVariables();
            }
        } catch (AwsException e) {
            LOG.debugv("Failed to load stage {0} for stage variables: {1}", stageName, e.getMessage());
        }

        // Get connection info for connectedAt, sourceIp, userAgent
        ConnectionInfo connectionInfo = connectionManager.getConnectionInfo(connectionId);
        long connectedAt = connectionInfo != null ? connectionInfo.getConnectedAt() : System.currentTimeMillis();
        String sourceIp = connectionInfo != null ? connectionInfo.getSourceIp() : "127.0.0.1";
        String userAgent = connectionInfo != null ? connectionInfo.getUserAgent() : "";

        // Build MESSAGE proxy event
        String eventJson = proxyEventBuilder.buildMessageEvent(
                connectionId, effectiveRouteKey, apiId, stageName, region,
                connectedAt, message, sourceIp, userAgent, stageVariables, isBinary);

        // Invoke integration on a worker thread to avoid blocking the event loop
        final Integration finalIntegration = integration;
        final Map<String, String> finalStageVariables = stageVariables;
        final Route finalRoute = route;
        final String finalConnectionId = connectionId;
        vertx.executeBlocking(() -> {
            return integrationInvoker.invoke(region, finalIntegration, eventJson,
                    finalStageVariables, Collections.emptyMap(), Collections.emptyMap());
        }).onSuccess(result -> {
            // Route response handling (Requirements 5.1–5.3)
            if (finalRoute.getRouteResponseSelectionExpression() != null && result.body() != null) {
                try {
                    JsonNode responseJson = objectMapper.readTree(result.body());
                    if (responseJson.has("body")) {
                        String responseBody = responseJson.get("body").asText();
                        safeWriteTextMessage(ws, responseBody, finalConnectionId);
                    } else {
                        // If no "body" field, send the entire body as-is
                        safeWriteTextMessage(ws, result.body(), finalConnectionId);
                    }
                } catch (Exception e) {
                    // If parsing fails, send the raw body
                    LOG.debugv("Failed to parse integration response as JSON, sending raw body: {0}",
                            e.getMessage());
                    safeWriteTextMessage(ws, result.body(), finalConnectionId);
                }
            }
        }).onFailure(e -> {
            LOG.warnv("Integration invocation failed for route {0} on connection {1}: {2}",
                    finalRoute.getRouteKey(), finalConnectionId, e.getMessage());
            String requestId = UUID.randomUUID().toString();
            String errorFrame = String.format(
                    "{\"message\":\"Internal server error\",\"connectionId\":\"%s\",\"requestId\":\"%s\"}",
                    finalConnectionId, requestId);
            safeWriteTextMessage(ws, errorFrame, finalConnectionId);
        });
    }

    /**
     * Handle WebSocket connection close.
     * Invokes the $disconnect route's Lambda integration (if configured) and then
     * unregisters the connection from the ConnectionManager.
     * Errors during $disconnect invocation are logged but never propagated.
     *
     * AWS behavior: $disconnect is NOT invoked when the close was initiated by the server
     * via the @connections DELETE API. Only client-initiated disconnections trigger $disconnect.
     */
    private void onClose(ServerWebSocket ws, String connectionId, String apiId,
                         String stageName, String region) {
        LOG.debugv("WebSocket connection {0} closed", connectionId);

        // Check if this close was initiated by the server via @connections DELETE API.
        // In AWS, server-initiated disconnections do NOT invoke the $disconnect Lambda.
        if (connectionManager.isServerInitiatedClose(connectionId)) {
            LOG.debugv("Skipping $disconnect for server-initiated close on connection {0}", connectionId);
            connectionManager.unregister(connectionId);
            return;
        }

        // Retrieve connection info BEFORE unregistering so we have connectedAt, sourceIp, userAgent
        ConnectionInfo connectionInfo = connectionManager.getConnectionInfo(connectionId);
        long connectedAt = connectionInfo != null ? connectionInfo.getConnectedAt() : System.currentTimeMillis();
        String sourceIp = connectionInfo != null ? connectionInfo.getSourceIp() : "127.0.0.1";
        String userAgent = connectionInfo != null ? connectionInfo.getUserAgent() : "";

        // Look up the $disconnect route
        Route disconnectRoute = apiGatewayV2Service.findRouteByKey(region, apiId, "$disconnect");

        if (disconnectRoute != null && disconnectRoute.getTarget() != null) {
            // Load stage variables
            Map<String, String> stageVariables = Collections.emptyMap();
            try {
                Stage stage = apiGatewayV2Service.getStage(region, apiId, stageName);
                if (stage.getStageVariables() != null) {
                    stageVariables = stage.getStageVariables();
                }
            } catch (AwsException e) {
                LOG.debugv("Failed to load stage {0} for $disconnect stage variables: {1}",
                        stageName, e.getMessage());
            }

            // Resolve integration and invoke on a worker thread
            String integrationId = parseIntegrationId(disconnectRoute.getTarget());
            Integration integration;
            try {
                integration = apiGatewayV2Service.getIntegration(region, apiId, integrationId);
            } catch (AwsException e) {
                LOG.warnv("$disconnect integration {0} not found for connection {1}: {2}",
                        integrationId, connectionId, e.getMessage());
                connectionManager.unregister(connectionId);
                return;
            }

            // Build DISCONNECT proxy event
            String eventJson = proxyEventBuilder.buildDisconnectEvent(
                    connectionId, apiId, stageName, region, connectedAt,
                    sourceIp, userAgent, stageVariables);

            // Execute the blocking Lambda invocation on a worker thread
            final Integration finalIntegration = integration;
            final Map<String, String> finalStageVariables = stageVariables;
            vertx.executeBlocking(() -> {
                integrationInvoker.invoke(region, finalIntegration, eventJson,
                        finalStageVariables, Collections.emptyMap(), Collections.emptyMap());
                return null;
            }).onComplete(ar -> {
                if (ar.failed()) {
                    // $disconnect errors must never prevent cleanup — log and continue
                    LOG.warnv("Error invoking $disconnect route for connection {0}: {1}",
                            connectionId, ar.cause().getMessage());
                }
                // Always unregister the connection regardless of $disconnect outcome
                connectionManager.unregister(connectionId);
            });
        } else {
            // No $disconnect route — just unregister
            connectionManager.unregister(connectionId);
        }
    }

    /**
     * Safely write a text message to a WebSocket, catching any exceptions that may occur
     * if the connection is in a closing or closed state.
     */
    private void safeWriteTextMessage(ServerWebSocket ws, String message, String connectionId) {
        try {
            ws.writeTextMessage(message);
        } catch (Exception e) {
            LOG.debugv("Failed to write message to connection {0}: {1}", connectionId, e.getMessage());
        }
    }

    /**
     * Generate a connection ID matching the AWS format.
     * AWS connection IDs are URL-safe base64-encoded random bytes, typically 10-14 characters
     * (e.g. "L0SM9cOFvHcCIhw", "d2ljbGVz").
     * Uses 9 random bytes → 12 base64url characters (no padding).
     */
    private static String generateConnectionId() {
        byte[] bytes = new byte[9];
        java.util.concurrent.ThreadLocalRandom.current().nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Parse the integrationId from a route target string.
     * Target format: "integrations/{integrationId}"
     */
    private String parseIntegrationId(String target) {
        if (target == null) {
            return null;
        }
        if (target.startsWith("integrations/")) {
            return target.substring("integrations/".length());
        }
        return target;
    }

    /**
     * Extract headers from the Vert.x routing context as a multi-value map.
     */
    private Map<String, List<String>> extractHeaders(RoutingContext ctx) {
        Map<String, List<String>> headers = new HashMap<>();
        ctx.request().headers().forEach(entry ->
                headers.computeIfAbsent(entry.getKey(), k -> new java.util.ArrayList<>())
                        .add(entry.getValue()));
        return headers;
    }

    /**
     * Extract query parameters from the Vert.x routing context as a multi-value map.
     */
    private Map<String, List<String>> extractQueryParams(RoutingContext ctx) {
        Map<String, List<String>> params = new HashMap<>();
        ctx.request().params().forEach(entry ->
                params.computeIfAbsent(entry.getKey(), k -> new java.util.ArrayList<>())
                        .add(entry.getValue()));
        return params;
    }

    /**
     * Resolve the AWS region from the Vert.x request headers.
     * Extracts the Authorization header and delegates to RegionResolver's parsing logic.
     * Falls back to the default region if no Authorization header is present.
     */
    private String resolveRegionFromVertxRequest(RoutingContext ctx) {
        String authHeader = ctx.request().getHeader("Authorization");
        if (authHeader == null || authHeader.isEmpty()) {
            return regionResolver.getDefaultRegion();
        }
        // Use a simple JAX-RS HttpHeaders adapter to delegate to RegionResolver
        jakarta.ws.rs.core.HttpHeaders headers = new SimpleHttpHeaders(authHeader);
        return regionResolver.resolveRegion(headers);
    }

    /**
     * Minimal HttpHeaders implementation that provides only the Authorization header.
     * Used to bridge Vert.x request headers to the RegionResolver API.
     */
    private static class SimpleHttpHeaders implements jakarta.ws.rs.core.HttpHeaders {
        private final String authorizationHeader;

        SimpleHttpHeaders(String authorizationHeader) {
            this.authorizationHeader = authorizationHeader;
        }

        @Override
        public String getHeaderString(String name) {
            if ("Authorization".equalsIgnoreCase(name)) {
                return authorizationHeader;
            }
            return null;
        }

        @Override
        public java.util.List<String> getRequestHeader(String name) {
            if ("Authorization".equalsIgnoreCase(name) && authorizationHeader != null) {
                return java.util.List.of(authorizationHeader);
            }
            return java.util.Collections.emptyList();
        }

        @Override
        public jakarta.ws.rs.core.MultivaluedMap<String, String> getRequestHeaders() {
            return new jakarta.ws.rs.core.MultivaluedHashMap<>();
        }

        @Override
        public java.util.List<jakarta.ws.rs.core.MediaType> getAcceptableMediaTypes() {
            return java.util.Collections.emptyList();
        }

        @Override
        public java.util.List<java.util.Locale> getAcceptableLanguages() {
            return java.util.Collections.emptyList();
        }

        @Override
        public jakarta.ws.rs.core.MediaType getMediaType() {
            return null;
        }

        @Override
        public java.util.Locale getLanguage() {
            return null;
        }

        @Override
        public java.util.Map<String, jakarta.ws.rs.core.Cookie> getCookies() {
            return java.util.Collections.emptyMap();
        }

        @Override
        public java.util.Date getDate() {
            return null;
        }

        @Override
        public int getLength() {
            return -1;
        }
    }
}
