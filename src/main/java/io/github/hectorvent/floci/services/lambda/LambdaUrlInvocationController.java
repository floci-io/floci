package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaAlias;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.UUID;

/**
 * Handles Lambda Function URL invocations.
 *
 * Supports host-based routing if possible, but also path-based routing:
 * /lambda-url/{urlId}/{proxy: .*}
 */
@Path("/lambda-url/{urlId}")
@Produces(MediaType.WILDCARD)
@Consumes(MediaType.WILDCARD)
public class LambdaUrlInvocationController {

    private static final Logger LOG = Logger.getLogger(LambdaUrlInvocationController.class);

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
                                 @Context HttpHeaders headers, @Context UriInfo uriInfo) {
        return invoke("DELETE", urlId, proxy, headers, uriInfo, null);
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

        if (target instanceof LambdaAlias alias) {
            functionName = alias.getFunctionName();
            functionArn = alias.getAliasArn();
            AwsArnUtils.Arn arn = AwsArnUtils.parse(functionArn);
            region = arn.region();
            accountId = arn.accountId();
        } else if (target instanceof LambdaFunction fn) {
            functionName = fn.getFunctionName();
            functionArn = fn.getFunctionArn();
            AwsArnUtils.Arn arn = AwsArnUtils.parse(functionArn);
            region = arn.region();
            accountId = fn.getAccountId() != null ? fn.getAccountId() : arn.accountId();
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
            return buildResponse(result);
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
        ctx.put("time", DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z").withZone(ZoneOffset.UTC).format(Instant.now()));
        ctx.put("timeEpoch", System.currentTimeMillis());

        ObjectNode httpNode = ctx.putObject("http");
        httpNode.put("method", method);
        httpNode.put("path", rawPath);
        httpNode.put("protocol", "HTTP/1.1");
        httpNode.put("sourceIp", "127.0.0.1");
        httpNode.put("userAgent", headers.getHeaderString("user-agent"));

        if (body != null && body.length > 0) {
            root.put("body", new String(body));
            root.put("isBase64Encoded", false);
        } else {
            root.putNull("body");
            root.put("isBase64Encoded", false);
        }

        return root.toString();
    }

    private Response buildResponse(InvokeResult result) {
        if (result.getPayload() == null || result.getPayload().length == 0) {
            return Response.status(result.getStatusCode()).build();
        }
        try {
            JsonNode node = objectMapper.readTree(result.getPayload());
            if (node.isObject() && node.has("statusCode")) {
                int status = node.get("statusCode").asInt();
                Response.ResponseBuilder builder = Response.status(status);
                if (node.has("headers")) {
                    node.get("headers").fields().forEachRemaining(e -> builder.header(e.getKey(), e.getValue().asText()));
                }
                if (node.has("body")) {
                    String body = node.get("body").asText();
                    boolean isBase64 = node.path("isBase64Encoded").asBoolean(false);
                    byte[] bytes = isBase64 ? Base64.getDecoder().decode(body) : body.getBytes();
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

    private String jsonMessage(String message) {
        return objectMapper.createObjectNode().put("message", message).toString();
    }
}
