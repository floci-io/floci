package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP proxy to Lambda functions via API Gateway v1 proxy event format.
 * Requests to /_api/{functionName}/{proxy+} are packaged as API Gateway proxy events
 * and synchronously invoked on the target Lambda function.
 */
@Path("/_api/{functionName}")
@Produces(MediaType.WILDCARD)
public class ApiGatewayController {

    private static final Logger LOG = Logger.getLogger(ApiGatewayController.class);
    private static final DateTimeFormatter LOG_STREAM_DATE_FMT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final LambdaService lambdaService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;
    private final CloudWatchLogsService cloudWatchLogsService;

    @Inject
    public ApiGatewayController(LambdaService lambdaService, RegionResolver regionResolver,
                                ObjectMapper objectMapper, CloudWatchLogsService cloudWatchLogsService) {
        this.lambdaService = lambdaService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
        this.cloudWatchLogsService = cloudWatchLogsService;
    }

    @GET
    @Path("/{proxy: .*}")
    public Response handleGet(@Context HttpHeaders headers, @Context UriInfo uriInfo,
                               @PathParam("functionName") String functionName,
                               @PathParam("proxy") String proxy) {
        return proxyRequest("GET", functionName, proxy, headers, uriInfo, null);
    }

    @POST
    @Path("/{proxy: .*}")
    public Response handlePost(@Context HttpHeaders headers, @Context UriInfo uriInfo,
                                @PathParam("functionName") String functionName,
                                @PathParam("proxy") String proxy,
                                byte[] body) {
        return proxyRequest("POST", functionName, proxy, headers, uriInfo, body);
    }

    @PUT
    @Path("/{proxy: .*}")
    public Response handlePut(@Context HttpHeaders headers, @Context UriInfo uriInfo,
                               @PathParam("functionName") String functionName,
                               @PathParam("proxy") String proxy,
                               byte[] body) {
        return proxyRequest("PUT", functionName, proxy, headers, uriInfo, body);
    }

    @DELETE
    @Path("/{proxy: .*}")
    public Response handleDelete(@Context HttpHeaders headers, @Context UriInfo uriInfo,
                                  @PathParam("functionName") String functionName,
                                  @PathParam("proxy") String proxy) {
        return proxyRequest("DELETE", functionName, proxy, headers, uriInfo, null);
    }

    @PATCH
    @Path("/{proxy: .*}")
    public Response handlePatch(@Context HttpHeaders headers, @Context UriInfo uriInfo,
                                 @PathParam("functionName") String functionName,
                                 @PathParam("proxy") String proxy,
                                 byte[] body) {
        return proxyRequest("PATCH", functionName, proxy, headers, uriInfo, body);
    }

    private Response proxyRequest(String httpMethod, String functionName, String proxy,
                                   HttpHeaders headers, UriInfo uriInfo, byte[] body) {
        String region = regionResolver.resolveRegion(headers);
        String path = "/" + (proxy == null ? "" : proxy);
        String requestId = UUID.randomUUID().toString();

        String logGroup = "/aws/apigateway/" + functionName;
        String logStream = LOG_STREAM_DATE_FMT.format(LocalDate.now());
        writeExecutionLog(logGroup, logStream, region, requestId,
                "HTTP Method: " + httpMethod + ", Resource Path: " + path);

        String eventJson = buildProxyEvent(httpMethod, path, proxy, headers, uriInfo, body, requestId);

        InvokeResult result;
        try {
            result = lambdaService.invoke(region, functionName, eventJson.getBytes(),
                    InvocationType.RequestResponse);
        } catch (AwsException e) {
            writeExecutionLog(logGroup, logStream, region, requestId,
                    "Method completed with status: 404");
            if (e.getHttpStatus() == 404) {
                return Response.status(404)
                        .entity("{\"message\":\"Function not found: " + functionName + "\"}")
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            }
            throw e;
        }

        Response response = buildHttpResponse(result);
        writeExecutionLog(logGroup, logStream, region, requestId,
                "Method completed with status: " + response.getStatus());
        return response;
    }

