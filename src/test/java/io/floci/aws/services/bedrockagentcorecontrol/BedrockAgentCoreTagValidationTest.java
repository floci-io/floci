package io.floci.aws.services.bedrockagentcorecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BedrockAgentCoreTagValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void acceptsBoundaryLengthsAndFiftyEntries() {
        ObjectNode tags = MAPPER.createObjectNode();
        tags.put(" ", "");
        tags.put("k".repeat(128), "v".repeat(256));
        for (int i = 0; i < 48; i++) {
            tags.put("key-" + i, "value-" + i);
        }

        BedrockAgentCoreTagValidation.validateTags(tags);
        tags.put("extra", "value");
        assertInvalid(tags, "tags must be an object with at most 50 entries");
    }

    @Test
    void keepsAgentCoreKeyAndValueErrors() {
        for (String key : List.of("", "k".repeat(129), "bad?key")) {
            assertInvalid(MAPPER.createObjectNode().put(key, "value"),
                    "tag key does not satisfy AgentCore constraints");
        }
        for (String value : List.of("v".repeat(257), "bad?value")) {
            assertInvalid(MAPPER.createObjectNode().put("key", value),
                    "tag value does not satisfy AgentCore constraints");
        }
        assertInvalid(MAPPER.createObjectNode().put("key", 123), "tag value must be a string");
        assertInvalid(MAPPER.createArrayNode(), "tags must be an object with at most 50 entries");
    }

    private static void assertInvalid(JsonNode tags, String message) {
        AwsException error = assertThrows(AwsException.class,
                () -> BedrockAgentCoreTagValidation.validateTags(tags));
        assertEquals("ValidationException", error.getErrorCode());
        assertEquals(message, error.getMessage());
    }
}
