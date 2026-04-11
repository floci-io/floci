package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Helpers for building DynamoDB JSON protocol responses.
 */
public final class DynamoDbResponses {

    private static final Logger LOG = Logger.getLogger(DynamoDbResponses.class);

    private DynamoDbResponses() {}

    /**
     * Pre-serialize the response entity and attach an {@code X-Amz-Crc32} header whose value
     * is the decimal CRC32 of the serialized bytes.
     *
     * <p>Real AWS DynamoDB includes this header on every response, and the AWS SDK for Go v2
     * DynamoDB client wraps the response body in a CRC32-verifying reader
     * ({@code service/dynamodb/internal/customizations/checksum.go}). When the header is
     * missing the wrapper compares the computed CRC32 against an expected value of 0 on
     * {@code Close()}, returns a checksum error, and smithy-go logs
     * "failed to close HTTP response body, this may affect connection reuse" for every
     * call. Sending a correct header silences the warning and gives clients a real
     * integrity check.
     *
     * <p>This is applied only at the JSON protocol boundary (e.g. {@code AwsJsonController})
     * because {@link DynamoDbJsonHandler} is also invoked from CBOR, API Gateway proxy, and
     * Step Functions task flows — those callers keep the original {@code ObjectNode} entity.
     */
    public static Response withCrc32(Response response, ObjectMapper objectMapper) {
        if (response == null) {
            return null;
        }
        Object entity = response.getEntity();
        byte[] bodyBytes;
        try {
            if (entity == null) {
                bodyBytes = new byte[0];
            } else if (entity instanceof byte[] b) {
                bodyBytes = b;
            } else {
                bodyBytes = objectMapper.writeValueAsBytes(entity);
            }
        } catch (Exception e) {
            LOG.warn("Failed to serialize DynamoDB response for CRC32 computation", e);
            return response;
        }

        CRC32 crc = new CRC32();
        crc.update(bodyBytes);

        Response.ResponseBuilder builder = Response.status(response.getStatus())
                .entity(bodyBytes)
                .type(MediaType.valueOf("application/x-amz-json-1.0"))
                .header("X-Amz-Crc32", Long.toString(crc.getValue()));

        MultivaluedMap<String, Object> existing = response.getHeaders();
        if (existing != null) {
            for (Map.Entry<String, List<Object>> e : existing.entrySet()) {
                String name = e.getKey();
                if ("Content-Type".equalsIgnoreCase(name)
                        || "Content-Length".equalsIgnoreCase(name)
                        || "X-Amz-Crc32".equalsIgnoreCase(name)) {
                    continue;
                }
                for (Object v : e.getValue()) {
                    builder.header(name, v);
                }
            }
        }
        return builder.build();
    }
}
