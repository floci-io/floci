package io.floci.aws.services.iam;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PublicAccessConditionsTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "123456789012|true",
        "o-abc|true",
        "''|false",
        "1234*|false",
        "12?4|false",
        "${aws:PrincipalAccount}|false",
    })
    void aFixedValueNamesOneThing(String value, boolean fixed) {
        assertEquals(fixed, PublicAccessConditions.isFixedValue(value));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "arn:aws:iam::123456789012:role/app|true",
        "arn:aws:iam::123456789012:role/*|true",
        "arn:aws:iam::*:role/app|false",
        "arn:aws:iam::12345678901?:root|false",
        "arn:aws:iam::123456789012|false",
    })
    void anArnPinsItsAccountWhenTheAccountHasNoWildcard(String arn, boolean pins) {
        assertEquals(pins, PublicAccessConditions.pinsAccount(arn));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "203.0.113.7|true",
        "10.0.0.0/8|true",
        "10.0.0.0/7|false",
        "2001:db8::/32|true",
        "2001:db8::/31|false",
        "10.0.0.0/x|false",
        "10.*.0.0/16|false",
    })
    void aSourceIpRangeIsNarrowFromSlash8ForIpv4AndSlash32ForIpv6(String cidr, boolean narrow) {
        assertEquals(narrow, PublicAccessConditions.isNarrowSourceIp(cidr));
    }
}
