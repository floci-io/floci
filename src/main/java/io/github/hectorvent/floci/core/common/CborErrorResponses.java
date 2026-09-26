package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import jakarta.ws.rs.core.Response;

/**
 * The one shape a CBOR error takes: a CBOR-encoded {@link AwsErrorResponse}, the
 * {@code smithy-protocol} header, and the query-error header AWS sends alongside it. Shared so the
 * rpcv2 controller and {@link IamEnforcementFilter} cannot drift into answering the same failure
 * two different ways, which is how a CBOR client ends up unable to decode a rejection.
 */
public final class CborErrorResponses {

    public static final String AWS_CBOR_1_1_MEDIA_TYPE = "application/x-amz-cbor-1.1";
    public static final String GENERIC_CBOR_MEDIA_TYPE = "application/cbor";

    private static final ObjectMapper CBOR_MAPPER = new ObjectMapper(new CBORFactory());

    private CborErrorResponses() {
    }

    /** The CBOR media type matching what the request sent, defaulting to the generic one. */
    public static String mediaTypeFor(String requestContentType) {
        return requestContentType != null && requestContentType.contains("x-amz-cbor")
                ? AWS_CBOR_1_1_MEDIA_TYPE
                : GENERIC_CBOR_MEDIA_TYPE;
    }

    public static Response of(AwsException e, String mediaType) {
        try {
            byte[] body = CBOR_MAPPER.writeValueAsBytes(new AwsErrorResponse(e.jsonType(), e.getMessage()));
            String fault = e.getHttpStatus() < 500 ? "Sender" : "Receiver";
            return Response.status(e.getHttpStatus())
                    .header("smithy-protocol", "rpc-v2-cbor")
                    .header("x-amzn-query-error", e.getErrorCode() + ";" + fault)
                    .type(mediaType)
                    .entity(body)
                    .build();
        } catch (Exception ex) {
            return Response.status(e.getHttpStatus()).build();
        }
    }
}
