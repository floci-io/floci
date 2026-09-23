package io.github.hectorvent.floci.services.appsync.graphql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.docker.ContainerReachableEndpoint;
import io.github.hectorvent.floci.services.appsync.AppSyncService;
import io.github.hectorvent.floci.services.appsync.GraphqlSidecarClient;
import io.github.hectorvent.floci.services.appsync.GraphqlSidecarClient.DenyField;
import io.github.hectorvent.floci.services.appsync.GraphqlSidecarClient.PlanResult;
import io.github.hectorvent.floci.services.appsync.GraphqlSidecarClient.PlannedField;
import io.github.hectorvent.floci.services.appsync.GraphqlSidecarClient.ResolveField;
import io.github.hectorvent.floci.services.appsync.GraphqlSidecarClient.ResolveSpec;
import io.github.hectorvent.floci.services.appsync.graphql.auth.AppSyncAuthContext;
import io.github.hectorvent.floci.services.appsync.graphql.auth.AuthMiddleware;
import io.github.hectorvent.floci.services.appsync.graphql.auth.AuthRequestInfo;
import io.github.hectorvent.floci.services.appsync.graphql.auth.SidecarFieldAuthorizationPlanner;
import io.github.hectorvent.floci.services.appsync.graphql.resolver.AppSyncResolverError;
import io.github.hectorvent.floci.services.appsync.graphql.resolver.AppSyncResolverExecutor;
import io.github.hectorvent.floci.services.appsync.graphql.resolver.ResolverCallbackSessions;
import io.github.hectorvent.floci.services.appsync.model.GraphqlApi;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * AppSync GraphQL data-plane HTTP endpoint (separate from management {@code AppSyncController}).
 * Accepts {@code application/graphql} and {@code application/json}.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class AppSyncExecutionController {

    private static final Logger LOG = Logger.getLogger(AppSyncExecutionController.class);
    private static final String HEADER_ERROR_TYPE = "x-amzn-errortype";
    /** Matches the sidecar's own default; one execution level rarely has more fields than this. */
    private static final int RESOLVE_MAX_BATCH = 100;

    private final AppSyncService appSyncService;
    private final SchemaRegistry schemaRegistry;
    private final SidecarSchemaCompiler schemaCompiler;
    private final SidecarFieldAuthorizationPlanner fieldAuthorizationPlanner;
    private final GraphqlSidecarClient sidecarClient;
    private final AppSyncErrorFormatter errorFormatter;
    private final ObjectMapper objectMapper;
    private final AuthMiddleware authMiddleware;
    private final RequestContext requestContext;
    private final AppSyncResolverExecutor resolverExecutor;
    private final ResolverCallbackSessions callbackSessions;
    private final ContainerReachableEndpoint reachableEndpoint;

    @Inject
    public AppSyncExecutionController(AppSyncService appSyncService,
                                      SchemaRegistry schemaRegistry,
                                      SidecarSchemaCompiler schemaCompiler,
                                      SidecarFieldAuthorizationPlanner fieldAuthorizationPlanner,
                                      GraphqlSidecarClient sidecarClient,
                                      AppSyncErrorFormatter errorFormatter,
                                      ObjectMapper objectMapper,
                                      AuthMiddleware authMiddleware,
                                      RequestContext requestContext,
                                      AppSyncResolverExecutor resolverExecutor,
                                      ResolverCallbackSessions callbackSessions,
                                      ContainerReachableEndpoint reachableEndpoint) {
        this.appSyncService = appSyncService;
        this.schemaRegistry = schemaRegistry;
        this.schemaCompiler = schemaCompiler;
        this.fieldAuthorizationPlanner = fieldAuthorizationPlanner;
        this.sidecarClient = sidecarClient;
        this.errorFormatter = errorFormatter;
        this.objectMapper = objectMapper;
        this.authMiddleware = authMiddleware;
        this.requestContext = requestContext;
        this.resolverExecutor = resolverExecutor;
        this.callbackSessions = callbackSessions;
        this.reachableEndpoint = reachableEndpoint;
    }

    @POST
    @Path("/v1/apis/{apiId}/graphql")
    public Response execute(@PathParam("apiId") String apiId,
                            @Context HttpHeaders headers,
                            String body) {
        try {
            if (!isAcceptedContentType(headers)) {
                return graphqlError(400, "MalformedHttpRequestException",
                        AppSyncErrorFormatter.MSG_UNABLE_TO_PARSE);
            }

            ParsedRequest parsed;
            try {
                parsed = parseBody(body);
            } catch (AppSyncTransportException e) {
                return graphqlError(e.getHttpStatus(), e.getErrorType(), e.getMessage());
            }

            GraphqlApi api;
            try {
                api = appSyncService.getGraphqlApi(apiId);
            } catch (AwsException e) {
                if (e.getHttpStatus() == 404) {
                    return graphqlError(404, "NotFoundException", e.getMessage());
                }
                // Stay on the data-plane errors envelope; never leak to AwsExceptionMapper (__type).
                LOG.errorv(e, "Unexpected AwsException looking up API {0}", apiId);
                return graphqlError(500, "InternalFailure", "InternalFailure");
            }

            AppSyncAuthContext authContext;
            try {
                authContext = authMiddleware.authenticate(
                        headerMap(headers), api, authRequestInfo(parsed, headers, body));
            } catch (AppSyncTransportException e) {
                return graphqlError(e.getHttpStatus(), e.getErrorType(), e.getMessage());
            }

            Optional<String> sdlOpt = schemaRegistry.getSdl(apiId);
            if (sdlOpt.isEmpty()) {
                return graphqlError(502, "GraphQLSchemaException",
                        AppSyncErrorFormatter.MSG_NO_SCHEMA);
            }

            try {
                String preparedSdl = schemaCompiler.withDirectivesAndScalars(sdlOpt.get());
                Map<String, String> scalars = schemaCompiler.scalarKindMapping();
                PlanResult plan = sidecarClient.plan(preparedSdl, scalars, parsed.query(), parsed.operationName());
                if ("SUBSCRIPTION".equals(plan.operationType())) {
                    // Real AppSync serves subscriptions over its separate realtime endpoint, not
                    // this plain-HTTP one; graphql-java itself has no opinion on that, so Floci
                    // rejects it before ever calling /v1/execute.
                    return Response.ok(errorFormatter.format(operationNotSupported())).type(MediaType.APPLICATION_JSON).build();
                }
                List<DenyField> denyFields = fieldAuthorizationPlanner.planDenyFields(plan, authContext);
                Map<String, Object> rawResult = executeQuery(apiId, preparedSdl, scalars, parsed,
                        denyFields, plan, authContext);
                return Response.ok(errorFormatter.format(rawResult)).type(MediaType.APPLICATION_JSON).build();
            } catch (AppSyncTransportException e) {
                return graphqlError(e.getHttpStatus(), e.getErrorType(), e.getMessage());
            }
        } catch (RuntimeException e) {
            LOG.errorv(e, "Unexpected error executing GraphQL for API {0}", apiId);
            return graphqlError(500, "InternalFailure", "InternalFailure");
        }
    }

    /**
     * Runs the query on the sidecar, wiring the resolver callback when any field the query touches
     * actually has a resolver. Without one, the sidecar resolves every field over a null root value
     * exactly as before, so an API with no resolvers costs no session and no callback.
     */
    private Map<String, Object> executeQuery(String apiId, String preparedSdl, Map<String, String> scalars,
                                             ParsedRequest parsed, List<DenyField> denyFields,
                                             PlanResult plan, AppSyncAuthContext authContext) {
        List<ResolveField> resolveFields = resolverBackedFields(apiId, plan, denyFields);
        if (resolveFields.isEmpty()) {
            return sidecarClient.execute(preparedSdl, scalars, parsed.query(), parsed.variables(),
                    parsed.operationName(), denyFields);
        }
        try (ResolverCallbackSessions.Session session =
                     callbackSessions.open(apiId, authContext.identity(), authContext.authType())) {
            ResolveSpec resolve = new ResolveSpec(reachableEndpoint.baseUrl() + "/_floci/appsync/resolve",
                    session.token(), resolveFields, RESOLVE_MAX_BATCH);
            Map<String, Object> result = sidecarClient.execute(preparedSdl, scalars, parsed.query(),
                    parsed.variables(), parsed.operationName(), denyFields, resolve);
            return withAppendedErrors(result, session.appendedErrors());
        }
    }

    /**
     * The coordinates the sidecar should call back for: every planned field that has a resolver and
     * is not already denied, since {@code denyFields} wins and a denied field must never reach one.
     */
    private List<ResolveField> resolverBackedFields(String apiId, PlanResult plan, List<DenyField> denyFields) {
        Set<String> denied = new HashSet<>();
        for (DenyField deny : denyFields) {
            denied.add(deny.typeName() + "." + deny.fieldName());
        }
        Map<String, ResolveField> wired = new LinkedHashMap<>();
        for (PlannedField field : plan.fields()) {
            String key = field.typeName() + "." + field.fieldName();
            if (denied.contains(key) || wired.containsKey(key)) {
                continue;
            }
            if (hasResolver(apiId, field)) {
                wired.put(key, new ResolveField(field.typeName(), field.fieldName()));
            }
        }
        return List.copyOf(wired.values());
    }

    private boolean hasResolver(String apiId, PlannedField field) {
        try {
            return resolverExecutor.findResolver(apiId, field.typeName(), field.fieldName()) != null;
        } catch (AwsException e) {
            // A control-plane failure here (a schema mid-recreation answers 409) must not take down
            // the whole operation: wire the field anyway and let the callback report it on that
            // field alone, which is where a resolver failure belongs.
            LOG.debugv("Could not tell whether {0}.{1} has a resolver: {2}",
                    field.typeName(), field.fieldName(), e.getMessage());
            return true;
        }
    }

    /**
     * {@code util.appendError} reports errors <em>and</em> keeps the data, which the sidecar's
     * per-field callback response cannot say (it carries a value or an error, never both). They
     * come back on the session instead and join the envelope here.
     */
    private static Map<String, Object> withAppendedErrors(Map<String, Object> result,
                                                          List<AppSyncResolverError> appended) {
        if (appended.isEmpty()) {
            return result;
        }
        Map<String, Object> merged = new LinkedHashMap<>(result);
        List<Object> errors = new ArrayList<>();
        if (merged.get("errors") instanceof List<?> existing) {
            errors.addAll(existing);
        }
        for (AppSyncResolverError error : appended) {
            errors.add(envelopeError(error));
        }
        merged.put("errors", errors);
        return merged;
    }

    /** The sidecar's own error shape, so {@link AppSyncErrorFormatter} reads both kinds the same way. */
    private static Map<String, Object> envelopeError(AppSyncResolverError error) {
        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("type", error.errorType());
        extensions.put("data", error.data());
        extensions.put("info", error.errorInfo());
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("message", error.message());
        if (error.path() != null && !error.path().isEmpty()) {
            entry.put("path", error.path());
        }
        entry.put("extensions", extensions);
        return entry;
    }

    private boolean isAcceptedContentType(HttpHeaders headers) {
        String contentType = headers.getHeaderString(HttpHeaders.CONTENT_TYPE);
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        String normalized = contentType.toLowerCase(Locale.ROOT).trim();
        int semicolon = normalized.indexOf(';');
        if (semicolon >= 0) {
            normalized = normalized.substring(0, semicolon).trim();
        }
        return "application/json".equals(normalized) || "application/graphql".equals(normalized);
    }

    private ParsedRequest parseBody(String body) {
        if (body == null || body.isBlank()) {
            throw new AppSyncTransportException(400, "MalformedHttpRequestException",
                    AppSyncErrorFormatter.MSG_EMPTY_BODY);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new AppSyncTransportException(400, "MalformedHttpRequestException",
                    AppSyncErrorFormatter.MSG_UNABLE_TO_PARSE);
        }

        if (root == null || root.isNull() || root.isArray() || !root.isObject()) {
            throw new AppSyncTransportException(400, "MalformedHttpRequestException",
                    AppSyncErrorFormatter.MSG_UNABLE_TO_PARSE);
        }
        if (root.isEmpty()) {
            throw new AppSyncTransportException(400, "MalformedHttpRequestException",
                    AppSyncErrorFormatter.MSG_UNABLE_TO_PARSE);
        }

        JsonNode queryNode = root.get("query");
        if (queryNode == null || !queryNode.isTextual()) {
            throw new AppSyncTransportException(400, "MalformedHttpRequestException",
                    AppSyncErrorFormatter.MSG_UNABLE_TO_PARSE);
        }
        // Blank queries are valid GraphQL-over-HTTP JSON and become SyntaxError (HTTP 200)
        // via graphql-java — not MalformedHttpRequestException (400).
        String query = queryNode.asText();

        Map<String, Object> variables = null;
        JsonNode variablesNode = root.get("variables");
        if (variablesNode != null && !variablesNode.isNull()) {
            if (!variablesNode.isObject()) {
                throw new AppSyncTransportException(400, "MalformedHttpRequestException",
                        AppSyncErrorFormatter.MSG_UNABLE_TO_PARSE);
            }
            variables = objectMapper.convertValue(variablesNode, Map.class);
        }

        String operationName = null;
        JsonNode opNode = root.get("operationName");
        if (opNode != null && !opNode.isNull()) {
            if (!opNode.isTextual()) {
                throw new AppSyncTransportException(400, "MalformedHttpRequestException",
                        AppSyncErrorFormatter.MSG_UNABLE_TO_PARSE);
            }
            operationName = opNode.asText();
        }

        return new ParsedRequest(query, variables, operationName);
    }

    private Map<String, String> headerMap(HttpHeaders headers) {
        Map<String, String> map = new HashMap<>();
        if (headers == null || headers.getRequestHeaders() == null) {
            return map;
        }
        for (String name : headers.getRequestHeaders().keySet()) {
            if (name != null) {
                map.put(name, headers.getHeaderString(name));
            }
        }
        return map;
    }

    private AuthRequestInfo authRequestInfo(ParsedRequest parsed, HttpHeaders headers, String rawBody) {
        String accountId = requestContext.getAccountId() != null ? requestContext.getAccountId() : "000000000000";
        String region = requestContext.getRegion() != null ? requestContext.getRegion() : "us-east-1";
        String requestId = headers.getHeaderString("x-amzn-RequestId");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        return new AuthRequestInfo(
                parsed.query(),
                parsed.operationName(),
                parsed.variables() == null ? Map.of() : parsed.variables(),
                sourceIp(headers),
                requestId,
                accountId,
                region,
                headerMap(headers),
                rawBody);
    }

    private static List<String> sourceIp(HttpHeaders headers) {
        List<String> ips = new ArrayList<>();
        String forwarded = headers.getHeaderString("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            for (String part : forwarded.split(",")) {
                if (!part.isBlank()) {
                    ips.add(part.trim());
                }
            }
        }
        if (ips.isEmpty()) {
            ips.add("127.0.0.1");
        }
        return ips;
    }

    private static Map<String, Object> operationNotSupported() {
        Map<String, Object> error = new HashMap<>();
        error.put("message", "Subscriptions are not supported over HTTP");
        error.put("extensions", Map.of("classification", "OperationNotSupported"));
        return Map.of("errors", List.of(error));
    }

    private Response graphqlError(int status, String errorType, String message) {
        return Response.status(status)
                .header(HEADER_ERROR_TYPE, errorType)
                .type(MediaType.APPLICATION_JSON)
                .entity(errorFormatter.transportError(errorType, message))
                .build();
    }

    private record ParsedRequest(String query, Map<String, Object> variables, String operationName) {}
}
