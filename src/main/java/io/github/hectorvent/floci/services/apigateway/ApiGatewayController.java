package io.github.hectorvent.floci.services.apigateway;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.apigateway.model.*;
import io.github.hectorvent.floci.services.apigatewayv2.ApiGatewayV2Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.apigatewayv2.model.Api;
import io.github.hectorvent.floci.services.apigatewayv2.model.Authorizer;
import io.github.hectorvent.floci.services.apigatewayv2.model.Deployment;
import io.github.hectorvent.floci.services.apigatewayv2.model.Integration;
import io.github.hectorvent.floci.services.apigatewayv2.model.Route;
import io.github.hectorvent.floci.services.apigatewayv2.model.Stage;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Unified AWS API Gateway management endpoints (v1 REST and v2 HTTP).
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes({MediaType.APPLICATION_JSON, "application/json-patch+json"})
public class ApiGatewayController {

    private static final Logger LOG = Logger.getLogger(ApiGatewayController.class);

    private final ApiGatewayService service;
    private final ApiGatewayV2Service v2Service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public ApiGatewayController(ApiGatewayService service, ApiGatewayV2Service v2Service,
                                RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.service = service;
        this.v2Service = v2Service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    // ──────────────────────────── Specific v1 Paths (ORDER MATTERS) ────────────────────────────

    @GET
    @Path("/restapis/{apiId}/resources/{resourceId}/methods/{httpMethod}/responses/{statusCode}")
    public Response getMethodResponse(@Context HttpHeaders headers,
                                      @PathParam("apiId") String apiId,
                                      @PathParam("resourceId") String resourceId,
                                      @PathParam("httpMethod") String httpMethod,
                                      @PathParam("statusCode") String statusCode) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(toMethodResponseNode(service.getMethodResponse(region, apiId, resourceId, httpMethod, statusCode)).toString()).type(MediaType.APPLICATION_JSON).build();
    }

    @PUT
    @Path("/restapis/{apiId}/resources/{resourceId}/methods/{httpMethod}/responses/{statusCode}")
    public Response putMethodResponse(@Context HttpHeaders headers,
                                      @PathParam("apiId") String apiId,
                                      @PathParam("resourceId") String resourceId,
                                      @PathParam("httpMethod") String httpMethod,
                                      @PathParam("statusCode") String statusCode,
                                      String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = objectMapper.readValue(body, Map.class);
            MethodResponse resp = service.putMethodResponse(region, apiId, resourceId, httpMethod, statusCode, request);
            return Response.status(201).entity(toMethodResponseNode(resp).toString()).type(MediaType.APPLICATION_JSON).build();
        } catch (IOException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/restapis/{apiId}/resources/{resourceId}/methods/{httpMethod}/integration/responses/{statusCode}")
    public Response getIntegrationResponse(@Context HttpHeaders headers,
                                           @PathParam("apiId") String apiId,
                                           @PathParam("resourceId") String resourceId,
                                           @PathParam("httpMethod") String httpMethod,
                                           @PathParam("statusCode") String statusCode) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(toIntegrationResponseNode(service.getIntegrationResponse(region, apiId, resourceId, httpMethod, statusCode)).toString()).type(MediaType.APPLICATION_JSON).build();
    }

    @PUT
    @Path("/restapis/{apiId}/resources/{resourceId}/methods/{httpMethod}/integration/responses/{statusCode}")
    public Response putIntegrationResponse(@Context HttpHeaders headers,
                                           @PathParam("apiId") String apiId,
                                           @PathParam("resourceId") String resourceId,
                                           @PathParam("httpMethod") String httpMethod,
                                           @PathParam("statusCode") String statusCode,
                                           String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = objectMapper.readValue(body, Map.class);
            IntegrationResponse ir = service.putIntegrationResponse(region, apiId, resourceId, httpMethod, statusCode, request);
            return Response.status(201).entity(toIntegrationResponseNode(ir).toString()).type(MediaType.APPLICATION_JSON).build();
        } catch (IOException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/restapis/{apiId}/authorizers/{authorizerId}")
    public Response getAuthorizer(@Context HttpHeaders headers,
                                  @PathParam("apiId") String apiId,
                                  @PathParam("authorizerId") String authorizerId) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(toAuthorizerNode(service.getAuthorizer(region, apiId, authorizerId)).toString()).type(MediaType.APPLICATION_JSON).build();
    }

    @GET
    @Path("/restapis/{apiId}/authorizers")
    public Response getAuthorizers(@Context HttpHeaders headers, @PathParam("apiId") String apiId) {
        String region = regionResolver.resolveRegion(headers);
        List<io.github.hectorvent.floci.services.apigateway.model.Authorizer> auths = service.getAuthorizers(region, apiId);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("item");
        auths.forEach(a -> items.add(toAuthorizerNode(a)));
        return Response.ok(root.toString()).type(MediaType.APPLICATION_JSON).build();
    }

