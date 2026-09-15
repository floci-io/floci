package io.github.hectorvent.floci.services.appsync.graphql;

import graphql.ErrorType;
import graphql.ExecutionResult;
import graphql.GraphQLError;
import graphql.ErrorClassification;
import graphql.language.SourceLocation;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps graphql-java {@link ExecutionResult} errors to AWS AppSync wire shapes
 * with top-level {@code errorType}/{@code errorInfo} (not extensions-only).
 *
 * @see <a href="https://docs.aws.amazon.com/appsync/latest/devguide/built-in-util-js.html">AppSync DG {@code $util.error} ({@code errorType}/{@code errorInfo})</a>
 * @see <a href="https://github.com/graphql/graphql-over-http/issues/81">AppSync HTTP behavior (AppSync team / @robzhu)</a>
 */
@ApplicationScoped
public class AppSyncErrorFormatter {

    /**
     * Messages from AppSync team sample:
     * <a href="https://github.com/graphql/graphql-over-http/issues/81">graphql-over-http#81</a>
     */
    public static final String MSG_EMPTY_BODY = "Request body is empty.";
    public static final String MSG_UNABLE_TO_PARSE = "Unable to parse GraphQL query.";
    public static final String MSG_MISSING_OPERATION_NAME = "Missing operation name.";
    public static final String MSG_NO_SCHEMA = "No schema definition exists.";

    public Map<String, Object> format(ExecutionResult result) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (result.isDataPresent()) {
            response.put("data", result.getData());
        }
        List<GraphQLError> errors = result.getErrors();
        if (errors != null && !errors.isEmpty()) {
            List<Map<String, Object>> formatted = new ArrayList<>(errors.size());
            for (GraphQLError error : errors) {
                formatted.add(formatError(error));
            }
            response.put("errors", formatted);
        }
        return response;
    }

    public Map<String, Object> transportError(String errorType, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("errorType", errorType);
        error.put("message", message);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("errors", List.of(error));
        return response;
    }

    private Map<String, Object> formatError(GraphQLError error) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("message", error.getMessage());
        List<SourceLocation> locations = error.getLocations();
        if (locations != null && !locations.isEmpty()) {
            List<Map<String, Object>> locationMaps = new ArrayList<>(locations.size());
            for (SourceLocation location : locations) {
                Map<String, Object> loc = new LinkedHashMap<>();
                loc.put("line", location.getLine());
                loc.put("column", location.getColumn());
                locationMaps.add(loc);
            }
            map.put("locations", locationMaps);
        }
        List<Object> path = error.getPath();
        if (path != null && !path.isEmpty()) {
            map.put("path", path);
        }
        // A resolver that called util.error chose its own errorType, errorInfo and data, and AppSync
        // reports all three on the error itself. They arrive in extensions, which is the only
        // channel a GraphQLError has for them; anything else keeps the classification-derived type
        // and the null errorInfo that a server-side failure has.
        Map<String, Object> extensions = error.getExtensions();
        Object suppliedType = extensions == null ? null : extensions.get("errorType");
        map.put("errorType", suppliedType instanceof String type && !type.isBlank()
                ? type
                : toAppSyncErrorType(error.getErrorType()));
        map.put("errorInfo", extensions == null ? null : extensions.get("errorInfo"));
        Object suppliedData = extensions == null ? null : extensions.get("data");
        if (suppliedData != null) {
            map.put("data", suppliedData);
        }
        return map;
    }

    static String toAppSyncErrorType(ErrorClassification classification) {
        if (classification == ErrorType.InvalidSyntax) {
            return "SyntaxError";
        }
        if (classification == ErrorType.ValidationError) {
            return "ValidationError";
        }
        if (classification == ErrorType.OperationNotSupported) {
            return "OperationNotSupported";
        }
        if (classification == ErrorType.DataFetchingException) {
            return "DataFetchingException";
        }
        if (classification == null) {
            return "Unknown";
        }
        if (classification instanceof ErrorType errorType) {
            return errorType.name();
        }
        return classification.toString();
    }
}
