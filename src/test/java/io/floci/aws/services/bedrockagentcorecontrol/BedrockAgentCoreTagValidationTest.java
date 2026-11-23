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

    @Test
    void directMapNullMembersKeepTheirErrors() {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put(null, "value");
        AwsException keyError = assertThrows(AwsException.class,
                () -> BedrockAgentCoreTagValidation.validateTagMap(tags));
        assertEquals("ValidationException", keyError.getErrorCode());
        assertEquals(400, keyError.getHttpStatus());
        assertEquals("1 validation error detected: Value null at 'tags.key' failed to satisfy constraint: "
                + "Member must not be null", keyError.getMessage());

        tags.clear();
        tags.put("key", null);
        AwsException valueError = assertThrows(AwsException.class,
                () -> BedrockAgentCoreTagValidation.validateTagMap(tags));
        assertEquals("ValidationException", valueError.getErrorCode());
        assertEquals(400, valueError.getHttpStatus());
        assertEquals("tag value must be a string", valueError.getMessage());

        AwsException mapError = assertThrows(AwsException.class,
                () -> BedrockAgentCoreTagValidation.validateTagMap(null));
        assertEquals("ValidationException", mapError.getErrorCode());
        assertEquals(400, mapError.getHttpStatus());
        assertEquals("tags must be an object with at most 50 entries", mapError.getMessage());
    }

    private static Stream<Arguments> modeledTagFailures() {
        String prefix = "1 validation error detected: ";
        String keyRules = "Value at 'tags' failed to satisfy constraint: Map keys must satisfy constraint: ["
                + "Member must have length less than or equal to 128, "
                + "Member must have length greater than or equal to 1, "
                + "Member must satisfy regular expression pattern: "
                + BedrockAgentCoreTagFixtures.EXPECTED_TAG_PATTERN + "]";
        String valueRules = "Value at 'tags' failed to satisfy constraint: Map value must satisfy constraint: ["
                + "Member must have length less than or equal to 256, "
                + "Member must satisfy regular expression pattern: "
                + BedrockAgentCoreTagFixtures.EXPECTED_TAG_PATTERN + "]";
        Map<String, String> tooMany = new LinkedHashMap<>();
        for (int i = 0; i < 51; i++) {
            tooMany.put("key-" + i, "value");
        }
        return Stream.of(
                Arguments.of(Map.of("", "value"), prefix + keyRules),
                Arguments.of(Map.of("k".repeat(129), "value"), prefix + keyRules),
                Arguments.of(Map.of("bad?key", "value"), prefix + keyRules),
                Arguments.of(Map.of("key", "v".repeat(257)), prefix + valueRules),
                Arguments.of(Map.of("key", "bad?value"), prefix + valueRules),
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
