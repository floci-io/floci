package io.floci.aws.core.common;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Route53ConditionKeysTest {
    @ParameterizedTest
    @CsvSource({"WWW.Example.TEST.,www.example.test", "*.Example.test.,\\052.example.test",
            "\\052.Example.test,\\052.example.test", "\\101.Example.test.,a.example.test",
            "a\\056b.Example.test.,a\\056b.example.test", "_Acme-Challenge.Example.test.,_acme-challenge.example.test"})
    void normalizesDnsNamesAndOctalEscapes(String input, String expected) {
        assertEquals(expected, Route53ConditionKeys.normalizedName(input));
    }
}
