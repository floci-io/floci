package io.github.hectorvent.floci.services.apigatewayv2.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.RegionResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Constructs AWS-compatible WebSocket proxy event JSON payloads for Lambda invocations.
 *
 * Produces CONNECT, MESSAGE, DISCONNECT, and Lambda REQUEST authorizer event formats
 * matching the AWS API Gateway v2 WebSocket proxy integration contract.
 */
@ApplicationScoped
public class WebSocketProxyEventBuilder {

    private static final Logger LOG = Logger.getLogger(WebSocketProxyEventBuilder.class);
    private static final DateTimeFormatter REQUEST_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss +0000", Locale.ENGLISH);

    @Inject
    ObjectMapper objectMapper;

    @Inject
    RegionResolver regionResolver;

    /**
     * Build a CONNECT event from the upgrade request.
     */
    public String buildConnectEvent(String connectionId, String apiId, String stageName,
                                    String region, long connectedAt,
                                    Map<String, List<String>> headers,
                                    Map<String, List<String>> queryParams,
                                    String sourceIp, String userAgent,
                                    Map<String, String> stageVariables,
                                    Map<String, Object> authorizerContext) {
        ObjectNode event = objectMapper.createObjectNode();

        putHeaders(event, headers);
        putMultiValueHeaders(event, headers);
        putQueryStringParameters(event, queryParams);
        putMultiValueQueryStringParameters(event, queryParams);
        putStageVariables(event, stageVariables);

        ObjectNode requestContext = buildRequestContext(connectionId, "$connect", "CONNECT",
                apiId, stageName, region, connectedAt, sourceIp, userAgent);

        // Include authorizer context in requestContext if present
        if (authorizerContext != null && !authorizerContext.isEmpty()) {
            ObjectNode authorizerNode = objectMapper.valueToTree(authorizerContext);
            requestContext.set("authorizer", authorizerNode);
        }

        event.set("requestContext", requestContext);

        event.put("isBase64Encoded", false);

        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize CONNECT proxy event", e);
        }
    }

    /**
     * Build a MESSAGE event from an incoming WebSocket message.
     */
    public String buildMessageEvent(String connectionId, String routeKey, String apiId,
                                    String stageName, String region, long connectedAt,
                                    String body, String sourceIp, String userAgent,
                                    Map<String, String> stageVariables) {
        return buildMessageEvent(connectionId, routeKey, apiId, stageName, region, connectedAt,
                body, sourceIp, userAgent, stageVariables, false);
    }

    /**
     * Build a MESSAGE event from an incoming WebSocket message with explicit base64 encoding flag.
     *
     * @param isBase64Encoded true if the body is base64-encoded binary data
     */
    public String buildMessageEvent(String connectionId, String routeKey, String apiId,
                                    String stageName, String region, long connectedAt,
                                    String body, String sourceIp, String userAgent,
                                    Map<String, String> stageVariables, boolean isBase64Encoded) {
        ObjectNode event = objectMapper.createObjectNode();

        ObjectNode requestContext = buildRequestContext(connectionId, routeKey, "MESSAGE",
                apiId, stageName, region, connectedAt, sourceIp, userAgent);
        event.set("requestContext", requestContext);

        if (body != null) {
            event.put("body", body);
        } else {
            event.putNull("body");
        }

        putStageVariables(event, stageVariables);
        event.put("isBase64Encoded", isBase64Encoded);

        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize MESSAGE proxy event", e);
        }
    }

    /**
     * Build a DISCONNECT event.
     */
    public String buildDisconnectEvent(String connectionId, String apiId, String stageName,
                                       String region, long connectedAt,
                                       String sourceIp, String userAgent,
                                       Map<String, String> stageVariables) {
        ObjectNode event = objectMapper.createObjectNode();

        ObjectNode requestContext = buildRequestContext(connectionId, "$disconnect", "DISCONNECT",
                apiId, stageName, region, connectedAt, sourceIp, userAgent);
        event.set("requestContext", requestContext);

        event.putNull("body");
        putStageVariables(event, stageVariables);
        event.put("isBase64Encoded", false);

        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize DISCONNECT proxy event", e);
        }
    }

    /**
     * Build a Lambda REQUEST authorizer event for $connect.
     */
    public String buildAuthorizerEvent(String connectionId, String apiId, String stageName,
                                       String region, long connectedAt,
                                       Map<String, List<String>> headers,
                                       Map<String, List<String>> queryParams,
                                       String sourceIp, String userAgent,
                                       Map<String, String> stageVariables) {
        ObjectNode event = objectMapper.createObjectNode();

        event.put("type", "REQUEST");
        event.put("methodArn", buildMethodArn(region, apiId, stageName));

        putHeaders(event, headers);
        putMultiValueHeaders(event, headers);
        putQueryStringParameters(event, queryParams);
        putMultiValueQueryStringParameters(event, queryParams);
        putStageVariables(event, stageVariables);

        ObjectNode requestContext = objectMapper.createObjectNode();
        requestContext.put("apiId", apiId);
        requestContext.put("stage", stageName);
        requestContext.put("connectionId", connectionId);
        requestContext.put("domainName", buildDomainName(apiId, region));

        String requestId = UUID.randomUUID().toString();
        requestContext.put("requestId", requestId);

        long now = System.currentTimeMillis();
        requestContext.put("requestTime", formatRequestTime(now));
        requestContext.put("requestTimeEpoch", now);

        ObjectNode identity = requestContext.putObject("identity");
        identity.put("sourceIp", sourceIp != null ? sourceIp : "127.0.0.1");
        identity.put("userAgent", userAgent != null ? userAgent : "");

        requestContext.put("eventType", "CONNECT");

        event.set("requestContext", requestContext);

        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize authorizer event", e);
        }
    }

    private ObjectNode buildRequestContext(String connectionId, String routeKey, String eventType,
                                           String apiId, String stageName, String region,
                                           long connectedAt, String sourceIp, String userAgent) {
        ObjectNode ctx = objectMapper.createObjectNode();

        ctx.put("routeKey", routeKey);
        ctx.put("eventType", eventType);

        String extendedRequestId = UUID.randomUUID().toString();
        ctx.put("extendedRequestId", extendedRequestId);

        long now = System.currentTimeMillis();
        ctx.put("requestTime", formatRequestTime(now));
        ctx.put("messageDirection", "IN");
        ctx.put("stage", stageName);
        ctx.put("connectedAt", connectedAt);
        ctx.put("requestTimeEpoch", now);

        ObjectNode identity = ctx.putObject("identity");
        identity.put("sourceIp", sourceIp != null ? sourceIp : "127.0.0.1");
        identity.put("userAgent", userAgent != null ? userAgent : "");

        String requestId = UUID.randomUUID().toString();
        ctx.put("requestId", requestId);
        ctx.put("domainName", buildDomainName(apiId, region));
        ctx.put("connectionId", connectionId);
        ctx.put("apiId", apiId);

        return ctx;
    }

    private void putHeaders(ObjectNode event, Map<String, List<String>> headers) {
        if (headers != null && !headers.isEmpty()) {
            ObjectNode headersNode = event.putObject("headers");
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                    headersNode.put(entry.getKey(), entry.getValue().get(0));
                }
            }
        } else {
            event.putNull("headers");
        }
    }

    private void putMultiValueHeaders(ObjectNode event, Map<String, List<String>> headers) {
        if (headers != null && !headers.isEmpty()) {
            ObjectNode mvHeaders = event.putObject("multiValueHeaders");
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                    ArrayNode arr = mvHeaders.putArray(entry.getKey());
                    for (String val : entry.getValue()) {
                        arr.add(val);
                    }
                }
            }
        } else {
            event.putNull("multiValueHeaders");
        }
    }

    private void putQueryStringParameters(ObjectNode event, Map<String, List<String>> queryParams) {
        if (queryParams != null && !queryParams.isEmpty()) {
            ObjectNode qsp = event.putObject("queryStringParameters");
            for (Map.Entry<String, List<String>> entry : queryParams.entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                    qsp.put(entry.getKey(), entry.getValue().get(0));
                }
            }
        } else {
            event.putNull("queryStringParameters");
        }
    }

    private void putMultiValueQueryStringParameters(ObjectNode event, Map<String, List<String>> queryParams) {
        if (queryParams != null && !queryParams.isEmpty()) {
            ObjectNode mvQsp = event.putObject("multiValueQueryStringParameters");
            for (Map.Entry<String, List<String>> entry : queryParams.entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                    ArrayNode arr = mvQsp.putArray(entry.getKey());
                    for (String val : entry.getValue()) {
                        arr.add(val);
                    }
                }
            }
        } else {
            event.putNull("multiValueQueryStringParameters");
        }
    }

    private void putStageVariables(ObjectNode event, Map<String, String> stageVariables) {
        if (stageVariables != null && !stageVariables.isEmpty()) {
            ObjectNode svNode = event.putObject("stageVariables");
            stageVariables.forEach(svNode::put);
        } else {
            event.putNull("stageVariables");
        }
    }

    private String buildDomainName(String apiId, String region) {
        return apiId + ".execute-api." + region + ".amazonaws.com";
    }

    private String buildMethodArn(String region, String apiId, String stageName) {
        return AwsArnUtils.Arn.of("execute-api", region, regionResolver.getAccountId(),
                apiId + "/" + stageName + "/$connect").toString();
    }

    private String formatRequestTime(long epochMillis) {
        ZonedDateTime time = ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC);
        return REQUEST_TIME_FORMATTER.format(time);
    }
}
