package io.github.hectorvent.floci.core.common;

import jakarta.ws.rs.core.Response;

public class JsonErrorResponseUtils {

    private JsonErrorResponseUtils() {
        // Do not instantiate
    }

    public static Response createErrorResponse(Exception e) {
        return JsonErrorResponseUtils.createErrorResponse(500, "InternalFailure", "InternalFailure", e.getMessage());
    }

    public static Response createErrorResponse(AwsException e) {
        return createErrorResponse(e.getHttpStatus(), e.getErrorCode(), e.jsonType(), e.getMessage());
    }

    public static Response createUnknownOperationErrorResponse(String target) {
        return createErrorResponse(404,
                "UnknownOperationException",
                "UnknownOperationException",
                "Unknown operation: " + target);
    }

    public static Response createErrorResponse(int httpStatusCode, String queryError, String errorType, String errorMessage) {
        String queryErrorFault = (httpStatusCode < 500) ? "Sender" : "Receiver";
        return Response.status(httpStatusCode)
                .header("x-amzn-query-error", queryError + ";" + queryErrorFault)
                .header("X-Amzn-Errortype", errorType)
                .entity(new AwsErrorResponse(errorType, errorMessage))
                .build();
    }
}
