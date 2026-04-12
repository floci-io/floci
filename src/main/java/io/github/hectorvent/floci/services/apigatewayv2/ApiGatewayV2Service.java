package io.github.hectorvent.floci.services.apigatewayv2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.services.apigatewayv2.model.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@ApplicationScoped
public class ApiGatewayV2Service {

    private static final Logger LOG = Logger.getLogger(ApiGatewayV2Service.class);

    private final StorageBackend<String, Api> apiStore;
    private final StorageBackend<String, Route> routeStore;
    private final StorageBackend<String, Integration> integrationStore;
    private final StorageBackend<String, Authorizer> authorizerStore;
    private final StorageBackend<String, Deployment> deploymentStore;
    private final StorageBackend<String, Stage> stageStore;
    private final RegionResolver regionResolver;

    @Inject
    public ApiGatewayV2Service(StorageFactory storageFactory, EmulatorConfig config, RegionResolver regionResolver) {
        this.apiStore = storageFactory.create("apigatewayv2", "apigatewayv2-apis.json",
                new TypeReference<>() {});
        this.routeStore = storageFactory.create("apigatewayv2", "apigatewayv2-routes.json",
                new TypeReference<>() {});
        this.integrationStore = storageFactory.create("apigatewayv2", "apigatewayv2-integrations.json",
                new TypeReference<>() {});
        this.authorizerStore = storageFactory.create("apigatewayv2", "apigatewayv2-authorizers.json",
                new TypeReference<>() {});
        this.deploymentStore = storageFactory.create("apigatewayv2", "apigatewayv2-deployments.json",
                new TypeReference<>() {});
        this.stageStore = storageFactory.create("apigatewayv2", "apigatewayv2-stages.json",
                new TypeReference<>() {});
        this.regionResolver = regionResolver;
    }

    // ──────────────────────────── API CRUD ────────────────────────────

    public Api createApi(String region, Map<String, Object> request) {
        String name = (String) request.get("name");
        String protocolType = (String) request.getOrDefault("protocolType", "HTTP");

        Api api = new Api();
        api.setApiId(shortId(10));
        api.setName(name);
        api.setProtocolType(protocolType);
        api.setCreatedDate(System.currentTimeMillis());
        api.setApiEndpoint(String.format("https://%s.execute-api.%s.amazonaws.com", api.getApiId(), region));

        apiStore.put(apiKey(region, api.getApiId()), api);
        LOG.infov("Created HTTP API: {0} ({1}) in {2}", api.getName(), api.getApiId(), region);
        return api;
    }

    public Api getApi(String region, String apiId) {
        return apiStore.get(apiKey(region, apiId))
                .orElseThrow(() -> new AwsException("NotFoundException", "Invalid API id specified", 404));
    }

    public List<Api> getApis(String region) {
        String prefix = region + "::";
        return apiStore.scan(k -> k.startsWith(prefix));
    }

    public void deleteApi(String region, String apiId) {
        getApi(region, apiId);
        apiStore.delete(apiKey(region, apiId));
    }

    // ──────────────────────────── Authorizer CRUD ────────────────────────────

    public Authorizer createAuthorizer(String region, String apiId, Map<String, Object> request) {
        getApi(region, apiId);
        Authorizer auth = new Authorizer();
        auth.setAuthorizerId(shortId(8));
        auth.setName((String) request.get("name"));
        auth.setAuthorizerType((String) request.get("authorizerType"));

        @SuppressWarnings("unchecked")
        List<String> identitySource = (List<String>) request.get("identitySource");
        auth.setIdentitySource(identitySource);

        @SuppressWarnings("unchecked")
        Map<String, Object> jwtConfig = (Map<String, Object>) request.get("jwtConfiguration");
        if (jwtConfig != null) {
            @SuppressWarnings("unchecked")
            List<String> audience = (List<String>) jwtConfig.get("audience");
            String issuer = (String) jwtConfig.get("issuer");
            auth.setJwtConfiguration(new Authorizer.JwtConfiguration(audience, issuer));
        }

        authorizerStore.put(authorizerKey(region, apiId, auth.getAuthorizerId()), auth);
        LOG.infov("Created JWT authorizer: {0} ({1}) for API {2}", auth.getName(), auth.getAuthorizerId(), apiId);
        return auth;
    }

