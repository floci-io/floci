package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.lambda.model.EventSourceMapping;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.LambdaAlias;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * AWS Lambda API REST endpoints.
 * All endpoints are under /2015-03-31 matching the AWS Lambda API version.
 */
@Path("/2015-03-31")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LambdaController {

    private static final Logger LOG = Logger.getLogger(LambdaController.class);

    private final LambdaService lambdaService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public LambdaController(LambdaService lambdaService, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.lambdaService = lambdaService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    // ──────────────────────────── CreateFunction ────────────────────────────

    @POST
    @Path("/functions")
    public Response createFunction(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = objectMapper.readValue(body, Map.class);
            LambdaFunction fn = lambdaService.createFunction(region, request);
            return Response.status(201)
                    .entity(buildFunctionConfiguration(fn))
                    .build();
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("InvalidParameterValueException", e.getMessage(), 400);
        }
    }

    // ──────────────────────────── GetFunction ────────────────────────────

    @GET
    @Path("/functions/{functionName}")
    public Response getFunction(@Context HttpHeaders headers,
                                @PathParam("functionName") String functionName) {
        String region = regionResolver.resolveRegion(headers);
        LambdaFunction fn = lambdaService.getFunction(region, functionName);

        ObjectNode root = objectMapper.createObjectNode();
        root.set("Configuration", objectMapper.valueToTree(buildFunctionConfiguration(fn)));
        ObjectNode code = root.putObject("Code");
        if ("Image".equals(fn.getPackageType()) && fn.getImageUri() != null) {
            code.put("RepositoryType", "ECR");
            code.put("ImageUri", fn.getImageUri());
            code.put("ResolvedImageUri", fn.getImageUri());
        } else {
            code.put("Location", "https://awslambda-" + region + "-tasks.s3." + region
                    + ".amazonaws.com/" + fn.getFunctionName());
            code.put("RepositoryType", "S3");
        }

        return Response.ok(root).build();
    }

    // ──────────────────────────── ListFunctions ────────────────────────────

    @GET
    @Path("/functions")
    public Response listFunctions(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        List<LambdaFunction> functions = lambdaService.listFunctions(region);

        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("Functions");
        for (LambdaFunction fn : functions) {
            items.add(objectMapper.valueToTree(buildFunctionConfiguration(fn)));
        }
        return Response.ok(root).build();
    }

    // ──────────────────────────── GetFunctionConfiguration ────────────────────────────

    @GET
    @Path("/functions/{functionName}/configuration")
    public Response getFunctionConfiguration(@Context HttpHeaders headers,
                                              @PathParam("functionName") String functionName) {
        String region = regionResolver.resolveRegion(headers);
        LambdaFunction fn = lambdaService.getFunction(region, functionName);
        return Response.ok(buildFunctionConfiguration(fn)).build();
    }

    // ──────────────────────────── UpdateFunctionCode ────────────────────────────

    @PUT
    @Path("/functions/{functionName}/code")
    public Response updateFunctionCode(@Context HttpHeaders headers,
                                       @PathParam("functionName") String functionName,
                                       String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = objectMapper.readValue(body, Map.class);
            LambdaFunction fn = lambdaService.updateFunctionCode(region, functionName, request);
            return Response.ok(buildFunctionConfiguration(fn)).build();
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("InvalidParameterValueException", e.getMessage(), 400);
        }
    }

    // ──────────────────────────── DeleteFunction ────────────────────────────

    @DELETE
    @Path("/functions/{functionName}")
    public Response deleteFunction(@Context HttpHeaders headers,
                                   @PathParam("functionName") String functionName) {
        String region = regionResolver.resolveRegion(headers);
        lambdaService.deleteFunction(region, functionName);
        return Response.noContent().build();
    }

    // ──────────────────────────── GetFunctionCodeSigningConfig ────────────────────────────

    @GET
    @Path("/functions/{functionName}/code-signing-config")
    public Response getFunctionCodeSigningConfig(@Context HttpHeaders headers,
                                                  @PathParam("functionName") String functionName) {
        String region = regionResolver.resolveRegion(headers);
        // Verify the function exists
        lambdaService.getFunction(region, functionName);
        // Return empty code signing config — floci does not enforce code signing
        ObjectNode root = objectMapper.createObjectNode();
        root.put("CodeSigningConfigArn", "");
        root.put("FunctionName", functionName);
        return Response.ok(root).build();
    }

    // ──────────────────────────── Invoke ────────────────────────────

    @POST
    @Path("/functions/{functionName}/invocations")
    @Consumes(MediaType.WILDCARD)
    public Response invoke(@Context HttpHeaders headers,
                           @PathParam("functionName") String functionName,
                           byte[] payload) {
        String region = regionResolver.resolveRegion(headers);
        String invocationTypeHeader = headers.getHeaderString("X-Amz-Invocation-Type");
        InvocationType type = InvocationType.parse(invocationTypeHeader);

        InvokeResult result = lambdaService.invoke(region, functionName, payload, type);

        Response.ResponseBuilder builder = Response.status(result.getStatusCode());

        if (result.getFunctionError() != null) {
            builder.header("X-Amz-Function-Error", result.getFunctionError());
        }
        if (result.getLogResult() != null) {
            builder.header("X-Amz-Log-Result", result.getLogResult());
        }
        builder.header("X-Amz-Executed-Version", "$LATEST");
        builder.header("X-Amz-Request-Id", result.getRequestId());

        if (result.getPayload() != null && result.getPayload().length > 0) {
            builder.entity(result.getPayload())
                    .type(MediaType.APPLICATION_JSON);
        }

        return builder.build();
    }

    // ──────────────────────────── Event Source Mappings ────────────────────────────

    @POST
    @Path("/event-source-mappings")
    public Response createEventSourceMapping(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = objectMapper.readValue(body, Map.class);
            EventSourceMapping esm = lambdaService.createEventSourceMapping(region, request);
            return Response.status(202).entity(buildEsmResponse(esm)).build();
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("InvalidParameterValueException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/event-source-mappings/{uuid}")
    public Response getEventSourceMapping(@PathParam("uuid") String uuid) {
        EventSourceMapping esm = lambdaService.getEventSourceMapping(uuid);
        return Response.ok(buildEsmResponse(esm)).build();
    }

    @GET
    @Path("/event-source-mappings")
    public Response listEventSourceMappings(@QueryParam("FunctionName") String functionArn) {
        List<EventSourceMapping> esms = lambdaService.listEventSourceMappings(functionArn);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("EventSourceMappings");
        for (EventSourceMapping esm : esms) {
            items.add(objectMapper.valueToTree(buildEsmResponse(esm)));
        }
        return Response.ok(root).build();
    }

    @PUT
    @Path("/event-source-mappings/{uuid}")
    public Response updateEventSourceMapping(@PathParam("uuid") String uuid, String body) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = objectMapper.readValue(body, Map.class);
            EventSourceMapping esm = lambdaService.updateEventSourceMapping(uuid, request);
            return Response.status(202).entity(buildEsmResponse(esm)).build();
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("InvalidParameterValueException", e.getMessage(), 400);
        }
    }

    @DELETE
    @Path("/event-source-mappings/{uuid}")
    public Response deleteEventSourceMapping(@PathParam("uuid") String uuid) {
        EventSourceMapping esm = lambdaService.getEventSourceMapping(uuid);
        lambdaService.deleteEventSourceMapping(uuid);
        return Response.status(202).entity(buildEsmResponse(esm)).build();
    }

    private Map<String, Object> buildEsmResponse(EventSourceMapping esm) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("UUID", esm.getUuid());
        node.put("FunctionArn", esm.getFunctionArn());
        node.put("EventSourceArn", esm.getEventSourceArn());
        node.put("BatchSize", esm.getBatchSize());
        node.put("State", esm.getState());
        node.put("LastModified", (double) esm.getLastModified() / 1000.0);
        ArrayNode responseTypes = node.putArray("FunctionResponseTypes");
        if (esm.getFunctionResponseTypes() != null) {
            esm.getFunctionResponseTypes().forEach(responseTypes::add);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> result = objectMapper.convertValue(node, Map.class);
        return result;
    }

    // ──────────────────────────── Versions ────────────────────────────

    @POST
    @Path("/functions/{functionName}/versions")
    public Response publishVersion(@Context HttpHeaders headers,
                                   @PathParam("functionName") String functionName,
                                   String body) {
        String region = regionResolver.resolveRegion(headers);
        String description = null;
        if (body != null && !body.isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> req = objectMapper.readValue(body, Map.class);
                description = (String) req.get("Description");
            } catch (Exception ignored) {}
        }
        LambdaFunction version = lambdaService.publishVersion(region, functionName, description);
        return Response.status(201).entity(buildFunctionConfiguration(version)).build();
    }

    @GET
    @Path("/functions/{functionName}/versions")
    public Response listVersionsByFunction(@Context HttpHeaders headers,
                                           @PathParam("functionName") String functionName) {
        String region = regionResolver.resolveRegion(headers);
        List<LambdaFunction> versions = lambdaService.listVersionsByFunction(region, functionName);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("Versions");
        for (LambdaFunction v : versions) {
            items.add(objectMapper.valueToTree(buildFunctionConfiguration(v)));
        }
        return Response.ok(root).build();
    }

    // ──────────────────────────── Aliases ────────────────────────────

    @POST
    @Path("/functions/{functionName}/aliases")
    public Response createAlias(@Context HttpHeaders headers,
                                @PathParam("functionName") String functionName,
                                String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> req = objectMapper.readValue(body, Map.class);
            String name = (String) req.get("Name");
            String functionVersion = (String) req.get("FunctionVersion");
            String description = (String) req.get("Description");
            LambdaAlias alias = lambdaService.createAlias(region, functionName, name, functionVersion, description);
            return Response.status(201).entity(buildAliasResponse(alias)).build();
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("InvalidParameterValueException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/functions/{functionName}/aliases/{aliasName}")
    public Response getAlias(@Context HttpHeaders headers,
                             @PathParam("functionName") String functionName,
                             @PathParam("aliasName") String aliasName) {
        String region = regionResolver.resolveRegion(headers);
        LambdaAlias alias = lambdaService.getAlias(region, functionName, aliasName);
        return Response.ok(buildAliasResponse(alias)).build();
    }

    @GET
    @Path("/functions/{functionName}/aliases")
    public Response listAliases(@Context HttpHeaders headers,
                                @PathParam("functionName") String functionName) {
        String region = regionResolver.resolveRegion(headers);
        List<LambdaAlias> aliases = lambdaService.listAliases(region, functionName);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("Aliases");
        for (LambdaAlias alias : aliases) {
            items.add(objectMapper.valueToTree(buildAliasResponse(alias)));
        }
        return Response.ok(root).build();
    }

    @PUT
    @Path("/functions/{functionName}/aliases/{aliasName}")
    public Response updateAlias(@Context HttpHeaders headers,
                                @PathParam("functionName") String functionName,
                                @PathParam("aliasName") String aliasName,
                                String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> req = objectMapper.readValue(body, Map.class);
            String functionVersion = (String) req.get("FunctionVersion");
            String description = (String) req.get("Description");
            LambdaAlias alias = lambdaService.updateAlias(region, functionName, aliasName, functionVersion, description);
            return Response.ok(buildAliasResponse(alias)).build();
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("InvalidParameterValueException", e.getMessage(), 400);
        }
    }

    @DELETE
    @Path("/functions/{functionName}/aliases/{aliasName}")
    public Response deleteAlias(@Context HttpHeaders headers,
                                @PathParam("functionName") String functionName,
                                @PathParam("aliasName") String aliasName) {
        String region = regionResolver.resolveRegion(headers);
        lambdaService.deleteAlias(region, functionName, aliasName);
        return Response.noContent().build();
    }

    // ──────────────────────────── Permissions (Policy) ────────────────────────────

    @POST
    @Path("/functions/{functionName}/policy")
    public Response addPermission(@Context HttpHeaders headers,
                                  @PathParam("functionName") String functionName,
                                  String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = objectMapper.readValue(body, Map.class);
            Map<String, Object> statement = lambdaService.addPermission(region, functionName, request);
            String statementJson = objectMapper.writeValueAsString(statement);
            ObjectNode root = objectMapper.createObjectNode();
            root.put("Statement", statementJson);
            return Response.status(201).entity(root).build();
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("InvalidParameterValueException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/functions/{functionName}/policy")
    public Response getPolicy(@Context HttpHeaders headers,
                              @PathParam("functionName") String functionName) {
        String region = regionResolver.resolveRegion(headers);
        try {
            Map<String, Object> data = lambdaService.getPolicy(region, functionName);
            @SuppressWarnings("unchecked")
            Map<String, Object> policy = (Map<String, Object>) data.get("policy");
            String policyJson = objectMapper.writeValueAsString(policy);
            ObjectNode root = objectMapper.createObjectNode();
            root.put("Policy", policyJson);
            root.put("RevisionId", (String) data.get("revisionId"));
            return Response.ok(root).build();
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("ServiceException", e.getMessage(), 500);
        }
    }

    @DELETE
    @Path("/functions/{functionName}/policy/{statementId}")
    public Response removePermission(@Context HttpHeaders headers,
                                     @PathParam("functionName") String functionName,
                                     @PathParam("statementId") String statementId) {
        String region = regionResolver.resolveRegion(headers);
        lambdaService.removePermission(region, functionName, statementId);
        return Response.noContent().build();
    }

    // ──────────────────────────── Helper ────────────────────────────

    private Map<String, Object> buildAliasResponse(LambdaAlias alias) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Name", alias.getName());
        node.put("FunctionVersion", alias.getFunctionVersion() != null ? alias.getFunctionVersion() : "$LATEST");
        node.put("AliasArn", alias.getAliasArn());
        if (alias.getDescription() != null) node.put("Description", alias.getDescription());
        node.put("RevisionId", alias.getRevisionId());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = objectMapper.convertValue(node, Map.class);
        return result;
    }

    private Map<String, Object> buildFunctionConfiguration(LambdaFunction fn) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("FunctionName", fn.getFunctionName());
        node.put("FunctionArn", fn.getFunctionArn());
        if (fn.getRuntime() != null) node.put("Runtime", fn.getRuntime());
        node.put("Role", fn.getRole());
        node.put("Handler", fn.getHandler());
        if (fn.getDescription() != null) node.put("Description", fn.getDescription());
        node.put("Timeout", fn.getTimeout());
        node.put("MemorySize", fn.getMemorySize());
        node.put("State", fn.getState());
        if (fn.getStateReason() != null) node.put("StateReason", fn.getStateReason());
        if (fn.getStateReasonCode() != null) node.put("StateReasonCode", fn.getStateReasonCode());
        node.put("CodeSize", fn.getCodeSizeBytes());
        node.put("PackageType", fn.getPackageType());
        if (fn.getImageUri() != null) node.put("ImageUri", fn.getImageUri());
        node.put("LastModified", String.valueOf(fn.getLastModified()));
        node.put("RevisionId", fn.getRevisionId());
        node.put("Version", fn.getVersion());
        node.put("LastUpdateStatus", "Successful");

        if (fn.getEnvironment() != null && !fn.getEnvironment().isEmpty()) {
            ObjectNode envNode = node.putObject("Environment");
            ObjectNode vars = envNode.putObject("Variables");
            fn.getEnvironment().forEach(vars::put);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> result = objectMapper.convertValue(node, Map.class);
        return result;
    }
}
