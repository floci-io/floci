package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DoubleNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonNumberCoercionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String value) {
        try {
            return MAPPER.readTree(value);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void truncatesFractionsTowardZero() {
        assertEquals(1, JsonNumberCoercion.truncateAndClampToInt(json("1.9")));
        assertEquals(-1, JsonNumberCoercion.truncateAndClampToInt(json("-1.9")));
        assertEquals(0, JsonNumberCoercion.truncateAndClampToInt(json("-0.9")));
    }

    @Test
    void preservesInRangeValuesAndClampsIntegerBounds() {
        assertEquals(Integer.MAX_VALUE,
                JsonNumberCoercion.truncateAndClampToInt(json("2147483647")));
        assertEquals(Integer.MIN_VALUE,
                JsonNumberCoercion.truncateAndClampToInt(json("-2147483648")));
        assertEquals(Integer.MAX_VALUE,
                JsonNumberCoercion.truncateAndClampToInt(json("2147483648")));
        assertEquals(Integer.MIN_VALUE,
                JsonNumberCoercion.truncateAndClampToInt(json("-2147483649")));
    }

    @Test
    void clampsHugeIntegerAndExponentValues() {
        assertEquals(Integer.MAX_VALUE,
                JsonNumberCoercion.truncateAndClampToInt(json("92233720368547758081234567890")));
        assertEquals(Integer.MIN_VALUE,
                JsonNumberCoercion.truncateAndClampToInt(json("-92233720368547758081234567890")));
        assertEquals(Integer.MAX_VALUE, JsonNumberCoercion.truncateAndClampToInt(json("1e400")));
        assertEquals(Integer.MIN_VALUE, JsonNumberCoercion.truncateAndClampToInt(json("-1e400")));
    }

    @Test
    void handlesNonFiniteNumericNodesWithoutDecimalConversion() {
        assertEquals(Integer.MAX_VALUE,
                JsonNumberCoercion.truncateAndClampToInt(DoubleNode.valueOf(Double.POSITIVE_INFINITY)));
        assertEquals(Integer.MIN_VALUE,
                JsonNumberCoercion.truncateAndClampToInt(DoubleNode.valueOf(Double.NEGATIVE_INFINITY)));
        assertEquals(0, JsonNumberCoercion.truncateAndClampToInt(DoubleNode.valueOf(Double.NaN)));
    }

    @Test
    void rejectsNonNumericNodes() {
        for (String input : List.of("\"1\"", "true", "null", "[]", "{}")) {
            assertThrows(IllegalArgumentException.class,
                    () -> JsonNumberCoercion.truncateAndClampToInt(json(input)), input);
        }
        assertThrows(IllegalArgumentException.class,
                () -> JsonNumberCoercion.truncateAndClampToInt(null));
    }
}
