package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;

/** Numeric conversions shared by JSON protocol deserializers. */
public final class JsonNumberCoercion {

    private static final BigDecimal INT_MAX = BigDecimal.valueOf(Integer.MAX_VALUE);
    private static final BigDecimal INT_MIN = BigDecimal.valueOf(Integer.MIN_VALUE);

    private JsonNumberCoercion() {
    }

    /**
     * Narrows a JSON number to an {@code int}, truncating fractions toward zero and saturating at
     * the integer bounds. This matches Coral's truncating integer deserializer. Non-finite Jackson
     * number nodes are handled before {@link JsonNode#decimalValue()} because that conversion can
     * throw for infinities produced by extremely large exponents.
     *
     * <p>Callers remain responsible for validating the member type and producing protocol-specific
     * errors before calling this method.
     *
     * @throws IllegalArgumentException if {@code value} is not a numeric node
     */
    public static int truncateAndClampToInt(JsonNode value) {
        if (value == null || !value.isNumber()) {
            throw new IllegalArgumentException("Expected a JSON number");
        }

        double doubleValue = value.doubleValue();
        if (!Double.isFinite(doubleValue)) {
            if (Double.isNaN(doubleValue)) {
                return 0;
            }
            return doubleValue > 0 ? Integer.MAX_VALUE : Integer.MIN_VALUE;
        }

        BigDecimal decimalValue = value.decimalValue();
        if (decimalValue.compareTo(INT_MAX) >= 0) {
            return Integer.MAX_VALUE;
        }
        if (decimalValue.compareTo(INT_MIN) <= 0) {
            return Integer.MIN_VALUE;
        }
        return decimalValue.intValue();
    }
}
