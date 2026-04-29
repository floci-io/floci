package io.github.hectorvent.floci.services.glue.schemaregistry;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaCompatibilityCheckerTest {

    private static final String AVRO_V1 =
            "{\"type\":\"record\",\"name\":\"User\",\"namespace\":\"x\","
                    + "\"fields\":[{\"name\":\"id\",\"type\":\"long\"}]}";

    private static final String AVRO_ADD_OPTIONAL =
            "{\"type\":\"record\",\"name\":\"User\",\"namespace\":\"x\","
                    + "\"fields\":[{\"name\":\"id\",\"type\":\"long\"},"
                    + "{\"name\":\"email\",\"type\":[\"null\",\"string\"],\"default\":null}]}";

    private static final String AVRO_ADD_REQUIRED =
            "{\"type\":\"record\",\"name\":\"User\",\"namespace\":\"x\","
                    + "\"fields\":[{\"name\":\"id\",\"type\":\"long\"},"
                    + "{\"name\":\"email\",\"type\":\"string\"}]}";

    @Test
    void noneAlwaysCompatible() {
        var r = SchemaCompatibilityChecker.check("NONE", List.of(AVRO_V1), AVRO_ADD_REQUIRED, "AVRO");
        assertTrue(r.compatible());
    }

    @Test
    void disabledShortCircuits() {
        var r = SchemaCompatibilityChecker.check("DISABLED", List.of(AVRO_V1), AVRO_ADD_REQUIRED, "AVRO");
        assertTrue(r.compatible());
    }

    @Test
    void emptyExistingIsCompatible() {
        var r = SchemaCompatibilityChecker.check("BACKWARD", List.of(), AVRO_V1, "AVRO");
        assertTrue(r.compatible());
    }

    @Test
    void backwardAcceptsAddOptionalField() {
        var r = SchemaCompatibilityChecker.check("BACKWARD", List.of(AVRO_V1), AVRO_ADD_OPTIONAL, "AVRO");
        assertTrue(r.compatible(), () -> "expected compatible, got: " + r.reason());
    }

    @Test
    void backwardRejectsAddRequiredField() {
        var r = SchemaCompatibilityChecker.check("BACKWARD", List.of(AVRO_V1), AVRO_ADD_REQUIRED, "AVRO");
        assertFalse(r.compatible());
        assertNotNull(r.reason());
    }

    @Test
    void backwardAllRejectsRequiredAddedAcrossAnyPriorVersion() {
        var r = SchemaCompatibilityChecker.check("BACKWARD_ALL",
                List.of(AVRO_V1, AVRO_ADD_OPTIONAL), AVRO_ADD_REQUIRED, "AVRO");
        assertFalse(r.compatible());
    }

    @Test
    void forwardAcceptsAddRequired() {
        // FORWARD: latest reader can read new (writer) data. Adding a required field
        // means new writers produce extra fields that old readers don't know about,
        // which old readers ignore — so it is FORWARD-compatible.
        var r = SchemaCompatibilityChecker.check("FORWARD", List.of(AVRO_V1), AVRO_ADD_REQUIRED, "AVRO");
        assertTrue(r.compatible(), () -> "expected compatible, got: " + r.reason());
    }

    @Test
    void unknownModeThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                SchemaCompatibilityChecker.check("WAT", List.of(AVRO_V1), AVRO_ADD_OPTIONAL, "AVRO"));
    }

    @Test
    void unknownDataFormatThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                SchemaCompatibilityChecker.check("BACKWARD", List.of(AVRO_V1), AVRO_ADD_OPTIONAL, "BOGUS"));
    }

    @Test
    void canonicalizeNormalizesAvroWhitespace() {
        String spaced = AVRO_V1.replace(",", " , ").replace(":", " : ");
        String c1 = SchemaCompatibilityChecker.canonicalize(AVRO_V1, "AVRO");
        String c2 = SchemaCompatibilityChecker.canonicalize(spaced, "AVRO");
        assertEquals(c1, c2);
    }

    @Test
    void validateDefinitionAcceptsValidAvro() {
        assertNull(SchemaCompatibilityChecker.validateDefinition(AVRO_V1, "AVRO"));
    }

    @Test
    void validateDefinitionRejectsInvalidAvro() {
        String error = SchemaCompatibilityChecker.validateDefinition("{garbage", "AVRO");
        assertNotNull(error);
    }
}
