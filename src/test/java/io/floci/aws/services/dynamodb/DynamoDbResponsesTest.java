package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class DynamoDbResponsesTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
    }

    private static long crc32Of(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return crc.getValue();
    }

    @Test
    void withCrc32_serializesObjectNode_andAttachesCorrectChecksum() throws Exception {
        ObjectNode entity = mapper.createObjectNode();
        entity.put("TableName", "users");
        entity.putObject("TableStatus").put("value", "ACTIVE");

        byte[] expectedBytes = mapper.writeValueAsBytes(entity);
        long expectedCrc = crc32Of(expectedBytes);

        Response input = Response.ok(entity).build();
        Response wrapped = DynamoDbResponses.withCrc32(input, mapper);

        assertNotNull(wrapped);
        assertEquals(200, wrapped.getStatus());
        assertArrayEquals(expectedBytes, (byte[]) wrapped.getEntity());
        assertEquals(Long.toString(expectedCrc), wrapped.getHeaderString("X-Amz-Crc32"));
        assertEquals("application/x-amz-json-1.0", wrapped.getMediaType().toString());
    }

    @Test
    void withCrc32_byteArrayEntity_usedAsIs() {
        byte[] rawBody = "{\"TableNames\":[]}".getBytes(StandardCharsets.UTF_8);
        long expectedCrc = crc32Of(rawBody);

        Response input = Response.ok(rawBody).build();
        Response wrapped = DynamoDbResponses.withCrc32(input, mapper);

        assertArrayEquals(rawBody, (byte[]) wrapped.getEntity());
        assertEquals(Long.toString(expectedCrc), wrapped.getHeaderString("X-Amz-Crc32"));
    }

    @Test
    void withCrc32_nullEntity_emptyBodyAndCrc32OfEmpty() {
        Response input = Response.status(204).build();
        Response wrapped = DynamoDbResponses.withCrc32(input, mapper);

        assertEquals(204, wrapped.getStatus());
        assertArrayEquals(new byte[0], (byte[]) wrapped.getEntity());
        assertEquals(Long.toString(crc32Of(new byte[0])), wrapped.getHeaderString("X-Amz-Crc32"));
    }

    @Test
    void withCrc32_preservesStatusCode_forErrorResponse() throws Exception {
        ObjectNode errorBody = mapper.createObjectNode();
        errorBody.put("__type", "ResourceNotFoundException");
        errorBody.put("message", "Table not found");

        Response input = Response.status(400).entity(errorBody).build();
        Response wrapped = DynamoDbResponses.withCrc32(input, mapper);

        assertEquals(400, wrapped.getStatus());
        byte[] expectedBytes = mapper.writeValueAsBytes(errorBody);
        assertArrayEquals(expectedBytes, (byte[]) wrapped.getEntity());
        assertEquals(Long.toString(crc32Of(expectedBytes)), wrapped.getHeaderString("X-Amz-Crc32"));
    }

    @Test
    void withCrc32_preservesCustomHeaders_overridesContentTypeAndLength() throws Exception {
        ObjectNode entity = mapper.createObjectNode();
        entity.put("ok", true);

        Response input = Response.ok(entity)
                .header("x-amz-request-id", "req-123")
                .header("x-amz-id-2", "id-456")
                .header("Content-Type", MediaType.APPLICATION_JSON)
                .header("Content-Length", 999)
                .build();

        Response wrapped = DynamoDbResponses.withCrc32(input, mapper);

        assertEquals("req-123", wrapped.getHeaderString("x-amz-request-id"));
        assertEquals("id-456", wrapped.getHeaderString("x-amz-id-2"));
        // Content-Type must be the DynamoDB JSON protocol type, not the one from the input
        assertEquals("application/x-amz-json-1.0", wrapped.getMediaType().toString());
        // Content-Length from input must not leak through; JAX-RS will recompute from bytes
        assertNull(wrapped.getHeaderString("Content-Length"));
    }

    @Test
    void withCrc32_nullResponse_returnsNull() {
        assertNull(DynamoDbResponses.withCrc32(null, mapper));
    }

    @Test
    void withCrc32_serializationFailurePreservesOriginalResponse() {
        ObjectMapper failingMapper = new ObjectMapper() {
            @Override
            public byte[] writeValueAsBytes(Object value) throws JsonProcessingException {
                throw new JsonProcessingException("expected serialization failure") {};
            }
        };
        Response original = Response.status(202).entity(new Object())
                .header("x-amzn-requestid", "request-123").build();

        assertSame(original, DynamoDbResponses.withCrc32(original, failingMapper));
        assertNull(original.getHeaderString("X-Amz-Crc32"));
    }

    @Test
    void legacyErrorsRetainMessageHeadersAndOriginalBackendBody() {
        ObjectNode entity = mapper.createObjectNode()
                .put("__type", "com.amazonaws.dynamodb.v20120810#ResourceNotFoundException")
                .put("message", "Table not found");
        Response input = Response.status(400).entity(entity).header("x-request-id", "probe").build();
        Response legacy = DynamoDbResponses.withLegacyErrorNamespace(input, mapper);
        ObjectNode body = (ObjectNode) legacy.getEntity();
        assertEquals("com.amazonaws.dynamodb.v20111205#ResourceNotFoundException", body.path("__type").asText());
        assertEquals("Table not found", body.path("message").asText());
        assertEquals("probe", legacy.getHeaderString("x-request-id"));
        assertEquals(400, legacy.getStatus());
        assertEquals("com.amazonaws.dynamodb.v20120810#ResourceNotFoundException", entity.path("__type").asText());
    }

    @Test
    void legacyResponsesPreserveCoralValidationAndSuccessfulBodies() {
        Response validation = Response.status(400).entity(mapper.createObjectNode()
                .put("__type", "com.amazon.coral.validate#ValidationException")).build();
        assertSame(validation, DynamoDbResponses.withLegacyErrorNamespace(validation, mapper));
        Response success = Response.ok(mapper.createObjectNode().put("Count", 1)).build();
        assertSame(success, DynamoDbResponses.withLegacyErrorNamespace(success, mapper));
    }
}