    private void writeExecutionLog(String logGroup, String logStream, String region,
                                    String requestId, String message) {
        try {
            cloudWatchLogsService.createLogGroup(logGroup, null, null, region);
        } catch (Exception ignored) {}
        try {
            cloudWatchLogsService.createLogStream(logGroup, logStream, region);
        } catch (Exception ignored) {}
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("timestamp", System.currentTimeMillis());
            event.put("message", "(" + requestId + ") " + message);
            cloudWatchLogsService.putLogEvents(logGroup, logStream, List.of(event), region);
        } catch (Exception e) {
            LOG.debugv("Could not write API Gateway execution log: {0}", e.getMessage());
        }
    }

    private String buildProxyEvent(String httpMethod, String path, String proxy,
                                    HttpHeaders headers, UriInfo uriInfo,
                                    byte[] body, String requestId) {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("resource", "/{proxy+}");
        event.put("path", path);
        event.put("httpMethod", httpMethod);

        // Headers
        ObjectNode headersNode = event.putObject("headers");
        MultivaluedMap<String, String> reqHeaders = headers.getRequestHeaders();
        for (Map.Entry<String, java.util.List<String>> entry : reqHeaders.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                headersNode.put(entry.getKey(), entry.getValue().get(0));
            }
        }

        // Query string parameters
        MultivaluedMap<String, String> queryParams = uriInfo.getQueryParameters();
        if (!queryParams.isEmpty()) {
            ObjectNode qspNode = event.putObject("queryStringParameters");
            for (Map.Entry<String, java.util.List<String>> entry : queryParams.entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    qspNode.put(entry.getKey(), entry.getValue().get(0));
                }
            }
        } else {
            event.putNull("queryStringParameters");
        }

        // Path parameters
        ObjectNode pathParams = event.putObject("pathParameters");
        pathParams.put("proxy", proxy != null ? proxy : "");

        event.putNull("stageVariables");

        // Request context
        ObjectNode ctx = event.putObject("requestContext");
        ctx.put("resourcePath", "/{proxy+}");
        ctx.put("httpMethod", httpMethod);
        ctx.put("stage", "local");
        ctx.put("requestId", requestId);
        ctx.put("requestTimeEpoch", System.currentTimeMillis());
        ObjectNode identity = ctx.putObject("identity");
        identity.put("sourceIp", "127.0.0.1");

        // Body
        if (body != null && body.length > 0) {
            event.put("body", new String(body));
            event.put("isBase64Encoded", false);
        } else {
            event.putNull("body");
            event.put("isBase64Encoded", false);
        }

        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize proxy event", e);
        }
    }

    private Response buildHttpResponse(InvokeResult result) {
        if (result.getPayload() == null || result.getPayload().length == 0) {
            int status = result.getFunctionError() != null ? 502 : result.getStatusCode();
            return Response.status(status).build();
        }

        try {
            JsonNode responseNode = objectMapper.readTree(result.getPayload());
            int statusCode = responseNode.path("statusCode").asInt(200);

            // If Lambda returned a function error and no valid statusCode, use 502
            if (result.getFunctionError() != null && !responseNode.has("statusCode")) {
                statusCode = 502;
            }

            Response.ResponseBuilder builder = Response.status(statusCode);

            // Apply response headers
            JsonNode responseHeaders = responseNode.get("headers");
            if (responseHeaders != null && responseHeaders.isObject()) {
                responseHeaders.fields().forEachRemaining(e ->
                        builder.header(e.getKey(), e.getValue().asText()));
            }
            JsonNode multiValueHeaders = responseNode.get("multiValueHeaders");
            if (multiValueHeaders != null && multiValueHeaders.isObject()) {
                multiValueHeaders.fields().forEachRemaining(e -> {
                    if (e.getValue().isArray()) {
                        e.getValue().forEach(v -> builder.header(e.getKey(), v.asText()));
                    }
                });
            }

            // Apply body
            JsonNode bodyNode = responseNode.get("body");
            if (bodyNode != null && !bodyNode.isNull()) {
                String bodyStr = bodyNode.asText();
                boolean isBase64 = responseNode.path("isBase64Encoded").asBoolean(false);
                byte[] bodyBytes = isBase64 ? Base64.getDecoder().decode(bodyStr)
                        : bodyStr.getBytes();

                // Determine content type from headers or default to JSON
                String contentType = MediaType.APPLICATION_JSON;
                JsonNode ct = responseNode.path("headers").path("Content-Type");
                if (!ct.isMissingNode() && !ct.isNull()) {
                    contentType = ct.asText();
                }
                builder.entity(bodyBytes).type(contentType);
            }

            return builder.build();

        } catch (Exception e) {
            LOG.warnv("Failed to parse Lambda response: {0}", e.getMessage());
            // Return raw payload with 502
            return Response.status(502)
                    .entity(result.getPayload())
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
    }
}
