package io.github.hectorvent.floci.services.msk.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * Parses {@code numberOfBrokerNodes} without losing the precision needed to tell a whole
 * number from a fractional one.
 *
 * <p>Binding the field straight to {@code Integer} isn't safe: Jackson's default float-to-int
 * coercion (on by default) truncates a JSON float token the same way {@code intValue()} would,
 * silently turning 2.7 into 2. Comparing the value as a {@code double} after the fact doesn't
 * fix that either - it just moves the precision loss earlier. A literal like
 * {@code 1.0000000000000001} has no exact {@code double} representation, so parsing it into a
 * double collapses it to exactly {@code 1.0} before any {@code d == Math.rint(d)} check ever
 * runs, and the malformed input is silently accepted as a valid integer.
 *
 * <p>{@link JsonParser#getDecimalValue()} avoids that: it builds a {@link BigDecimal} straight
 * from the token's source text, the same way {@code new BigDecimal(String)} would, so it never
 * routes the value through a lossy {@code double}. Checking exactness on that value catches a
 * fractional literal - including one only a double comparison would have missed - while still
 * accepting a whole number written with a decimal point (e.g. {@code 3.0}).
 *
 * <p>This deserializer never throws on malformed input; it returns {@link BrokerCount#malformed()}
 * instead. See that class for why - in short, an exception raised here is caught while the
 * request body is still being read, before this service's AWS-shaped error handling ever runs.
 */
@RegisterForReflection
public class BrokerCountDeserializer extends JsonDeserializer<BrokerCount> {

    @Override
    public BrokerCount deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonToken token = p.currentToken();
        if (token == JsonToken.VALUE_NULL) {
            return null;
        }
        if (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT) {
            BigDecimal decimal = p.getDecimalValue();
            if (decimal.stripTrailingZeros().scale() <= 0) {
                try {
                    return BrokerCount.of(decimal.intValueExact());
                } catch (ArithmeticException overflow) {
                    // Falls through to "malformed" below - out of int range is as malformed as
                    // a fractional value.
                }
            }
        }
        return BrokerCount.malformed();
    }
}
