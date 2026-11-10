package io.floci.aws.services.lambda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.CookieHeaders;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.services.lambda.model.InvocationType;
import io.floci.aws.services.lambda.model.InvokeResult;
import io.floci.aws.services.lambda.model.LambdaAlias;
import io.floci.aws.services.lambda.model.LambdaFunction;
import io.floci.aws.services.lambda.model.LambdaUrlConfig;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
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
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Handles Lambda Function URL invocations.
 *
 * Supports host-based routing if possible, but also path-based routing:
 * /lambda-url/{urlId}/{proxy: .*}
 *
 * Requests run on virtual threads: an invocation waits for its function to finish, and that wait
 * must not hold one of the shared request workers.
 */
@Path("/lambda-url/{urlId}")
@Produces(MediaType.WILDCARD)
@Consumes(MediaType.WILDCARD)
@RunOnVirtualThread
public class LambdaUrlInvocationController {

    private static final Logger LOG = Logger.getLogger(LambdaUrlInvocationController.class);
    private static final int FUNCTION_ERROR_STATUS = 502;
    private static final byte[] STREAM_PRELUDE_DELIMITER = new byte[8];

    private final LambdaService lambdaService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;
    private final RequestContext requestContext;

    @Inject
    public LambdaUrlInvocationController(LambdaService lambdaService, RegionResolver regionResolver,
                                         ObjectMapper objectMapper, RequestContext requestContext) {
        this.lambdaService = lambdaService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
        this.requestContext = requestContext;
    }

    @GET
    @Path("/{proxy: .*}")
    public Response handleGet(@PathParam("urlId") String urlId, @PathParam("proxy") String proxy,
                              @Context HttpHeaders headers, @Context UriInfo uriInfo) {
        return invoke("GET", urlId, proxy, headers, uriInfo, null);
    }

    @POST
    @Path("/{proxy: .*}")
    public Response handlePost(@PathParam("urlId") String urlId, @PathParam("proxy") String proxy,
                               @Context HttpHeaders headers, @Context UriInfo uriInfo, byte[] body) {
        return invoke("POST", urlId, proxy, headers, uriInfo, body);
    }

    @PUT
    @Path("/{proxy: .*}")
    public Response handlePut(@PathParam("urlId") String urlId, @PathParam("proxy") String proxy,
                              @Context HttpHeaders headers, @Context UriInfo uriInfo, byte[] body) {
        return invoke("PUT", urlId, proxy, headers, uriInfo, body);
    }

    @DELETE
    @Path("/{proxy: .*}")
    public Response handleDelete(@PathParam("urlId") String urlId, @PathParam("proxy") String proxy,
                                 @Context HttpHeaders headers, @Context UriInfo uriInfo,
                                 byte[] body) {
        return invoke("DELETE", urlId, proxy, headers, uriInfo, body);
    }

    @PATCH
    @Path("/{proxy: .*}")
    public Response handlePatch(@PathParam("urlId") String urlId, @PathParam("proxy") String proxy,
                                @Context HttpHeaders headers, @Context UriInfo uriInfo, byte[] body) {
        return invoke("PATCH", urlId, proxy, headers, uriInfo, body);
    }

