package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.JsonResponseBytes;
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

    public static Response withLegacyErrorNamespace(Response response, ObjectMapper objectMapper) {
        if (response.getStatus() < 400 || response.getEntity() == null) {
            return response;
        }
        JsonNode body = objectMapper.valueToTree(response.getEntity());
        if (!(body instanceof ObjectNode error) || !error.path("__type").isTextual()) {
            return response;
        }
        String type = error.path("__type").asText();
        if (type.startsWith("com.amazon.coral.")) {
            return response;
        }
        ObjectNode legacyError = error.deepCopy();
        legacyError.put("__type", "com.amazonaws.dynamodb.v20111205#" + type.substring(type.lastIndexOf('#') + 1));
        return Response.fromResponse(response).entity(legacyError).build();
    }

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
        byte[] bodyBytes = JsonResponseBytes.serializeOrNull(response.getEntity(), objectMapper,
                LOG, "Failed to serialize DynamoDB response for CRC32 computation");
        if (bodyBytes == null) {
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
