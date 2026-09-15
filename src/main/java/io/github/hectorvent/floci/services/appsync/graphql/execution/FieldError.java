package io.github.hectorvent.floci.services.appsync.graphql.execution;

/**
 * A resolver-level error before it is attached to a GraphQL path and location.
 * {@code data} is filtered by the query selection set when rendered; {@code errorInfo} is not.
 */
public record FieldError(String errorType, String message, Object data, Object errorInfo) {

    public static FieldError of(String errorType, String message) {
        return new FieldError(errorType, message, null, null);
    }
}
