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
                case "DeleteApi" -> handleDeleteApi(request, region);
                case "CreateRoute" -> handleCreateRoute(request, region);
                case "GetRoute" -> handleGetRoute(request, region);
                case "GetRoutes" -> handleGetRoutes(request, region);
                case "DeleteRoute" -> handleDeleteRoute(request, region);
                case "CreateIntegration" -> handleCreateIntegration(request, region);
                case "GetIntegration" -> handleGetIntegration(request, region);
                case "GetIntegrations" -> handleGetIntegrations(request, region);
                case "CreateAuthorizer" -> handleCreateAuthorizer(request, region);
                case "GetAuthorizer" -> handleGetAuthorizer(request, region);
                case "GetAuthorizers" -> handleGetAuthorizers(request, region);
                case "DeleteAuthorizer" -> handleDeleteAuthorizer(request, region);
                case "CreateStage" -> handleCreateStage(request, region);
                case "GetStage" -> handleGetStage(request, region);
                case "GetStages" -> handleGetStages(request, region);
                case "DeleteStage" -> handleDeleteStage(request, region);
                case "CreateDeployment" -> handleCreateDeployment(request, region);
                case "GetDeployment" -> handleGetDeployment(request, region);
                case "GetDeployments" -> handleGetDeployments(request, region);
                case "DeleteDeployment" -> handleDeleteDeployment(request, region);
                case "DeleteIntegration" -> handleDeleteIntegration(request, region);
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

    private Response handleDeleteIntegration(JsonNode request, String region) {
        String apiId = request.path("ApiId").asText();
        String integrationId = request.path("IntegrationId").asText();
        service.deleteIntegration(region, apiId, integrationId);
        return Response.noContent().build();
    }

    // ──────────────────────────── Serializers ────────────────────────────

    private ObjectNode toApiNode(Api api) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ApiId", api.getApiId());
        node.put("Name", api.getName());
        node.put("ProtocolType", api.getProtocolType());
        node.put("ApiEndpoint", api.getApiEndpoint());
        node.put("CreatedDate", api.getCreatedDate() / 1000.0);
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
        return node;
    }

    private ObjectNode toRouteNode(Route r) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("RouteId", r.getRouteId());
        node.put("RouteKey", r.getRouteKey());
        node.put("AuthorizationType", r.getAuthorizationType());
        if (r.getAuthorizerId() != null) node.put("AuthorizerId", r.getAuthorizerId());
        if (r.getTarget() != null) node.put("Target", r.getTarget());
        return node;
    }

    private ObjectNode toIntegrationNode(Integration i) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("IntegrationId", i.getIntegrationId());
        node.put("IntegrationType", i.getIntegrationType());
        node.put("PayloadFormatVersion", i.getPayloadFormatVersion());
        if (i.getIntegrationUri() != null) node.put("IntegrationUri", i.getIntegrationUri());
        return node;
    }

    private ObjectNode toStageNode(Stage s) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("StageName", s.getStageName());
        node.put("AutoDeploy", s.isAutoDeploy());
        node.put("CreatedDate", s.getCreatedDate() / 1000.0);
        node.put("LastUpdatedDate", s.getLastUpdatedDate() / 1000.0);
        if (s.getDeploymentId() != null) node.put("DeploymentId", s.getDeploymentId());
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

    /**
     * Converts PascalCase map keys to lowerCamelCase so the service layer's
     * field lookups work regardless of whether the request arrived via the
     * REST path (lowerCamelCase body) or JSON 1.1 path (PascalCase body).
     */
    @SuppressWarnings("unchecked")
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
