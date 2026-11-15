package io.floci.aws.core.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AwsQueryBooleansTest {

    @ParameterizedTest
    @CsvSource({"true,true", "1,true", "false,false", "0,false"})
    void acceptsXsdBooleanLexicalValues(String value, boolean expected) {
        assertEquals(expected, AwsQueryBooleans.parse(value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"TRUE", "False", " true", "false ", " ", "yes", "2", "invalid\u0000flag"})
    void rejectsOtherValuesWithoutEchoingInput(String value) {
        AwsException error = assertThrows(AwsException.class, () -> AwsQueryBooleans.parse(value));
        assertEquals("MalformedInput", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertEquals("boolean must follow xsd1.1 definition", error.getMessage());
    }

    @Test
    void emptyValueRetainsMissingValueError() {
        AwsException error = assertThrows(AwsException.class, () -> AwsQueryBooleans.parse(""));
        assertEquals("MalformedInput", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertEquals("missing value for boolean type", error.getMessage());
    }
}
