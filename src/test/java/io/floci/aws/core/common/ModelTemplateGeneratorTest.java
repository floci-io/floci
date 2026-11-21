package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelTemplateGeneratorTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "true", "null", "{\"type\":[\"string\",\"null\"]}",
            "{\"type\":[\"object\",\"null\"],\"properties\":{}}",
            "{\"type\":null,\"items\":{\"type\":\"string\"}}",
            "{\"type\":\"\",\"properties\":{}}",
            "{\"allOf\":[{\"type\":\"object\"}]}", "{\"type\":\"string\",\"enum\":[\"a\"]}",
            "{\"$ref\":\"https://example.invalid/schema\"}", "{\"$ref\":\"#/missing\"}",
            "{\"type\":\"array\"}", "{\"type\":\"array\",\"items\":[{\"type\":\"string\"}]}",
            "{\"type\":\"object\",\"properties\":[]}",
            "{\"type\":\"object\",\"properties\":{\"#set\":{\"type\":\"string\"}}}",
            "{\"definitions\":{\"Loop\":{\"$ref\":\"#/definitions/Loop\"}},\"$ref\":\"#/definitions/Loop\"}"
    })
    void explicitlyRefusesUnsupportedSchemas(String schema) {
        AwsException error = assertThrows(AwsException.class,
                () -> ModelTemplateGenerator.generate(schema, "application/json", true));
        assertEquals(501, error.getHttpStatus());
    }

    @Test
    void malformedSchemaHasAClientError() {
        assertEquals(400, assertThrows(AwsException.class,
                () -> ModelTemplateGenerator.generate("{", "application/json", true)).getHttpStatus());
        assertEquals(400, assertThrows(AwsException.class,
                () -> ModelTemplateGenerator.generate(null, "application/json", false)).getHttpStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"type\":\"string\"} garbage", "{\"type\":\"string\"} {\"type\":\"number\"}"})
    void rejectsTrailingTokensInEitherTemplateMode(String schema) {
        for (boolean mapping : new boolean[]{false, true}) {
            AwsException error = assertThrows(AwsException.class,
                    () -> ModelTemplateGenerator.generate(schema, "application/json", mapping));
            assertEquals(400, error.getHttpStatus());
            assertEquals("The model schema is not valid JSON.", error.getMessage());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void acceptsWhitespaceAfterTheSchema(boolean mapping) {
        assertTrue(ModelTemplateGenerator.generate("{\"type\":\"string\"} \n\t", "application/json", mapping)
                .startsWith("#set($inputRoot"));
    }

    @Test
    void refusesNonJsonContent() {
        assertEquals(501, assertThrows(AwsException.class,
                () -> ModelTemplateGenerator.generate("{\"type\":\"string\"}", "application/xml", false))
                .getHttpStatus());
    }

    @Test
    void boundsDeepAndLargeSchemasBeforeUnboundedExpansion() {
        String schema = "{\"type\":\"string\"}";
        for (int i = 0; i < 35; i++) {
            schema = "{\"type\":\"array\",\"items\":" + schema + "}";
        }
        String deep = schema;
        assertEquals(501, assertThrows(AwsException.class,
                () -> ModelTemplateGenerator.generate(deep, "application/json", true)).getHttpStatus());
        assertEquals(501, assertThrows(AwsException.class,
                () -> ModelTemplateGenerator.generate("x".repeat(1_048_577), "application/json", true))
                .getHttpStatus());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void boundsGeneratedOutputIndependentlyOfSchemaTraversal(boolean mapping) {
        ObjectNode schema = new ObjectMapper().createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        String name = "a".repeat(8_000);
        for (int i = 0; i < 40; i++) {
            properties.putObject(name + i).put("type", "string");
        }
        AwsException error = assertThrows(AwsException.class,
                () -> ModelTemplateGenerator.generate(schema.toString(), "application/json", mapping));
        assertEquals(501, error.getHttpStatus());
        assertTrue(error.getMessage().contains("output limit"));
    }

    @Test
    void referenceExpansionIsBoundedAndRepeatedFiniteReferencesWork() {
        String schema = """
                {"definitions":{"S":{"type":"string"}},"type":"object","properties":{
                "left":{"$ref":"#/definitions/S"},"right":{"$ref":"#/definitions/S"}}}
                """;
        assertTrue(ModelTemplateGenerator.generate(schema, "application/json; charset=utf-8", true)
                .contains(".get('right')"));
        String many = "{\"type\":\"object\",\"properties\":{";
        StringBuilder properties = new StringBuilder(many);
        for (int i = 0; i < 4_100; i++) {
            if (i > 0) {
                properties.append(',');
            }
            properties.append('"').append(i).append("\":{\"type\":\"string\"}");
        }
        properties.append("}}");
        assertEquals(501, assertThrows(AwsException.class,
                () -> ModelTemplateGenerator.generate(properties.toString(), "application/json", true))
                .getHttpStatus());
    }
}
