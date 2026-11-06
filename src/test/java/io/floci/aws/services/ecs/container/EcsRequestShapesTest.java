package io.floci.aws.services.ecs.container;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A container's {@code linuxParameters} walked against the vendored ECS model, with the
 * InvalidParameterException messages measured with raw RegisterTaskDefinition requests
 * (eu-west-1, 2026-10-18).
 */
class EcsRequestShapesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String STRUCTURE = "Start of structure or map found where not expected";

    @Test
    void aStructureTakesOnlyAnObject() {
        assertRejected("\"x\"", "Unexpected field type");
        assertRejected("{\"capabilities\":\"x\"}", "Unexpected field type");
        assertRejected("{\"devices\":[\"not-a-device\"]}", "Unexpected value type in payload");
        assertRejected("{\"tmpfs\":[\"not-a-mount\"]}", "Unexpected value type in payload");
        assertRejected("{\"devices\":[5]}", "Unexpected value type in payload");
        assertRejectedWithoutMessage("[]");
        assertRejectedWithoutMessage("{\"capabilities\":[]}");
    }

    @Test
    void aListTakesOnlyAnArray() {
        assertRejected("{\"devices\":\"x\"}", "Unexpected field type");
        assertRejected("{\"devices\":5}", "Unexpected field type");
        assertRejected("{\"capabilities\":{\"add\":\"SYS_PTRACE\"}}", "Unexpected field type");
        assertRejected("{\"devices\":{\"hostPath\":\"/dev/null\"}}", STRUCTURE);
    }

    @Test
    void aScalarNamesTheJsonTokenItGot() {
        assertRejected("{\"capabilities\":{\"add\":[5]}}", "NUMBER_VALUE cannot be converted to String");
        assertRejected("{\"capabilities\":{\"add\":[true]}}", "TRUE_VALUE cannot be converted to String");
        assertRejected("{\"capabilities\":{\"add\":[{\"a\":1}]}}", STRUCTURE);
        assertRejectedWithoutMessage("{\"capabilities\":{\"add\":[[\"x\"]]}}");
        assertRejected("{\"devices\":[{\"hostPath\":true}]}", "TRUE_VALUE cannot be converted to String");
        assertRejected("{\"devices\":[{\"hostPath\":\"/dev/null\",\"permissions\":[5]}]}",
                "NUMBER_VALUE cannot be converted to String");
        assertRejected("{\"tmpfs\":[{\"containerPath\":\"/t\",\"size\":1,\"mountOptions\":[5]}]}",
                "NUMBER_VALUE cannot be converted to String");
        assertRejected("{\"sharedMemorySize\":\"64\"}", "STRING_VALUE cannot be converted to Integer");
        assertRejected("{\"sharedMemorySize\":true}", "TRUE_VALUE cannot be converted to Integer");
        assertRejected("{\"sharedMemorySize\":false}", "FALSE_VALUE cannot be converted to Integer");
        assertRejected("{\"sharedMemorySize\":{}}", STRUCTURE);
        assertRejectedWithoutMessage("{\"sharedMemorySize\":[]}");
        assertRejected("{\"initProcessEnabled\":1}", "NUMBER_VALUE cannot be converted to Boolean");
        assertRejected("{\"initProcessEnabled\":{}}", STRUCTURE);
        assertRejectedWithoutMessage("{\"initProcessEnabled\":[]}");
    }

    @Test
    void aBooleanReadsTrueOrFalseTextInAnyCase() {
        assertAccepted("{\"initProcessEnabled\":\"true\"}");
        assertAccepted("{\"initProcessEnabled\":\"False\"}");
        assertRejected("{\"initProcessEnabled\":\"abc\"}", "Unexpected token received from parser");
        assertRejected("{\"initProcessEnabled\":\"\"}", "Unexpected token received from parser");
    }

    @Test
    void nullsAndNumbersWithFractionsPass() {
        assertAccepted("{\"sharedMemorySize\":null,\"devices\":null,\"capabilities\":{\"add\":[null]}}");
        assertAccepted("{\"devices\":[null]}");
        assertAccepted("{\"sharedMemorySize\":1.9,\"maxSwap\":3000000000,\"swappiness\":10.5,"
                + "\"tmpfs\":[{\"containerPath\":\"/t\",\"size\":2.5}]}");
    }

    private static void assertRejected(String linuxParameters, String message) {
        AwsException e = assertThrows(AwsException.class,
                () -> EcsRequestShapes.check("LinuxParameters", parse(linuxParameters)), linuxParameters);
        assertEquals("InvalidParameterException", e.getErrorCode(), linuxParameters);
        assertEquals(message, e.getMessage(), linuxParameters);
    }

    private static void assertRejectedWithoutMessage(String linuxParameters) {
        AwsException e = assertThrows(AwsException.class,
                () -> EcsRequestShapes.check("LinuxParameters", parse(linuxParameters)), linuxParameters);
        assertEquals("InvalidParameterException", e.getErrorCode(), linuxParameters);
        assertNull(e.getMessage(), linuxParameters);
    }

    private static void assertAccepted(String linuxParameters) {
        assertDoesNotThrow(() -> EcsRequestShapes.check("LinuxParameters", parse(linuxParameters)), linuxParameters);
    }

    private static Object parse(String json) {
        try {
            return MAPPER.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(json, e);
        }
    }
}
