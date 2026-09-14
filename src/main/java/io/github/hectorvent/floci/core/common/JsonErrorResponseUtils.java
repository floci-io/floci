package io.github.hectorvent.floci.core.common;
import io.github.hectorvent.floci.services.dynamodb.model.ConditionalCheckFailedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.ws.rs.core.Response;

import java.util.Map;

public class JsonErrorResponseUtils {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private JsonErrorResponseUtils() {
        // Do not instantiate
    }

    public static Response createErrorResponse(Exception e) {
        return JsonErrorResponseUtils.createErrorResponse(500, "InternalFailure", "InternalFailure", e.getMessage(), null);
    }

    public static Response createErrorResponse(AwsException e) {
        if (e.getExtendedData() != null) {
            return createExtendedErrorResponse(e);
        }
        JsonNode item = null;
        if (e instanceof ConditionalCheckFailedException){
            item = ((ConditionalCheckFailedException) e).getItem();
        }
        return createErrorResponse(e.getHttpStatus(), e.getErrorCode(), e.jsonType(), e.getMessage(), item);
    }

    /**
     * AwsJson11Controller dispatches services like WAFv2 through this class rather than the
     * generic {@code AwsExceptionMapper} JAX-RS provider, so extendedData (Field/Parameter/Reason
     * style structured errors) needs its own serialization path here to reach the client at all.
     */
    private static Response createExtendedErrorResponse(AwsException e) {
        String queryErrorFault = (e.getHttpStatus() < 500) ? "Sender" : "Receiver";
        ObjectNode node = OBJECT_MAPPER.createObjectNode();
        node.put("__type", e.jsonType());
        node.put("message", e.getMessage());
        for (Map.Entry<String, Object> entry : e.getExtendedData().entrySet()) {
            node.set(entry.getKey(), OBJECT_MAPPER.valueToTree(entry.getValue()));
        }
        return Response.status(e.getHttpStatus())
                .header("x-amzn-query-error", e.getErrorCode() + ";" + queryErrorFault)
                .entity(node)
                .build();
    }

    public static Response createUnknownOperationErrorResponse(String target) {
        return createErrorResponse(404,
                "UnknownOperationException",
                "UnknownOperationException",
                "Unknown operation: " + target, null);
    }

    /**
     * A request body that is not valid JSON is a client (deserialization) error, not a server
     * fault. AWS's JSON protocols reject it with 400 SerializationException; without this the
     * unparsed body escapes to the generic catch and surfaces as 500 InternalFailure.
     */
    public static Response createSerializationErrorResponse() {
        return createErrorResponse(400,
                "SerializationException",
                "SerializationException",
                "The request could not be parsed as valid JSON.", null);
    }

    public static Response createErrorResponse(int httpStatusCode, String queryError, String errorType, String errorMessage, JsonNode item) {
        String queryErrorFault = (httpStatusCode < 500) ? "Sender" : "Receiver";
        if (item != null) {
            return Response.status(httpStatusCode)
                    .header("x-amzn-query-error", queryError + ";" + queryErrorFault)
                    .entity(new AwsErrorResponseWithItem(errorType, errorMessage, item))
                    .build();
        }
        return Response.status(httpStatusCode)
                .header("x-amzn-query-error", queryError + ";" + queryErrorFault)
                .entity(new AwsErrorResponse(errorType, errorMessage))
                .build();
    }
}
