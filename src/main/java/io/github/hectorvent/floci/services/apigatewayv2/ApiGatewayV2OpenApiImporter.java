package io.github.hectorvent.floci.services.apigatewayv2;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.apigatewayv2.model.Api;
import io.github.hectorvent.floci.services.apigatewayv2.model.Authorizer;
import io.github.hectorvent.floci.services.apigatewayv2.model.Integration;
import io.github.hectorvent.floci.services.apigatewayv2.model.Route;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * OpenAPI import for HTTP APIs (ImportApi / ReimportApi).
 *
 * <p>Mirrors {@code ApiGatewayService#importRestApi}/{@code putRestApi} for the v1 REST API, but
 * materialises the v2 object model (routes + integrations + authorizers) instead of the v1
 * resource/method tree. The AWS extensions honoured are
 * {@code x-amazon-apigateway-integration}, {@code x-amazon-apigateway-authorizer},
 * {@code x-amazon-apigateway-any-method} and {@code x-amazon-apigateway-cors}.
 */
@ApplicationScoped
public class ApiGatewayV2OpenApiImporter {

    private static final Logger LOG = Logger.getLogger(ApiGatewayV2OpenApiImporter.class);

    private static final String EXT_INTEGRATION = "x-amazon-apigateway-integration";
    private static final String EXT_AUTHORIZER = "x-amazon-apigateway-authorizer";
    private static final String EXT_ANY_METHOD = "x-amazon-apigateway-any-method";
    private static final String EXT_CORS = "x-amazon-apigateway-cors";

    private final ApiGatewayV2Service service;

    @Inject
    public ApiGatewayV2OpenApiImporter(ApiGatewayV2Service service) {
        this.service = service;
    }

    // ──────────────────────────── Entry points ────────────────────────────

    /** ImportApi — creates a brand new HTTP API from an OpenAPI 3 document. */
    public Api importApi(String region, String specBody) {
        OpenAPI openAPI = parseSpec(specBody);

        Map<String, Object> request = new HashMap<>();
        request.put("protocolType", "HTTP");
        request.put("name", specTitle(openAPI, "Imported API"));
        String description = specDescription(openAPI);
        if (description != null) {
            request.put("description", description);
        }
        String version = specVersion(openAPI);
        if (version != null) {
            request.put("version", version);
        }
        Map<String, Object> cors = corsConfiguration(openAPI);
        if (cors != null) {
            request.put("corsConfiguration", cors);
        }

        Api api = service.createApi(region, request);
        applySpec(region, api.getApiId(), openAPI);
        LOG.infov("Imported HTTP API from OpenAPI spec: {0} ({1})", api.getName(), api.getApiId());
        return service.getApi(region, api.getApiId());
    }

    /**
     * ReimportApi — replaces the definition of an existing HTTP API.
     *
     * <p>AWS replaces the whole definition, so every route, integration and authorizer is dropped
     * before the spec is applied. API-level settings that an OpenAPI document cannot express (CORS
     * when the spec carries no {@code x-amazon-apigateway-cors}, tags, the execute-api toggle) are
     * left untouched, which is what keeps a Terraform {@code aws_apigatewayv2_api} whose
     * {@code cors_configuration} lives on the resource rather than in {@code body} from flapping.
     */
    public Api reimportApi(String region, String apiId, String specBody) {
        Api api = service.getApi(region, apiId);
        if (!"HTTP".equals(api.getProtocolType())) {
            throw new AwsException("BadRequestException",
                    "Cannot import an OpenAPI definition into a " + api.getProtocolType() + " API", 400);
        }

        OpenAPI openAPI = parseSpec(specBody);

        for (Route route : service.getRoutes(region, apiId)) {
            service.deleteRoute(region, apiId, route.getRouteId());
        }
        for (Integration integration : service.getIntegrations(region, apiId)) {
            service.deleteIntegration(region, apiId, integration.getIntegrationId());
        }
        for (Authorizer authorizer : service.getAuthorizers(region, apiId)) {
            service.deleteAuthorizer(region, apiId, authorizer.getAuthorizerId());
        }

        Map<String, Object> update = new HashMap<>();
        String title = specTitle(openAPI, null);
        if (title != null) {
            update.put("name", title);
        }
        String description = specDescription(openAPI);
        if (description != null) {
            update.put("description", description);
        }
        String version = specVersion(openAPI);
        if (version != null) {
            update.put("version", version);
        }
        Map<String, Object> cors = corsConfiguration(openAPI);
        if (cors != null) {
            update.put("corsConfiguration", cors);
        }
        if (!update.isEmpty()) {
            service.updateApi(region, apiId, update);
        }

        applySpec(region, apiId, openAPI);
        LOG.infov("Reimported HTTP API from OpenAPI spec: {0} ({1})", api.getName(), apiId);
        return service.getApi(region, apiId);
    }

    // ──────────────────────────── Spec application ────────────────────────────

    private OpenAPI parseSpec(String specBody) {
        if (specBody == null || specBody.isBlank()) {
            throw new AwsException("BadRequestException", "Body is required for OpenAPI import", 400);
        }
        SwaggerParseResult result = new io.swagger.parser.OpenAPIParser().readContents(specBody, null, null);
        if (result.getOpenAPI() == null) {
            String errors = result.getMessages() != null ? String.join(", ", result.getMessages()) : "unknown error";
            throw new AwsException("BadRequestException", "Failed to parse OpenAPI spec: " + errors, 400);
        }
        return result.getOpenAPI();
    }

    private void applySpec(String region, String apiId, OpenAPI openAPI) {
        Map<String, SchemeBinding> schemes = createAuthorizers(region, apiId, openAPI);
        List<SecurityRequirement> globalSecurity = openAPI.getSecurity();

        if (openAPI.getPaths() == null) {
            return;
        }
        for (Map.Entry<String, PathItem> pathEntry : openAPI.getPaths().entrySet()) {
            String path = pathEntry.getKey();
            PathItem pathItem = pathEntry.getValue();
            if (pathItem == null) {
                continue;
            }

            for (Map.Entry<PathItem.HttpMethod, Operation> opEntry : pathItem.readOperationsMap().entrySet()) {
                createRouteForOperation(region, apiId, opEntry.getKey().name(), path,
                        opEntry.getValue(), schemes, globalSecurity);
            }

            Operation anyMethod = anyMethodOperation(pathItem);
            if (anyMethod != null) {
                createRouteForOperation(region, apiId, "ANY", path, anyMethod, schemes, globalSecurity);
            }
        }
    }

    private void createRouteForOperation(String region, String apiId, String method, String path,
                                         Operation operation, Map<String, SchemeBinding> schemes,
                                         List<SecurityRequirement> globalSecurity) {
        Map<String, Object> routeRequest = new HashMap<>();
        routeRequest.put("routeKey", routeKey(method, path));

        Map<String, Object> integrationDef = extensionAsMap(
                operation == null ? null : operation.getExtensions(), EXT_INTEGRATION);
        if (integrationDef != null) {
            Integration integration = service.createIntegration(region, apiId,
                    toIntegrationRequest(integrationDef));
            routeRequest.put("target", "integrations/" + integration.getIntegrationId());
        }

        List<SecurityRequirement> security =
                operation != null && operation.getSecurity() != null ? operation.getSecurity() : globalSecurity;
        SchemeBinding binding = resolveSecurity(security, schemes);
        if (binding == null) {
            routeRequest.put("authorizationType", "NONE");
        } else {
            routeRequest.put("authorizationType", binding.authorizationType());
            if (binding.authorizerId() != null) {
                routeRequest.put("authorizerId", binding.authorizerId());
            }
        }

        service.createRoute(region, apiId, routeRequest);
    }

    /**
     * AWS route keys are "{METHOD} {path}", with the path always leading with a slash. "$default"
     * is passed through untouched so a spec can declare the catch-all route.
     */
    private static String routeKey(String method, String path) {
        if ("$default".equals(path)) {
            return "$default";
        }
        String normalized = path.startsWith("/") ? path : "/" + path;
        return method.toUpperCase(Locale.ROOT) + " " + normalized;
    }

    private Operation anyMethodOperation(PathItem pathItem) {
        Map<String, Object> anyMethod = extensionAsMap(pathItem.getExtensions(), EXT_ANY_METHOD);
        if (anyMethod == null) {
            return null;
        }
        // The extension holds a bare operation object; only its extensions and security matter here.
        Operation operation = new Operation();
        Map<String, Object> integrationDef = extensionAsMap(anyMethod, EXT_INTEGRATION);
        if (integrationDef != null) {
            operation.addExtension(EXT_INTEGRATION, integrationDef);
        }
        Object security = anyMethod.get("security");
        if (security instanceof List<?> list) {
            operation.setSecurity(toSecurityRequirements(list));
        }
        return operation;
    }

    @SuppressWarnings("unchecked")
    private static List<SecurityRequirement> toSecurityRequirements(List<?> raw) {
        List<SecurityRequirement> requirements = new ArrayList<>();
        for (Object entry : raw) {
            if (!(entry instanceof Map<?, ?> map)) {
                continue;
            }
            SecurityRequirement requirement = new SecurityRequirement();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                List<String> scopes = e.getValue() instanceof List<?> scopeList
                        ? (List<String>) scopeList
                        : List.of();
                requirement.addList(String.valueOf(e.getKey()), scopes);
            }
            requirements.add(requirement);
        }
        return requirements;
    }

    // ──────────────────────────── Authorizers ────────────────────────────

    /** What a security scheme name resolves to once its authorizer (if any) has been created. */
    private record SchemeBinding(String authorizationType, String authorizerId) {}

    private Map<String, SchemeBinding> createAuthorizers(String region, String apiId, OpenAPI openAPI) {
        Map<String, SchemeBinding> bindings = new LinkedHashMap<>();
        if (openAPI.getComponents() == null || openAPI.getComponents().getSecuritySchemes() == null) {
            return bindings;
        }

        for (Map.Entry<String, SecurityScheme> entry : openAPI.getComponents().getSecuritySchemes().entrySet()) {
            String schemeName = entry.getKey();
            SecurityScheme scheme = entry.getValue();
            if (scheme == null) {
                continue;
            }

            Map<String, Object> authDef = extensionAsMap(scheme.getExtensions(), EXT_AUTHORIZER);
            if (authDef == null) {
                // A scheme with no AWS authorizer extension still selects an authorization type:
                // sigv4 means IAM, everything else is unauthenticated as far as HTTP APIs go.
                if ("awsSigv4".equalsIgnoreCase(scheme.getName())
                        || SecurityScheme.Type.HTTP.equals(scheme.getType())
                        && "aws.v4".equalsIgnoreCase(scheme.getScheme())) {
                    bindings.put(schemeName, new SchemeBinding("AWS_IAM", null));
                }
                continue;
            }

            String type = stringValue(authDef.get("type"));
            String authorizerType = type == null ? null : switch (type.toLowerCase(Locale.ROOT)) {
                case "request" -> "REQUEST";
                case "jwt" -> "JWT";
                default -> null;
            };
            if (authorizerType == null) {
                throw new AwsException("BadRequestException",
                        "Unsupported " + EXT_AUTHORIZER + ".type '" + type + "' for security scheme "
                                + schemeName + "; HTTP APIs support 'request' and 'jwt'", 400);
            }

            Map<String, Object> request = new HashMap<>();
            request.put("name", schemeName);
            request.put("authorizerType", authorizerType);

            List<String> identitySource = identitySource(authDef, scheme, authorizerType);
            if (!identitySource.isEmpty()) {
                request.put("identitySource", identitySource);
            }
            putIfPresent(request, "authorizerUri", stringValue(authDef.get("authorizerUri")));
            putIfPresent(request, "authorizerPayloadFormatVersion",
                    stringValue(authDef.get("authorizerPayloadFormatVersion")));
            if (authDef.get("authorizerResultTtlInSeconds") instanceof Number ttl) {
                request.put("authorizerResultTtlInSeconds", ttl);
            }
            if (authDef.get("enableSimpleResponses") != null) {
                request.put("enableSimpleResponses", authDef.get("enableSimpleResponses"));
            }
            Map<String, Object> jwtConfiguration = extensionAsMap(authDef, "jwtConfiguration");
            if (jwtConfiguration != null) {
                request.put("jwtConfiguration", jwtConfiguration);
            }

            if ("REQUEST".equals(authorizerType)) {
                Object ttl = request.get("authorizerResultTtlInSeconds");
                boolean cachingEnabled = ttl instanceof Number n && n.intValue() > 0;
                if (cachingEnabled && identitySource.isEmpty()) {
                    throw new AwsException("BadRequestException",
                            "REQUEST authorizer " + schemeName
                                    + " must specify identitySource when authorizer caching is enabled.", 400);
                }
            }

            Authorizer authorizer = service.createAuthorizer(region, apiId, request);
            bindings.put(schemeName,
                    new SchemeBinding("JWT".equals(authorizerType) ? "JWT" : "CUSTOM", authorizer.getAuthorizerId()));
        }
        return bindings;
    }

    /**
     * REQUEST authorizers carry identitySource on the extension (a comma-separated string or an
     * array); JWT authorizers derive it from where the security scheme says the token lives.
     */
    private static List<String> identitySource(Map<String, Object> authDef, SecurityScheme scheme,
                                               String authorizerType) {
        Object raw = authDef.get("identitySource");
        if (raw instanceof String s && !s.isBlank()) {
            List<String> sources = new ArrayList<>();
            for (String part : s.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    sources.add(trimmed);
                }
            }
            return sources;
        }
        if (raw instanceof List<?> list) {
            List<String> sources = new ArrayList<>();
            for (Object item : list) {
                String value = stringValue(item);
                if (value != null && !value.isBlank()) {
                    sources.add(value.trim());
                }
            }
            return sources;
        }
        if ("JWT".equals(authorizerType) && scheme.getName() != null) {
            String in = scheme.getIn() == null ? "header" : scheme.getIn().toString().toLowerCase(Locale.ROOT);
            return List.of("$request." + in + "." + scheme.getName());
        }
        return List.of();
    }

    private static SchemeBinding resolveSecurity(List<SecurityRequirement> security,
                                                 Map<String, SchemeBinding> schemes) {
        if (security == null || security.isEmpty()) {
            return null;
        }
        for (SecurityRequirement requirement : security) {
            for (String schemeName : requirement.keySet()) {
                SchemeBinding binding = schemes.get(schemeName);
                if (binding != null) {
                    return binding;
                }
            }
        }
        return null;
    }

    // ──────────────────────────── Integrations ────────────────────────────

    private static Map<String, Object> toIntegrationRequest(Map<String, Object> definition) {
        Map<String, Object> request = new HashMap<>();

        String type = stringValue(definition.get("type"));
        if (type != null) {
            request.put("integrationType", type.toUpperCase(Locale.ROOT));
        }
        putIfPresent(request, "integrationUri", stringValue(definition.get("uri")));
        putIfPresent(request, "integrationMethod", stringValue(definition.get("httpMethod")));
        putIfPresent(request, "connectionType", upper(stringValue(definition.get("connectionType"))));
        putIfPresent(request, "connectionId", stringValue(definition.get("connectionId")));
        putIfPresent(request, "templateSelectionExpression",
                stringValue(definition.get("templateSelectionExpression")));

        String payloadFormatVersion = stringValue(definition.get("payloadFormatVersion"));
        if (payloadFormatVersion != null) {
            request.put("payloadFormatVersion", payloadFormatVersion);
        }
        if (definition.get("timeoutInMillis") instanceof Number timeout) {
            request.put("timeoutInMillis", timeout);
        }

        copyStringMap(definition, request, "requestParameters");
        copyStringMap(definition, request, "requestTemplates");
        copyStringMap(definition, request, "responseTemplates");

        return request;
    }

    // ──────────────────────────── CORS ────────────────────────────

    private static Map<String, Object> corsConfiguration(OpenAPI openAPI) {
        Map<String, Object> cors = extensionAsMap(openAPI.getExtensions(), EXT_CORS);
        if (cors == null) {
            return null;
        }
        Map<String, Object> configuration = new HashMap<>();
        copyStringList(cors, configuration, "allowOrigins");
        copyStringList(cors, configuration, "allowMethods");
        copyStringList(cors, configuration, "allowHeaders");
        copyStringList(cors, configuration, "exposeHeaders");
        if (cors.get("maxAge") instanceof Number maxAge) {
            configuration.put("maxAge", maxAge);
        }
        if (cors.get("allowCredentials") != null) {
            configuration.put("allowCredentials", cors.get("allowCredentials"));
        }
        return configuration.isEmpty() ? null : configuration;
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private static String specTitle(OpenAPI openAPI, String fallback) {
        if (openAPI.getInfo() != null && openAPI.getInfo().getTitle() != null) {
            return openAPI.getInfo().getTitle();
        }
        return fallback;
    }

    private static String specDescription(OpenAPI openAPI) {
        return openAPI.getInfo() == null ? null : openAPI.getInfo().getDescription();
    }

    /** AWS carries the document's info.version through to the API's Version. */
    private static String specVersion(OpenAPI openAPI) {
        return openAPI.getInfo() == null ? null : openAPI.getInfo().getVersion();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extensionAsMap(Map<String, Object> source, String key) {
        if (source == null) {
            return null;
        }
        Object value = source.get(key);
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
    }

    private static void copyStringMap(Map<String, Object> source, Map<String, Object> target, String key) {
        if (!(source.get(key) instanceof Map<?, ?> map) || map.isEmpty()) {
            return;
        }
        Map<String, String> copy = new LinkedHashMap<>();
        map.forEach((k, v) -> copy.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
        target.put(key, copy);
    }

    private static void copyStringList(Map<String, Object> source, Map<String, Object> target, String key) {
        if (!(source.get(key) instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        List<String> copy = new ArrayList<>();
        list.forEach(item -> copy.add(item == null ? null : String.valueOf(item)));
        target.put(key, copy);
    }

    private static void putIfPresent(Map<String, Object> request, String key, String value) {
        if (value != null && !value.isBlank()) {
            request.put(key, value);
        }
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }
}