    @GET
    @Path("/restapis/{apiId}/stages/{stageName}")
    public Response getStage(@Context HttpHeaders headers,
                             @PathParam("apiId") String apiId,
                             @PathParam("stageName") String stageName) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(toStageNode(service.getStage(region, apiId, stageName)).toString()).type(MediaType.APPLICATION_JSON).build();
    }

    @GET
    @Path("/restapis/{apiId}/stages")
    public Response getStages(@Context HttpHeaders headers, @PathParam("apiId") String apiId) {
        String region = regionResolver.resolveRegion(headers);
        List<io.github.hectorvent.floci.services.apigateway.model.Stage> stages = service.getStages(region, apiId);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("item");
        stages.forEach(s -> items.add(toStageNode(s)));
        return Response.ok(root.toString()).type(MediaType.APPLICATION_JSON).build();
    }

    // ──────────────────────────── General REST APIs (v1) ────────────────────────────

    @POST
    @Path("/restapis")
    public Response createRestApi(@Context HttpHeaders headers,
                                  @QueryParam("mode") String mode,
                                  String body) {
        String region = regionResolver.resolveRegion(headers);
        if ("import".equals(mode)) {
            RestApi api = service.importRestApi(region, body);
            return Response.status(201).entity(toApiNode(api).toString()).type(MediaType.APPLICATION_JSON).build();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = objectMapper.readValue(body, Map.class);
            RestApi api = service.createRestApi(region, request);
            return Response.status(201).entity(toApiNode(api).toString()).type(MediaType.APPLICATION_JSON).build();
        } catch (IOException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @PUT
    @Path("/restapis/{apiId}")
    public Response putRestApi(@Context HttpHeaders headers,
                               @PathParam("apiId") String apiId,
                               @QueryParam("mode") String mode,
                               String body) {
        String region = regionResolver.resolveRegion(headers);
        RestApi api = service.putRestApi(region, apiId, mode, body);
        return Response.ok(toApiNode(api).toString()).type(MediaType.APPLICATION_JSON).build();
    }

    @GET
    @Path("/restapis")
    public Response getRestApis(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        List<RestApi> apis = service.getRestApis(region);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("item");
        apis.forEach(a -> items.add(toApiNode(a)));
        return Response.ok(root.toString()).type(MediaType.APPLICATION_JSON).build();
    }

    @GET
    @Path("/restapis/{apiId}")
    public Response getRestApi(@Context HttpHeaders headers, @PathParam("apiId") String apiId) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(toApiNode(service.getRestApi(region, apiId)).toString()).type(MediaType.APPLICATION_JSON).build();
    }

    @PATCH
    @Path("/restapis/{apiId}")
    public Response updateRestApi(@Context HttpHeaders headers, @PathParam("apiId") String apiId, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body).path("patchOperations");
            @SuppressWarnings("unchecked")
            List<Map<String, String>> patchOperations = objectMapper.convertValue(node, List.class);
            RestApi api = service.updateRestApi(region, apiId, patchOperations);
            return Response.ok(toApiNode(api).toString()).type(MediaType.APPLICATION_JSON).build();
        } catch (IOException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @DELETE
    @Path("/restapis/{apiId}")
    public Response deleteRestApi(@Context HttpHeaders headers, @PathParam("apiId") String apiId) {
        String region = regionResolver.resolveRegion(headers);
        service.deleteRestApi(region, apiId);
        return Response.accepted().build();
    }

    // ──────────────────────────── Resources (v1) ────────────────────────────

    @GET
    @Path("/restapis/{apiId}/resources")
    public Response getResources(@Context HttpHeaders headers, @PathParam("apiId") String apiId) {
        String region = regionResolver.resolveRegion(headers);
        List<ApiGatewayResource> resources = service.getResources(region, apiId);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("item");
        resources.forEach(r -> items.add(toResourceNode(r)));
        return Response.ok(root.toString()).type(MediaType.APPLICATION_JSON).build();
    }

    @GET
    @Path("/restapis/{apiId}/resources/{resourceId}")
    public Response getResource(@Context HttpHeaders headers,
                                @PathParam("apiId") String apiId,
                                @PathParam("resourceId") String resourceId) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(toResourceNode(service.getResource(region, apiId, resourceId))).build();
    }

    @PATCH
    @Path("/restapis/{apiId}/resources/{resourceId}")
    public Response updateResource(@Context HttpHeaders headers,
                                   @PathParam("apiId") String apiId,
                                   @PathParam("resourceId") String resourceId,
                                   String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body).path("patchOperations");
            @SuppressWarnings("unchecked")
            List<Map<String, String>> patchOperations = objectMapper.convertValue(node, List.class);
            ApiGatewayResource resource = service.updateResource(region, apiId, resourceId, patchOperations);
            return Response.ok(toResourceNode(resource).toString()).type(MediaType.APPLICATION_JSON).build();
        } catch (IOException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @POST
    @Path("/restapis/{apiId}/resources/{parentId}")
    public Response createResource(@Context HttpHeaders headers,
                                   @PathParam("apiId") String apiId,
                                   @PathParam("parentId") String parentId,
                                   String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = objectMapper.readValue(body, Map.class);
            ApiGatewayResource resource = service.createResource(region, apiId, parentId, request);
            return Response.status(201).entity(toResourceNode(resource).toString()).type(MediaType.APPLICATION_JSON).build();
        } catch (IOException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @DELETE
    @Path("/restapis/{apiId}/resources/{resourceId}")
    public Response deleteResource(@Context HttpHeaders headers,
                                   @PathParam("apiId") String apiId,
                                   @PathParam("resourceId") String resourceId) {
        String region = regionResolver.resolveRegion(headers);
        service.deleteResource(region, apiId, resourceId);
        return Response.noContent().build();
    }

    // ──────────────────────────── Methods (v1) ────────────────────────────

    @PUT
    @Path("/restapis/{apiId}/resources/{resourceId}/methods/{httpMethod}")
    public Response putMethod(@Context HttpHeaders headers,
                              @PathParam("apiId") String apiId,
                              @PathParam("resourceId") String resourceId,
                              @PathParam("httpMethod") String httpMethod,
                              String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = objectMapper.readValue(body, Map.class);
            MethodConfig method = service.putMethod(region, apiId, resourceId, httpMethod, request);
            return Response.status(201).entity(toMethodNode(method).toString()).type(MediaType.APPLICATION_JSON).build();
        } catch (IOException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/restapis/{apiId}/resources/{resourceId}/methods/{httpMethod}")
    public Response getMethod(@Context HttpHeaders headers,
                              @PathParam("apiId") String apiId,
                              @PathParam("resourceId") String resourceId,
                              @PathParam("httpMethod") String httpMethod) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(toMethodNode(service.getMethod(region, apiId, resourceId, httpMethod)).toString()).type(MediaType.APPLICATION_JSON).build();
    }

    @PATCH
    @Path("/restapis/{apiId}/resources/{resourceId}/methods/{httpMethod}")
    public Response updateMethod(@Context HttpHeaders headers,
                                 @PathParam("apiId") String apiId,
                                 @PathParam("resourceId") String resourceId,
                                 @PathParam("httpMethod") String httpMethod,
                                 String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body).path("patchOperations");
            @SuppressWarnings("unchecked")
            List<Map<String, String>> patchOperations = objectMapper.convertValue(node, List.class);
            MethodConfig method = service.updateMethod(region, apiId, resourceId, httpMethod, patchOperations);
            return Response.ok(toMethodNode(method).toString()).type(MediaType.APPLICATION_JSON).build();
        } catch (IOException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @DELETE
    @Path("/restapis/{apiId}/resources/{resourceId}/methods/{httpMethod}")
    public Response deleteMethod(@Context HttpHeaders headers,
                                 @PathParam("apiId") String apiId,
                                 @PathParam("resourceId") String resourceId,
                                 @PathParam("httpMethod") String httpMethod) {
        String region = regionResolver.resolveRegion(headers);
        service.deleteMethod(region, apiId, resourceId, httpMethod);
        return Response.accepted().build();
    }

    // ──────────────────────────── Integrations (v1) ────────────────────────────

    @PUT
    @Path("/restapis/{apiId}/resources/{resourceId}/methods/{httpMethod}/integration")
    public Response putIntegration(@Context HttpHeaders headers,
                                   @PathParam("apiId") String apiId,
                                   @PathParam("resourceId") String resourceId,
                                   @PathParam("httpMethod") String httpMethod,
                                   String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = objectMapper.readValue(body, Map.class);
            io.github.hectorvent.floci.services.apigateway.model.Integration integration = service.putIntegration(region, apiId, resourceId, httpMethod, request);
            return Response.status(201).entity(toIntegrationNode(integration).toString()).type(MediaType.APPLICATION_JSON).build();
        } catch (IOException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/restapis/{apiId}/resources/{resourceId}/methods/{httpMethod}/integration")
    public Response getIntegration(@Context HttpHeaders headers,
                                   @PathParam("apiId") String apiId,
                                   @PathParam("resourceId") String resourceId,
                                   @PathParam("httpMethod") String httpMethod) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(toIntegrationNode(service.getIntegration(region, apiId, resourceId, httpMethod)).toString()).type(MediaType.APPLICATION_JSON).build();
    }

    @PATCH
    @Path("/restapis/{apiId}/resources/{resourceId}/methods/{httpMethod}/integration")
    public Response updateIntegration(@Context HttpHeaders headers,
                                      @PathParam("apiId") String apiId,
                                      @PathParam("resourceId") String resourceId,
                                      @PathParam("httpMethod") String httpMethod,
                                      String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body).path("patchOperations");
            @SuppressWarnings("unchecked")
            List<Map<String, String>> patchOperations = objectMapper.convertValue(node, List.class);
            io.github.hectorvent.floci.services.apigateway.model.Integration integration = service.updateIntegration(region, apiId, resourceId, httpMethod, patchOperations);
            return Response.ok(toIntegrationNode(integration).toString()).type(MediaType.APPLICATION_JSON).build();
        } catch (IOException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @DELETE
    @Path("/restapis/{apiId}/resources/{resourceId}/methods/{httpMethod}/integration")
    public Response deleteIntegration(@Context HttpHeaders headers,
                                      @PathParam("apiId") String apiId,
                                      @PathParam("resourceId") String resourceId,
                                      @PathParam("httpMethod") String httpMethod) {
        String region = regionResolver.resolveRegion(headers);
        service.deleteIntegration(region, apiId, resourceId, httpMethod);
        return Response.noContent().build();
    }

    // ──────────────────────────── Deployments & Stages (v1) ────────────────────────────

    @POST
    @Path("/restapis/{apiId}/deployments")
    public Response createDeployment(@Context HttpHeaders headers,
                                     @PathParam("apiId") String apiId,
                                     String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = objectMapper.readValue(body, Map.class);
            io.github.hectorvent.floci.services.apigateway.model.Deployment deployment = service.createDeployment(region, apiId, request);
            return Response.status(201).entity(toDeploymentNode(deployment).toString()).type(MediaType.APPLICATION_JSON).build();
        } catch (IOException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/restapis/{apiId}/deployments")
    public Response getDeployments(@Context HttpHeaders headers,
                                   @PathParam("apiId") String apiId) {
        String region = regionResolver.resolveRegion(headers);
        List<io.github.hectorvent.floci.services.apigateway.model.Deployment> deployments = service.getDeployments(region, apiId);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("item");
        deployments.forEach(d -> items.add(toDeploymentNode(d)));
        return Response.ok(root.toString()).type(MediaType.APPLICATION_JSON).build();
    }

    @GET
    @Path("/restapis/{apiId}/deployments/{deploymentId}")
    public Response getDeployment(@Context HttpHeaders headers,
                                   @PathParam("apiId") String apiId,
                                   @PathParam("deploymentId") String deploymentId) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(toDeploymentNode(service.getDeployment(region, apiId, deploymentId)).toString())
                .type(MediaType.APPLICATION_JSON).build();
    }

    @POST
    @Path("/restapis/{apiId}/stages")
    public Response createStage(@Context HttpHeaders headers,
                                @PathParam("apiId") String apiId,
                                String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = objectMapper.readValue(body, Map.class);
            io.github.hectorvent.floci.services.apigateway.model.Stage stage = service.createStage(region, apiId, request);
            return Response.status(201).entity(toStageNode(stage).toString()).type(MediaType.APPLICATION_JSON).build();
        } catch (IOException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @PATCH
    @Path("/restapis/{apiId}/stages/{stageName}")
    public Response updateStage(@Context HttpHeaders headers,
                                @PathParam("apiId") String apiId,
                                @PathParam("stageName") String stageName,
                                String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body).path("patchOperations");
            @SuppressWarnings("unchecked")
            List<Map<String, String>> patchOperations = objectMapper.convertValue(node, List.class);
            io.github.hectorvent.floci.services.apigateway.model.Stage stage = service.updateStage(region, apiId, stageName, patchOperations);
            return Response.ok(toStageNode(stage).toString()).type(MediaType.APPLICATION_JSON).build();
        } catch (IOException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @DELETE
    @Path("/restapis/{apiId}/stages/{stageName}")
    public Response deleteStage(@Context HttpHeaders headers,
                                @PathParam("apiId") String apiId,
                                @PathParam("stageName") String stageName) {
        String region = regionResolver.resolveRegion(headers);
        service.deleteStage(region, apiId, stageName);
        return Response.accepted().build();
    }

    // ──────────────────────────── Authorizers, API Keys, Usage Plans (v1) ────────────────────────────

    @POST
    @Path("/restapis/{apiId}/authorizers")
    public Response createAuthorizer(@Context HttpHeaders headers,
                                     @PathParam("apiId") String apiId,
                                     String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = objectMapper.readValue(body, Map.class);
            io.github.hectorvent.floci.services.apigateway.model.Authorizer auth = service.createAuthorizer(region, apiId, request);
            return Response.status(201).entity(toAuthorizerNode(auth).toString()).type(MediaType.APPLICATION_JSON).build();
        } catch (IOException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @POST
    @Path("/apikeys")
    public Response createApiKey(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = objectMapper.readValue(body, Map.class);
            ApiKey key = service.createApiKey(region, request);
            return Response.status(201).entity(toApiKeyNode(key).toString()).type(MediaType.APPLICATION_JSON).build();
        } catch (IOException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/apikeys")
    public Response getApiKeys(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        List<ApiKey> keys = service.getApiKeys(region);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("item");
        keys.forEach(k -> items.add(toApiKeyNode(k)));
        return Response.ok(root.toString()).type(MediaType.APPLICATION_JSON).build();
    }

    @POST
    @Path("/usageplans")
    public Response createUsagePlan(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = objectMapper.readValue(body, Map.class);
            UsagePlan plan = service.createUsagePlan(region, request);
            return Response.status(201).entity(toUsagePlanNode(plan).toString()).type(MediaType.APPLICATION_JSON).build();
        } catch (IOException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/usageplans")
    public Response getUsagePlans(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        List<UsagePlan> plans = service.getUsagePlans(region);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("item");
        plans.forEach(p -> items.add(toUsagePlanNode(p)));
        return Response.ok(root.toString()).type(MediaType.APPLICATION_JSON).build();
    }

    @DELETE
    @Path("/usageplans/{usagePlanId}")
    public Response deleteUsagePlan(@Context HttpHeaders headers, @PathParam("usagePlanId") String usagePlanId) {
        String region = regionResolver.resolveRegion(headers);
        service.deleteUsagePlan(region, usagePlanId);
        return Response.accepted().build();
    }

    @POST
    @Path("/usageplans/{usagePlanId}/keys")
    public Response createUsagePlanKey(@Context HttpHeaders headers, @PathParam("usagePlanId") String usagePlanId, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = objectMapper.readValue(body, Map.class);
            UsagePlanKey key = service.createUsagePlanKey(region, usagePlanId, request);
            return Response.status(201).entity(toUsagePlanKeyNode(key).toString()).type(MediaType.APPLICATION_JSON).build();
        } catch (IOException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/usageplans/{usagePlanId}/keys")
    public Response getUsagePlanKeys(@Context HttpHeaders headers, @PathParam("usagePlanId") String usagePlanId) {
        String region = regionResolver.resolveRegion(headers);
        List<UsagePlanKey> keys = service.getUsagePlanKeys(region, usagePlanId);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("item");
        keys.forEach(k -> items.add(toUsagePlanKeyNode(k)));
        return Response.ok(root.toString()).type(MediaType.APPLICATION_JSON).build();
    }

    @GET
    @Path("/usageplans/{usagePlanId}/keys/{keyId}")
    public Response getUsagePlanKey(@Context HttpHeaders headers, @PathParam("usagePlanId") String usagePlanId, @PathParam("keyId") String keyId) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(toUsagePlanKeyNode(service.getUsagePlanKey(region, usagePlanId, keyId)).toString()).type(MediaType.APPLICATION_JSON).build();
    }

    @DELETE
    @Path("/usageplans/{usagePlanId}/keys/{keyId}")
    public Response deleteUsagePlanKey(@Context HttpHeaders headers, @PathParam("usagePlanId") String usagePlanId, @PathParam("keyId") String keyId) {
        String region = regionResolver.resolveRegion(headers);
        service.deleteUsagePlanKey(region, usagePlanId, keyId);
        return Response.accepted().build();
    }

    // ──────────────────────────── Request Validators (v1) ────────────────────────────

    @POST
    @Path("/restapis/{apiId}/requestvalidators")
    public Response createRequestValidator(@Context HttpHeaders headers, @PathParam("apiId") String apiId, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = objectMapper.readValue(body, Map.class);
            RequestValidator validator = service.createRequestValidator(region, apiId, request);
            return Response.status(201).entity(toRequestValidatorNode(validator).toString()).type(MediaType.APPLICATION_JSON).build();
        } catch (IOException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/restapis/{apiId}/requestvalidators")
    public Response getRequestValidators(@Context HttpHeaders headers, @PathParam("apiId") String apiId) {
        String region = regionResolver.resolveRegion(headers);
        List<RequestValidator> validators = service.getRequestValidators(region, apiId);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("item");
        validators.forEach(v -> items.add(toRequestValidatorNode(v)));
        return Response.ok(root.toString()).type(MediaType.APPLICATION_JSON).build();
    }

    @GET
    @Path("/restapis/{apiId}/requestvalidators/{validatorId}")
    public Response getRequestValidator(@Context HttpHeaders headers,
                                        @PathParam("apiId") String apiId,
                                        @PathParam("validatorId") String validatorId) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(toRequestValidatorNode(service.getRequestValidator(region, apiId, validatorId)).toString()).type(MediaType.APPLICATION_JSON).build();
    }

    @DELETE
    @Path("/restapis/{apiId}/requestvalidators/{validatorId}")
    public Response deleteRequestValidator(@Context HttpHeaders headers,
                                           @PathParam("apiId") String apiId,
                                           @PathParam("validatorId") String validatorId) {
        String region = regionResolver.resolveRegion(headers);
        service.deleteRequestValidator(region, apiId, validatorId);
        return Response.accepted().build();
    }

    // ──────────────────────────── Models (v1) ────────────────────────────

    @POST
    @Path("/restapis/{apiId}/models")
    public Response createModel(@Context HttpHeaders headers, @PathParam("apiId") String apiId, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = objectMapper.readValue(body, Map.class);
            io.github.hectorvent.floci.services.apigateway.model.Model model = service.createModel(region, apiId, request);
            return Response.status(201).entity(toModelNode(model).toString()).type(MediaType.APPLICATION_JSON).build();
        } catch (IOException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/restapis/{apiId}/models")
    public Response getModels(@Context HttpHeaders headers, @PathParam("apiId") String apiId) {
        String region = regionResolver.resolveRegion(headers);
        List<io.github.hectorvent.floci.services.apigateway.model.Model> models = service.getModels(region, apiId);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("item");
        models.forEach(m -> items.add(toModelNode(m)));
        return Response.ok(root.toString()).type(MediaType.APPLICATION_JSON).build();
    }

    @GET
    @Path("/restapis/{apiId}/models/{modelName}")
    public Response getModel(@Context HttpHeaders headers,
                             @PathParam("apiId") String apiId,
                             @PathParam("modelName") String modelName) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(toModelNode(service.getModel(region, apiId, modelName)).toString()).type(MediaType.APPLICATION_JSON).build();
    }

    @DELETE
    @Path("/restapis/{apiId}/models/{modelName}")
    public Response deleteModel(@Context HttpHeaders headers,
                                @PathParam("apiId") String apiId,
                                @PathParam("modelName") String modelName) {
        String region = regionResolver.resolveRegion(headers);
        service.deleteModel(region, apiId, modelName);
        return Response.accepted().build();
    }

    // ──────────────────────────── Custom Domains (v1) ────────────────────────────

    @POST
    @Path("/domainnames")
    public Response createDomainName(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = objectMapper.readValue(body, Map.class);
            CustomDomain domain = service.createDomainName(region, request);
            return Response.status(201).entity(toDomainNode(domain).toString()).type(MediaType.APPLICATION_JSON).build();
        } catch (IOException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/domainnames")
    public Response getDomainNames(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        List<CustomDomain> domains = service.getDomainNames(region);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("item");
        domains.forEach(d -> items.add(toDomainNode(d)));
        return Response.ok(root.toString()).type(MediaType.APPLICATION_JSON).build();
    }

    @GET
    @Path("/domainnames/{domainName}")
    public Response getDomainName(@Context HttpHeaders headers, @PathParam("domainName") String domainName) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(toDomainNode(service.getDomainName(region, domainName)).toString()).type(MediaType.APPLICATION_JSON).build();
    }

    @DELETE
    @Path("/domainnames/{domainName}")
    public Response deleteDomainName(@Context HttpHeaders headers, @PathParam("domainName") String domainName) {
        String region = regionResolver.resolveRegion(headers);
        service.deleteDomainName(region, domainName);
        return Response.accepted().build();
    }

    // ──────────────────────────── Base Path Mappings (v1) ────────────────────────────

    @POST
    @Path("/domainnames/{domainName}/basepathmappings")
    public Response createBasePathMapping(@Context HttpHeaders headers, @PathParam("domainName") String domainName, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = objectMapper.readValue(body, Map.class);
            BasePathMapping mapping = service.createBasePathMapping(region, domainName, request);
            return Response.status(201).entity(toMappingNode(mapping).toString()).type(MediaType.APPLICATION_JSON).build();
        } catch (IOException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/domainnames/{domainName}/basepathmappings")
    public Response getBasePathMappings(@Context HttpHeaders headers, @PathParam("domainName") String domainName) {
        String region = regionResolver.resolveRegion(headers);
        List<BasePathMapping> mappings = service.getBasePathMappings(region, domainName);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("item");
        mappings.forEach(m -> items.add(toMappingNode(m)));
        return Response.ok(root.toString()).type(MediaType.APPLICATION_JSON).build();
    }

    @GET
    @Path("/domainnames/{domainName}/basepathmappings/{basePath}")
    public Response getBasePathMapping(@Context HttpHeaders headers, @PathParam("domainName") String domainName, @PathParam("basePath") String basePath) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(toMappingNode(service.getBasePathMapping(region, domainName, basePath)).toString()).type(MediaType.APPLICATION_JSON).build();
    }

    @DELETE
    @Path("/domainnames/{domainName}/basepathmappings/{basePath}")
    public Response deleteBasePathMapping(@Context HttpHeaders headers, @PathParam("domainName") String domainName, @PathParam("basePath") String basePath) {
        String region = regionResolver.resolveRegion(headers);
        service.deleteBasePathMapping(region, domainName, basePath);
        return Response.accepted().build();
    }

    // ──────────────────────────── HTTP APIs (v2) ────────────────────────────

    @POST
    @Path("/v2/apis")
    public Response createApi(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = objectMapper.readValue(body, Map.class);
            Api api = v2Service.createApi(region, request);
            return Response.status(201).entity(toV2ApiNode(api).toString()).type(MediaType.APPLICATION_JSON).build();
        } catch (IOException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/v2/apis")
    public Response getApis(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        List<Api> apis = v2Service.getApis(region);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("items");
        apis.forEach(a -> items.add(toV2ApiNode(a)));
        return Response.ok(root.toString()).type(MediaType.APPLICATION_JSON).build();
    }

    @GET
    @Path("/v2/apis/{apiId}")
    public Response getApi(@Context HttpHeaders headers, @PathParam("apiId") String apiId) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(toV2ApiNode(v2Service.getApi(region, apiId)).toString()).type(MediaType.APPLICATION_JSON).build();
    }

    @DELETE
    @Path("/v2/apis/{apiId}")
    public Response deleteApi(@Context HttpHeaders headers, @PathParam("apiId") String apiId) {
        String region = regionResolver.resolveRegion(headers);
        v2Service.deleteApi(region, apiId);
        return Response.noContent().build();
    }

    @POST
    @Path("/v2/apis/{apiId}/routes")
    public Response createRoute(@Context HttpHeaders headers, @PathParam("apiId") String apiId, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = objectMapper.readValue(body, Map.class);
            Route route = v2Service.createRoute(region, apiId, request);
            return Response.status(201).entity(toV2RouteNode(route).toString()).type(MediaType.APPLICATION_JSON).build();
        } catch (IOException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/v2/apis/{apiId}/routes")
    public Response getRoutes(@Context HttpHeaders headers, @PathParam("apiId") String apiId) {
        String region = regionResolver.resolveRegion(headers);
        List<Route> routes = v2Service.getRoutes(region, apiId);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("items");
        routes.forEach(r -> items.add(toV2RouteNode(r)));
        return Response.ok(root.toString()).type(MediaType.APPLICATION_JSON).build();
    }

    @GET
    @Path("/v2/apis/{apiId}/routes/{routeId}")
    public Response getRoute(@Context HttpHeaders headers, @PathParam("apiId") String apiId, @PathParam("routeId") String routeId) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(toV2RouteNode(v2Service.getRoute(region, apiId, routeId)).toString()).type(MediaType.APPLICATION_JSON).build();
    }

    @DELETE
    @Path("/v2/apis/{apiId}/routes/{routeId}")
    public Response deleteRoute(@Context HttpHeaders headers, @PathParam("apiId") String apiId, @PathParam("routeId") String routeId) {
        String region = regionResolver.resolveRegion(headers);
        v2Service.deleteRoute(region, apiId, routeId);
        return Response.noContent().build();
    }

    @POST
    @Path("/v2/apis/{apiId}/integrations")
    public Response createIntegration(@Context HttpHeaders headers, @PathParam("apiId") String apiId, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = objectMapper.readValue(body, Map.class);
            Integration integration = v2Service.createIntegration(region, apiId, request);
            return Response.status(201).entity(toV2IntegrationNode(integration).toString()).type(MediaType.APPLICATION_JSON).build();
        } catch (IOException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/v2/apis/{apiId}/integrations")
    public Response getIntegrations(@Context HttpHeaders headers, @PathParam("apiId") String apiId) {
        String region = regionResolver.resolveRegion(headers);
        List<Integration> integrations = v2Service.getIntegrations(region, apiId);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("items");
        integrations.forEach(i -> items.add(toV2IntegrationNode(i)));
        return Response.ok(root.toString()).type(MediaType.APPLICATION_JSON).build();
    }

    @GET
    @Path("/v2/apis/{apiId}/integrations/{integrationId}")
    public Response getIntegration(@Context HttpHeaders headers, @PathParam("apiId") String apiId, @PathParam("integrationId") String integrationId) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(toV2IntegrationNode(v2Service.getIntegration(region, apiId, integrationId)).toString()).type(MediaType.APPLICATION_JSON).build();
    }

    @DELETE
    @Path("/v2/apis/{apiId}/integrations/{integrationId}")
    public Response deleteIntegration(@Context HttpHeaders headers, @PathParam("apiId") String apiId, @PathParam("integrationId") String integrationId) {
        String region = regionResolver.resolveRegion(headers);
        v2Service.deleteIntegration(region, apiId, integrationId);
        return Response.noContent().build();
    }

    @POST
    @Path("/v2/apis/{apiId}/authorizers")
    public Response createV2Authorizer(@Context HttpHeaders headers, @PathParam("apiId") String apiId, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = objectMapper.readValue(body, Map.class);
            Authorizer authorizer = v2Service.createAuthorizer(region, apiId, request);
            return Response.status(201).entity(toV2AuthorizerNode(authorizer).toString()).type(MediaType.APPLICATION_JSON).build();
        } catch (IOException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/v2/apis/{apiId}/authorizers")
    public Response getV2Authorizers(@Context HttpHeaders headers, @PathParam("apiId") String apiId) {
        String region = regionResolver.resolveRegion(headers);
        List<Authorizer> authorizers = v2Service.getAuthorizers(region, apiId);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("items");
        authorizers.forEach(a -> items.add(toV2AuthorizerNode(a)));
        return Response.ok(root.toString()).type(MediaType.APPLICATION_JSON).build();
    }

    @GET
    @Path("/v2/apis/{apiId}/authorizers/{authorizerId}")
    public Response getV2Authorizer(@Context HttpHeaders headers, @PathParam("apiId") String apiId, @PathParam("authorizerId") String authorizerId) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(toV2AuthorizerNode(v2Service.getAuthorizer(region, apiId, authorizerId)).toString()).type(MediaType.APPLICATION_JSON).build();
    }

    @DELETE
    @Path("/v2/apis/{apiId}/authorizers/{authorizerId}")
    public Response deleteV2Authorizer(@Context HttpHeaders headers, @PathParam("apiId") String apiId, @PathParam("authorizerId") String authorizerId) {
        String region = regionResolver.resolveRegion(headers);
        v2Service.deleteAuthorizer(region, apiId, authorizerId);
        return Response.noContent().build();
    }

    @POST
    @Path("/v2/apis/{apiId}/stages")
    public Response createV2Stage(@Context HttpHeaders headers, @PathParam("apiId") String apiId, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = objectMapper.readValue(body, Map.class);
            Stage stage = v2Service.createStage(region, apiId, request);
            return Response.status(201).entity(toV2StageNode(stage).toString()).type(MediaType.APPLICATION_JSON).build();
        } catch (IOException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/v2/apis/{apiId}/stages")
    public Response getV2Stages(@Context HttpHeaders headers, @PathParam("apiId") String apiId) {
        String region = regionResolver.resolveRegion(headers);
        List<Stage> stages = v2Service.getStages(region, apiId);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("items");
        stages.forEach(s -> items.add(toV2StageNode(s)));
        return Response.ok(root.toString()).type(MediaType.APPLICATION_JSON).build();
    }

    @GET
    @Path("/v2/apis/{apiId}/stages/{stageName}")
    public Response getV2Stage(@Context HttpHeaders headers, @PathParam("apiId") String apiId, @PathParam("stageName") String stageName) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(toV2StageNode(v2Service.getStage(region, apiId, stageName)).toString()).type(MediaType.APPLICATION_JSON).build();
    }

    @DELETE
    @Path("/v2/apis/{apiId}/stages/{stageName}")
    public Response deleteV2Stage(@Context HttpHeaders headers, @PathParam("apiId") String apiId, @PathParam("stageName") String stageName) {
        String region = regionResolver.resolveRegion(headers);
        v2Service.deleteStage(region, apiId, stageName);
        return Response.noContent().build();
    }

    @POST
    @Path("/v2/apis/{apiId}/deployments")
    public Response createV2Deployment(@Context HttpHeaders headers, @PathParam("apiId") String apiId, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = objectMapper.readValue(body, Map.class);
            Deployment deployment = v2Service.createDeployment(region, apiId, request);
            return Response.status(201).entity(toV2DeploymentNode(deployment).toString()).type(MediaType.APPLICATION_JSON).build();
        } catch (IOException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/v2/apis/{apiId}/deployments")
    public Response getV2Deployments(@Context HttpHeaders headers, @PathParam("apiId") String apiId) {
        String region = regionResolver.resolveRegion(headers);
        List<Deployment> deployments = v2Service.getDeployments(region, apiId);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("items");
        deployments.forEach(d -> items.add(toV2DeploymentNode(d)));
        return Response.ok(root.toString()).type(MediaType.APPLICATION_JSON).build();
    }

    @GET
    @Path("/v2/apis/{apiId}/deployments/{deploymentId}")
    public Response getV2Deployment(@Context HttpHeaders headers, @PathParam("apiId") String apiId, @PathParam("deploymentId") String deploymentId) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(toV2DeploymentNode(v2Service.getDeployment(region, apiId, deploymentId)).toString()).type(MediaType.APPLICATION_JSON).build();
    }

    @DELETE
    @Path("/v2/apis/{apiId}/deployments/{deploymentId}")
    public Response deleteV2Deployment(@Context HttpHeaders headers, @PathParam("apiId") String apiId, @PathParam("deploymentId") String deploymentId) {
        String region = regionResolver.resolveRegion(headers);
        v2Service.deleteDeployment(region, apiId, deploymentId);
        return Response.noContent().build();
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private ObjectNode toApiNode(RestApi api) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", api.getId());
        node.put("name", api.getName());
        if (api.getDescription() != null) node.put("description", api.getDescription());
        node.put("createdDate", api.getCreatedDate());
        if (api.getTags() != null && !api.getTags().isEmpty()) {
            ObjectNode tagsNode = objectMapper.createObjectNode();
            api.getTags().forEach(tagsNode::put);
            node.set("tags", tagsNode);
        }
        return node;
    }

    private ObjectNode toResourceNode(ApiGatewayResource r) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", r.getId());
        if (r.getParentId() != null) node.put("parentId", r.getParentId());
        if (r.getPathPart() != null) node.put("pathPart", r.getPathPart());
        node.put("path", r.getPath());
        return node;
    }

    private ObjectNode toMethodNode(MethodConfig m) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("httpMethod", m.getHttpMethod());
        node.put("authorizationType", m.getAuthorizationType());
        if (m.getAuthorizerId() != null) node.put("authorizerId", m.getAuthorizerId());
        if (m.getRequestValidatorId() != null) node.put("requestValidatorId", m.getRequestValidatorId());
        if (m.getRequestModels() != null && !m.getRequestModels().isEmpty()) {
            ObjectNode models = objectMapper.createObjectNode();
            m.getRequestModels().forEach(models::put);
            node.set("requestModels", models);
        }
        if (m.getMethodIntegration() != null) {
            node.set("methodIntegration", toIntegrationNode(m.getMethodIntegration()));
        }
        return node;
    }

    private ObjectNode toMethodResponseNode(MethodResponse r) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("statusCode", r.statusCode());
        return node;
    }

    private ObjectNode toIntegrationNode(io.github.hectorvent.floci.services.apigateway.model.Integration i) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", i.getType());
        node.put("httpMethod", i.getHttpMethod());
        node.put("uri", i.getUri());
        node.put("passthroughBehavior", i.getPassthroughBehavior());
        return node;
    }

    private ObjectNode toIntegrationResponseNode(IntegrationResponse r) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("statusCode", r.statusCode());
        node.put("selectionPattern", r.selectionPattern());
        return node;
    }

    private ObjectNode toDeploymentNode(io.github.hectorvent.floci.services.apigateway.model.Deployment d) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", d.id());
        if (d.description() != null) node.put("description", d.description());
        node.put("createdDate", d.createdDate());
        return node;
    }

    private ObjectNode toStageNode(io.github.hectorvent.floci.services.apigateway.model.Stage s) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("stageName", s.getStageName());
        node.put("deploymentId", s.getDeploymentId());
        if (s.getDescription() != null) node.put("description", s.getDescription());
        node.put("createdDate", s.getCreatedDate());
        node.put("lastUpdatedDate", s.getLastUpdatedDate());
        if (!s.getVariables().isEmpty()) {
            ObjectNode vars = node.putObject("variables");
            s.getVariables().forEach(vars::put);
        }
        return node;
    }

    private ObjectNode toAuthorizerNode(io.github.hectorvent.floci.services.apigateway.model.Authorizer a) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", a.getId());
        node.put("name", a.getName());
        node.put("type", a.getType());
        if (a.getAuthorizerUri() != null) node.put("authorizerUri", a.getAuthorizerUri());
        if (a.getIdentitySource() != null) node.put("identitySource", a.getIdentitySource());
        node.put("authorizerResultTtlInSeconds", Integer.parseInt(a.getAuthorizerResultTtlInSeconds()));
        return node;
    }

    private ObjectNode toApiKeyNode(ApiKey k) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", k.getId());
        node.put("name", k.getName());
        node.put("value", k.getValue());
        node.put("enabled", k.isEnabled());
        return node;
    }

    private ObjectNode toUsagePlanNode(UsagePlan p) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", p.getId());
        node.put("name", p.getName());
        return node;
    }

    private ObjectNode toUsagePlanKeyNode(UsagePlanKey k) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", k.getId());
        node.put("name", k.getName());
        node.put("type", k.getType());
        node.put("value", k.getValue());
        return node;
    }

    private ObjectNode toDomainNode(CustomDomain d) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("domainName", d.getDomainName());
        node.put("domainNameStatus", d.getDomainNameStatus());
        node.put("endpointConfigurationType", d.getEndpointConfigurationType());
        if (d.getCertificateName() != null) node.put("certificateName", d.getCertificateName());
        if (d.getCertificateArn() != null) node.put("certificateArn", d.getCertificateArn());
        node.put("regionalDomainName", d.getRegionalDomainName());
        node.put("regionalHostedZoneId", d.getRegionalHostedZoneId());
        return node;
    }

    private ObjectNode toMappingNode(BasePathMapping m) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("basePath", m.getBasePath());
        node.put("restApiId", m.getRestApiId());
        node.put("stage", m.getStage());
        return node;
    }

    private ObjectNode toModelNode(io.github.hectorvent.floci.services.apigateway.model.Model m) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", m.getId());
        node.put("name", m.getName());
        if (m.getDescription() != null) node.put("description", m.getDescription());
        node.put("contentType", m.getContentType());
        if (m.getSchema() != null) node.put("schema", m.getSchema());
        return node;
    }

    private ObjectNode toRequestValidatorNode(RequestValidator v) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", v.getId());
        node.put("name", v.getName());
        node.put("validateRequestBody", v.isValidateRequestBody());
        node.put("validateRequestParameters", v.isValidateRequestParameters());
        return node;
    }

    private ObjectNode toV2ApiNode(Api api) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("apiId", api.getApiId());
        node.put("name", api.getName());
        node.put("protocolType", api.getProtocolType());
        node.put("apiEndpoint", api.getApiEndpoint());
        node.put("createdDate", java.time.Instant.ofEpochMilli(api.getCreatedDate()).toString());
        return node;
    }

    private ObjectNode toV2RouteNode(Route r) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("routeId", r.getRouteId());
        node.put("routeKey", r.getRouteKey());
        node.put("authorizationType", r.getAuthorizationType());
        if (r.getTarget() != null) node.put("target", r.getTarget());
        return node;
    }

    private ObjectNode toV2IntegrationNode(Integration i) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("integrationId", i.getIntegrationId());
        node.put("integrationType", i.getIntegrationType());
        node.put("payloadFormatVersion", i.getPayloadFormatVersion());
        if (i.getIntegrationUri() != null) node.put("integrationUri", i.getIntegrationUri());
        return node;
    }

    private ObjectNode toV2StageNode(Stage s) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("stageName", s.getStageName());
        if (s.getDeploymentId() != null) node.put("deploymentId", s.getDeploymentId());
        node.put("autoDeploy", s.isAutoDeploy());
        node.put("createdDate", java.time.Instant.ofEpochMilli(s.getCreatedDate()).toString());
        node.put("lastUpdatedDate", java.time.Instant.ofEpochMilli(s.getLastUpdatedDate()).toString());
        return node;
    }

    private ObjectNode toV2DeploymentNode(Deployment d) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("deploymentId", d.getDeploymentId());
        node.put("deploymentStatus", d.getDeploymentStatus());
        if (d.getDescription() != null) node.put("description", d.getDescription());
        node.put("createdDate", java.time.Instant.ofEpochMilli(d.getCreatedDate()).toString());
        return node;
    }

    private ObjectNode toV2AuthorizerNode(Authorizer a) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("authorizerId", a.getAuthorizerId());
        node.put("authorizerType", a.getAuthorizerType());
        node.put("name", a.getName());
        if (a.getIdentitySource() != null) {
            ArrayNode idSources = node.putArray("identitySource");
            a.getIdentitySource().forEach(idSources::add);
        }
        if (a.getJwtConfiguration() != null) {
            ObjectNode jwt = node.putObject("jwtConfiguration");
            if (a.getJwtConfiguration().audience() != null) {
                ArrayNode aud = jwt.putArray("audience");
                a.getJwtConfiguration().audience().forEach(aud::add);
            }
            if (a.getJwtConfiguration().issuer() != null) {
                jwt.put("issuer", a.getJwtConfiguration().issuer());
            }
        }
        return node;
    }

}
