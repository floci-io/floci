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
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.servers.ServerVariable;
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
import java.util.function.BiConsumer;

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
    public Api importApi(String region, String specBody, String basePathMode, boolean failOnWarnings) {
        ImportPlan plan = plan(specBody, basePathMode, failOnWarnings);
        OpenAPI openAPI = plan.openAPI();

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
        applySpec(region, api.getApiId(), plan);
        recordImportDiagnostics(region, api.getApiId(), plan);
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
    public Api reimportApi(String region, String apiId, String specBody, String basePathMode,
                           boolean failOnWarnings) {
        Api api = service.getApi(region, apiId);
        if (!"HTTP".equals(api.getProtocolType())) {
            throw new AwsException("BadRequestException",
                    "Cannot import an OpenAPI definition into a " + api.getProtocolType() + " API", 400);
        }

        // Parse and validate before touching anything: failOnWarnings must roll the whole
        // operation back, and a half-replaced API is worse than a rejected one.
        ImportPlan plan = plan(specBody, basePathMode, failOnWarnings);
        OpenAPI openAPI = plan.openAPI();

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

        applySpec(region, apiId, plan);
        recordImportDiagnostics(region, apiId, plan);
        LOG.infov("Reimported HTTP API from OpenAPI spec: {0} ({1})", api.getName(), apiId);
        return service.getApi(region, apiId);
    }

    // ──────────────────────────── Planning ────────────────────────────

    /**
     * A parsed document plus everything decided before the first write: the route path prefix the
     * basepath mode implies, and the diagnostics AWS reports back on the Api.
     */
    private record ImportPlan(OpenAPI openAPI, String pathPrefix, List<AuthorizerSpec> authorizerSpecs,
                              List<String> warnings, List<String> importInfo) {}

    private ImportPlan plan(String specBody, String basePathMode, boolean failOnWarnings) {
        if (specBody == null || specBody.isBlank()) {
            throw new AwsException("BadRequestException", "Body is required for OpenAPI import", 400);
        }
        String mode = basePathMode == null || basePathMode.isBlank()
                ? "ignore"
                : basePathMode.toLowerCase(Locale.ROOT);
        if (!List.of("ignore", "prepend", "split").contains(mode)) {
            throw new AwsException("BadRequestException",
                    "Invalid basepath '" + basePathMode + "'; valid values are ignore, prepend and split", 400);
        }

        SwaggerParseResult result = new io.swagger.parser.OpenAPIParser().readContents(specBody, null, null);
        if (result.getOpenAPI() == null) {
            String errors = result.getMessages() != null ? String.join(", ", result.getMessages()) : "unknown error";
            throw new AwsException("BadRequestException", "Failed to parse OpenAPI spec: " + errors, 400);
        }
        OpenAPI openAPI = result.getOpenAPI();

        List<String> warnings = new ArrayList<>();
        if (result.getMessages() != null) {
            result.getMessages().stream().filter(m -> m != null && !m.isBlank()).forEach(warnings::add);
        }

        List<String> importInfo = new ArrayList<>();
        String pathPrefix = pathPrefix(openAPI, mode, importInfo);
        collectImportInfo(openAPI, importInfo);

        // Resolve the authorizers here rather than during application: this is what makes a
        // rejected reimport leave the previous definition intact.
        List<AuthorizerSpec> authorizerSpecs = authorizerSpecs(openAPI);
        collectUnresolvedSecurity(openAPI, authorizerSpecs, warnings);

        if (failOnWarnings && !warnings.isEmpty()) {
            throw new AwsException("BadRequestException",
                    "Warnings found during import: " + String.join(", ", warnings), 400);
        }
        return new ImportPlan(openAPI, pathPrefix, authorizerSpecs, warnings, importInfo);
    }

    /**
     * Flags security requirements that name a scheme HTTP APIs cannot enforce — a plain bearer or
     * apiKey scheme with no {@code x-amazon-apigateway-authorizer}, or a misspelled scheme name.
     * Such a route is imported as NONE, so without this the document would appear to secure an
     * endpoint that ends up publicly invokable. Raised as a warning rather than an error so
     * failOnWarnings decides whether it is fatal.
     */
    private static void collectUnresolvedSecurity(OpenAPI openAPI, List<AuthorizerSpec> specs,
                                                  List<String> warnings) {
        Map<String, String> bindable = new LinkedHashMap<>();
        specs.forEach(spec -> bindable.put(spec.schemeName(), spec.authorizationType()));

        java.util.Set<String> reported = new java.util.LinkedHashSet<>();
        BiConsumer<String, List<SecurityRequirement>> check = (routeKey, security) -> {
            if (security == null || security.isEmpty()) {
                return;
            }
            boolean anyResolves = security.stream()
                    .flatMap(requirement -> requirement.keySet().stream())
                    .anyMatch(bindable::containsKey);
            if (!anyResolves) {
                String names = security.stream()
                        .flatMap(requirement -> requirement.keySet().stream())
                        .distinct()
                        .collect(java.util.stream.Collectors.joining(", "));
                reported.add("Security requirement (" + names + ") on " + routeKey
                        + " does not resolve to a supported authorizer; the route is imported as NONE"
                        + " and will accept unauthenticated requests");
                return;
            }
            // Several schemes inside one SecurityRequirement is OpenAPI's AND: the caller must
            // satisfy all of them. A route carries exactly one authorizer, so only the first
            // resolvable scheme is enforced and the rest are dropped.
            security.stream()
                    .filter(requirement -> requirement.keySet().size() > 1)
                    .findFirst()
                    .ifPresent(requirement -> {
                        String enforced = requirement.keySet().stream()
                                .filter(bindable::containsKey)
                                .findFirst()
                                .orElse(null);
                        reported.add("Security requirement on " + routeKey + " combines ("
                                + String.join(", ", requirement.keySet())
                                + "); HTTP API routes carry a single authorizer, so only "
                                + enforced + " is enforced and the remaining schemes are dropped");
                    });

            // An awsSigv4 scheme used to warn here, because dispatch recorded AWS_IAM without
            // enforcing it. Dispatch now verifies the caller's SigV4 signature before the
            // integration runs, so the document's guarantee holds locally and a
            // warning would only make failOnWarnings reject a document Floci honours.
        };

        List<SecurityRequirement> globalSecurity = openAPI.getSecurity();
        forEachImportedOperation(openAPI, (method, path, operation) -> {
            List<SecurityRequirement> security =
                    operation != null && operation.getSecurity() != null
                            ? operation.getSecurity()
                            : globalSecurity;
            check.accept(method + " " + path, security);
        });
        warnings.addAll(reported);
    }

    /**
     * Resolves the base path the way the Import API does for OpenAPI 3: a {@code basePath} server
     * variable wins (the first, if several are declared), otherwise any path carried by
     * {@code server.url}. "prepend" puts the whole thing in front of every route; "split" drops its
     * first segment first; "ignore" — the default — contributes nothing.
     */
    private static String pathPrefix(OpenAPI openAPI, String mode, List<String> importInfo) {
        if ("ignore".equals(mode)) {
            return "";
        }
        String basePath = resolveBasePath(openAPI);
        if (basePath == null) {
            importInfo.add("basepath=" + mode + " was requested but the definition declares no base path");
            return "";
        }
        if ("split".equals(mode)) {
            int nextSlash = basePath.indexOf('/', 1);
            return nextSlash < 0 ? "" : trimTrailingSlash(basePath.substring(nextSlash));
        }
        return trimTrailingSlash(basePath);
    }

    private static String resolveBasePath(OpenAPI openAPI) {
        if (openAPI.getServers() == null) {
            return null;
        }
        for (Server server : openAPI.getServers()) {
            if (server.getVariables() != null) {
                ServerVariable variable = server.getVariables().get("basePath");
                if (variable != null && variable.getDefault() != null && !variable.getDefault().isBlank()) {
                    return leadingSlash(variable.getDefault());
                }
            }
        }
        for (Server server : openAPI.getServers()) {
            String path = serverUrlPath(server.getUrl());
            if (path != null) {
                return path;
            }
        }
        return null;
    }

    /** The path portion of a server URL, or null when it carries none beyond "/". */
    private static String serverUrlPath(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String path = url;
        int schemeEnd = path.indexOf("://");
        if (schemeEnd >= 0) {
            int hostEnd = path.indexOf('/', schemeEnd + 3);
            path = hostEnd < 0 ? "" : path.substring(hostEnd);
        }
        path = trimTrailingSlash(path);
        return path.isEmpty() || "/".equals(path) ? null : leadingSlash(path);
    }

    private static String leadingSlash(String value) {
        return value.startsWith("/") ? value : "/" + value;
    }

    private static String trimTrailingSlash(String value) {
        return value.length() > 1 && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /** Notes definition properties the import does not carry into the v2 object model. */
    private static void collectImportInfo(OpenAPI openAPI, List<String> importInfo) {
        if (openAPI.getComponents() != null && openAPI.getComponents().getSchemas() != null
                && !openAPI.getComponents().getSchemas().isEmpty()) {
            importInfo.add("Ignoring component schemas; HTTP APIs do not perform request validation");
        }
        forEachImportedOperation(openAPI, (method, path, operation) -> {
            if (operation != null && extensionAsMap(operation.getExtensions(), EXT_INTEGRATION) == null) {
                importInfo.add("No " + EXT_INTEGRATION + " for " + method + " " + path
                        + "; the route was created without an integration");
            }
        });
    }

    private void recordImportDiagnostics(String region, String apiId, ImportPlan plan) {
        Api api = service.getApi(region, apiId);
        api.setWarnings(plan.warnings().isEmpty() ? null : List.copyOf(plan.warnings()));
        api.setImportInfo(plan.importInfo().isEmpty() ? null : List.copyOf(plan.importInfo()));
        service.putApi(region, api);
    }

    // ──────────────────────────── Spec application ────────────────────────────

    /** Receives every operation the import turns into a route. */
    @FunctionalInterface
    private interface OperationVisitor {
        void visit(String method, String path, Operation operation);
    }

    /**
     * Single enumeration of the operations an import materialises, so route creation and the
     * diagnostic passes can never disagree about which they are. The
     * {@code x-amazon-apigateway-any-method} extension is not part of {@code readOperationsMap()},
     * and walking that map alone left ANY-method routes unexamined by the warning collectors.
     */
    private static void forEachImportedOperation(OpenAPI openAPI, OperationVisitor visitor) {
        if (openAPI.getPaths() == null) {
            return;
        }
        openAPI.getPaths().forEach((path, pathItem) -> {
            if (pathItem == null) {
                return;
            }
            pathItem.readOperationsMap()
                    .forEach((method, operation) -> visitor.visit(method.name(), path, operation));
            Operation anyMethod = anyMethodOperation(pathItem);
            if (anyMethod != null) {
                visitor.visit("ANY", path, anyMethod);
            }
        });
    }

    private void applySpec(String region, String apiId, ImportPlan plan) {
        OpenAPI openAPI = plan.openAPI();
        Map<String, SchemeBinding> schemes = createAuthorizers(region, apiId, plan);
        List<SecurityRequirement> globalSecurity = openAPI.getSecurity();

        forEachImportedOperation(openAPI, (method, path, operation) ->
                createRouteForOperation(region, apiId, method, plan.pathPrefix() + path,
                        operation, schemes, globalSecurity));
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

    private static Operation anyMethodOperation(PathItem pathItem) {
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

    /**
     * A security scheme resolved to the CreateAuthorizer call it implies. {@code request} is null
     * for schemes that select an authorization type without an authorizer of their own — sigv4,
     * which is simply AWS_IAM.
     */
    private record AuthorizerSpec(String schemeName, String authorizationType, Map<String, Object> request) {}

    /**
     * Turns the document's security schemes into CreateAuthorizer calls, rejecting anything HTTP
     * APIs cannot express. Deliberately side-effect free: {@link #plan} runs this before
     * {@link #reimportApi} deletes anything, so a definition that is syntactically valid but
     * carries an unusable authorizer is refused with the previous definition still intact.
     */
    private static List<AuthorizerSpec> authorizerSpecs(OpenAPI openAPI) {
        List<AuthorizerSpec> specs = new ArrayList<>();
        if (openAPI.getComponents() == null || openAPI.getComponents().getSecuritySchemes() == null) {
            return specs;
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
                    specs.add(new AuthorizerSpec(schemeName, "AWS_IAM", null));
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
                request.put("jwtConfiguration", normalisedJwtConfiguration(jwtConfiguration, schemeName));
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

            specs.add(new AuthorizerSpec(schemeName,
                    "JWT".equals(authorizerType) ? "JWT" : "CUSTOM", request));
        }
        return specs;
    }

    private Map<String, SchemeBinding> createAuthorizers(String region, String apiId, ImportPlan plan) {
        Map<String, SchemeBinding> bindings = new LinkedHashMap<>();
        for (AuthorizerSpec spec : plan.authorizerSpecs()) {
            if (spec.request() == null) {
                bindings.put(spec.schemeName(), new SchemeBinding(spec.authorizationType(), null));
                continue;
            }
            Authorizer authorizer = service.createAuthorizer(region, apiId, spec.request());
            bindings.put(spec.schemeName(),
                    new SchemeBinding(spec.authorizationType(), authorizer.getAuthorizerId()));
        }
        return bindings;
    }

    /**
     * Coerces jwtConfiguration into the shape CreateAuthorizer expects, in the planning phase.
     * {@code audience} reaches the store as a {@code List<String>} and is cast as one, so a scalar
     * — which the extension is commonly written with — would otherwise throw a ClassCastException
     * during application, after ReimportApi had already dropped the previous definition.
     */
    private static Map<String, Object> normalisedJwtConfiguration(Map<String, Object> jwtConfiguration,
                                                                  String schemeName) {
        Map<String, Object> normalised = new LinkedHashMap<>(jwtConfiguration);
        Object audience = normalised.get("audience");
        if (audience instanceof String single) {
            normalised.put("audience", List.of(single));
        } else if (audience instanceof List<?> list) {
            List<String> values = new ArrayList<>();
            for (Object item : list) {
                if (item == null) {
                    continue;
                }
                values.add(String.valueOf(item));
            }
            normalised.put("audience", values);
        } else if (audience != null) {
            throw new AwsException("BadRequestException",
                    "jwtConfiguration.audience for security scheme " + schemeName
                            + " must be a string or an array of strings", 400);
        }

        Object issuer = normalised.get("issuer");
        if (issuer != null && !(issuer instanceof String)) {
            throw new AwsException("BadRequestException",
                    "jwtConfiguration.issuer for security scheme " + schemeName + " must be a string", 400);
        }
        return normalised;
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
