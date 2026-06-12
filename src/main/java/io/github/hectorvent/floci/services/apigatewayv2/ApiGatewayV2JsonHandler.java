package io.github.hectorvent.floci.services.apigatewayv2;

import io.github.hectorvent.floci.core.common.AwsException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.services.apigatewayv2.model.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class ApiGatewayV2JsonHandler {

    private final ApiGatewayV2Service service;
    private final ObjectMapper objectMapper;

    @Inject
    public ApiGatewayV2JsonHandler(ApiGatewayV2Service service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        try {
            return switch (action) {
                case "CreateApi" -> handleCreateApi(request, region);
                case "GetApis" -> handleGetApis(region);
                case "GetApi" -> handleGetApi(request, region);
                case "UpdateApi" -> handleUpdateApi(request, region);
                case "DeleteApi" -> handleDeleteApi(request, region);
                case "CreateRoute" -> handleCreateRoute(request, region);
                case "GetRoute" -> handleGetRoute(request, region);
                case "GetRoutes" -> handleGetRoutes(request, region);
                case "UpdateRoute" -> handleUpdateRoute(request, region);
                case "DeleteRoute" -> handleDeleteRoute(request, region);
                case "CreateIntegration" -> handleCreateIntegration(request, region);
                case "GetIntegration" -> handleGetIntegration(request, region);
                case "GetIntegrations" -> handleGetIntegrations(request, region);
                case "UpdateIntegration" -> handleUpdateIntegration(request, region);
                case "CreateAuthorizer" -> handleCreateAuthorizer(request, region);
                case "GetAuthorizer" -> handleGetAuthorizer(request, region);
                case "GetAuthorizers" -> handleGetAuthorizers(request, region);
                case "DeleteAuthorizer" -> handleDeleteAuthorizer(request, region);
                case "UpdateAuthorizer" -> handleUpdateAuthorizer(request, region);
                case "CreateStage" -> handleCreateStage(request, region);
                case "GetStage" -> handleGetStage(request, region);
                case "GetStages" -> handleGetStages(request, region);
                case "DeleteStage" -> handleDeleteStage(request, region);
                case "UpdateStage" -> handleUpdateStage(request, region);
                case "CreateDeployment" -> handleCreateDeployment(request, region);
                case "GetDeployment" -> handleGetDeployment(request, region);
                case "GetDeployments" -> handleGetDeployments(request, region);
                case "DeleteDeployment" -> handleDeleteDeployment(request, region);
                case "UpdateDeployment" -> handleUpdateDeployment(request, region);
                case "DeleteIntegration" -> handleDeleteIntegration(request, region);
                case "CreateRouteResponse" -> handleCreateRouteResponse(request, region);
                case "GetRouteResponse" -> handleGetRouteResponse(request, region);
                case "GetRouteResponses" -> handleGetRouteResponses(request, region);
                case "UpdateRouteResponse" -> handleUpdateRouteResponse(request, region);
                case "DeleteRouteResponse" -> handleDeleteRouteResponse(request, region);
                case "CreateIntegrationResponse" -> handleCreateIntegrationResponse(request, region);
                case "GetIntegrationResponse" -> handleGetIntegrationResponse(request, region);
                case "GetIntegrationResponses" -> handleGetIntegrationResponses(request, region);
                case "UpdateIntegrationResponse" -> handleUpdateIntegrationResponse(request, region);
                case "DeleteIntegrationResponse" -> handleDeleteIntegrationResponse(request, region);
                case "CreateModel" -> handleCreateModel(request, region);
                case "GetModel" -> handleGetModel(request, region);
                case "GetModels" -> handleGetModels(request, region);
                case "UpdateModel" -> handleUpdateModel(request, region);
                case "DeleteModel" -> handleDeleteModel(request, region);
                case "TagResource" -> handleTagResource(request, region);
                case "UntagResource" -> handleUntagResource(request, region);
                case "GetTags" -> handleGetTags(request, region);
                default -> JsonErrorResponseUtils.createUnknownOperationErrorResponse(action);
            };
        } catch (AwsException e) {
            return JsonErrorResponseUtils.createErrorResponse(e);
        }
    }

    // ──────────────────────────── API ────────────────────────────

    private Response handleCreateApi(JsonNode request, String region) {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = toLowerCamelCase(objectMapper.convertValue(request, Map.class));
        Api api = service.createApi(region, map);
        return Response.status(201).entity(toApiNode(api).toString()).build();
    }

    private Response handleGetApis(String region) {
        List<Api> apis = service.getApis(region);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("Items");
        apis.forEach(a -> items.add(toApiNode(a)));
        return Response.ok(root.toString()).build();
    }

    private Response handleGetApi(JsonNode request, String region) {
        String apiId = request.path("ApiId").asText();
        return Response.ok(toApiNode(service.getApi(region, apiId)).toString()).build();
    }

    private Response handleDeleteApi(JsonNode request, String region) {
        String apiId = request.path("ApiId").asText();
        service.deleteApi(region, apiId);
        return Response.noContent().build();
    }

    private Response handleUpdateApi(JsonNode request, String region) {
        String apiId = request.path("ApiId").asText();
        @SuppressWarnings("unchecked")
        Map<String, Object> map = toLowerCamelCase(objectMapper.convertValue(request, Map.class));
        Api api = service.updateApi(region, apiId, map);
        return Response.ok(toApiNode(api).toString()).build();
    }

    // ──────────────────────────── Authorizer ────────────────────────────

    private Response handleCreateAuthorizer(JsonNode request, String region) {
        String apiId = request.path("ApiId").asText();
        @SuppressWarnings("unchecked")
        Map<String, Object> map = toLowerCamelCase(objectMapper.convertValue(request, Map.class));
        Authorizer auth = service.createAuthorizer(region, apiId, map);
        return Response.status(201).entity(toAuthorizerNode(auth).toString()).build();
    }

    private Response handleGetAuthorizer(JsonNode request, String region) {
        String apiId = request.path("ApiId").asText();
        String authorizerId = request.path("AuthorizerId").asText();
        return Response.ok(toAuthorizerNode(service.getAuthorizer(region, apiId, authorizerId)).toString()).build();
    }

    private Response handleGetAuthorizers(JsonNode request, String region) {
        String apiId = request.path("ApiId").asText();
        List<Authorizer> authorizers = service.getAuthorizers(region, apiId);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("Items");
        authorizers.forEach(a -> items.add(toAuthorizerNode(a)));
        return Response.ok(root.toString()).build();
    }

    private Response handleDeleteAuthorizer(JsonNode request, String region) {
        String apiId = request.path("ApiId").asText();
        String authorizerId = request.path("AuthorizerId").asText();
        service.deleteAuthorizer(region, apiId, authorizerId);
        return Response.noContent().build();
    }

    private Response handleUpdateAuthorizer(JsonNode request, String region) {
        String apiId = request.path("ApiId").asText();
        String authorizerId = request.path("AuthorizerId").asText();
        @SuppressWarnings("unchecked")
        Map<String, Object> map = toLowerCamelCase(objectMapper.convertValue(request, Map.class));
        Authorizer auth = service.updateAuthorizer(region, apiId, authorizerId, map);
        return Response.ok(toAuthorizerNode(auth).toString()).build();
    }

    // ──────────────────────────── Route ────────────────────────────

    private Response handleCreateRoute(JsonNode request, String region) {
        String apiId = request.path("ApiId").asText();
        @SuppressWarnings("unchecked")
        Map<String, Object> map = toLowerCamelCase(objectMapper.convertValue(request, Map.class));
        Route route = service.createRoute(region, apiId, map);
        return Response.status(201).entity(toRouteNode(route).toString()).build();
    }

    private Response handleGetRoute(JsonNode request, String region) {
        String apiId = request.path("ApiId").asText();
        String routeId = request.path("RouteId").asText();
        return Response.ok(toRouteNode(service.getRoute(region, apiId, routeId)).toString()).build();
    }

    private Response handleGetRoutes(JsonNode request, String region) {
        String apiId = request.path("ApiId").asText();
        List<Route> routes = service.getRoutes(region, apiId);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("Items");
        routes.forEach(r -> items.add(toRouteNode(r)));
        return Response.ok(root.toString()).build();
    }

    private Response handleDeleteRoute(JsonNode request, String region) {
        String apiId = request.path("ApiId").asText();
        String routeId = request.path("RouteId").asText();
        service.deleteRoute(region, apiId, routeId);
        return Response.noContent().build();
    }

    private Response handleUpdateRoute(JsonNode request, String region) {
        String apiId = request.path("ApiId").asText();
        String routeId = request.path("RouteId").asText();
        @SuppressWarnings("unchecked")
        Map<String, Object> map = toLowerCamelCase(objectMapper.convertValue(request, Map.class));
        Route route = service.updateRoute(region, apiId, routeId, map);
        return Response.ok(toRouteNode(route).toString()).build();
    }

    // ──────────────────────────── Integration ────────────────────────────

    private Response handleCreateIntegration(JsonNode request, String region) {
        String apiId = request.path("ApiId").asText();
        @SuppressWarnings("unchecked")
        Map<String, Object> map = toLowerCamelCase(objectMapper.convertValue(request, Map.class));
        Integration integration = service.createIntegration(region, apiId, map);
        return Response.status(201).entity(toIntegrationNode(integration).toString()).build();
    }

    private Response handleGetIntegration(JsonNode request, String region) {
        String apiId = request.path("ApiId").asText();
        String integrationId = request.path("IntegrationId").asText();
        return Response.ok(toIntegrationNode(service.getIntegration(region, apiId, integrationId)).toString()).build();
    }

    private Response handleGetIntegrations(JsonNode request, String region) {
        String apiId = request.path("ApiId").asText();
        List<Integration> integrations = service.getIntegrations(region, apiId);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("Items");
        integrations.forEach(i -> items.add(toIntegrationNode(i)));
        return Response.ok(root.toString()).build();
    }

    private Response handleUpdateIntegration(JsonNode request, String region) {
        String apiId = request.path("ApiId").asText();
        String integrationId = request.path("IntegrationId").asText();
        @SuppressWarnings("unchecked")
        Map<String, Object> map = toLowerCamelCase(objectMapper.convertValue(request, Map.class));
        Integration integration = service.updateIntegration(region, apiId, integrationId, map);
        return Response.ok(toIntegrationNode(integration).toString()).build();
    }

    // ──────────────────────────── Stage ────────────────────────────

    private Response handleCreateStage(JsonNode request, String region) {
        String apiId = request.path("ApiId").asText();
        @SuppressWarnings("unchecked")
        Map<String, Object> map = toLowerCamelCase(objectMapper.convertValue(request, Map.class));
        Stage stage = service.createStage(region, apiId, map);
        return Response.status(201).entity(toStageNode(stage).toString()).build();
    }

    private Response handleGetStage(JsonNode request, String region) {
        String apiId = request.path("ApiId").asText();
        String stageName = request.path("StageName").asText();
        return Response.ok(toStageNode(service.getStage(region, apiId, stageName)).toString()).build();
    }

    private Response handleGetStages(JsonNode request, String region) {
        String apiId = request.path("ApiId").asText();
        List<Stage> stages = service.getStages(region, apiId);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("Items");
        stages.forEach(s -> items.add(toStageNode(s)));
        return Response.ok(root.toString()).build();
    }

    private Response handleDeleteStage(JsonNode request, String region) {
        String apiId = request.path("ApiId").asText();
        String stageName = request.path("StageName").asText();
        service.deleteStage(region, apiId, stageName);
        return Response.noContent().build();
    }

    private Response handleUpdateStage(JsonNode request, String region) {
        String apiId = request.path("ApiId").asText();
        String stageName = request.path("StageName").asText();
        @SuppressWarnings("unchecked")
        Map<String, Object> map = toLowerCamelCase(objectMapper.convertValue(request, Map.class));
        Stage stage = service.updateStage(region, apiId, stageName, map);
        return Response.ok(toStageNode(stage).toString()).build();
    }

    // ──────────────────────────── Deployment ────────────────────────────

    private Response handleCreateDeployment(JsonNode request, String region) {
        String apiId = request.path("ApiId").asText();
        @SuppressWarnings("unchecked")
        Map<String, Object> map = toLowerCamelCase(objectMapper.convertValue(request, Map.class));
        Deployment deployment = service.createDeployment(region, apiId, map);
        return Response.status(201).entity(toDeploymentNode(deployment).toString()).build();
    }

    private Response handleGetDeployment(JsonNode request, String region) {
        String apiId = request.path("ApiId").asText();
        String deploymentId = request.path("DeploymentId").asText();
        return Response.ok(toDeploymentNode(service.getDeployment(region, apiId, deploymentId)).toString()).build();
    }

    private Response handleGetDeployments(JsonNode request, String region) {
        String apiId = request.path("ApiId").asText();
        List<Deployment> deployments = service.getDeployments(region, apiId);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("Items");
        deployments.forEach(d -> items.add(toDeploymentNode(d)));
        return Response.ok(root.toString()).build();
    }

    private Response handleDeleteDeployment(JsonNode request, String region) {
        String apiId = request.path("ApiId").asText();
        String deploymentId = request.path("DeploymentId").asText();
        service.deleteDeployment(region, apiId, deploymentId);
        return Response.noContent().build();
    }

    private Response handleUpdateDeployment(JsonNode request, String region) {
        String apiId = request.path("ApiId").asText();
        String deploymentId = request.path("DeploymentId").asText();
        @SuppressWarnings("unchecked")
        Map<String, Object> map = toLowerCamelCase(objectMapper.convertValue(request, Map.class));
        Deployment deployment = service.updateDeployment(region, apiId, deploymentId, map);
        return Response.ok(toDeploymentNode(deployment).toString()).build();
    }

    private Response handleDeleteIntegration(JsonNode request, String region) {
        String apiId = request.path("ApiId").asText();
        String integrationId = request.path("IntegrationId").asText();
        service.deleteIntegration(region, apiId, integrationId);
        return Response.noContent().build();
    }

    // ──────────────────────────── Route Response ────────────────────────────

    private Response handleCreateRouteResponse(JsonNode request, String region) {
        String apiId = request.path("ApiId").asText();
        String routeId = request.path("RouteId").asText();
        @SuppressWarnings("unchecked")
        Map<String, Object> map = toLowerCamelCase(objectMapper.convertValue(request, Map.class));
        RouteResponse rr = service.createRouteResponse(region, apiId, routeId, map);
        return Response.status(201).entity(toRouteResponseNode(rr).toString()).build();
    }

    private Response handleGetRouteResponse(JsonNode request, String region) {
        String apiId = request.path("ApiId").asText();
        String routeId = request.path("RouteId").asText();
        String routeResponseId = request.path("RouteResponseId").asText();
        return Response.ok(toRouteResponseNode(service.getRouteResponse(region, apiId, routeId, routeResponseId)).toString()).build();
    }

    private Response handleGetRouteResponses(JsonNode request, String region) {
        String apiId = request.path("ApiId").asText();
        String routeId = request.path("RouteId").asText();
        List<RouteResponse> routeResponses = service.getRouteResponses(region, apiId, routeId);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("Items");
        routeResponses.forEach(rr -> items.add(toRouteResponseNode(rr)));
        return Response.ok(root.toString()).build();
    }

    private Response handleUpdateRouteResponse(JsonNode request, String region) {
        String apiId = request.path("ApiId").asText();
        String routeId = request.path("RouteId").asText();
        String routeResponseId = request.path("RouteResponseId").asText();
        @SuppressWarnings("unchecked")
        Map<String, Object> map = toLowerCamelCase(objectMapper.convertValue(request, Map.class));
        RouteResponse rr = service.updateRouteResponse(region, apiId, routeId, routeResponseId, map);
        return Response.ok(toRouteResponseNode(rr).toString()).build();
    }

    private Response handleDeleteRouteResponse(JsonNode request, String region) {
        String apiId = request.path("ApiId").asText();
        String routeId = request.path("RouteId").asText();
        String routeResponseId = request.path("RouteResponseId").asText();
        service.deleteRouteResponse(region, apiId, routeId, routeResponseId);
        return Response.noContent().build();
    }

    // ──────────────────────────── Integration Response ────────────────────────────

    private Response handleCreateIntegrationResponse(JsonNode request, String region) {
        String apiId = request.path("ApiId").asText();
        String integrationId = request.path("IntegrationId").asText();
        @SuppressWarnings("unchecked")
        Map<String, Object> map = toLowerCamelCase(objectMapper.convertValue(request, Map.class));
        IntegrationResponse ir = service.createIntegrationResponse(region, apiId, integrationId, map);
        return Response.status(201).entity(toIntegrationResponseNode(ir).toString()).build();
    }

    private Response handleGetIntegrationResponse(JsonNode request, String region) {
        String apiId = request.path("ApiId").asText();
        String integrationId = request.path("IntegrationId").asText();
        String integrationResponseId = request.path("IntegrationResponseId").asText();
        return Response.ok(toIntegrationResponseNode(service.getIntegrationResponse(region, apiId, integrationId, integrationResponseId)).toString()).build();
    }

    private Response handleGetIntegrationResponses(JsonNode request, String region) {
        String apiId = request.path("ApiId").asText();
        String integrationId = request.path("IntegrationId").asText();
        List<IntegrationResponse> integrationResponses = service.getIntegrationResponses(region, apiId, integrationId);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("Items");
        integrationResponses.forEach(ir -> items.add(toIntegrationResponseNode(ir)));
        return Response.ok(root.toString()).build();
    }

    private Response handleUpdateIntegrationResponse(JsonNode request, String region) {
        String apiId = request.path("ApiId").asText();
        String integrationId = request.path("IntegrationId").asText();
        String integrationResponseId = request.path("IntegrationResponseId").asText();
        @SuppressWarnings("unchecked")
        Map<String, Object> map = toLowerCamelCase(objectMapper.convertValue(request, Map.class));
        IntegrationResponse ir = service.updateIntegrationResponse(region, apiId, integrationId, integrationResponseId, map);
        return Response.ok(toIntegrationResponseNode(ir).toString()).build();
    }

    private Response handleDeleteIntegrationResponse(JsonNode request, String region) {
        String apiId = request.path("ApiId").asText();
        String integrationId = request.path("IntegrationId").asText();
        String integrationResponseId = request.path("IntegrationResponseId").asText();
        service.deleteIntegrationResponse(region, apiId, integrationId, integrationResponseId);
        return Response.noContent().build();
    }

    // ──────────────────────────── Model ────────────────────────────

    private Response handleCreateModel(JsonNode request, String region) {
        String apiId = request.path("ApiId").asText();
        @SuppressWarnings("unchecked")
        Map<String, Object> map = toLowerCamelCase(objectMapper.convertValue(request, Map.class));
        Model model = service.createModel(region, apiId, map);
        return Response.status(201).entity(toModelNode(model).toString()).build();
    }

    private Response handleGetModel(JsonNode request, String region) {
        String apiId = request.path("ApiId").asText();
        String modelId = request.path("ModelId").asText();
        return Response.ok(toModelNode(service.getModel(region, apiId, modelId)).toString()).build();
    }

    private Response handleGetModels(JsonNode request, String region) {
        String apiId = request.path("ApiId").asText();
        List<Model> models = service.getModels(region, apiId);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("Items");
        models.forEach(m -> items.add(toModelNode(m)));
        return Response.ok(root.toString()).build();
    }

    private Response handleUpdateModel(JsonNode request, String region) {
        String apiId = request.path("ApiId").asText();
        String modelId = request.path("ModelId").asText();
        @SuppressWarnings("unchecked")
        Map<String, Object> map = toLowerCamelCase(objectMapper.convertValue(request, Map.class));
        Model model = service.updateModel(region, apiId, modelId, map);
        return Response.ok(toModelNode(model).toString()).build();
    }

    private Response handleDeleteModel(JsonNode request, String region) {
        String apiId = request.path("ApiId").asText();
        String modelId = request.path("ModelId").asText();
        service.deleteModel(region, apiId, modelId);
        return Response.noContent().build();
    }

    // ──────────────────────────── Tagging ────────────────────────────

    private Response handleTagResource(JsonNode request, String region) {
        String resourceArn = request.path("ResourceArn").asText();
        Map<String, String> tags = objectMapper.convertValue(
                request.path("Tags"), new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
        service.tagResource(resourceArn, tags);
        return Response.ok("{}").build();
    }

    private Response handleUntagResource(JsonNode request, String region) {
        String resourceArn = request.path("ResourceArn").asText();
        List<String> tagKeys = new java.util.ArrayList<>();
        request.path("TagKeys").forEach(n -> tagKeys.add(n.asText()));
        service.untagResource(resourceArn, tagKeys);
        return Response.noContent().build();
    }

    private Response handleGetTags(JsonNode request, String region) {
        String resourceArn = request.path("ResourceArn").asText();
        Map<String, String> tags = service.getTags(resourceArn);
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode tagsNode = root.putObject("Tags");
        tags.forEach(tagsNode::put);
        return Response.ok(root.toString()).build();
    }

    // ──────────────────────────── Serializers ────────────────────────────

    private ObjectNode toApiNode(Api api) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ApiId", api.getApiId());
        node.put("Name", api.getName());
        node.put("ProtocolType", api.getProtocolType());
        node.put("ApiEndpoint", api.getApiEndpoint());
        node.put("CreatedDate", api.getCreatedDate() / 1000.0);
        if (api.getRouteSelectionExpression() != null) {
            node.put("RouteSelectionExpression", api.getRouteSelectionExpression());
        }
        if (api.getDescription() != null) {
            node.put("Description", api.getDescription());
        }
        if (api.getApiKeySelectionExpression() != null) {
            node.put("ApiKeySelectionExpression", api.getApiKeySelectionExpression());
        }
        if (api.getTags() != null && !api.getTags().isEmpty()) {
            ObjectNode tagsNode = node.putObject("Tags");
            api.getTags().forEach(tagsNode::put);
        }
        if (api.getCorsConfiguration() != null) {
            node.set("CorsConfiguration", toCorsNode(api.getCorsConfiguration()));
        }
        return node;
    }

    private ObjectNode toCorsNode(Api.Cors cors) {
        ObjectNode node = objectMapper.createObjectNode();
        if (cors.allowOrigins() != null) {
            ArrayNode arr = node.putArray("AllowOrigins");
            cors.allowOrigins().forEach(arr::add);
        }
        if (cors.allowMethods() != null) {
            ArrayNode arr = node.putArray("AllowMethods");
            cors.allowMethods().forEach(arr::add);
        }
        if (cors.allowHeaders() != null) {
            ArrayNode arr = node.putArray("AllowHeaders");
            cors.allowHeaders().forEach(arr::add);
        }
        if (cors.exposeHeaders() != null) {
            ArrayNode arr = node.putArray("ExposeHeaders");
            cors.exposeHeaders().forEach(arr::add);
        }
        if (cors.maxAge() != null) node.put("MaxAge", cors.maxAge());
        if (cors.allowCredentials() != null) node.put("AllowCredentials", cors.allowCredentials());
        return node;
    }

    private ObjectNode toAuthorizerNode(Authorizer auth) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("AuthorizerId", auth.getAuthorizerId());
        node.put("Name", auth.getName());
        node.put("AuthorizerType", auth.getAuthorizerType());
        if (auth.getIdentitySource() != null) {
            ArrayNode sources = node.putArray("IdentitySource");
            auth.getIdentitySource().forEach(sources::add);
        }
        if (auth.getJwtConfiguration() != null) {
            ObjectNode jwt = node.putObject("JwtConfiguration");
            if (auth.getJwtConfiguration().issuer() != null) {
                jwt.put("Issuer", auth.getJwtConfiguration().issuer());
            }
            if (auth.getJwtConfiguration().audience() != null) {
                ArrayNode aud = jwt.putArray("Audience");
                auth.getJwtConfiguration().audience().forEach(aud::add);
            }
        }
        if (auth.getAuthorizerUri() != null) {
            node.put("AuthorizerUri", auth.getAuthorizerUri());
        }
        if (auth.getAuthorizerPayloadFormatVersion() != null) {
            node.put("AuthorizerPayloadFormatVersion", auth.getAuthorizerPayloadFormatVersion());
        }
        if (auth.getAuthorizerResultTtlInSeconds() != null) {
            node.put("AuthorizerResultTtlInSeconds", auth.getAuthorizerResultTtlInSeconds());
        }
        if (auth.getEnableSimpleResponses() != null) {
            node.put("EnableSimpleResponses", auth.getEnableSimpleResponses());
        }
        return node;
    }

    private ObjectNode toRouteNode(Route r) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("RouteId", r.getRouteId());
        node.put("RouteKey", r.getRouteKey());
        node.put("AuthorizationType", r.getAuthorizationType());
        if (r.getAuthorizerId() != null) node.put("AuthorizerId", r.getAuthorizerId());
        if (r.getTarget() != null) node.put("Target", r.getTarget());
        if (r.getRouteResponseSelectionExpression() != null) {
            node.put("RouteResponseSelectionExpression", r.getRouteResponseSelectionExpression());
        }
        return node;
    }

    private ObjectNode toIntegrationNode(Integration i) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("IntegrationId", i.getIntegrationId());
        node.put("IntegrationType", i.getIntegrationType());
        if (i.getConnectionType() != null) node.put("ConnectionType", i.getConnectionType());
        if (i.getConnectionId() != null) node.put("ConnectionId", i.getConnectionId());
        node.put("PayloadFormatVersion", i.getPayloadFormatVersion());
        if (i.getIntegrationUri() != null) node.put("IntegrationUri", i.getIntegrationUri());
        if (i.getRequestTemplates() != null) {
            ObjectNode requestTemplates = node.putObject("RequestTemplates");
            i.getRequestTemplates().forEach(requestTemplates::put);
        }
        if (i.getResponseTemplates() != null) {
            ObjectNode responseTemplates = node.putObject("ResponseTemplates");
            i.getResponseTemplates().forEach(responseTemplates::put);
        }
        if (i.getRequestParameters() != null) {
            ObjectNode requestParameters = node.putObject("RequestParameters");
            i.getRequestParameters().forEach(requestParameters::put);
        }
        if (i.getTemplateSelectionExpression() != null) {
            node.put("TemplateSelectionExpression", i.getTemplateSelectionExpression());
        }
        if (i.getIntegrationMethod() != null) {
            node.put("IntegrationMethod", i.getIntegrationMethod());
        }
        if (i.getTimeoutInMillis() != 0) {
            node.put("TimeoutInMillis", i.getTimeoutInMillis());
        }
        return node;
    }

    private ObjectNode toStageNode(Stage s) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("StageName", s.getStageName());
        node.put("AutoDeploy", s.isAutoDeploy());
        node.put("CreatedDate", s.getCreatedDate() / 1000.0);
        node.put("LastUpdatedDate", s.getLastUpdatedDate() / 1000.0);
        if (s.getDeploymentId() != null) node.put("DeploymentId", s.getDeploymentId());
        if (s.getStageVariables() != null) {
            ObjectNode stageVariables = node.putObject("StageVariables");
            s.getStageVariables().forEach(stageVariables::put);
        }
        return node;
    }

    private ObjectNode toDeploymentNode(Deployment d) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("DeploymentId", d.getDeploymentId());
        node.put("DeploymentStatus", d.getDeploymentStatus());
        node.put("CreatedDate", d.getCreatedDate() / 1000.0);
        if (d.getDescription() != null) node.put("Description", d.getDescription());
        return node;
    }

    private ObjectNode toRouteResponseNode(RouteResponse rr) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("RouteResponseId", rr.getRouteResponseId());
        node.put("RouteResponseKey", rr.getRouteResponseKey());
        if (rr.getRouteId() != null) {
            node.put("RouteId", rr.getRouteId());
        }
        if (rr.getModelSelectionExpression() != null) {
            node.put("ModelSelectionExpression", rr.getModelSelectionExpression());
        }
        if (rr.getResponseModels() != null) {
            ObjectNode responseModels = node.putObject("ResponseModels");
            rr.getResponseModels().forEach(responseModels::put);
        }
        if (rr.getResponseParameters() != null) {
            ObjectNode responseParameters = node.putObject("ResponseParameters");
            rr.getResponseParameters().forEach(responseParameters::put);
        }
        return node;
    }

    private ObjectNode toIntegrationResponseNode(IntegrationResponse ir) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("IntegrationResponseId", ir.getIntegrationResponseId());
        node.put("IntegrationResponseKey", ir.getIntegrationResponseKey());
        if (ir.getIntegrationId() != null) {
            node.put("IntegrationId", ir.getIntegrationId());
        }
        if (ir.getContentHandlingStrategy() != null) {
            node.put("ContentHandlingStrategy", ir.getContentHandlingStrategy());
        }
        if (ir.getTemplateSelectionExpression() != null) {
            node.put("TemplateSelectionExpression", ir.getTemplateSelectionExpression());
        }
        if (ir.getResponseTemplates() != null) {
            ObjectNode responseTemplates = node.putObject("ResponseTemplates");
            ir.getResponseTemplates().forEach(responseTemplates::put);
        }
        if (ir.getResponseParameters() != null) {
            ObjectNode responseParameters = node.putObject("ResponseParameters");
            ir.getResponseParameters().forEach(responseParameters::put);
        }
        return node;
    }

    private ObjectNode toModelNode(Model m) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ModelId", m.getModelId());
        node.put("Name", m.getName());
        if (m.getSchema() != null)      node.put("Schema", m.getSchema());
        if (m.getDescription() != null) node.put("Description", m.getDescription());
        if (m.getContentType() != null) node.put("ContentType", m.getContentType());
        return node;
    }

    /**
     * Converts PascalCase map keys to lowerCamelCase so the service layer's
     * field lookups work regardless of whether the request arrived via the
     * REST path (lowerCamelCase body) or JSON 1.1 path (PascalCase body).
     */
    private Map<String, Object> toLowerCamelCase(Map<String, Object> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            if (!key.isEmpty() && Character.isUpperCase(key.charAt(0))) {
                key = Character.toLowerCase(key.charAt(0)) + key.substring(1);
            }
            result.put(key, normalizeValue(entry.getValue()));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Object normalizeValue(Object value) {
        if (value instanceof Map) {
            return toLowerCamelCase((Map<String, Object>) value);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::normalizeValue).toList();
        }
        return value;
    }

}
