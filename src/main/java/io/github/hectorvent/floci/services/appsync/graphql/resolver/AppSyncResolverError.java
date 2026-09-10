package io.github.hectorvent.floci.services.appsync.graphql.resolver;

import graphql.ErrorClassification;
import graphql.GraphQLError;
import graphql.language.SourceLocation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A resolver's own error, shaped the way AppSync reports one.
 *
 * <p>{@code util.error("...", "Forbidden")} does not produce a generic
 * "exception while fetching data" — it produces an error carrying that {@code errorType}, which
 * clients switch on. Carried in {@code extensions} beside {@code data}, since
 * {@code util.appendError} exists precisely to return both at once.
 */
public record AppSyncResolverError(String message, String errorType, Object data, Object errorInfo,
                                  List<Object> path) implements GraphQLError {

    @Override
    public List<SourceLocation> getLocations() {
        return null;
    }

    @Override
    public ErrorClassification getErrorType() {
        return null;
    }

    @Override
    public String getMessage() {
        return message;
    }

    @Override
    public List<Object> getPath() {
        return path;
    }

    @Override
    public Map<String, Object> getExtensions() {
        Map<String, Object> extensions = new LinkedHashMap<>();
        if (errorType != null) {
            extensions.put("errorType", errorType);
        }
        if (data != null) {
            extensions.put("data", data);
        }
        if (errorInfo != null) {
            extensions.put("errorInfo", errorInfo);
        }
        return extensions;
    }
}