    public Authorizer getAuthorizer(String region, String apiId, String authorizerId) {
        return authorizerStore.get(authorizerKey(region, apiId, authorizerId))
                .orElseThrow(() -> new AwsException("NotFoundException", "Authorizer not found", 404));
    }

    public List<Authorizer> getAuthorizers(String region, String apiId) {
        String prefix = region + "::" + apiId + "::";
        return authorizerStore.scan(k -> k.startsWith(prefix));
    }

    public void deleteAuthorizer(String region, String apiId, String authorizerId) {
        getAuthorizer(region, apiId, authorizerId);
        authorizerStore.delete(authorizerKey(region, apiId, authorizerId));
    }

    // ──────────────────────────── Route CRUD ────────────────────────────

    public Route createRoute(String region, String apiId, Map<String, Object> request) {
        getApi(region, apiId);
        Route route = new Route();
        route.setRouteId(shortId(8));
        route.setRouteKey((String) request.get("routeKey"));
        route.setAuthorizationType((String) request.getOrDefault("authorizationType", "NONE"));
        route.setAuthorizerId((String) request.get("authorizerId"));
        route.setTarget((String) request.get("target"));

        routeStore.put(routeKey(region, apiId, route.getRouteId()), route);
        return route;
    }

    public Route getRoute(String region, String apiId, String routeId) {
        return routeStore.get(routeKey(region, apiId, routeId))
                .orElseThrow(() -> new AwsException("NotFoundException", "Route not found", 404));
    }

    public List<Route> getRoutes(String region, String apiId) {
        String prefix = region + "::" + apiId + "::";
        return routeStore.scan(k -> k.startsWith(prefix));
    }

    public void deleteRoute(String region, String apiId, String routeId) {
        getRoute(region, apiId, routeId);
        routeStore.delete(routeKey(region, apiId, routeId));
    }

    /**
     * Finds the best matching route for the given HTTP method and path.
     * Priority: exact match > path-template match > $default.
     */
    public Route findMatchingRoute(String region, String apiId, String httpMethod, String path) {
        List<Route> routes = getRoutes(region, apiId);
        String candidate = httpMethod.toUpperCase() + " " + path;

        // 1. Exact match
        for (Route r : routes) {
            if (candidate.equals(r.getRouteKey())) return r;
        }

        // 2. Path-template match (e.g. "GET /users/{id}")
        for (Route r : routes) {
            if (r.getRouteKey() == null || r.getRouteKey().equals("$default")) continue;
            if (routeKeyMatchesPath(r.getRouteKey(), httpMethod, path)) return r;
        }

        // 3. $default catch-all
        for (Route r : routes) {
            if ("$default".equals(r.getRouteKey())) return r;
        }

        return null;
    }

    private boolean routeKeyMatchesPath(String routeKey, String httpMethod, String path) {
        int space = routeKey.indexOf(' ');
        if (space < 0) return false;
        String method = routeKey.substring(0, space);
        String pattern = routeKey.substring(space + 1);
        if (!method.equalsIgnoreCase(httpMethod)) return false;

        // Convert path template to regex: {param} → [^/]+, {proxy+} → .+
        String regex = "^" + Pattern.quote(pattern)
                .replace("\\{proxy+\\}", "\\E.+\\Q")
                .replace("\\{", "\\E[^/]+\\Q")
                .replaceAll("\\\\Q\\\\E", "") + "$";
        return path.matches(regex);
    }

    // ──────────────────────────── Integration CRUD ────────────────────────────

    public Integration createIntegration(String region, String apiId, Map<String, Object> request) {
        getApi(region, apiId);
        Integration integration = new Integration();
        integration.setIntegrationId(shortId(8));
        integration.setIntegrationType((String) request.get("integrationType"));
        integration.setIntegrationUri((String) request.get("integrationUri"));
        integration.setPayloadFormatVersion((String) request.getOrDefault("payloadFormatVersion", "2.0"));

        integrationStore.put(integrationKey(region, apiId, integration.getIntegrationId()), integration);
        return integration;
    }

