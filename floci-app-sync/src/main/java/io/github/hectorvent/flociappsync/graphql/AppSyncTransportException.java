package io.github.hectorvent.flociappsync.graphql;

/**
 * Transport-layer AppSync GraphQL error that {@code AppSyncEngineResource} maps to the
 * GraphQL {@code errors[]} envelope. Ported verbatim from Floci's
 * {@code services.appsync.graphql.AppSyncTransportException}.
 */
public class AppSyncTransportException extends RuntimeException {

    private final int httpStatus;
    private final String errorType;

    public AppSyncTransportException(int httpStatus, String errorType, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.errorType = errorType;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getErrorType() {
        return errorType;
    }
}
