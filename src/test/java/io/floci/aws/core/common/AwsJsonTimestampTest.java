package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AwsJsonTimestampTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void epochSecondsRoundsNumericValuesToMilliseconds() throws Exception {
        JsonNode positive = mapper.readTree("{\"time\":1761000000.1256}");
        JsonNode negative = mapper.readTree("{\"time\":-0.0016}");

        assertEquals(Instant.ofEpochMilli(1_761_000_000_126L),
                AwsJsonTimestamp.epochSeconds(positive, "time", "InvalidRequestException"));
        assertEquals(Instant.ofEpochMilli(-2),
                AwsJsonTimestamp.epochSeconds(negative, "time", "ValidationException"));
    }

    @Test
    void missingAndNullMembersRemainAbsent() {
        ObjectNode request = mapper.createObjectNode();
        assertNull(AwsJsonTimestamp.epochSeconds(request, "time", "ValidationException"));

        request.putNull("time");
        assertNull(AwsJsonTimestamp.epochSeconds(request, "time", "ValidationException"));
    }

    @Test
    void invalidValuesUseTheRequestedAwsErrorCode() throws Exception {
        JsonNode wrongType = mapper.readTree("{\"time\":\"invalid\"}");
        JsonNode outOfRange = mapper.readTree("{\"time\":123456789012345678901234567890}");

        AwsException emrError = assertThrows(AwsException.class,
                () -> AwsJsonTimestamp.epochSeconds(wrongType, "time", "InvalidRequestException"));
        assertEquals("InvalidRequestException", emrError.getErrorCode());
        assertEquals(400, emrError.getHttpStatus());

        AwsException redshiftError = assertThrows(AwsException.class,
                () -> AwsJsonTimestamp.epochSeconds(outOfRange, "time", "ValidationException"));
        assertEquals("ValidationException", redshiftError.getErrorCode());
        assertEquals(400, redshiftError.getHttpStatus());
    }
}
