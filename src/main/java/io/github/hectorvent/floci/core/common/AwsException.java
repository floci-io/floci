package io.github.hectorvent.floci.core.common;

import java.util.Map;

/**
 * Base exception for AWS emulator errors.
 * Maps to AWS-style error responses with code, message, and HTTP status.
 * <p>
 * Some services use different error code formats for Query (XML) and JSON protocols.
 * {@link #jsonType()} returns the JSON-protocol {@code __type} value that the AWS SDK v2
 * uses to instantiate a specific typed exception rather than falling back to a generic one.
 */
public class AwsException extends RuntimeException {

    /**
     * Maps Query-protocol error codes to their JSON-protocol {@code __type} equivalents.
     * Codes absent from this map are used as-is for both protocols.
     */
    private static final Map<String, String> JSON_TYPE_BY_QUERY_CODE = Map.of(
            "AWS.SimpleQueueService.NonExistentQueue", "QueueDoesNotExist",
            "QueueAlreadyExists",                      "QueueNameExists",
            "ReceiptHandleIsInvalid",                  "ReceiptHandleIsInvalid",
            "TooManyEntriesInBatchRequest",            "TooManyEntriesInBatchRequest",
            "BatchEntryIdNotUnique",                   "BatchEntryIdNotDistinct"
    );

    private final String errorCode;
    private final int httpStatus;

    public AwsException(String errorCode, String message, int httpStatus) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    /**
     * Returns the JSON-protocol {@code __type} value for this error.
     * The AWS SDK v2 uses this to map responses to typed exception classes.
     */
    public String jsonType() {
        return JSON_TYPE_BY_QUERY_CODE.getOrDefault(errorCode, errorCode);
    }
}
