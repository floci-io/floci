package io.github.hectorvent.floci.services.glue.schemaregistry;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        SchemaCompatibilityChecker.Result r = SchemaCompatibilityChecker.check("NONE", List.of(AVRO_V1), AVRO_ADD_REQUIRED, "AVRO");
        assertTrue(r.compatible());
    }

    @Test
    void disabledShortCircuits() {
        SchemaCompatibilityChecker.Result r = SchemaCompatibilityChecker.check("DISABLED", List.of(AVRO_V1), AVRO_ADD_REQUIRED, "AVRO");
        assertTrue(r.compatible());
    }

    @Test
    void emptyExistingIsCompatible() {
        SchemaCompatibilityChecker.Result r = SchemaCompatibilityChecker.check("BACKWARD", List.of(), AVRO_V1, "AVRO");
        assertTrue(r.compatible());
    }

    @Test
    void backwardAcceptsAddOptionalField() {
        SchemaCompatibilityChecker.Result r = SchemaCompatibilityChecker.check("BACKWARD", List.of(AVRO_V1), AVRO_ADD_OPTIONAL, "AVRO");
        assertTrue(r.compatible(), () -> "expected compatible, got: " + r.reason());
    }

    @Test
    void backwardRejectsAddRequiredField() {
        SchemaCompatibilityChecker.Result r = SchemaCompatibilityChecker.check("BACKWARD", List.of(AVRO_V1), AVRO_ADD_REQUIRED, "AVRO");
        assertFalse(r.compatible());
        assertNotNull(r.reason());
    }

    @Test
    void backwardAllRejectsRequiredAddedAcrossAnyPriorVersion() {
        SchemaCompatibilityChecker.Result r = SchemaCompatibilityChecker.check("BACKWARD_ALL",
                List.of(AVRO_V1, AVRO_ADD_OPTIONAL), AVRO_ADD_REQUIRED, "AVRO");
        assertFalse(r.compatible());
    }

    @Test
    void forwardAcceptsAddRequired() {
        // FORWARD: latest reader can read new (writer) data. Adding a required field
        // means new writers produce extra fields that old readers don't know about,
        // which old readers ignore — so it is FORWARD-compatible.
        SchemaCompatibilityChecker.Result r = SchemaCompatibilityChecker.check("FORWARD", List.of(AVRO_V1), AVRO_ADD_REQUIRED, "AVRO");
        assertTrue(r.compatible(), () -> "expected compatible, got: " + r.reason());
    }

    @Test
    void protobufBackwardRejectsRemovingRequiredField() {
        SchemaCompatibilityChecker.Result r = SchemaCompatibilityChecker.check(
                "BACKWARD",
                List.of(PROTOBUF_REQUIRED_EMAIL),
                PROTOBUF_REMOVE_REQUIRED_EMAIL,
                "PROTOBUF");
        assertFalse(r.compatible());
        assertNotNull(r.reason());
    }

    @Test
    void protobufForwardRejectsAddingRequiredField() {
        SchemaCompatibilityChecker.Result r = SchemaCompatibilityChecker.check(
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
    void canonicalizeIgnoresAvroCustomAttributes() {
        String withCustomAttributes = "{\"type\":\"record\",\"name\":\"User\",\"namespace\":\"x\","
                + "\"x-record\":{\"any\":true},\"fields\":[{\"name\":\"id\",\"type\":\"long\","
                + "\"x-field\":\"value\"}]}";
        assertEquals(SchemaCompatibilityChecker.canonicalize(AVRO_V1, "AVRO"),
                SchemaCompatibilityChecker.canonicalize(withCustomAttributes, "AVRO"));
    }

    @Test
    void canonicalizeIgnoresAttributesThatAreNotStandardForRecords() {
        String base = SchemaCompatibilityChecker.canonicalize(AVRO_V1, "AVRO");
        for (String attribute : List.of("\"order\":\"descending\"", "\"default\":\"unused\"",
                "\"symbols\":[\"IGNORED\"]", "\"size\":8")) {
            String withAttribute = AVRO_V1.replace("\"fields\"", attribute + ",\"fields\"");
            assertNull(SchemaCompatibilityChecker.validateDefinition(withAttribute, "AVRO"), attribute);
            assertEquals(base, SchemaCompatibilityChecker.canonicalize(withAttribute, "AVRO"), attribute);
        }
    }

    @Test
    void canonicalizePreservesTypeSpecificAvroProperties() {
        String fieldOrder = AVRO_V1.replace("\"type\":\"long\"",
                "\"type\":\"long\",\"order\":\"descending\"");
        String fieldDefault = AVRO_V1.replace("\"type\":\"long\"",
                "\"type\":\"long\",\"default\":1");
        assertNotEquals(SchemaCompatibilityChecker.canonicalize(AVRO_V1, "AVRO"),
                SchemaCompatibilityChecker.canonicalize(fieldOrder, "AVRO"));
        assertNotEquals(SchemaCompatibilityChecker.canonicalize(AVRO_V1, "AVRO"),
                SchemaCompatibilityChecker.canonicalize(fieldDefault, "AVRO"));

        String enumSchema = "{\"type\":\"enum\",\"name\":\"Status\",\"symbols\":[\"OPEN\",\"CLOSED\"]}";
        String fixedSchema = "{\"type\":\"fixed\",\"name\":\"Id\",\"size\":8}";
        assertNotEquals(SchemaCompatibilityChecker.canonicalize(enumSchema, "AVRO"),
                SchemaCompatibilityChecker.canonicalize(enumSchema.replace("CLOSED", "PENDING"), "AVRO"));
        assertNotEquals(SchemaCompatibilityChecker.canonicalize(fixedSchema, "AVRO"),
                SchemaCompatibilityChecker.canonicalize(fixedSchema.replace("\"size\":8", "\"size\":16"), "AVRO"));
    }

    @Test
    void canonicalizePreservesExistingLogicalAnnotationsOnComplexTypes() {
        for (String schema : List.of(AVRO_V1,
                "{\"type\":\"array\",\"items\":\"string\"}",
                "{\"type\":\"map\",\"values\":\"string\"}",
                "{\"type\":\"enum\",\"name\":\"Status\",\"symbols\":[\"OPEN\",\"CLOSED\"]}")) {
            String annotated = schema.replaceFirst("\\{", "{\"logicalType\":\"custom\",");
            assertNotEquals(SchemaCompatibilityChecker.canonicalize(schema, "AVRO"),
                    SchemaCompatibilityChecker.canonicalize(annotated, "AVRO"), schema);
        }

        String decimal = "{\"type\":\"bytes\",\"logicalType\":\"decimal\",\"precision\":4,\"scale\":2}";
        assertNotEquals(SchemaCompatibilityChecker.canonicalize(decimal, "AVRO"),
                SchemaCompatibilityChecker.canonicalize(decimal.replace("\"precision\":4", "\"precision\":5"), "AVRO"));
        assertNotEquals(SchemaCompatibilityChecker.canonicalize(decimal, "AVRO"),
                SchemaCompatibilityChecker.canonicalize(decimal.replace("\"scale\":2", "\"scale\":1"), "AVRO"));
    }

    @Test
    void canonicalizePreservesExistingArrayAndMapDefaults() {
        String array = "{\"type\":\"array\",\"items\":\"string\"}";
        String map = "{\"type\":\"map\",\"values\":\"string\"}";
        assertNotEquals(SchemaCompatibilityChecker.canonicalize(array, "AVRO"),
                SchemaCompatibilityChecker.canonicalize(array.replace("\"items\"",
                        "\"default\":[],\"items\""), "AVRO"));
        assertNotEquals(SchemaCompatibilityChecker.canonicalize(map, "AVRO"),
                SchemaCompatibilityChecker.canonicalize(map.replace("\"values\"",
                        "\"default\":{},\"values\""), "AVRO"));
    }

    @Test
    void canonicalizeIgnoresNestedAvroCustomAttributesButPreservesDefaults() {
        String base = "{\"type\":\"record\",\"name\":\"Outer\",\"fields\":[{\"name\":\"inner\","
                + "\"type\":{\"type\":\"record\",\"name\":\"Inner\",\"fields\":[{\"name\":\"value\","
                + "\"type\":\"string\"}]},\"default\":{\"value\":\"one\"}}]}";
        String withCustom = base.replace("\"name\":\"Inner\"", "\"name\":\"Inner\",\"x-record\":true")
                .replace("\"name\":\"value\"", "\"name\":\"value\",\"x-field\":42");
        String changedDefault = base.replace("\"value\":\"one\"", "\"value\":\"two\"");

        assertEquals(SchemaCompatibilityChecker.canonicalize(base, "AVRO"),
                SchemaCompatibilityChecker.canonicalize(withCustom, "AVRO"));
        assertNotEquals(SchemaCompatibilityChecker.canonicalize(base, "AVRO"),
                SchemaCompatibilityChecker.canonicalize(changedDefault, "AVRO"));
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
        SchemaCompatibilityChecker.Result r = SchemaCompatibilityChecker.check("BACKWARD", List.of(JSON_V1), JSON_V1, "JSON");
        assertTrue(r.compatible(), () -> "expected compatible, got: " + r.reason());
    }

    /**
     * Apicurio treats adding even an optional property as narrowing, because data that carried that
     * key with another type validated before and no longer does. Asserted as observed rather than
     * assumed: it is the opposite of the AVRO case above.
     */
    @Test
    void jsonBackwardTreatsANewOptionalPropertyAsNarrowing() {
        SchemaCompatibilityChecker.Result r = SchemaCompatibilityChecker.check("BACKWARD", List.of(JSON_V1), JSON_ADD_OPTIONAL, "JSON");
        assertFalse(r.compatible());
        assertNotNull(r.reason());
    }

    @Test
    void jsonBackwardRejectsAddingARequiredProperty() {
        SchemaCompatibilityChecker.Result r = SchemaCompatibilityChecker.check("BACKWARD", List.of(JSON_V1), JSON_ADD_REQUIRED, "JSON");
        assertFalse(r.compatible());
        assertNotNull(r.reason());
    }

    @Test
    void jsonFirstVersionIsCompatible() {
        SchemaCompatibilityChecker.Result r = SchemaCompatibilityChecker.check("BACKWARD", List.of(), JSON_V1, "JSON");
        assertTrue(r.compatible(), () -> "expected compatible, got: " + r.reason());
    }
}
