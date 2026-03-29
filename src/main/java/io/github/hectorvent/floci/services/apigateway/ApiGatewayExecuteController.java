package io.github.hectorvent.floci.services.apigateway;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.apigateway.model.ApiGatewayResource;
import io.github.hectorvent.floci.services.apigateway.model.Integration;
import io.github.hectorvent.floci.services.apigateway.model.IntegrationResponse;
import io.github.hectorvent.floci.services.apigateway.model.MethodConfig;
import io.github.hectorvent.floci.services.apigatewayv2.ApiGatewayV2Service;
import io.github.hectorvent.floci.services.apigatewayv2.model.Authorizer;
import io.github.hectorvent.floci.services.apigatewayv2.model.Route;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Executes API Gateway stage requests, routing them through the configured
 * integration (AWS_PROXY or MOCK).
 *
 * <p>Endpoint: {@code /{apiId}/{stageName}/{proxy+}}
 *
 * <p>This mirrors the real AWS execute-api URL format:
 * {@code https://{apiId}.execute-api.{region}.amazonaws.com/{stageName}/{path}}
 */
@Path("/execute-api/{apiId}/{stageName}")
@Produces(MediaType.WILDCARD)
public class ApiGatewayExecuteController {

    private static final Logger LOG = Logger.getLogger(ApiGatewayExecuteController.class);

    private final ApiGatewayService apiGatewayService;
    private final ApiGatewayV2Service apiGatewayV2Service;
    private final LambdaService lambdaService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;
    private final VtlTemplateEngine vtlEngine;
    private final AwsServiceRouter serviceRouter;

    @Inject
    public ApiGatewayExecuteController(ApiGatewayService apiGatewayService, ApiGatewayV2Service apiGatewayV2Service,
                                       LambdaService lambdaService, RegionResolver regionResolver,
                                       ObjectMapper objectMapper, VtlTemplateEngine vtlEngine,
                                       AwsServiceRouter serviceRouter) {
        this.apiGatewayService = apiGatewayService;
        this.apiGatewayV2Service = apiGatewayV2Service;
        this.lambdaService = lambdaService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
        this.vtlEngine = vtlEngine;
        this.serviceRouter = serviceRouter;
    }

    @GET
    @Path("/{proxy: .*}")
    public Response handleGet(@Context HttpHeaders headers, @Context UriInfo uriInfo,
                              @PathParam("apiId") String apiId,
                              @PathParam("stageName") String stageName,
                              @PathParam("proxy") String proxy) {
        return dispatch("GET", apiId, stageName, proxy, headers, uriInfo, null);
    }

    @POST
    @Path("/{proxy: .*}")
    public Response handlePost(@Context HttpHeaders headers, @Context UriInfo uriInfo,
                               @PathParam("apiId") String apiId,
                               @PathParam("stageName") String stageName,
                               @PathParam("proxy") String proxy,
                               byte[] body) {
        return dispatch("POST", apiId, stageName, proxy, headers, uriInfo, body);
    }

    @PUT
    @Path("/{proxy: .*}")
    public Response handlePut(@Context HttpHeaders headers, @Context UriInfo uriInfo,
                              @PathParam("apiId") String apiId,
                              @PathParam("stageName") String stageName,
                              @PathParam("proxy") String proxy,
                              byte[] body) {
        return dispatch("PUT", apiId, stageName, proxy, headers, uriInfo, body);
    }

    @DELETE
    @Path("/{proxy: .*}")
    public Response handleDelete(@Context HttpHeaders headers, @Context UriInfo uriInfo,
                                 @PathParam("apiId") String apiId,
                                 @PathParam("stageName") String stageName,
                                 @PathParam("proxy") String proxy) {
        return dispatch("DELETE", apiId, stageName, proxy, headers, uriInfo, null);
    }

    @PATCH
    @Path("/{proxy: .*}")
    public Response handlePatch(@Context HttpHeaders headers, @Context UriInfo uriInfo,
                                @PathParam("apiId") String apiId,
                                @PathParam("stageName") String stageName,
                                @PathParam("proxy") String proxy,
                                byte[] body) {
        return dispatch("PATCH", apiId, stageName, proxy, headers, uriInfo, body);
    }

    // ──────────────────────────── Core dispatch ────────────────────────────

    private Response dispatch(String httpMethod, String apiId, String stageName,
                              String proxy, HttpHeaders headers, UriInfo uriInfo, byte[] body) {
        String region = regionResolver.resolveRegion(headers);

        // Check if this is a v2 (HTTP API) or v1 (REST API)
        boolean isV2 = false;
        try {
            apiGatewayV2Service.getApi(region, apiId);
            isV2 = true;
        } catch (AwsException ignored) {
            // Not a v2 API — fall through to v1 handling
        }

        if (isV2) {
            return dispatchV2(httpMethod, apiId, stageName, proxy, headers, uriInfo, body, region);
        }

        // Verify API and stage exist
        try {
            apiGatewayService.getRestApi(region, apiId);
            apiGatewayService.getStage(region, apiId, stageName);
        } catch (AwsException e) {
            return Response.status(e.getHttpStatus())
                    .entity(jsonMessage(e.getMessage()))
                    .type(MediaType.APPLICATION_JSON).build();
        }

        String path = "/" + (proxy == null ? "" : proxy);

        // Find matching resource and method
        List<ApiGatewayResource> resources = apiGatewayService.getResources(region, apiId);
        ApiGatewayResource matched = matchResource(resources, path);
        if (matched == null) {
            return Response.status(404)
                    .entity(jsonMessage("Not Found"))
                    .type(MediaType.APPLICATION_JSON).build();
        }

        MethodConfig method = matched.getResourceMethods().get(httpMethod.toUpperCase());
        if (method == null) {
            return Response.status(405)
                    .entity(jsonMessage("Method Not Allowed"))
                    .type(MediaType.APPLICATION_JSON).build();
        }

        // 1. Authorizer
        Response authResponse = invokeAuthorizer(region, apiId, method, headers, uriInfo);
        if (authResponse != null) return authResponse;

        // 2. Request validation
        Response validationResponse = validateRequest(region, apiId, method, headers, uriInfo, body);
        if (validationResponse != null) return validationResponse;

        Integration integration = method.getMethodIntegration();
        if (integration == null) {
            return Response.status(500)
                    .entity(jsonMessage("No integration configured"))
                    .type(MediaType.APPLICATION_JSON).build();
        }

        LOG.debugv("execute-api: {0} {1}/{2}{3} → {4}", httpMethod, apiId, stageName, path,
                integration.getType());

        return switch (integration.getType().toUpperCase()) {
            case "AWS_PROXY" -> invokeProxy(region, httpMethod, path, proxy, stageName,
                    matched, integration, headers, uriInfo, body);
            case "AWS" -> invokeAwsIntegration(region, httpMethod, path, proxy, stageName,
                    matched, integration, headers, uriInfo, body);
            case "MOCK" -> invokeMock(integration);
            default -> Response.status(500)
                    .entity(jsonMessage("Unsupported integration type: " + integration.getType()))
                    .type(MediaType.APPLICATION_JSON).build();
        };
    }

    // ──────────────────────────── AWS_PROXY ────────────────────────────

    private Response invokeProxy(String region, String httpMethod, String path, String proxy,
                                 String stageName, ApiGatewayResource resource,
                                 Integration integration, HttpHeaders headers,
                                 UriInfo uriInfo, byte[] body) {
        String functionName = functionNameFromUri(integration.getUri());
        if (functionName == null) {
            return Response.status(500)
                    .entity(jsonMessage("Cannot resolve function from URI: " + integration.getUri()))
                    .type(MediaType.APPLICATION_JSON).build();
        }

        String requestId = UUID.randomUUID().toString();
        String eventJson = buildProxyEvent(httpMethod, path, proxy, resource.getPath(),
                stageName, headers, uriInfo, body, requestId);

        try {
            InvokeResult result = lambdaService.invoke(region, functionName, eventJson.getBytes(),
                    InvocationType.RequestResponse);
            return buildProxyResponse(result);
        } catch (AwsException e) {
            if (e.getHttpStatus() == 404) {
                return Response.status(404)
                        .entity(jsonMessage("Function not found: " + functionName))
                        .type(MediaType.APPLICATION_JSON).build();
            }
            throw e;
        }
    }

    private Response invokeAuthorizer(String region, String apiId, MethodConfig method,
                                      HttpHeaders headers, UriInfo uriInfo) {
        if ("CUSTOM".equals(method.getAuthorizationType())) {
            String authorizerId = method.getAuthorizerId();
            if (authorizerId == null) {
                return null;
            }

            io.github.hectorvent.floci.services.apigateway.model.Authorizer auth = apiGatewayService.getAuthorizer(region, apiId, authorizerId);
            String lambdaName = functionNameFromUri(auth.getAuthorizerUri());
            if (lambdaName == null) {
                return null;
            }

            String event = toAuthorizerEvent(auth, headers);
            try {
                InvokeResult result = lambdaService.invoke(region, lambdaName, event.getBytes(), InvocationType.RequestResponse);
                if (result.getFunctionError() != null) {
                    return Response.status(403).build();
                }
                
                JsonNode policy = objectMapper.readTree(result.getPayload());
                String effect = policy.path("policyDocument").path("Statement").get(0).path("Effect").asText("Deny");
                if ("Deny".equalsIgnoreCase(effect)) {
                    return Response.status(403).entity(jsonMessage("User is not authorized to access this resource")).build();
                }
            } catch (Exception e) {
                LOG.warnv("Authorizer failure: {0}", e.getMessage());
                return Response.status(500).build();
            }
        }
        return null;
    }

    private Response validateRequest(String region, String apiId, MethodConfig method,
                                      HttpHeaders headers, UriInfo uriInfo, byte[] body) {
        String validatorId = method.getRequestValidatorId();
        if (validatorId == null) return null;

        io.github.hectorvent.floci.services.apigateway.model.RequestValidator validator;
        try {
            validator = apiGatewayService.getRequestValidator(region, apiId, validatorId);
        } catch (AwsException e) {
            return null; // Validator not found — skip validation
        }

        // Validate request parameters
        if (validator.isValidateRequestParameters()) {
            Map<String, Boolean> requiredParams = method.getRequestParameters();
            if (requiredParams != null) {
                MultivaluedMap<String, String> queryParams = uriInfo.getQueryParameters();
                for (Map.Entry<String, Boolean> entry : requiredParams.entrySet()) {
                    if (!Boolean.TRUE.equals(entry.getValue())) continue;
                    String paramKey = entry.getKey();
                    // Format: method.request.querystring.name or method.request.header.name
                    if (paramKey.startsWith("method.request.querystring.")) {
                        String name = paramKey.substring("method.request.querystring.".length());
                        if (!queryParams.containsKey(name) || queryParams.getFirst(name) == null) {
                            return Response.status(400)
                                    .entity(jsonMessage("Missing required request parameter in QUERY_STRING: '" + name + "'"))
                                    .type(MediaType.APPLICATION_JSON).build();
                        }
                    } else if (paramKey.startsWith("method.request.header.")) {
                        String name = paramKey.substring("method.request.header.".length());
                        if (headers.getHeaderString(name) == null) {
                            return Response.status(400)
                                    .entity(jsonMessage("Missing required request parameter in HEADER: '" + name + "'"))
                                    .type(MediaType.APPLICATION_JSON).build();
                        }
                    }
                }
            }
        }

        // Validate request body against model schema
        if (validator.isValidateRequestBody()) {
            Map<String, String> requestModels = method.getRequestModels();
            if (requestModels != null && !requestModels.isEmpty()) {
                String contentType = headers.getMediaType() != null
                        ? headers.getMediaType().getType() + "/" + headers.getMediaType().getSubtype()
                        : "application/json";
                String modelName = requestModels.get(contentType);
                if (modelName == null) modelName = requestModels.get("application/json");

                if (modelName != null) {
                    try {
                        io.github.hectorvent.floci.services.apigateway.model.Model model =
                                apiGatewayService.getModel(region, apiId, modelName);
                        String schemaStr = model.getSchema();
                        if (schemaStr != null && !schemaStr.isBlank()) {
                            String bodyStr = body != null ? new String(body, StandardCharsets.UTF_8) : "";
                            if (bodyStr.isBlank()) {
                                return Response.status(400)
                                        .entity(jsonMessage("Invalid request body"))
                                        .type(MediaType.APPLICATION_JSON).build();
                            }
                            JsonNode schemaNode = objectMapper.readTree(schemaStr);
                            JsonNode bodyNode = objectMapper.readTree(bodyStr);

                            com.networknt.schema.JsonSchemaFactory factory =
                                    com.networknt.schema.JsonSchemaFactory.getInstance(
                                            com.networknt.schema.SpecVersion.VersionFlag.V4);
                            com.networknt.schema.JsonSchema schema = factory.getSchema(schemaNode);
                            var errors = schema.validate(bodyNode);
                            if (!errors.isEmpty()) {
                                String errorMsg = errors.iterator().next().getMessage();
                                return Response.status(400)
                                        .entity(jsonMessage("Invalid request body: " + errorMsg))
                                        .type(MediaType.APPLICATION_JSON).build();
                            }
                        }
                    } catch (AwsException e) {
                        // Model not found — skip body validation
                    } catch (Exception e) {
                        return Response.status(400)
                                .entity(jsonMessage("Invalid request body"))
                                .type(MediaType.APPLICATION_JSON).build();
                    }
                }
            }
        }

        return null;
    }

    private String toAuthorizerEvent(io.github.hectorvent.floci.services.apigateway.model.Authorizer auth, HttpHeaders headers) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", auth.getType());
        node.put("methodArn", "arn:aws:execute-api:" + regionResolver.getDefaultRegion()
                + ":" + regionResolver.getAccountId() + ":api/stage/METHOD/path");
        if ("TOKEN".equals(auth.getType())) {
            String headerName = auth.getIdentitySource().replace("method.request.header.", "");
            node.put("authorizationToken", headers.getHeaderString(headerName));
        }
        return node.toString();
    }

    /**
     * Extracts function name from integration URI like
     * {@code arn:aws:apigateway:...:lambda:path/2015-03-31/functions/{fnArn}/invocations}.
     */
    private String functionNameFromUri(String uri) {
        if (uri == null) {
            return null;
        }
        // URI contains "function:{name}" or "function:{arn}"
        int idx = uri.indexOf("function:");
        if (idx < 0) {
            return null;
        }
        String after = uri.substring(idx + "function:".length());
        // after may be "myFn/invocations" or "arn:aws:lambda:...:function:myFn/invocations"
        // If it starts with "arn:" recurse on the remaining ARN
        if (after.startsWith("arn:")) {
            int fnIdx = after.lastIndexOf(":function:");
            if (fnIdx < 0) {
                return null;
            }
            after = after.substring(fnIdx + ":function:".length());
        }
        // Strip trailing "/invocations" or any path suffix
        int slash = after.indexOf('/');
        return slash >= 0 ? after.substring(0, slash) : after;
    }

    private String buildProxyEvent(String httpMethod, String path, String proxy,
                                   String resourcePath, String stageName,
                                   HttpHeaders headers, UriInfo uriInfo,
                                   byte[] body, String requestId) {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("resource", resourcePath);
        event.put("path", path);
        event.put("httpMethod", httpMethod);

        ObjectNode headersNode = event.putObject("headers");
        MultivaluedMap<String, String> reqHeaders = headers.getRequestHeaders();
        for (Map.Entry<String, java.util.List<String>> e : reqHeaders.entrySet()) {
            if (!e.getValue().isEmpty()) headersNode.put(e.getKey(), e.getValue().get(0));
        }

        MultivaluedMap<String, String> queryParams = uriInfo.getQueryParameters();
        if (!queryParams.isEmpty()) {
            ObjectNode qsp = event.putObject("queryStringParameters");
            for (Map.Entry<String, java.util.List<String>> e : queryParams.entrySet()) {
                if (!e.getValue().isEmpty()) qsp.put(e.getKey(), e.getValue().get(0));
            }
        } else {
            event.putNull("queryStringParameters");
        }

        ObjectNode pathParams = event.putObject("pathParameters");
        if (proxy != null && !proxy.isEmpty()) pathParams.put("proxy", proxy);

        event.putNull("stageVariables");

        ObjectNode ctx = event.putObject("requestContext");
        ctx.put("resourcePath", resourcePath);
        ctx.put("httpMethod", httpMethod);
        ctx.put("stage", stageName);
        ctx.put("requestId", requestId);
        ctx.put("requestTimeEpoch", System.currentTimeMillis());
        ctx.putObject("identity").put("sourceIp", "127.0.0.1");

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

    private Response buildProxyResponse(InvokeResult result) {
        if (result.getPayload() == null || result.getPayload().length == 0) {
            return Response.status(result.getFunctionError() != null ? 502 : result.getStatusCode()).build();
        }
        try {
            JsonNode node = objectMapper.readTree(result.getPayload());
            int statusCode = node.path("statusCode").asInt(200);
            if (result.getFunctionError() != null && !node.has("statusCode")) statusCode = 502;

            Response.ResponseBuilder builder = Response.status(statusCode);

            JsonNode respHeaders = node.get("headers");
            if (respHeaders != null && respHeaders.isObject()) {
                respHeaders.fields().forEachRemaining(e -> builder.header(e.getKey(), e.getValue().asText()));
            }
            JsonNode multiHeaders = node.get("multiValueHeaders");
            if (multiHeaders != null && multiHeaders.isObject()) {
                multiHeaders.fields().forEachRemaining(e -> {
                    if (e.getValue().isArray()) e.getValue().forEach(v -> builder.header(e.getKey(), v.asText()));
                });
            }

            JsonNode bodyNode = node.get("body");
            if (bodyNode != null && !bodyNode.isNull()) {
                String bodyStr = bodyNode.asText();
                boolean isBase64 = node.path("isBase64Encoded").asBoolean(false);
                byte[] bytes = isBase64 ? Base64.getDecoder().decode(bodyStr) : bodyStr.getBytes();
                String ct = MediaType.APPLICATION_JSON;
                JsonNode ctNode = node.path("headers").path("Content-Type");
                if (!ctNode.isMissingNode() && !ctNode.isNull()) ct = ctNode.asText();
                builder.entity(bytes).type(ct);
            }
            return builder.build();
        } catch (Exception e) {
            LOG.warnv("Failed to parse Lambda response: {0}", e.getMessage());
            return Response.status(502).entity(result.getPayload()).type(MediaType.APPLICATION_JSON).build();
        }
    }

    // ──────────────────────────── AWS (non-proxy) ────────────────────────────

    private Response invokeAwsIntegration(String region, String httpMethod, String path, String proxy,
                                          String stageName, ApiGatewayResource resource,
                                          Integration integration, HttpHeaders headers,
                                          UriInfo uriInfo, byte[] body) {
        AwsServiceRouter.IntegrationTarget target = serviceRouter.parseIntegrationUri(integration.getUri());
        if (target == null) {
            return Response.status(500)
                    .entity(jsonMessage("Cannot parse AWS integration URI: " + integration.getUri()))
                    .type(MediaType.APPLICATION_JSON).build();
        }

        String requestId = UUID.randomUUID().toString();
        String bodyStr = body != null && body.length > 0 ? new String(body) : null;

        // Build VTL context
        Map<String, String> headerMap = new HashMap<>();
        for (Map.Entry<String, List<String>> e : headers.getRequestHeaders().entrySet()) {
            if (!e.getValue().isEmpty()) headerMap.put(e.getKey(), e.getValue().get(0));
        }
        Map<String, String> queryMap = new HashMap<>();
        for (Map.Entry<String, List<String>> e : uriInfo.getQueryParameters().entrySet()) {
            if (!e.getValue().isEmpty()) queryMap.put(e.getKey(), e.getValue().get(0));
        }
        Map<String, String> pathMap = new HashMap<>();
        if (proxy != null && !proxy.isEmpty()) pathMap.put("proxy", proxy);

        VtlTemplateEngine.VtlContext vtlCtx = new VtlTemplateEngine.VtlContext(
                bodyStr, headerMap, queryMap, pathMap, stageName, httpMethod,
                resource.getPath(), requestId, "000000000000", null);

        // Apply request parameter mapping (method.request.* → integration.request.*)
        Map<String, String> integrationReqParams = integration.getRequestParameters();
        if (integrationReqParams != null && !integrationReqParams.isEmpty()) {
            for (Map.Entry<String, String> param : integrationReqParams.entrySet()) {
                String dest = param.getKey();    // integration.request.header.X-Foo or integration.request.querystring.bar
                String source = param.getValue(); // method.request.querystring.q or method.request.header.Auth or method.request.path.id
                String resolvedValue = resolveRequestParameter(source, queryMap, pathMap, headerMap);
                if (resolvedValue != null) {
                    if (dest.startsWith("integration.request.header.")) {
                        headerMap.put(dest.substring("integration.request.header.".length()), resolvedValue);
                    } else if (dest.startsWith("integration.request.querystring.")) {
                        queryMap.put(dest.substring("integration.request.querystring.".length()), resolvedValue);
                    } else if (dest.startsWith("integration.request.path.")) {
                        pathMap.put(dest.substring("integration.request.path.".length()), resolvedValue);
                    }
                }
            }
        }

        // Content-Type negotiation and passthrough behavior
        String transformedBody;
        Map<String, String> requestTemplates = integration.getRequestTemplates();
        String incomingContentType = headerMap.getOrDefault("Content-Type",
                headerMap.getOrDefault("content-type", "application/json"));

        if (requestTemplates != null && !requestTemplates.isEmpty()) {
            // Try exact match first, then wildcard fallback
            String template = requestTemplates.get(incomingContentType);
            if (template == null) {
                // Try without charset: "application/json; charset=utf-8" → "application/json"
                String baseType = incomingContentType.contains(";")
                        ? incomingContentType.substring(0, incomingContentType.indexOf(';')).trim()
                        : incomingContentType;
                template = requestTemplates.get(baseType);
            }

            if (template != null) {
                transformedBody = vtlEngine.evaluate(template, vtlCtx);
            } else {
                // No matching template for this Content-Type
                String behavior = integration.getPassthroughBehavior();
                if ("NEVER".equalsIgnoreCase(behavior)) {
                    return Response.status(415)
                            .entity(jsonMessage("Unsupported Media Type"))
                            .type(MediaType.APPLICATION_JSON).build();
                } else if ("WHEN_NO_TEMPLATES".equalsIgnoreCase(behavior)) {
                    // Templates exist but none match → reject
                    return Response.status(415)
                            .entity(jsonMessage("Unsupported Media Type"))
                            .type(MediaType.APPLICATION_JSON).build();
                } else {
                    // WHEN_NO_MATCH (default) — passthrough
                    transformedBody = bodyStr != null ? bodyStr : "";
                }
            }
        } else {
            // No templates defined at all
            String behavior = integration.getPassthroughBehavior();
            if ("NEVER".equalsIgnoreCase(behavior)) {
                return Response.status(415)
                        .entity(jsonMessage("Unsupported Media Type"))
                        .type(MediaType.APPLICATION_JSON).build();
            }
            transformedBody = bodyStr != null ? bodyStr : "";
        }

        // Dispatch to service
        Response serviceResponse;
        String errorType = null;
        String errorMessage = null;
        try {
            JsonNode requestJson = objectMapper.readTree(transformedBody);
            serviceResponse = serviceRouter.invoke(target.service(), target.action(), requestJson, region);
        } catch (AwsException e) {
            errorType = e.getErrorCode();
            errorMessage = e.getMessage();
            serviceResponse = null;
        } catch (Exception e) {
            errorType = "InternalError";
            errorMessage = e.getMessage() != null ? e.getMessage() : "Service invocation failed";
            serviceResponse = null;
        }

        // Build response body string
        String responseBodyStr;
        int serviceStatus;
        if (serviceResponse != null) {
            serviceStatus = serviceResponse.getStatus();
            Object entity = serviceResponse.getEntity();
            if (entity instanceof JsonNode jsonNode) {
                try {
                    responseBodyStr = objectMapper.writeValueAsString(jsonNode);
                } catch (Exception e) {
                    responseBodyStr = entity.toString();
                }
            } else if (entity != null) {
                responseBodyStr = entity.toString();
            } else {
                responseBodyStr = "{}";
            }

            // Check if service returned an error status
            if (serviceStatus >= 400) {
                try {
                    JsonNode errorNode = objectMapper.readTree(responseBodyStr);
                    errorType = errorNode.path("__type").asText(
                            errorNode.path("errorType").asText(null));
                    errorMessage = errorNode.path("message").asText(
                            errorNode.path("Message").asText(
                                    errorNode.path("errorMessage").asText("Service error")));
                } catch (Exception ignored) {
                    errorType = "ServiceError";
                    errorMessage = responseBodyStr;
                }
            }
        } else {
            serviceStatus = 500;
            responseBodyStr = String.format("{\"errorMessage\":\"%s\",\"errorType\":\"%s\"}",
                    errorMessage != null ? errorMessage.replace("\"", "\\\"") : "Unknown error",
                    errorType != null ? errorType : "UnknownError");
        }

        // Select integration response
        Map<String, IntegrationResponse> integrationResponses = integration.getIntegrationResponses();
        IntegrationResponse matchedResponse = null;
        IntegrationResponse defaultResponse = null;

        // Build the error string to match selectionPattern against.
        // AWS matches against the error response body/message. We match against
        // both errorType and errorMessage to catch patterns like ".*ResourceNotFoundException.*".
        String errorMatchString = errorType != null
                ? errorType + (errorMessage != null ? ": " + errorMessage : "")
                : errorMessage;

        if (integrationResponses != null && !integrationResponses.isEmpty()) {
            for (IntegrationResponse ir : integrationResponses.values()) {
                if (ir.selectionPattern() == null || ir.selectionPattern().isEmpty()) {
                    defaultResponse = ir;
                } else if (errorMatchString != null) {
                    try {
                        if (Pattern.matches(ir.selectionPattern(), errorMatchString)) {
                            matchedResponse = ir;
                            break;
                        }
                    } catch (Exception ignored) {
                        // Invalid regex — skip
                    }
                }
            }
            if (matchedResponse == null) {
                matchedResponse = defaultResponse;
            }
        }

        // Determine final status code and body
        int finalStatus;
        String finalBody;

        if (matchedResponse != null) {
            finalStatus = Integer.parseInt(matchedResponse.statusCode());

            Map<String, String> responseTemplates = matchedResponse.responseTemplates();
            if (responseTemplates != null && !responseTemplates.isEmpty()) {
                String responseTemplate = responseTemplates.getOrDefault("application/json",
                        responseTemplates.values().iterator().next());
                if (responseTemplate != null && !responseTemplate.isEmpty()) {
                    VtlTemplateEngine.VtlContext responseMappingCtx = new VtlTemplateEngine.VtlContext(
                            responseBodyStr, headerMap, queryMap, pathMap, stageName, httpMethod,
                            resource.getPath(), requestId, "000000000000", null);
                    finalBody = vtlEngine.evaluate(responseTemplate, responseMappingCtx);
                } else {
                    finalBody = responseBodyStr;
                }
            } else {
                finalBody = responseBodyStr;
            }
        } else {
            finalStatus = errorType != null ? 500 : (serviceStatus >= 400 ? serviceStatus : 200);
            finalBody = responseBodyStr;
        }

        Response.ResponseBuilder rb = Response.status(finalStatus)
                .entity(finalBody)
                .type(MediaType.APPLICATION_JSON);

        // Apply response parameter mapping (header mapping)
        if (matchedResponse != null && matchedResponse.responseParameters() != null) {
            Map<String, String> serviceResponseHeaders = new HashMap<>();
            if (serviceResponse != null) {
                for (Map.Entry<String, List<String>> e : serviceResponse.getStringHeaders().entrySet()) {
                    if (!e.getValue().isEmpty()) serviceResponseHeaders.put(e.getKey(), e.getValue().get(0));
                }
            }
            for (Map.Entry<String, String> param : matchedResponse.responseParameters().entrySet()) {
                String dest = param.getKey();   // method.response.header.X-Foo
                String source = param.getValue(); // integration.response.header.X-Bar or 'static' or integration.response.body.jsonpath
                if (!dest.startsWith("method.response.header.")) continue;
                String headerName = dest.substring("method.response.header.".length());
                String headerValue = resolveResponseParameter(source, serviceResponseHeaders, responseBodyStr);
                if (headerValue != null) {
                    rb.header(headerName, headerValue);
                }
            }
        }

        return rb.build();
    }

    private String resolveResponseParameter(String source, Map<String, String> serviceHeaders, String responseBody) {
        if (source == null) return null;
        // Static value: 'some value'
        if (source.startsWith("'") && source.endsWith("'")) {
            return source.substring(1, source.length() - 1);
        }
        // Integration response header
        if (source.startsWith("integration.response.header.")) {
            String headerName = source.substring("integration.response.header.".length());
            return serviceHeaders.get(headerName);
        }
        // Integration response body (JSONPath)
        if (source.startsWith("integration.response.body.")) {
            String jsonPath = "$." + source.substring("integration.response.body.".length());
            try {
                JsonNode root = objectMapper.readTree(responseBody);
                JsonNode node = VtlTemplateEngine.InputVariable.resolvePath(root, jsonPath);
                return node.isMissingNode() ? null : node.asText();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private String resolveRequestParameter(String source, Map<String, String> queryParams,
                                            Map<String, String> pathParams, Map<String, String> headers) {
        if (source == null) return null;
        if (source.startsWith("method.request.querystring.")) {
            return queryParams.get(source.substring("method.request.querystring.".length()));
        }
        if (source.startsWith("method.request.path.")) {
            return pathParams.get(source.substring("method.request.path.".length()));
        }
        if (source.startsWith("method.request.header.")) {
            return headers.get(source.substring("method.request.header.".length()));
        }
        // Static value
        if (source.startsWith("'") && source.endsWith("'")) {
            return source.substring(1, source.length() - 1);
        }
        return null;
    }

    // ──────────────────────────── MOCK ────────────────────────────

    private Response invokeMock(Integration integration) {
        // Use the "200" integration response if present, else return empty 200
        IntegrationResponse ir = integration.getIntegrationResponses().get("200");
        if (ir == null) {
            return Response.ok().build();
        }
        String template = ir.responseTemplates() != null
                ? ir.responseTemplates().getOrDefault("application/json", "") : "";
        return Response.status(Integer.parseInt(ir.statusCode()))
                .entity(template)
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    // ──────────────────────────── API Gateway v2 dispatch ────────────────────────────

    private Response dispatchV2(String httpMethod, String apiId, String stageName,
                                String proxy, HttpHeaders headers, UriInfo uriInfo,
                                byte[] body, String region) {
        String path = "/" + (proxy == null ? "" : proxy);

        Route route = apiGatewayV2Service.findMatchingRoute(region, apiId, httpMethod, path);
        if (route == null) {
            return Response.status(404)
                    .entity(jsonMessage("Not Found"))
                    .type(MediaType.APPLICATION_JSON).build();
        }

        if ("JWT".equalsIgnoreCase(route.getAuthorizationType()) && route.getAuthorizerId() != null) {
            Response authError = enforceJwtAuthorizer(region, apiId, route, headers);
            if (authError != null) return authError;
        }

        if (route.getTarget() == null) {
            return Response.status(500)
                    .entity(jsonMessage("No integration configured"))
                    .type(MediaType.APPLICATION_JSON).build();
        }

        // target is "integrations/{integrationId}"
        String integrationId = route.getTarget().startsWith("integrations/")
                ? route.getTarget().substring("integrations/".length()) : route.getTarget();

        io.github.hectorvent.floci.services.apigatewayv2.model.Integration integration;
        try {
            integration = apiGatewayV2Service.getIntegration(region, apiId, integrationId);
        } catch (AwsException e) {
            return Response.status(500)
                    .entity(jsonMessage("Integration not found: " + integrationId))
                    .type(MediaType.APPLICATION_JSON).build();
        }

        String functionName = functionNameFromUri(integration.getIntegrationUri());
        if (functionName == null) {
            return Response.status(500)
                    .entity(jsonMessage("Cannot resolve function from URI: " + integration.getIntegrationUri()))
                    .type(MediaType.APPLICATION_JSON).build();
        }

        String requestId = UUID.randomUUID().toString();
        String eventJson = buildV2ProxyEvent(httpMethod, path, route.getRouteKey(),
                apiId, stageName, headers, uriInfo, body, requestId);

        LOG.debugv("execute-api v2: {0} {1}/{2}{3} → Lambda {4}", httpMethod, apiId, stageName, path, functionName);

        try {
            InvokeResult result = lambdaService.invoke(region, functionName,
                    eventJson.getBytes(), InvocationType.RequestResponse);
            return buildProxyResponse(result);
        } catch (AwsException e) {
            if (e.getHttpStatus() == 404) {
                return Response.status(404)
                        .entity(jsonMessage("Function not found: " + functionName))
                        .type(MediaType.APPLICATION_JSON).build();
            }
            throw e;
        }
    }

    private Response enforceJwtAuthorizer(String region, String apiId, Route route, HttpHeaders headers) {
        Authorizer authorizer;
        try {
            authorizer = apiGatewayV2Service.getAuthorizer(region, apiId, route.getAuthorizerId());
        } catch (AwsException e) {
            return Response.status(500)
                    .entity(jsonMessage("Authorizer not found"))
                    .type(MediaType.APPLICATION_JSON).build();
        }

        String token = extractToken(authorizer, headers);
        if (token == null) {
            return Response.status(401)
                    .entity(jsonMessage("Unauthorized"))
                    .type(MediaType.APPLICATION_JSON).build();
        }

        JwtClaims claims = parseJwtClaims(token);
        if (claims == null) {
            return Response.status(401)
                    .entity(jsonMessage("Unauthorized"))
                    .type(MediaType.APPLICATION_JSON).build();
        }

        if (claims.exp > 0 && claims.exp < System.currentTimeMillis() / 1000) {
            return Response.status(401)
                    .entity(jsonMessage("The incoming token has expired"))
                    .type(MediaType.APPLICATION_JSON).build();
        }

        if (authorizer.getJwtConfiguration() != null) {
            String issuer = authorizer.getJwtConfiguration().issuer();
            if (issuer != null && !issuer.isBlank() && !issuer.equals(claims.iss)) {
                return Response.status(401)
                        .entity(jsonMessage("Unauthorized"))
                        .type(MediaType.APPLICATION_JSON).build();
            }

            List<String> audiences = authorizer.getJwtConfiguration().audience();
            if (audiences != null && !audiences.isEmpty()) {
                boolean audMatch = audiences.stream().anyMatch(a -> a.equals(claims.aud));
                if (!audMatch) {
                    return Response.status(401)
                            .entity(jsonMessage("Unauthorized"))
                            .type(MediaType.APPLICATION_JSON).build();
                }
            }
        }

        return null; // authorized
    }

    private String extractToken(Authorizer authorizer, HttpHeaders headers) {
        List<String> sources = authorizer.getIdentitySource();
        if (sources == null || sources.isEmpty()) {
            // Default: Authorization header
            String raw = headers.getHeaderString("Authorization");
            return stripBearer(raw);
        }
        for (String source : sources) {
            if (source.startsWith("$request.header.")) {
                String headerName = source.substring("$request.header.".length());
                String value = headers.getHeaderString(headerName);
                if (value != null) return stripBearer(value);
            }
        }
        return null;
    }

    private String stripBearer(String value) {
        if (value == null) return null;
        if (value.startsWith("Bearer ")) return value.substring(7);
        return value;
    }

    private record JwtClaims(String iss, String aud, long exp) {}

    private JwtClaims parseJwtClaims(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) return null;
            byte[] payloadBytes = Base64.getUrlDecoder().decode(padBase64(parts[1]));
            String payload = new String(payloadBytes, StandardCharsets.UTF_8);
            JsonNode claims = objectMapper.readTree(payload);
            String iss = claims.path("iss").asText(null);
            String aud = claims.path("aud").asText(null);
            long exp = claims.path("exp").asLong(0);
            return new JwtClaims(iss, aud, exp);
        } catch (Exception e) {
            LOG.debugv("JWT parse error: {0}", e.getMessage());
            return null;
        }
    }

    private static String padBase64(String base64) {
        return switch (base64.length() % 4) {
            case 2 -> base64 + "==";
            case 3 -> base64 + "=";
            default -> base64;
        };
    }

    private String buildV2ProxyEvent(String httpMethod, String path, String routeKey,
                                     String apiId, String stageName,
                                     HttpHeaders headers, UriInfo uriInfo,
                                     byte[] body, String requestId) {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("version", "2.0");
        event.put("routeKey", routeKey != null ? routeKey : "$default");
        event.put("rawPath", path);

        MultivaluedMap<String, String> queryParams = uriInfo.getQueryParameters();
        event.put("rawQueryString", uriInfo.getRequestUri().getRawQuery() != null
                ? uriInfo.getRequestUri().getRawQuery() : "");

        ObjectNode headersNode = event.putObject("headers");
        for (Map.Entry<String, java.util.List<String>> e : headers.getRequestHeaders().entrySet()) {
            if (!e.getValue().isEmpty()) headersNode.put(e.getKey().toLowerCase(), e.getValue().get(0));
        }

        if (!queryParams.isEmpty()) {
            ObjectNode qsp = event.putObject("queryStringParameters");
            for (Map.Entry<String, java.util.List<String>> e : queryParams.entrySet()) {
                if (!e.getValue().isEmpty()) qsp.put(e.getKey(), e.getValue().get(0));
            }
        }

        ObjectNode ctx = event.putObject("requestContext");
        ctx.put("accountId", "000000000000");
        ctx.put("apiId", apiId);
        ctx.put("domainName", apiId + ".execute-api.us-east-1.amazonaws.com");
        ctx.put("domainPrefix", apiId);
        ctx.put("requestId", requestId);
        ctx.put("routeKey", routeKey != null ? routeKey : "$default");
        ctx.put("stage", stageName);
        ctx.put("time", java.time.format.DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z")
                .format(java.time.ZonedDateTime.now()));
        ctx.put("timeEpoch", System.currentTimeMillis());

        ObjectNode http = ctx.putObject("http");
        http.put("method", httpMethod);
        http.put("path", path);
        http.put("protocol", "HTTP/1.1");
        http.put("sourceIp", "127.0.0.1");
        http.put("userAgent", headers.getHeaderString("User-Agent") != null
                ? headers.getHeaderString("User-Agent") : "");

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
            throw new RuntimeException("Failed to serialize v2 proxy event", e);
        }
    }

    private String jsonMessage(String message) {
        return objectMapper.createObjectNode().put("message", message).toString();
    }

    // ──────────────────────────── Path matching ────────────────────────────

    /**
     * Finds the best-matching resource for {@code requestPath}.
     * Priority: exact match > wildcard/proxy+ match.
     */
    private ApiGatewayResource matchResource(List<ApiGatewayResource> resources, String requestPath) {
        // 1. Exact match
        for (ApiGatewayResource r : resources) {
            if (requestPath.equals(r.getPath())) {
                return r;
            }
        }
        // 2. Wildcard / {proxy+} — any resource whose pathPart contains "{"
        for (ApiGatewayResource r : resources) {
            if (r.getPathPart() != null && r.getPathPart().contains("{")) {
                return r;
            }
        }
        return null;
    }
}
