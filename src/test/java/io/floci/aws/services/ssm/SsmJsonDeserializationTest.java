package io.floci.aws.services.ssm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SsmJsonDeserializationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @CsvSource({
            "30.0, 30",
            "30.5, 30",
            "-30.5, -30",
            "3e1, 30",
            "2147483647, 2147483647",
            "2147483648, 2147483647",
            "4294967326, 2147483647",
            "9223372036854775808, 2147483647",
            "-2147483648, -2147483648",
            "-2147483649, -2147483648",
            "-9223372036854775809, -2147483648",
            "1e400, 2147483647",
            "-1e400, -2147483648"
    })
    void numericValuesTruncateAndClampToTheIntegerRange(String input, int expected) throws Exception {
        assertEquals(expected, SsmJsonDeserialization.optionalInteger(mapper.readTree(input), 3600));
    }
}
