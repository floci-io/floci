package io.github.hectorvent.floci.services.appsync.graphql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.appsync.AppSyncService;
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

import java.util.Locale;
import java.util.Map;

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

    @Inject
    public AppSyncExecutionController(AppSyncService appSyncService,
                                      SchemaRegistry schemaRegistry,
                                      QueryExecutor queryExecutor,
                                      AppSyncErrorFormatter errorFormatter,
                                      ObjectMapper objectMapper) {
        this.appSyncService = appSyncService;
        this.schemaRegistry = schemaRegistry;
        this.queryExecutor = queryExecutor;
        this.errorFormatter = errorFormatter;
        this.objectMapper = objectMapper;
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

            try {
                appSyncService.getGraphqlApi(apiId);
            } catch (AwsException e) {
                if (e.getHttpStatus() == 404) {
                    return graphqlError(404, "NotFoundException", e.getMessage());
                }
                throw e;
            }

            var graphQLOpt = schemaRegistry.getGraphQL(apiId);
            if (graphQLOpt.isEmpty()) {
                return graphqlError(502, "GraphQLSchemaException",
                        AppSyncErrorFormatter.MSG_NO_SCHEMA);
            }

            try {
                Map<String, Object> result = queryExecutor.execute(
                        graphQLOpt.get(), parsed.query(), parsed.variables(), parsed.operationName());
                return Response.ok(result).type(MediaType.APPLICATION_JSON).build();
            } catch (AppSyncTransportException e) {
                return graphqlError(e.getHttpStatus(), e.getErrorType(), e.getMessage());
            }
        } catch (AwsException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.errorv(e, "Unexpected error executing GraphQL for API {0}", apiId);
            return graphqlError(500, "InternalFailure",
                    e.getMessage() != null ? e.getMessage() : "InternalFailure");
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

    private Response graphqlError(int status, String errorType, String message) {
        return Response.status(status)
                .header(HEADER_ERROR_TYPE, errorType)
                .type(MediaType.APPLICATION_JSON)
                .entity(errorFormatter.transportError(errorType, message))
                .build();
    }

    private record ParsedRequest(String query, Map<String, Object> variables, String operationName) {}
}
