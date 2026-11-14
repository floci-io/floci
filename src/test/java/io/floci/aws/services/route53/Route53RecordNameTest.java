package io.floci.aws.services.route53;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class Route53RecordNameTest {

    @ParameterizedTest
    @CsvSource({"WWW.Example.TEST.,www.example.test", "*.Example.test.,\\052.example.test",
            "\\052.Example.test,\\052.example.test", "\\101.Example.test.,a.example.test",
            "a\\056b.Example.test.,a\\056b.example.test", "_Acme-Challenge.Example.test.,_acme-challenge.example.test",
            "caf\u00e9.example.test,caf\\303\\251.example.test", "a\\400.example.test,a\\134400.example.test"})
    void conditionNamesAreSpelledAsAwsNormalizesThem(String input, String expected) {
        assertEquals(expected, Route53RecordName.conditionName(input));
    }

    @Test
    void recordIdentityDecodesLettersAndKeepsWildcardsAndEscapedDots() {
        assertEquals("a.example.test.", Route53RecordName.recordName("\\101.Example.test"));
        assertEquals("*.example.test.", Route53RecordName.recordName("\\052.Example.test"));
        assertEquals(Route53RecordName.recordName("*a.example.test"),
                Route53RecordName.recordName("\\052a.example.test"));
        assertNotEquals(Route53RecordName.recordName("a\\056b.example.test"),
                Route53RecordName.recordName("a.b.example.test"));
        assertEquals(Route53RecordName.recordName("caf\\303\\251.example.test"),
                Route53RecordName.recordName("caf\u00e9.example.test"), "a byte given as its escape code is that byte");
    }
}