    private Response invoke(String method, String urlId, String proxy, HttpHeaders headers, UriInfo uriInfo, byte[] body) {
        Object target = lambdaService.getTargetByUrlId(urlId);
        String functionName;
        String functionArn;
        String region;
        String accountId;
        LambdaUrlConfig urlConfig;

        if (target instanceof LambdaAlias alias) {
            functionName = alias.getFunctionName();
            functionArn = alias.getAliasArn();
            AwsArnUtils.Arn arn = AwsArnUtils.parse(functionArn);
            region = arn.region();
            accountId = arn.accountId();
            urlConfig = alias.getUrlConfig();
        } else if (target instanceof LambdaFunction fn) {
            functionName = fn.getFunctionName();
            functionArn = fn.getFunctionArn();
            AwsArnUtils.Arn arn = AwsArnUtils.parse(functionArn);
            region = arn.region();
            accountId = fn.getAccountId() != null ? fn.getAccountId() : arn.accountId();
            urlConfig = fn.getUrlConfig();
        } else {
            return Response.status(404).entity(jsonMessage("Function URL not found")).type(MediaType.APPLICATION_JSON).build();
        }

        requestContext.setAccountId(accountId);
        requestContext.setRegion(region);

        String requestId = UUID.randomUUID().toString();
        String event = buildEvent(method, urlId, proxy, headers, uriInfo, body, requestId, region);

        LOG.infov("Lambda URL invocation: {0} {1} -> {2} (region: {3})", method, urlId, functionName, region);

        try {
            InvokeResult result = lambdaService.invokeArn(
                    functionArn, event.getBytes(), InvocationType.RequestResponse);
            boolean streamed = urlConfig != null && "RESPONSE_STREAM".equals(urlConfig.getInvokeMode());
            return buildResponse(result, streamed);
        } catch (AwsException e) {
            return Response.status(e.getHttpStatus()).entity(e.getMessage()).build();
        }
    }

    private String buildEvent(String method, String urlId, String proxy, HttpHeaders headers, UriInfo uriInfo, byte[] body, String requestId, String region) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("version", "2.0");
        root.put("routeKey", "$default");
        String rawPath = "/" + (proxy != null ? proxy : "");
        root.put("rawPath", rawPath);
        root.put("rawQueryString", uriInfo.getRequestUri().getRawQuery() != null ? uriInfo.getRequestUri().getRawQuery() : "");

        List<String> cookies = CookieHeaders.cookiePairs(headers.getRequestHeaders());
        if (!cookies.isEmpty()) {
            ArrayNode cookiesNode = root.putArray("cookies");
            cookies.forEach(cookiesNode::add);
        }

        ObjectNode headersNode = root.putObject("headers");
        headers.getRequestHeaders().forEach((k, v) -> headersNode.put(k.toLowerCase(), String.join(",", v)));

        ObjectNode queryParams = root.putObject("queryStringParameters");
        uriInfo.getQueryParameters().forEach((k, v) -> queryParams.put(k, String.join(",", v)));

