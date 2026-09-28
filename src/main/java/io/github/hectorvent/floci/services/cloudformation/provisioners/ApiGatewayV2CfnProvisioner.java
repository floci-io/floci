package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.apigatewayv2.ApiGatewayV2Service;
import io.github.hectorvent.floci.services.apigatewayv2.model.Api;
import io.github.hectorvent.floci.services.apigatewayv2.model.Authorizer;
import io.github.hectorvent.floci.services.apigatewayv2.model.Deployment;
import io.github.hectorvent.floci.services.apigatewayv2.model.Integration;
import io.github.hectorvent.floci.services.apigatewayv2.model.Route;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * CloudFormation provisioning for the six {@code AWS::ApiGatewayV2::*} types (HTTP and WebSocket
 * APIs). They are one coupled family over {@link ApiGatewayV2Service}: an authorizer, route,
 * integration, stage and deployment all belong to an api, and the api's OpenAPI {@code Body}
 * materializes routes, integrations and authorizers of its own. {@code Ref} returns the api id for
 * an Api and the id component for each child type. Deleting the Api cascades to its children, so the
 * child types own no separate delete, except the Authorizer whose delete needs the api id (kept as a
 * create-time attribute).
 */
@ApplicationScoped
public class ApiGatewayV2CfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(ApiGatewayV2CfnProvisioner.class);

    private static final String API = "AWS::ApiGatewayV2::Api";
    private static final String AUTHORIZER = "AWS::ApiGatewayV2::Authorizer";
    private static final String ROUTE = "AWS::ApiGatewayV2::Route";
    private static final String INTEGRATION = "AWS::ApiGatewayV2::Integration";
    private static final String STAGE = "AWS::ApiGatewayV2::Stage";
    private static final String DEPLOYMENT = "AWS::ApiGatewayV2::Deployment";

    private static final String BODY_ROUTE_IDS_ATTR = "__FlociApiGatewayV2BodyRouteIds";
    private static final String BODY_INTEGRATION_IDS_ATTR = "__FlociApiGatewayV2BodyIntegrationIds";
    private static final String BODY_AUTHORIZER_IDS_ATTR = "__FlociApiGatewayV2BodyAuthorizerIds";

    private final ApiGatewayV2Service apiGatewayV2Service;
    private final S3Service s3Service;
    private final ObjectMapper objectMapper;

    @Inject
    public ApiGatewayV2CfnProvisioner(ApiGatewayV2Service apiGatewayV2Service, S3Service s3Service,
                                      ObjectMapper objectMapper) {
        this.apiGatewayV2Service = apiGatewayV2Service;
        this.s3Service = s3Service;
        this.objectMapper = objectMapper;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(API, AUTHORIZER, ROUTE, INTEGRATION, STAGE, DEPLOYMENT);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case API -> provisionApi(r, props, ctx);
            case AUTHORIZER -> provisionAuthorizer(r, props, ctx);
            case ROUTE -> provisionRoute(r, props, ctx);
            case INTEGRATION -> provisionIntegration(r, props, ctx);
            case STAGE -> provisionStage(r, props, ctx);
            case DEPLOYMENT -> provisionDeployment(r, props, ctx);
            default -> throw new IllegalStateException("Unhandled type: " + r.getResourceType());
        }
    }

    @Override
    public void delete(StackResource resource, String region) {
        switch (resource.getResourceType()) {
            case API -> apiGatewayV2Service.deleteApi(region, resource.getPhysicalId());
            case AUTHORIZER -> {
                // Deleting an authorizer needs the api id (a create-time attribute) as well as the
                // authorizer id (the physical id), which the id-only delete path cannot supply.
                String apiId = resource.getAttributes().get("ApiId");
                if (apiId != null && !apiId.isBlank()) {
                    try {
                        apiGatewayV2Service.deleteAuthorizer(region, apiId, resource.getPhysicalId());
                    } catch (RuntimeException e) {
                        LOG.debugv("Error deleting authorizer {0}: {1}",
                                resource.getPhysicalId(), e.getMessage());
                    }
                }
            }
            default -> {
                // Route, Integration, Stage and Deployment are removed when their Api is deleted.
            }
        }
    }

    @Override
    public void mergeFailedUpdateResourceTracking(StackResource previous, StackResource attempted) {
        // Only the Api carries body-generated sub-resource ids; keep any the failed attempt created
        // so the restored resource still owns them for the next cleanup or stack delete.
        if (!API.equals(previous.getResourceType())) {
            return;
        }
        retainBodyResourceIds(previous, BODY_ROUTE_IDS_ATTR, bodyResourceIds(attempted, BODY_ROUTE_IDS_ATTR));
        retainBodyResourceIds(previous, BODY_INTEGRATION_IDS_ATTR,
                bodyResourceIds(attempted, BODY_INTEGRATION_IDS_ATTR));
        retainBodyResourceIds(previous, BODY_AUTHORIZER_IDS_ATTR,
                bodyResourceIds(attempted, BODY_AUTHORIZER_IDS_ATTR));
    }

    // ── Api ──────────────────────────────────────────────────────────────────

    private void provisionApi(StackResource r, JsonNode props, ProvisionContext ctx) {
        CloudFormationTemplateEngine engine = ctx.engine();
        String region = ctx.region();
        String name = ctx.resolveOptional(props, "Name");
        if (name == null || name.isBlank()) {
            name = ctx.generatePhysicalName(r.getLogicalId(), 255, false);
        }
        Map<String, Object> req = new HashMap<>();
        req.put("name", name);
        req.put("protocolType", ctx.resolveOrDefault(props, "ProtocolType", "HTTP"));
        req.put("routeSelectionExpression", ctx.resolveOptional(props, "RouteSelectionExpression"));
        req.put("description", ctx.resolveOptional(props, "Description"));
        req.put("apiKeySelectionExpression", ctx.resolveOptional(props, "ApiKeySelectionExpression"));

        Map<String, String> tags = parseTags(props != null ? props.get("Tags") : null, engine);
        if (!tags.isEmpty()) {
            req.put("tags", tags);
        }
        Map<String, Object> cors = parseCors(props != null ? props.get("CorsConfiguration") : null, engine);
        if (cors != null) {
            req.put("corsConfiguration", cors);
        }

        Api api;
        if (r.getPhysicalId() == null) {
            api = apiGatewayV2Service.createApi(region, req);
        } else {
            api = apiGatewayV2Service.updateApi(region, r.getPhysicalId(), req);
        }
        r.setPhysicalId(api.getApiId());
        r.getAttributes().put("ApiId", api.getApiId());
        r.getAttributes().put("ApiEndpoint", api.getApiEndpoint());
        // The ARN prefix a Lambda permission's SourceArn is built from; AWS returns
        // arn:<partition>:execute-api:<region>:<account>:<apiId> for this attribute.
        r.getAttributes().put("ExecuteApiArn",
                AwsArnUtils.Arn.of("execute-api", region, ctx.accountId(), api.getApiId()).toString());
        reconcileBodyRoutes(r, region, api.getApiId(), props, engine);
    }

    /**
     * Reconciles the routes, integrations and authorizers materialized from an ApiGatewayV2 OpenAPI
     * body. Only ids stored on this resource are removed, so separately declared V2 resources stay
     * outside this generated-resource lifecycle.
     */
    private void reconcileBodyRoutes(StackResource r, String region, String apiId, JsonNode props,
                                     CloudFormationTemplateEngine engine) {
        JsonNode body = OpenApiDocuments.resolve(props, engine, s3Service, objectMapper);
        BodyResourceState previous = null;
        try {
            previous = snapshotBodyResources(r, region, apiId);
            // API Gateway requires route keys to be unique. Remove only the tracked body-generated
            // resources before creating their replacements; rollback restores this snapshot.
            deleteBodyResources(r, region, apiId);
        } catch (RuntimeException e) {
            rollbackBodyReplacement(r, region, apiId, new BodyResources(List.of(), List.of(), List.of()),
                    previous, e);
            throw e;
        }

        if (body == null) {
            return;
        }

        BodyResources replacement;
        try {
            replacement = materializeBodyRoutes(region, apiId, body);
        } catch (BodyMaterializationException e) {
            rollbackBodyReplacement(r, region, apiId, e.resources(), previous, e);
            throw e;
        } catch (RuntimeException e) {
            // materializeBodyRoutes already removed its partial replacement.
            rollbackBodyReplacement(r, region, apiId, new BodyResources(List.of(), List.of(), List.of()),
                    previous, e);
            throw e;
        }
        storeBodyResourceIds(r, BODY_ROUTE_IDS_ATTR, replacement.routeIds());
        storeBodyResourceIds(r, BODY_INTEGRATION_IDS_ATTR, replacement.integrationIds());
        storeBodyResourceIds(r, BODY_AUTHORIZER_IDS_ATTR, replacement.authorizerIds());
    }

    /**
     * CloudFormation's ApiGatewayV2 {@code Body} is an OpenAPI document. Materialize each declared
     * HTTP operation as the route API Gateway V2 serves, including its OpenAPI security requirement
     * and a route target when it declares an integration extension.
     */
    private BodyResources materializeBodyRoutes(String region, String apiId, JsonNode body) {
        List<String> routeIds = new ArrayList<>();
        List<String> integrationIds = new ArrayList<>();
        List<String> authorizerIds = new ArrayList<>();
        try {
            Map<String, OpenApiAuthorizerBinding> authorizers = materializeBodyAuthorizers(
                    region, apiId, body, authorizerIds);
            JsonNode paths = body.path("paths");
            if (!paths.isObject()) {
                return new BodyResources(routeIds, integrationIds, authorizerIds);
            }

            Iterator<Map.Entry<String, JsonNode>> pathEntries = paths.fields();
            while (pathEntries.hasNext()) {
                Map.Entry<String, JsonNode> pathEntry = pathEntries.next();
                if (!pathEntry.getValue().isObject()) {
                    continue;
                }
                Iterator<Map.Entry<String, JsonNode>> operations = pathEntry.getValue().fields();
                while (operations.hasNext()) {
                    Map.Entry<String, JsonNode> operation = operations.next();
                    String method = operation.getKey();
                    if (!isHttpApiOperation(method) || !operation.getValue().isObject()) {
                        continue;
                    }

                    Map<String, Object> routeRequest = new HashMap<>();
                    routeRequest.put("routeKey", openApiRouteKey(method, pathEntry.getKey()));
                    applyOpenApiRouteSecurity(body, operation.getValue(), pathEntry.getKey(), method,
                            authorizers, routeRequest);
                    JsonNode integration = operation.getValue().path("x-amazon-apigateway-integration");
                    if (integration.isObject()) {
                        String integrationType = textOrNull(integration, "type");
                        if (integrationType != null && !integrationType.isBlank()) {
                            Map<String, Object> integrationRequest = new HashMap<>();
                            integrationRequest.put("integrationType", integrationType.toUpperCase(Locale.ROOT));
                            putOpenApiIntegrationValue(integrationRequest, "integrationUri", integration, "uri");
                            putOpenApiIntegrationValue(integrationRequest, "integrationMethod", integration,
                                    "httpMethod");
                            putOpenApiIntegrationValue(integrationRequest, "payloadFormatVersion", integration,
                                    "payloadFormatVersion");
                            Integration createdIntegration = apiGatewayV2Service.createIntegration(region, apiId,
                                    integrationRequest);
                            integrationIds.add(createdIntegration.getIntegrationId());
                            routeRequest.put("target", "integrations/" + createdIntegration.getIntegrationId());
                        }
                    }
                    Route createdRoute = apiGatewayV2Service.createRoute(region, apiId, routeRequest);
                    routeIds.add(createdRoute.getRouteId());
                }
            }
            return new BodyResources(routeIds, integrationIds, authorizerIds);
        } catch (RuntimeException e) {
            BodyResources partial = new BodyResources(routeIds, integrationIds, authorizerIds);
            List<RuntimeException> cleanupFailures = cleanupBodyResources(region, apiId, partial);
            if (!cleanupFailures.isEmpty()) {
                cleanupFailures.forEach(e::addSuppressed);
                throw new BodyMaterializationException(e, partial);
            }
            throw e;
        }
    }

    private Map<String, OpenApiAuthorizerBinding> materializeBodyAuthorizers(String region, String apiId,
                                                                            JsonNode body, List<String> authorizerIds) {
        Map<String, OpenApiAuthorizerBinding> bindings = new LinkedHashMap<>();
        JsonNode schemes = body.path("components").path("securitySchemes");
        if (!schemes.isObject()) {
            return bindings;
        }

        Iterator<Map.Entry<String, JsonNode>> entries = schemes.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            String schemeName = entry.getKey();
            JsonNode scheme = entry.getValue();
            if (!scheme.isObject()) {
                continue;
            }
            JsonNode definition = scheme.path("x-amazon-apigateway-authorizer");
            if (!definition.isObject()) {
                continue;
            }

            String type = textOrNull(definition, "type");
            String authorizerType;
            String routeAuthorizationType;
            if ("jwt".equalsIgnoreCase(type)) {
                authorizerType = "JWT";
                routeAuthorizationType = "JWT";
            } else if ("request".equalsIgnoreCase(type)) {
                authorizerType = "REQUEST";
                routeAuthorizationType = "CUSTOM";
            } else {
                throw invalidOpenApiSecurity("Authorizer " + schemeName + " must declare type jwt or request");
            }

            Map<String, Object> request = new HashMap<>();
            request.put("name", schemeName);
            request.put("authorizerType", authorizerType);
            putOpenApiAuthorizerIdentitySource(request, definition);
            putOpenApiAuthorizerValue(request, "authorizerUri", definition, "authorizerUri");
            putOpenApiAuthorizerValue(request, "authorizerPayloadFormatVersion", definition,
                    "authorizerPayloadFormatVersion");
            putOpenApiAuthorizerValue(request, "authorizerResultTtlInSeconds", definition,
                    "authorizerResultTtlInSeconds");
            putOpenApiAuthorizerValue(request, "enableSimpleResponses", definition, "enableSimpleResponses");

            if ("JWT".equals(authorizerType)) {
                JsonNode jwt = definition.path("jwtConfiguration");
                if (!jwt.isObject()) {
                    throw invalidOpenApiSecurity("JWT authorizer " + schemeName
                            + " must declare jwtConfiguration");
                }
                Map<String, Object> jwtConfiguration = new HashMap<>();
                jwtConfiguration.put("issuer", textOrNull(jwt, "issuer"));
                jwtConfiguration.put("audience", openApiStringList(jwt.get("audience"),
                        "jwtConfiguration.audience for authorizer " + schemeName));
                request.put("jwtConfiguration", jwtConfiguration);
            }

            Authorizer created = apiGatewayV2Service.createAuthorizer(region, apiId, request);
            authorizerIds.add(created.getAuthorizerId());
            bindings.put(schemeName, new OpenApiAuthorizerBinding(routeAuthorizationType, created.getAuthorizerId()));
        }
        return bindings;
    }

    private void applyOpenApiRouteSecurity(JsonNode body, JsonNode operation, String path, String method,
                                           Map<String, OpenApiAuthorizerBinding> authorizers,
                                           Map<String, Object> routeRequest) {
        JsonNode security = operation.has("security") ? operation.get("security") : body.get("security");
        if (security == null || security.isNull() || security.isMissingNode()) {
            return;
        }
        if (!security.isArray()) {
            throw invalidOpenApiSecurity("security must be an array");
        }
        if (security.isEmpty()) {
            routeRequest.put("authorizationType", "NONE");
            return; // An operation-level empty array explicitly overrides inherited security.
        }

        // Each object is one alternative in the outer OR-list, but names inside one object are an
        // AND requirement. A V2 route can attach only one authorizer, so accepting a multi-name
        // object would silently weaken its authentication contract; AWS classifies multiple
        // security requirements as an HTTP API import error. Validate every alternative before
        // selecting a representable one.
        for (JsonNode requirement : security) {
            if (!requirement.isObject()) {
                throw invalidOpenApiSecurity("security requirements must be objects");
            }
            if (requirement.isEmpty()) {
                routeRequest.put("authorizationType", "NONE");
                return; // An empty requirement allows anonymous access by OpenAPI definition.
            }
            if (requirement.size() > 1) {
                throw invalidOpenApiSecurity(
                        "HTTP API routes do not support AND security requirements with multiple schemes");
            }
        }
        if (security.size() > 1) {
            throw invalidOpenApiSecurity(
                    "HTTP API routes do not support OR security requirements with multiple alternatives");
        }

        String unsupportedScheme = null;
        for (JsonNode requirement : security) {
            Iterator<Map.Entry<String, JsonNode>> schemes = requirement.fields();
            while (schemes.hasNext()) {
                Map.Entry<String, JsonNode> scheme = schemes.next();
                OpenApiAuthorizerBinding binding = authorizers.get(scheme.getKey());
                if (binding == null) {
                    unsupportedScheme = scheme.getKey();
                    continue;
                }
                routeRequest.put("authorizationType", binding.authorizationType());
                if (binding.authorizerId() != null) {
                    routeRequest.put("authorizerId", binding.authorizerId());
                }
                if ("JWT".equals(binding.authorizationType())) {
                    List<String> scopes = openApiStringList(scheme.getValue(),
                            "security scopes for scheme " + scheme.getKey());
                    if (!scopes.isEmpty()) {
                        routeRequest.put("authorizationScopes", scopes);
                    }
                }
                return;
            }
        }
        throw invalidOpenApiSecurity("Protected operation " + openApiRouteKey(method, path)
                + " references unsupported security scheme '" + unsupportedScheme + "'");
    }

    private static void putOpenApiAuthorizerIdentitySource(Map<String, Object> request, JsonNode definition) {
        JsonNode identitySource = definition.get("identitySource");
        if (identitySource == null || identitySource.isNull()) {
            return;
        }
        if (identitySource.isTextual()) {
            request.put("identitySource", identitySource.asText());
            return;
        }
        request.put("identitySource", openApiStringList(identitySource, "authorizer identitySource"));
    }

    private static void putOpenApiAuthorizerValue(Map<String, Object> request, String requestKey,
                                                  JsonNode definition, String definitionKey) {
        JsonNode value = definition.get(definitionKey);
        if (value == null || value.isNull()) {
            return;
        }
        if (value.isTextual()) {
            request.put(requestKey, value.asText());
        } else if (value.isBoolean()) {
            request.put(requestKey, value.booleanValue());
        } else if (value.isIntegralNumber()) {
            request.put(requestKey, value.intValue());
        } else {
            throw invalidOpenApiSecurity(definitionKey + " has an invalid value");
        }
    }

    private static List<String> openApiStringList(JsonNode value, String fieldName) {
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (!value.isArray()) {
            throw invalidOpenApiSecurity(fieldName + " must be an array of strings");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode element : value) {
            if (!element.isTextual()) {
                throw invalidOpenApiSecurity(fieldName + " must be an array of strings");
            }
            values.add(element.asText());
        }
        return values;
    }

    private static AwsException invalidOpenApiSecurity(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private void deleteBodyResources(StackResource r, String region, String apiId) {
        deleteBodyResources(region, apiId, new BodyResources(
                bodyResourceIds(r, BODY_ROUTE_IDS_ATTR),
                bodyResourceIds(r, BODY_INTEGRATION_IDS_ATTR),
                bodyResourceIds(r, BODY_AUTHORIZER_IDS_ATTR)));
        r.getAttributes().remove(BODY_ROUTE_IDS_ATTR);
        r.getAttributes().remove(BODY_INTEGRATION_IDS_ATTR);
        r.getAttributes().remove(BODY_AUTHORIZER_IDS_ATTR);
    }

    private void deleteBodyResources(String region, String apiId, BodyResources resources) {
        for (String routeId : resources.routeIds()) {
            deleteRouteIfPresent(region, apiId, routeId);
        }
        for (String integrationId : resources.integrationIds()) {
            deleteIntegrationIfPresent(region, apiId, integrationId);
        }
        for (String authorizerId : resources.authorizerIds()) {
            deleteAuthorizerIfPresent(region, apiId, authorizerId);
        }
    }

    private BodyResourceState snapshotBodyResources(StackResource r, String region, String apiId) {
        List<Route> routes = new ArrayList<>();
        for (String routeId : bodyResourceIds(r, BODY_ROUTE_IDS_ATTR)) {
            try {
                routes.add(apiGatewayV2Service.getRoute(region, apiId, routeId));
            } catch (AwsException e) {
                if (e.getHttpStatus() != 404) {
                    throw e;
                }
            }
        }
        List<Integration> integrations = new ArrayList<>();
        for (String integrationId : bodyResourceIds(r, BODY_INTEGRATION_IDS_ATTR)) {
            try {
                integrations.add(apiGatewayV2Service.getIntegration(region, apiId, integrationId));
            } catch (AwsException e) {
                if (e.getHttpStatus() != 404) {
                    throw e;
                }
            }
        }
        List<Authorizer> authorizers = new ArrayList<>();
        for (String authorizerId : bodyResourceIds(r, BODY_AUTHORIZER_IDS_ATTR)) {
            try {
                authorizers.add(apiGatewayV2Service.getAuthorizer(region, apiId, authorizerId));
            } catch (AwsException e) {
                if (e.getHttpStatus() != 404) {
                    throw e;
                }
            }
        }
        return new BodyResourceState(routes, integrations, authorizers);
    }

    private void rollbackBodyReplacement(StackResource r, String region, String apiId,
                                         BodyResources replacement, BodyResourceState previous,
                                         RuntimeException failure) {
        List<RuntimeException> cleanupFailures = cleanupBodyResources(region, apiId, replacement);

        if (previous != null) {
            storeBodyResourceIds(r, BODY_ROUTE_IDS_ATTR,
                    previous.routes().stream().map(Route::getRouteId).toList());
            storeBodyResourceIds(r, BODY_INTEGRATION_IDS_ATTR,
                    previous.integrations().stream().map(Integration::getIntegrationId).toList());
            storeBodyResourceIds(r, BODY_AUTHORIZER_IDS_ATTR,
                    previous.authorizers().stream().map(Authorizer::getAuthorizerId).toList());
        }
        if (!cleanupFailures.isEmpty()) {
            cleanupFailures.forEach(failure::addSuppressed);
            retainBodyResourceIds(r, replacement);
        }
        if (previous == null) {
            return;
        }
        try {
            // Routes refer to integrations and authorizers, so restore both before their routes.
            for (Authorizer authorizer : previous.authorizers()) {
                apiGatewayV2Service.restoreAuthorizer(region, apiId, authorizer);
            }
            for (Integration integration : previous.integrations()) {
                apiGatewayV2Service.restoreIntegration(region, apiId, integration);
            }
            for (Route route : previous.routes()) {
                apiGatewayV2Service.restoreRoute(region, apiId, route, replacement.routeIds());
            }
        } catch (RuntimeException restoreFailure) {
            failure.addSuppressed(restoreFailure);
            String reason = restoreFailure.getMessage() != null
                    ? restoreFailure.getMessage()
                    : restoreFailure.getClass().getSimpleName();
            r.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR, reason);
        }
    }

    private List<RuntimeException> cleanupBodyResources(String region, String apiId, BodyResources resources) {
        List<RuntimeException> failures = new ArrayList<>();
        for (String routeId : resources.routeIds()) {
            try {
                deleteRouteIfPresent(region, apiId, routeId);
            } catch (RuntimeException e) {
                failures.add(e);
            }
        }
        for (String integrationId : resources.integrationIds()) {
            try {
                deleteIntegrationIfPresent(region, apiId, integrationId);
            } catch (RuntimeException e) {
                failures.add(e);
            }
        }
        for (String authorizerId : resources.authorizerIds()) {
            try {
                deleteAuthorizerIfPresent(region, apiId, authorizerId);
            } catch (RuntimeException e) {
                failures.add(e);
            }
        }
        return failures;
    }

    private void retainBodyResourceIds(StackResource r, BodyResources resources) {
        retainBodyResourceIds(r, BODY_ROUTE_IDS_ATTR, resources.routeIds());
        retainBodyResourceIds(r, BODY_INTEGRATION_IDS_ATTR, resources.integrationIds());
        retainBodyResourceIds(r, BODY_AUTHORIZER_IDS_ATTR, resources.authorizerIds());
    }

    private static void retainBodyResourceIds(StackResource r, String attributeName, List<String> resourceIds) {
        LinkedHashSet<String> retained = new LinkedHashSet<>(bodyResourceIds(r, attributeName));
        retained.addAll(resourceIds);
        storeBodyResourceIds(r, attributeName, new ArrayList<>(retained));
    }

    private static List<String> bodyResourceIds(StackResource r, String attributeName) {
        String ids = r.getAttributes().get(attributeName);
        return ids == null || ids.isBlank() ? List.of() : Arrays.asList(ids.split(","));
    }

    private static void storeBodyResourceIds(StackResource r, String attributeName, List<String> resourceIds) {
        if (resourceIds.isEmpty()) {
            r.getAttributes().remove(attributeName);
        } else {
            r.getAttributes().put(attributeName, String.join(",", resourceIds));
        }
    }

    private void deleteRouteIfPresent(String region, String apiId, String routeId) {
        try {
            apiGatewayV2Service.deleteRoute(region, apiId, routeId);
        } catch (AwsException e) {
            if (e.getHttpStatus() != 404) {
                throw e;
            }
        }
    }

    private void deleteIntegrationIfPresent(String region, String apiId, String integrationId) {
        try {
            apiGatewayV2Service.deleteIntegration(region, apiId, integrationId);
        } catch (AwsException e) {
            if (e.getHttpStatus() != 404) {
                throw e;
            }
        }
    }

    private void deleteAuthorizerIfPresent(String region, String apiId, String authorizerId) {
        try {
            apiGatewayV2Service.deleteAuthorizer(region, apiId, authorizerId);
        } catch (AwsException e) {
            if (e.getHttpStatus() != 404) {
                throw e;
            }
        }
    }

    private record BodyResources(List<String> routeIds, List<String> integrationIds,
                                 List<String> authorizerIds) {}

    private record BodyResourceState(List<Route> routes, List<Integration> integrations,
                                     List<Authorizer> authorizers) {}

    private record OpenApiAuthorizerBinding(String authorizationType, String authorizerId) {}

    private static final class BodyMaterializationException extends RuntimeException {
        private final transient BodyResources resources;

        private BodyMaterializationException(RuntimeException cause, BodyResources resources) {
            super(cause.getMessage(), cause);
            this.resources = resources;
        }

        private BodyResources resources() {
            return resources;
        }
    }

    private static boolean isHttpApiOperation(String method) {
        return switch (method.toLowerCase(Locale.ROOT)) {
            case "get", "put", "post", "delete", "options", "head", "patch", "trace",
                    "x-amazon-apigateway-any-method" -> true;
            default -> false;
        };
    }

    private static String openApiRouteKey(String method, String path) {
        String routeMethod = "x-amazon-apigateway-any-method".equals(method) ? "ANY"
                : method.toUpperCase(Locale.ROOT);
        return routeMethod + " " + path;
    }

    private static void putOpenApiIntegrationValue(Map<String, Object> request, String requestKey,
                                                   JsonNode integration, String openApiKey) {
        String value = textOrNull(integration, openApiKey);
        if (value != null) {
            request.put(requestKey, value);
        }
    }

    private Map<String, String> parseTags(JsonNode tagsNode, CloudFormationTemplateEngine engine) {
        Map<String, String> out = new HashMap<>();
        if (tagsNode == null || tagsNode.isNull()) {
            return out;
        }
        JsonNode resolved = engine.resolveNode(tagsNode);
        if (!resolved.isObject()) {
            return out;
        }
        resolved.properties().forEach(e -> out.put(e.getKey(), e.getValue().asText("")));
        return out;
    }

    private Map<String, Object> parseCors(JsonNode corsNode, CloudFormationTemplateEngine engine) {
        if (corsNode == null || corsNode.isNull()) {
            return null;
        }
        JsonNode resolved = engine.resolveNode(corsNode);
        if (!resolved.isObject()) {
            return null;
        }
        Map<String, Object> out = new HashMap<>();
        resolved.properties().forEach(e -> {
            String key = e.getKey();
            String camel = key.isEmpty() || !Character.isUpperCase(key.charAt(0))
                    ? key
                    : Character.toLowerCase(key.charAt(0)) + key.substring(1);
            JsonNode v = e.getValue();
            if (v.isArray()) {
                List<String> list = new ArrayList<>();
                v.forEach(item -> list.add(item.asText()));
                out.put(camel, list);
            } else if (v.isBoolean()) {
                out.put(camel, v.booleanValue());
            } else if (v.isNumber()) {
                out.put(camel, v.numberValue());
            } else if (!v.isNull()) {
                out.put(camel, v.asText());
            }
        });
        return out;
    }

    // ── Authorizer ─────────────────────────────────────────────────────────────

    private List<String> resolveIdentitySource(JsonNode props, String source, CloudFormationTemplateEngine engine) {
        if (props == null || !props.has(source) || props.get(source).isNull()) {
            return List.of();
        }
        JsonNode resolved = engine.resolveNode(props.get(source));
        if (resolved == null) {
            return List.of();
        }
        if (resolved.isTextual()) {
            return List.of(resolved.asText());
        }
        if (!resolved.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        resolved.forEach(v -> values.add(v.asText()));
        return values;
    }

    private void provisionAuthorizer(StackResource r, JsonNode props, ProvisionContext ctx) {
        CloudFormationTemplateEngine engine = ctx.engine();
        String region = ctx.region();
        String apiId = ctx.resolveOptional(props, "ApiId");
        Map<String, Object> req = new HashMap<>();
        req.put("name", ctx.resolveOptional(props, "Name"));
        req.put("authorizerType", ctx.resolveOptional(props, "AuthorizerType"));
        req.put("identitySource", resolveIdentitySource(props, "IdentitySource", engine));
        req.put("authorizerUri", ctx.resolveOptional(props, "AuthorizerUri"));
        req.put("authorizerPayloadFormatVersion", ctx.resolveOptional(props, "AuthorizerPayloadFormatVersion"));

        String ttl = ctx.resolveOptional(props, "AuthorizerResultTtlInSeconds");
        if (ttl != null) {
            try {
                req.put("authorizerResultTtlInSeconds", Integer.parseInt(ttl));
            } catch (NumberFormatException ignored) {
                throw new AwsException("ValidationError",
                        "AuthorizerResultTtlInSeconds must be an integer", 400);
            }
        }
        String simpleResponses = ctx.resolveOptional(props, "EnableSimpleResponses");
        if (simpleResponses != null) {
            req.put("enableSimpleResponses", simpleResponses);
        }

        JsonNode jwtConfigNode = props != null ? props.get("JwtConfiguration") : null;
        if (jwtConfigNode != null && !jwtConfigNode.isNull()) {
            Map<String, Object> jwtConfig = new HashMap<>();
            jwtConfig.put("audience", resolveStringListOrEmpty(jwtConfigNode, "Audience", engine));
            jwtConfig.put("issuer", ctx.resolveOptional(jwtConfigNode, "Issuer"));
            req.put("jwtConfiguration", jwtConfig);
        }

        Authorizer authorizer;
        if (r.getPhysicalId() == null) {
            authorizer = apiGatewayV2Service.createAuthorizer(region, apiId, req);
        } else {
            authorizer = apiGatewayV2Service.updateAuthorizer(region, apiId, r.getPhysicalId(), req);
        }
        r.setPhysicalId(authorizer.getAuthorizerId());
        r.getAttributes().put("AuthorizerId", authorizer.getAuthorizerId());
        // ApiId is needed by delete() to scope deleteAuthorizer; the id-only delete path has none.
        r.getAttributes().put("ApiId", apiId);
    }

    // ── Route ────────────────────────────────────────────────────────────────

    private void provisionRoute(StackResource r, JsonNode props, ProvisionContext ctx) {
        String region = ctx.region();
        String apiId = ctx.resolveOptional(props, "ApiId");
        Map<String, Object> req = new HashMap<>();
        req.put("routeKey", ctx.resolveOptional(props, "RouteKey"));
        req.put("authorizationType", ctx.resolveOrDefault(props, "AuthorizationType", "NONE"));
        req.put("authorizerId", ctx.resolveOptional(props, "AuthorizerId"));
        // Always present (empty when the property is absent) so an UpdateStack that removes
        // AuthorizationScopes from the template clears the route's scopes instead of keeping them.
        req.put("authorizationScopes", resolveStringListOrEmpty(props, "AuthorizationScopes", ctx.engine()));
        req.put("target", ctx.resolveOptional(props, "Target"));

        Route route;
        if (r.getPhysicalId() == null) {
            route = apiGatewayV2Service.createRoute(region, apiId, req);
        } else {
            route = apiGatewayV2Service.updateRoute(region, apiId, r.getPhysicalId(), req);
        }
        r.setPhysicalId(route.getRouteId());
        r.getAttributes().put("RouteId", route.getRouteId());
    }

    // ── Integration ──────────────────────────────────────────────────────────

    private void provisionIntegration(StackResource r, JsonNode props, ProvisionContext ctx) {
        String region = ctx.region();
        String apiId = ctx.resolveOptional(props, "ApiId");
        Map<String, Object> req = new HashMap<>();
        req.put("integrationType", ctx.resolveOptional(props, "IntegrationType"));
        req.put("integrationUri", ctx.resolveOptional(props, "IntegrationUri"));
        req.put("payloadFormatVersion", ctx.resolveOrDefault(props, "PayloadFormatVersion", "2.0"));

        Integration integration;
        if (r.getPhysicalId() == null) {
            integration = apiGatewayV2Service.createIntegration(region, apiId, req);
        } else {
            integration = apiGatewayV2Service.updateIntegration(region, apiId, r.getPhysicalId(), req);
        }
        r.setPhysicalId(integration.getIntegrationId());
        r.getAttributes().put("IntegrationId", integration.getIntegrationId());
    }

    // ── Stage ────────────────────────────────────────────────────────────────

    private void provisionStage(StackResource r, JsonNode props, ProvisionContext ctx) {
        CloudFormationTemplateEngine engine = ctx.engine();
        String region = ctx.region();
        String apiId = ctx.resolveOptional(props, "ApiId");
        String stageName = ctx.resolveOptional(props, "StageName");

        Map<String, Object> req = new HashMap<>();
        req.put("stageName", stageName);
        req.put("autoDeploy", ctx.resolveOrDefault(props, "AutoDeploy", "false"));
        Map<String, String> stageVariables = parseStageVariables(props, engine);
        if (stageVariables != null) {
            req.put("stageVariables", stageVariables);
        }

        if (r.getPhysicalId() == null) {
            apiGatewayV2Service.createStage(region, apiId, req);
            r.setPhysicalId(stageName);
        } else {
            apiGatewayV2Service.updateStage(region, apiId, r.getPhysicalId(), req);
        }
    }

    // ── Deployment ───────────────────────────────────────────────────────────

    private void provisionDeployment(StackResource r, JsonNode props, ProvisionContext ctx) {
        // Deployments are immutable point-in-time snapshots; on redeploy keep the existing one
        // rather than minting a duplicate (idempotent re-deploy).
        if (r.getPhysicalId() != null) {
            return;
        }
        String apiId = ctx.resolveOptional(props, "ApiId");
        Map<String, Object> req = new HashMap<>();
        req.put("description", ctx.resolveOptional(props, "Description"));

        Deployment deployment = apiGatewayV2Service.createDeployment(ctx.region(), apiId, req);
        r.setPhysicalId(deployment.getDeploymentId());
        r.getAttributes().put("DeploymentId", deployment.getDeploymentId());
    }

    // ── Local copies of shared utilities (still used by staying monolith types) ──

    private static String textOrNull(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.path(field).asText() : null;
    }

    private List<String> resolveStringListOrEmpty(JsonNode props, String source,
                                                  CloudFormationTemplateEngine engine) {
        if (props == null || !props.has(source) || props.get(source).isNull()) {
            return List.of();
        }
        JsonNode resolved = engine.resolveNode(props.get(source));
        if (resolved == null || !resolved.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        resolved.forEach(v -> values.add(v.asText()));
        return values;
    }

    private Map<String, String> parseStageVariables(JsonNode props, CloudFormationTemplateEngine engine) {
        if (props == null || !props.has("StageVariables") || props.get("StageVariables").isNull()) {
            return null;
        }
        JsonNode resolved = engine.resolveNode(props.get("StageVariables"));
        if (resolved == null || !resolved.isObject()) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        resolved.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
        return out;
    }
}
