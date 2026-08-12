package io.github.hectorvent.floci.services.appsync.graphql;

/**
 * Transport-layer AppSync GraphQL HTTP error that must be returned as
 * GraphQL {@code errors[]} body (not management {@code __type} via AwsExceptionMapper).
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