    public Integration getIntegration(String region, String apiId, String integrationId) {
        return integrationStore.get(integrationKey(region, apiId, integrationId))
                .orElseThrow(() -> new AwsException("NotFoundException", "Integration not found", 404));
    }

    public List<Integration> getIntegrations(String region, String apiId) {
        String prefix = region + "::" + apiId + "::";
        return integrationStore.scan(k -> k.startsWith(prefix));
    }

    public void deleteIntegration(String region, String apiId, String integrationId) {
        getIntegration(region, apiId, integrationId);
        integrationStore.delete(integrationKey(region, apiId, integrationId));
    }

    // ──────────────────────────── Stage CRUD ────────────────────────────

    public Stage createStage(String region, String apiId, Map<String, Object> request) {
        getApi(region, apiId);
        Stage stage = new Stage();
        stage.setStageName((String) request.getOrDefault("stageName", "$default"));
        stage.setDeploymentId((String) request.get("deploymentId"));
        stage.setAutoDeploy(Boolean.parseBoolean(String.valueOf(request.getOrDefault("autoDeploy", "false"))));
        stage.setCreatedDate(System.currentTimeMillis());
        stage.setLastUpdatedDate(System.currentTimeMillis());

        stageStore.put(stageKey(region, apiId, stage.getStageName()), stage);
        LOG.infov("Created stage: {0} for API {1}", stage.getStageName(), apiId);
        return stage;
    }

    public Stage getStage(String region, String apiId, String stageName) {
        return stageStore.get(stageKey(region, apiId, stageName))
                .orElseThrow(() -> new AwsException("NotFoundException", "Stage not found", 404));
    }

    public List<Stage> getStages(String region, String apiId) {
        String prefix = region + "::" + apiId + "::";
        return stageStore.scan(k -> k.startsWith(prefix));
    }

    public void deleteStage(String region, String apiId, String stageName) {
        getStage(region, apiId, stageName);
        stageStore.delete(stageKey(region, apiId, stageName));
    }

    // ──────────────────────────── Deployment CRUD ────────────────────────────

    public Deployment createDeployment(String region, String apiId, Map<String, Object> request) {
        getApi(region, apiId);
        Deployment deployment = new Deployment();
        deployment.setDeploymentId(shortId(8));
        deployment.setDeploymentStatus("DEPLOYED");
        deployment.setDescription((String) request.get("description"));
        deployment.setCreatedDate(System.currentTimeMillis());

        deploymentStore.put(deploymentKey(region, apiId, deployment.getDeploymentId()), deployment);
        return deployment;
    }

    public Deployment getDeployment(String region, String apiId, String deploymentId) {
        return deploymentStore.get(deploymentKey(region, apiId, deploymentId))
                .orElseThrow(() -> new AwsException("NotFoundException", "Deployment not found", 404));
    }

    public List<Deployment> getDeployments(String region, String apiId) {
        String prefix = region + "::" + apiId + "::";
        return deploymentStore.scan(k -> k.startsWith(prefix));
    }

    public void deleteDeployment(String region, String apiId, String deploymentId) {
        getDeployment(region, apiId, deploymentId);
        deploymentStore.delete(deploymentKey(region, apiId, deploymentId));
    }

    // ──────────────────────────── Key helpers ────────────────────────────

    private String apiKey(String region, String apiId) {
        return region + "::" + apiId;
    }

    private String routeKey(String region, String apiId, String routeId) {
        return region + "::" + apiId + "::" + routeId;
    }

    private String integrationKey(String region, String apiId, String integrationId) {
        return region + "::" + apiId + "::" + integrationId;
    }

    private String authorizerKey(String region, String apiId, String authorizerId) {
        return region + "::" + apiId + "::" + authorizerId;
    }

    private String stageKey(String region, String apiId, String stageName) {
        return region + "::" + apiId + "::" + stageName;
    }

    private String deploymentKey(String region, String apiId, String deploymentId) {
        return region + "::" + apiId + "::" + deploymentId;
    }

    private static String shortId(int length) {
        return UUID.randomUUID().toString().replace("-", "").substring(0, length);
    }
}
