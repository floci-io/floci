package io.github.hectorvent.floci.services.appsync.graphql.execution.datasource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DynamoDbAttributeValuesTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String text) throws Exception {
        return mapper.readTree(text);
    }

    @Test
    void convertsScalarTypes() throws Exception {
        assertEquals("x", DynamoDbAttributeValues.toPlain(json("{\"S\":\"x\"}")));
        assertEquals(true, DynamoDbAttributeValues.toPlain(json("{\"BOOL\":true}")));
        assertNull(DynamoDbAttributeValues.toPlain(json("{\"NULL\":true}")));
        assertEquals("aGVsbG8=", DynamoDbAttributeValues.toPlain(json("{\"B\":\"aGVsbG8=\"}")));
    }

    @Test
    void normalisesNumbers() throws Exception {
        assertEquals(42L, DynamoDbAttributeValues.toPlain(json("{\"N\":\"42\"}")));
        assertEquals(42L, DynamoDbAttributeValues.toPlain(json("{\"N\":\"42.0\"}")));
        assertEquals(-7L, DynamoDbAttributeValues.toPlain(json("{\"N\":\"-7\"}")));
        assertEquals(new BigDecimal("1.5"), DynamoDbAttributeValues.toPlain(json("{\"N\":\"1.5\"}")));
        assertEquals(new BigDecimal("99999999999999999999"),
                DynamoDbAttributeValues.toPlain(json("{\"N\":\"99999999999999999999\"}")));
    }

    @Test
    void setsBecomeListsOfNormalisedValues() throws Exception {
        assertEquals(List.of("a", "b"), DynamoDbAttributeValues.toPlain(json("{\"SS\":[\"a\",\"b\"]}")));
        assertEquals(List.of(1L, new BigDecimal("2.5")), DynamoDbAttributeValues.toPlain(json("{\"NS\":[\"1\",\"2.5\"]}")));
        assertEquals(List.of("YQ==", "Yg=="), DynamoDbAttributeValues.toPlain(json("{\"BS\":[\"YQ==\",\"Yg==\"]}")));
    }

    @Test
    void convertsNestedListsAndMaps() throws Exception {
        Object value = DynamoDbAttributeValues.toPlain(json(
                "{\"M\":{\"tags\":{\"L\":[{\"S\":\"a\"},{\"N\":\"2\"}]},\"inner\":{\"M\":{\"ok\":{\"BOOL\":false}}}}}"));
        assertEquals(Map.of("tags", List.of("a", 2L), "inner", Map.of("ok", false)), value);
    }

    @Test
    void convertsWholeItemsAndNullItems() throws Exception {
        assertNull(DynamoDbAttributeValues.toPlainItem(null));
        Map<String, Object> item = DynamoDbAttributeValues.toPlainItem(json("{\"id\":{\"S\":\"1\"},\"n\":{\"N\":\"3\"}}"));
        assertEquals(Map.of("id", "1", "n", 3L), item);
    }

    @Test
    void unknownDescriptorsAreReturnedAsText() throws Exception {
        assertEquals("{\"XX\":\"?\"}", DynamoDbAttributeValues.toPlain(json("{\"XX\":\"?\"}")));
    }
}
