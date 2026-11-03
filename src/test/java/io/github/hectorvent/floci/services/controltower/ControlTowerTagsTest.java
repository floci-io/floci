package io.github.hectorvent.floci.services.controltower;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ControlTowerTagsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void fromRequest_absentTags_returnsEmpty() {
        assertEquals(Map.of(), ControlTowerTags.fromRequest(null));
    }

    @Test
    void fromRequest_stringValues_returnsThem() throws Exception {
        assertEquals(Map.of("env", "test"), ControlTowerTags.fromRequest(mapper.readTree("{\"env\":\"test\"}")));
    }

    @Test
    void fromRequest_nonStringValue_isRejected() throws Exception {
        JsonNode tags = mapper.readTree("{\"env\":123}");
        assertValidation(() -> ControlTowerTags.fromRequest(tags));
    }

    @Test
    void validate_moreThan200Entries_isRejected() {
        Map<String, String> tags = new HashMap<>();
        for (int i = 0; i <= 200; i++) {
            tags.put("key" + i, "value");
        }
        assertValidation(() -> ControlTowerTags.validate(tags));
    }

    @Test
    void validate_blankOrLongKeyOrLongValue_isRejected() {
        assertValidation(() -> ControlTowerTags.validate(Map.of(" ", "value")));
        assertValidation(() -> ControlTowerTags.validate(Map.of("k".repeat(129), "value")));
        assertValidation(() -> ControlTowerTags.validate(Map.of("key", "v".repeat(257))));
    }

    private static void assertValidation(Runnable call) {
        AwsException e = assertThrows(AwsException.class, call::run);
        assertEquals("ValidationException", e.getErrorCode());
    }
}
