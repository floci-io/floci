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

    private static final String PROTOBUF_REQUIRED_EMAIL =
            "syntax = \"proto2\";\n"
                    + "package x;\n"
                    + "message User {\n"
                    + "  required string name = 1;\n"
                    + "  required string email = 2;\n"
                    + "}\n";

    private static final String PROTOBUF_REMOVE_REQUIRED_EMAIL =
            "syntax = \"proto2\";\n"
                    + "package x;\n"
                    + "message User {\n"
                    + "  required string name = 1;\n"
                    + "}\n";

    private static final String PROTOBUF_OPTIONAL_EMAIL =
            "syntax = \"proto2\";\n"
                    + "package x;\n"
                    + "message User {\n"
                    + "  required string name = 1;\n"
                    + "  optional string email = 2;\n"
                    + "}\n";

    private static final String PROTOBUF_ADD_REQUIRED_PHONE =
            "syntax = \"proto2\";\n"
                    + "package x;\n"
                    + "message User {\n"
                    + "  required string name = 1;\n"
                    + "  optional string email = 2;\n"
                    + "  required string phone = 3;\n"
                    + "}\n";

    private static final String JSON_V1 =
            "{\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"type\":\"object\","
                    + "\"properties\":{\"id\":{\"type\":\"integer\"}},\"required\":[\"id\"]}";

    private static final String JSON_ADD_OPTIONAL =
            "{\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"type\":\"object\","
                    + "\"properties\":{\"id\":{\"type\":\"integer\"},"
                    + "\"email\":{\"type\":\"string\"}},\"required\":[\"id\"]}";

    private static final String JSON_ADD_REQUIRED =
            "{\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"type\":\"object\","
                    + "\"properties\":{\"id\":{\"type\":\"integer\"},"
                    + "\"email\":{\"type\":\"string\"}},\"required\":[\"id\",\"email\"]}";

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
    void protobufBackwardRejectsRemovingRequiredField() {
        var r = SchemaCompatibilityChecker.check(
                "BACKWARD",
                List.of(PROTOBUF_REQUIRED_EMAIL),
                PROTOBUF_REMOVE_REQUIRED_EMAIL,
                "PROTOBUF");
        assertFalse(r.compatible());
        assertNotNull(r.reason());
    }

    @Test
    void protobufForwardRejectsAddingRequiredField() {
        var r = SchemaCompatibilityChecker.check(
                "FORWARD",
                List.of(PROTOBUF_OPTIONAL_EMAIL),
                PROTOBUF_ADD_REQUIRED_PHONE,
                "PROTOBUF");
        assertFalse(r.compatible());
        assertNotNull(r.reason());
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

    // JSON goes through apicurio's JsonSchemaCompatibilityChecker and JsonSchemaContentValidator,
    // which are backed by the org.everit.json.schema library. These cases are what prove that
    // library actually loads: the AVRO and PROTOBUF paths never touch it, so before this the JSON
    // branch of checkerFor/validatorFor was entirely uncovered.

    @Test
    void jsonBackwardAcceptsAnUnchangedSchema() {
        var r = SchemaCompatibilityChecker.check("BACKWARD", List.of(JSON_V1), JSON_V1, "JSON");
        assertTrue(r.compatible(), () -> "expected compatible, got: " + r.reason());
    }

    /**
     * Apicurio treats adding even an optional property as narrowing, because data that carried that
     * key with another type validated before and no longer does. Asserted as observed rather than
     * assumed: it is the opposite of the AVRO case above.
     */
    @Test
    void jsonBackwardTreatsANewOptionalPropertyAsNarrowing() {
        var r = SchemaCompatibilityChecker.check("BACKWARD", List.of(JSON_V1), JSON_ADD_OPTIONAL, "JSON");
        assertFalse(r.compatible());
        assertNotNull(r.reason());
    }

    @Test
    void jsonBackwardRejectsAddingARequiredProperty() {
        var r = SchemaCompatibilityChecker.check("BACKWARD", List.of(JSON_V1), JSON_ADD_REQUIRED, "JSON");
        assertFalse(r.compatible());
        assertNotNull(r.reason());
    }

    @Test
    void jsonFirstVersionIsCompatible() {
        var r = SchemaCompatibilityChecker.check("BACKWARD", List.of(), JSON_V1, "JSON");
        assertTrue(r.compatible(), () -> "expected compatible, got: " + r.reason());
    }
}
