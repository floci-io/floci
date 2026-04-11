package io.github.hectorvent.floci.services.dynamodb;

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
}
