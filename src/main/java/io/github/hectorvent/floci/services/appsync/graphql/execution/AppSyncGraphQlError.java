package io.github.hectorvent.floci.services.appsync.graphql.execution;

import graphql.ErrorClassification;
import graphql.GraphQLError;
import graphql.language.SourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A GraphQL error in the shape AppSync returns:
 * {@code {path, data, errorType, errorInfo, locations, message}}.
 */
public final class AppSyncGraphQlError implements GraphQLError {

    private final String message;
    private final String errorType;
    private final Object data;
    private final Object errorInfo;
    private final List<Object> path;
    private final List<SourceLocation> locations;

    public AppSyncGraphQlError(String errorType, String message, Object data, Object errorInfo,
                               List<Object> path, List<SourceLocation> locations) {
        this.message = message;
        this.errorType = errorType;
        this.data = data;
        this.errorInfo = errorInfo;
        this.path = path;
        this.locations = locations != null ? locations : List.of();
    }

    public static AppSyncGraphQlError of(FieldError error, List<Object> path, List<SourceLocation> locations) {
        return new AppSyncGraphQlError(error.errorType(), error.message(), error.data(), error.errorInfo(),
                path, locations);
    }

    /** An error that is not tied to a field, e.g. an unsupported operation type. */
    public static AppSyncGraphQlError topLevel(String errorType, String message) {
        return new AppSyncGraphQlError(errorType, message, null, null, null, List.of());
    }

    @Override
    public String getMessage() {
        return message;
    }

    public String getErrorTypeName() {
        return errorType;
    }

    public Object getData() {
        return data;
    }

    public Object getErrorInfo() {
        return errorInfo;
    }

    @Override
    public List<SourceLocation> getLocations() {
        return locations;
    }

    @Override
    public ErrorClassification getErrorType() {
        return new ErrorClassification() {
            @Override
            public String toString() {
                return errorType;
            }
        };
    }

    @Override
    public List<Object> getPath() {
        return path;
    }

    /** Renders the AWS wire shape; key order matches AppSync responses. */
    public Map<String, Object> toEntry() {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("path", path);
        entry.put("data", data);
        entry.put("errorType", errorType);
        entry.put("errorInfo", errorInfo);
        List<Map<String, Object>> locs = new ArrayList<>();
        for (SourceLocation loc : locations) {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("line", loc.getLine());
            l.put("column", loc.getColumn());
            l.put("sourceName", loc.getSourceName());
            locs.add(l);
        }
        entry.put("locations", locs);
        entry.put("message", message);
        return entry;
    }
}
