package io.floci.aws.services.bedrockagentcorecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
        assertInvalid(tags, "1 validation error detected: Value at 'tags' failed to satisfy constraint: "
                + "Member must have length less than or equal to 50");
    }

    @Test
    void keepsMalformedTagTypeErrors() {
        assertInvalid(MAPPER.createObjectNode().put("key", 123), "tag value must be a string");
        assertInvalid(MAPPER.createArrayNode(), "tags must be an object with at most 50 entries");
    }

    @ParameterizedTest
    @MethodSource("modeledTagFailures")
    void createTagRefusalsUseSmithyMessages(Map<String, String> tags, String message) {
        assertInvalid(MAPPER.valueToTree(tags), message);
    }

    @ParameterizedTest
    @MethodSource("modeledTagFailures")
    void tagResourceRefusalsUseSmithyMessages(Map<String, String> tags, String message) {
        AwsException error = assertThrows(AwsException.class,
                () -> BedrockAgentCoreTagValidation.validateTagMap(tags));
        assertEquals("ValidationException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertEquals(message, error.getMessage());
    }

    @Test
    void preservesEmptyTagsAndModeledBoundariesOnBothPaths() {
        Map<String, String> tags = new LinkedHashMap<>();
        assertDoesNotThrow(() -> BedrockAgentCoreTagValidation.validateTags(MAPPER.valueToTree(tags)));
        assertDoesNotThrow(() -> BedrockAgentCoreTagValidation.validateTagMap(tags));
        tags.put(" ", "");
        tags.put("k".repeat(128), "v".repeat(256));
        for (int i = 0; i < 48; i++) {
            tags.put("key-" + i, "value-" + i);
        }
        assertDoesNotThrow(() -> BedrockAgentCoreTagValidation.validateTags(MAPPER.valueToTree(tags)));
        assertDoesNotThrow(() -> BedrockAgentCoreTagValidation.validateTagMap(tags));
        assertDoesNotThrow(() -> BedrockAgentCoreTagValidation.validateTags(null));
    }

    private static Stream<Arguments> modeledTagFailures() {
        String prefix = "1 validation error detected: ";
        String keyPattern = "Member must satisfy regular expression pattern: " + BedrockAgentCoreTagFixtures.EXPECTED_TAG_PATTERN;
        Map<String, String> tooMany = new LinkedHashMap<>();
        for (int i = 0; i < 51; i++) {
            tooMany.put("key-" + i, "value");
        }
        return Stream.of(
                Arguments.of(Map.of("", "value"), prefix + "Value '' at 'tags.key' failed to satisfy constraint: "
                        + "Member must have length greater than or equal to 1"),
                Arguments.of(Map.of("k".repeat(129), "value"), prefix + "Value '" + "k".repeat(129)
                        + "' at 'tags.key' failed to satisfy constraint: "
                        + "Member must have length less than or equal to 128"),
                Arguments.of(Map.of("bad?key", "value"), prefix
                        + "Value 'bad?key' at 'tags.key' failed to satisfy constraint: " + keyPattern),
                Arguments.of(Map.of("key", "v".repeat(257)), prefix + "Value '" + "v".repeat(257)
                        + "' at 'tags.value' failed to satisfy constraint: "
                        + "Member must have length less than or equal to 256"),
                Arguments.of(Map.of("key", "bad?value"), prefix
                        + "Value 'bad?value' at 'tags.value' failed to satisfy constraint: " + keyPattern),
                Arguments.of(tooMany, prefix + "Value at 'tags' failed to satisfy constraint: "
                        + "Member must have length less than or equal to 50"));
    }

    private static void assertInvalid(JsonNode tags, String message) {
        AwsException error = assertThrows(AwsException.class,
                () -> BedrockAgentCoreTagValidation.validateTags(tags));
        assertEquals("ValidationException", error.getErrorCode());
        assertEquals(message, error.getMessage());
    }
}
