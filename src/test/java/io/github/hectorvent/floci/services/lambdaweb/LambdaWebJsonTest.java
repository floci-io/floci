package io.github.hectorvent.floci.services.lambdaweb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LambdaWebJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String value) {
        try {
            return MAPPER.readTree(value);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void integerTruncatesAndSaturatesNumericMembers() {
        JsonNode request = json("{\"fraction\":-1.9,\"large\":9223372036854775808123,\"null\":null}");

        assertEquals(-1, LambdaWebJson.integer(request, "fraction"));
        assertEquals(Integer.MAX_VALUE, LambdaWebJson.integer(request, "large"));
        assertNull(LambdaWebJson.integer(request, "null"));
        assertNull(LambdaWebJson.integer(request, "missing"));
    }

    @Test
    void integerKeepsSerializationErrorForNonNumbers() {
        AwsException exception = assertThrows(AwsException.class,
                () -> LambdaWebJson.integer(json("{\"count\":\"1\"}"), "count"));

        assertEquals("SerializationException", exception.getErrorCode());
        assertEquals(400, exception.getHttpStatus());
        assertEquals("STRING_VALUE can not be converted to an Integer", exception.getMessage());
    }
}