        ObjectNode ctx = root.putObject("requestContext");
        ctx.put("accountId", regionResolver.getAccountId());
        ctx.put("apiId", urlId);
        ctx.put("domainName", urlId + ".lambda-url." + region + ".localhost");
        ctx.put("domainPrefix", urlId);
        ctx.put("requestId", requestId);
        ctx.put("routeKey", "$default");
        ctx.put("stage", "$default");
        ctx.put("time", DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.ENGLISH)
                .withZone(ZoneOffset.UTC).format(Instant.now()));
        ctx.put("timeEpoch", System.currentTimeMillis());

        ObjectNode httpNode = ctx.putObject("http");
        httpNode.put("method", method);
        httpNode.put("path", rawPath);
        httpNode.put("protocol", "HTTP/1.1");
        httpNode.put("sourceIp", "127.0.0.1");
        httpNode.put("userAgent", headers.getHeaderString("user-agent"));

        if (body != null && body.length > 0) {
            if (isTextMediaType(headers.getHeaderString(HttpHeaders.CONTENT_TYPE))) {
                root.put("body", new String(body, StandardCharsets.UTF_8));
                root.put("isBase64Encoded", false);
            } else {
                root.put("body", Base64.getEncoder().encodeToString(body));
                root.put("isBase64Encoded", true);
            }
        } else {
            root.putNull("body");
            root.put("isBase64Encoded", false);
        }

        return root.toString();
    }

    /**
     * Mirrors how AWS decides isBase64Encoded for Function URL / API Gateway proxy
     * integration requests: it is driven by the Content-Type header, not by whether
     * the raw bytes happen to be valid UTF-8. Same content-type allowlist already used
     * for the equivalent ALB-Lambda integration in ElbV2DataPlane, normalized to
     * lowercase first since media types are case-insensitive.
     */
    private boolean isTextMediaType(String contentType) {
        String normalized = contentType == null ? null : contentType.toLowerCase(Locale.ROOT);
        return normalized == null || normalized.startsWith("text/") || normalized.contains("json")
                || normalized.contains("xml") || normalized.contains("form");
    }

    private Response buildResponse(InvokeResult result, boolean streamed) {
        if (result.getFunctionError() != null) {
            return buildFunctionErrorResponse(result);
        }
        if (result.getPayload() == null || result.getPayload().length == 0) {
            return Response.status(result.getStatusCode()).build();
        }
        if (streamed) {
            return buildStreamedResponse(result.getPayload());
        }
        try {
            JsonNode node = objectMapper.readTree(result.getPayload());
            if (node.isObject() && node.has("statusCode")) {
                int status = node.get("statusCode").asInt();
                Response.ResponseBuilder builder = Response.status(status);
                if (node.has("headers")) {
                    node.get("headers").fields().forEachRemaining(e -> builder.header(e.getKey(), e.getValue().asText()));
                }
                if (node.has("cookies") && node.get("cookies").isArray()) {
                    node.get("cookies").forEach(cookie -> builder.header("Set-Cookie", cookie.asText()));
                }
                if (node.has("body")) {
                    String body = node.get("body").asText();
                    boolean isBase64 = node.path("isBase64Encoded").asBoolean(false);
                    byte[] bytes = isBase64 ? Base64.getDecoder().decode(body) : body.getBytes(StandardCharsets.UTF_8);
                    builder.entity(bytes);
                }
                return builder.build();
            } else {
                return Response.ok(result.getPayload()).type(MediaType.APPLICATION_JSON).build();
            }
        } catch (Exception e) {
            return Response.ok(result.getPayload()).build();
        }
    }

    /**
     * A streamed response that went through {@code awslambda.HttpResponseStream.from} starts with a
     * JSON prelude (statusCode, headers, cookies) and eight NUL bytes; everything after them is the
     * body. Without a prelude, the whole stream is the body of a 200 application/octet-stream.
     */
    private Response buildStreamedResponse(byte[] payload) {
        int delimiter = indexOf(payload, STREAM_PRELUDE_DELIMITER);
        JsonNode prelude = delimiter < 0 ? null : readPrelude(Arrays.copyOfRange(payload, 0, delimiter));
        if (prelude == null) {
            return Response.ok(payload).type(MediaType.APPLICATION_OCTET_STREAM).build();
        }
        Response.ResponseBuilder builder = Response.status(prelude.path("statusCode").asInt(200));
        prelude.path("headers").fields().forEachRemaining(e -> builder.header(e.getKey(), e.getValue().asText()));
        prelude.path("cookies").forEach(cookie -> builder.header("Set-Cookie", cookie.asText()));
        return builder.entity(Arrays.copyOfRange(payload, delimiter + STREAM_PRELUDE_DELIMITER.length, payload.length))
                .build();
    }

    private JsonNode readPrelude(byte[] json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            LOG.debugv("Streamed Function URL response has no JSON prelude: {0}", e.getMessage());
            return null;
        }
    }

    private static int indexOf(byte[] data, byte[] target) {
        for (int i = 0; i <= data.length - target.length; i++) {
            if (Arrays.equals(data, i, i + target.length, target, 0, target.length)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Real Function URLs never surface the raw invocation error payload (stack traces,
     * error types, internal fields) to the caller. A raw error payload has no "body"
     * field, so, matching ApiGatewayController's Lambda proxy integration, the response
     * carries the 502 status with no entity at all.
     */
    private Response buildFunctionErrorResponse(InvokeResult result) {
        return Response.status(FUNCTION_ERROR_STATUS).build();
    }

    private String jsonMessage(String message) {
        return objectMapper.createObjectNode().put("message", message).toString();
    }
}
