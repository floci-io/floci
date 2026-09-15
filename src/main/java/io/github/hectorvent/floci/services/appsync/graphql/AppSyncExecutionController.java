package io.github.hectorvent.floci.services.appsync.graphql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.introspection.Introspection;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.services.appsync.AppSyncService;
import io.github.hectorvent.floci.services.appsync.graphql.auth.AppSyncAuthContext;
import io.github.hectorvent.floci.services.appsync.graphql.auth.AuthMiddleware;
import io.github.hectorvent.floci.services.appsync.graphql.auth.AuthRequestInfo;
import io.github.hectorvent.floci.services.appsync.graphql.execution.GraphQlRequestContext;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    private final AppSyncService appSyncService;
    private final SchemaRegistry schemaRegistry;
    private final QueryExecutor queryExecutor;
    private final AppSyncErrorFormatter errorFormatter;
    private final ObjectMapper objectMapper;
    private final AuthMiddleware authMiddleware;
    private final RequestContext requestContext;

    @Inject
    public AppSyncExecutionController(AppSyncService appSyncService,
                                      SchemaRegistry schemaRegistry,
                                      QueryExecutor queryExecutor,
                                      AppSyncErrorFormatter errorFormatter,
                                      ObjectMapper objectMapper,
                                      AuthMiddleware authMiddleware,
                                      RequestContext requestContext) {
        this.appSyncService = appSyncService;
        this.schemaRegistry = schemaRegistry;
        this.queryExecutor = queryExecutor;
        this.errorFormatter = errorFormatter;
        this.objectMapper = objectMapper;
        this.authMiddleware = authMiddleware;
        this.requestContext = requestContext;
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

            GraphqlApi discoveredApi = appSyncService.findGraphqlApiAnyAccount(apiId).orElse(null);
            if (discoveredApi == null) {
                return graphqlError(404, "NotFoundException", "GraphQL API not found: " + apiId);
            }

            String previousAccount = requestContext.getAccountId();
            String previousRegion = requestContext.getRegion();
            String callerAccount = previousAccount != null ? previousAccount : "000000000000";
            String apiAccount = AwsArnUtils.accountOrDefault(discoveredApi.getArn(), callerAccount);
            String apiRegion = AwsArnUtils.regionOrDefault(discoveredApi.getArn(),
                    previousRegion != null ? previousRegion : "us-east-1");
            try {
                requestContext.setAccountId(apiAccount);
                requestContext.setRegion(apiRegion);
                // Refresh dynamic endpoint URIs only after entering the API owner's namespace.
                GraphqlApi api = appSyncService.getGraphqlApi(apiId);

                AppSyncAuthContext authContext;
                try {
                    authContext = authMiddleware.authenticate(
                            headerMap(headers), api,
                            authRequestInfo(parsed, headers, body, apiAccount, apiRegion, callerAccount));
                } catch (AppSyncTransportException e) {
                    return graphqlError(e.getHttpStatus(), e.getErrorType(), e.getMessage());
                }

                var graphQLOpt = schemaRegistry.getGraphQL(apiId);
                if (graphQLOpt.isEmpty()) {
                    return graphqlError(502, "GraphQLSchemaException",
                            AppSyncErrorFormatter.MSG_NO_SCHEMA);
                }

                try {
                    Map<String, Object> result = queryExecutor.execute(
                            graphQLOpt.get(), parsed.query(), parsed.variables(), parsed.operationName(),
                            graphQlContext(authContext, parsed, headers));
                    return Response.ok(result).type(MediaType.APPLICATION_JSON).build();
                } catch (AppSyncTransportException e) {
                    return graphqlError(e.getHttpStatus(), e.getErrorType(), e.getMessage());
                }
            } finally {
                requestContext.setAccountId(previousAccount);
                requestContext.setRegion(previousRegion);
            }
        } catch (RuntimeException e) {
            LOG.errorv(e, "Unexpected error executing GraphQL for API {0}", apiId);
            return graphqlError(500, "InternalFailure", "InternalFailure");
        }
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

    private AuthRequestInfo authRequestInfo(ParsedRequest parsed, HttpHeaders headers, String rawBody,
                                            String apiAccount, String apiRegion, String callerAccount) {
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
                apiAccount,
                apiRegion,
                callerAccount,
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

    private static Map<Object, Object> graphQlContext(AppSyncAuthContext authContext,
                                                       ParsedRequest parsed,
                                                       HttpHeaders headers) {
        Map<Object, Object> context = new HashMap<>();
        context.put(AppSyncAuthContext.KEY, authContext);
        if (authContext.identity() != null) {
            context.put("identity", authContext.identity());
        }
        context.put("authType", authContext.authType());
        context.put("deniedFields", authContext.deniedFieldsList());
        context.put(GraphQlRequestContext.CONTEXT_KEY, new GraphQlRequestContext(
                authContext.graphqlApi().getApiId(),
                authContext.accountId(),
                authContext.region(),
                authContext.authType(),
                authContext.identity(),
                resolverHeaders(headers),
                parsed.variables() == null ? Map.of() : parsed.variables()));
        if ("DISABLED".equals(authContext.graphqlApi().getIntrospectionConfig())) {
            context.put(Introspection.INTROSPECTION_DISABLED, true);
        }
        return context;
    }

    private static Map<String, Object> resolverHeaders(HttpHeaders headers) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (headers == null || headers.getRequestHeaders() == null) {
            return result;
        }
        headers.getRequestHeaders().forEach((name, values) -> {
            if (name == null || "cookie".equalsIgnoreCase(name) || values == null || values.isEmpty()) {
                return;
            }
            result.put(name.toLowerCase(Locale.ROOT), values.size() == 1 ? values.getFirst() : List.copyOf(values));
        });
        return result;
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
