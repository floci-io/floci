package io.floci.aws.core.common;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonBooleanCoercionTest {

    @ParameterizedTest
    @CsvSource({"true,true", "YES,true", "y,true", "T,true", "1,true",
            "false,false", "No,false", "n,false", "F,false", "0,false"})
    void aStringNamingABooleanInAnyCaseIsThatBoolean(String value, boolean expected) {
        assertEquals(expected, JsonBooleanCoercion.fromString(value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " true", "false ", "on", "2", "maybe"})
    void anyOtherStringIsRefusedAsTheDeserializerRefusesIt(String value) {
        AwsException error = assertThrows(AwsException.class, () -> JsonBooleanCoercion.fromString(value));
        assertEquals("SerializationException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertEquals("STRING_VALUE can not be converted to an Boolean", error.getMessage());
    }
}
