package io.github.hectorvent.floci.services.appsync.graphql.scalars;

import graphql.schema.GraphQLScalarType;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppSyncScalarsTest {

    @Test
    void scalar_allRegistered() {
        AppSyncScalarRegistry registry = new AppSyncScalarRegistry();
        List<GraphQLScalarType> scalars = registry.allScalars();
        assertThat(scalars, hasSize(greaterThanOrEqualTo(17)));
        assertThat(scalars.stream().map(GraphQLScalarType::getName).toList(),
            hasItems("AWSJSON", "AWSDateTime", "AWSDate", "AWSTime", "AWSTimestamp",
                     "AWSEmail", "AWSURL", "AWSPhone", "AWSIPAddress", "AWSBoolean",
                     "AWSLong", "AWSInteger", "AWSShort", "AWSFloat",
                     "AWSBigDecimal", "AWSBigInt", "AWSByte"));
    }

    @Test
    void scalar_awsdatetime_parseValue() {
        Object valid = AppSyncScalars.AWS_DATE_TIME.getCoercing().parseValue("2026-06-04T12:00:00Z");
        assertThat(valid, is("2026-06-04T12:00:00Z"));

        assertThrows(CoercingParseValueException.class, () -> AppSyncScalars.AWS_DATE_TIME.getCoercing().parseValue("not-a-date"));
    }

    @Test
    void scalar_awsjson_parseValue() {
        Object valid = AppSyncScalars.AWSJSON.getCoercing().parseValue("{\"key\": \"value\"}");
        assertThat(valid, is("{\"key\": \"value\"}"));

        assertThrows(CoercingParseValueException.class, () -> AppSyncScalars.AWSJSON.getCoercing().parseValue("not json"));
    }

    @Test
    void scalar_awsemail_parseValue() {
        Object valid = AppSyncScalars.AWS_EMAIL.getCoercing().parseValue("user@example.com");
        assertThat(valid, is("user@example.com"));

        assertThrows(CoercingParseValueException.class, () -> AppSyncScalars.AWS_EMAIL.getCoercing().parseValue("not-an-email"));
    }

    @Test
    void scalar_awstimestamp_range() {
        Object valid = AppSyncScalars.AWS_TIMESTAMP.getCoercing().parseValue(1700000000L);
        assertThat(valid, is(1700000000L));

        assertThrows(CoercingParseValueException.class, () -> AppSyncScalars.AWS_TIMESTAMP.getCoercing().parseValue(-1L));
        assertThrows(CoercingParseValueException.class, () -> AppSyncScalars.AWS_TIMESTAMP.getCoercing().parseValue(40000000000L));
    }

    @Test
    void scalar_awsurl_parseValue() {
        Object valid = AppSyncScalars.AWS_URL.getCoercing().parseValue("https://example.com");
        assertThat(valid, is("https://example.com"));

        assertThrows(CoercingParseValueException.class, () -> AppSyncScalars.AWS_URL.getCoercing().parseValue("not a url"));
    }

    @Test
    void scalar_awsphone_parseValue() {
        Object valid = AppSyncScalars.AWS_PHONE.getCoercing().parseValue("+1234567890");
        assertThat(valid, is("+1234567890"));

        assertThrows(CoercingParseValueException.class, () -> AppSyncScalars.AWS_PHONE.getCoercing().parseValue("12345"));
    }

    @Test
    void scalar_awsshort_range() {
        Object valid = AppSyncScalars.AWS_SHORT.getCoercing().parseValue(32767);
        assertThat(valid, is(32767));

        assertThrows(CoercingParseValueException.class, () -> AppSyncScalars.AWS_SHORT.getCoercing().parseValue(40000));
        assertThrows(CoercingParseValueException.class, () -> AppSyncScalars.AWS_SHORT.getCoercing().parseValue(-32769));
    }

    @Test
    void scalar_awstimestamp_parseValue_withString() {
        Object valid = AppSyncScalars.AWS_TIMESTAMP.getCoercing().parseValue("1700000000");
        assertThat(valid, is(1700000000L));

        assertThrows(Exception.class, () -> AppSyncScalars.AWS_TIMESTAMP.getCoercing().parseValue("not-a-number"));
    }

    @Test
    void scalar_serialize_nullSafety() {
        assertThat(AppSyncScalars.AWS_TIMESTAMP.getCoercing().serialize(null), is(nullValue()));
        assertThat(AppSyncScalars.AWS_LONG.getCoercing().serialize(null), is(nullValue()));
        assertThat(AppSyncScalars.AWS_INTEGER.getCoercing().serialize(null), is(nullValue()));
        assertThat(AppSyncScalars.AWS_FLOAT.getCoercing().serialize(null), is(nullValue()));
        assertThat(AppSyncScalars.AWS_BOOLEAN.getCoercing().serialize(null), is(nullValue()));
    }

    @Test
    void scalar_awsipaddress_validIps() {
        assertThat(AppSyncScalars.AWS_IP_ADDRESS.getCoercing().parseValue("192.168.1.1"), is("192.168.1.1"));
        assertThat(AppSyncScalars.AWS_IP_ADDRESS.getCoercing().parseValue("255.255.255.255"), is("255.255.255.255"));
        assertThat(AppSyncScalars.AWS_IP_ADDRESS.getCoercing().parseValue("0.0.0.0"), is("0.0.0.0"));
        assertThat(AppSyncScalars.AWS_IP_ADDRESS.getCoercing().parseValue("2001:0db8:85a3:0000:0000:8a2e:0370:7334"),
            is("2001:0db8:85a3:0000:0000:8a2e:0370:7334"));
        assertThat(AppSyncScalars.AWS_IP_ADDRESS.getCoercing().parseValue("::1"), is("::1"));
    }

    @Test
    void scalar_awsipaddress_invalidIps() {
        assertThrows(CoercingParseValueException.class, () -> AppSyncScalars.AWS_IP_ADDRESS.getCoercing().parseValue("999.999.999.999"));
        assertThrows(CoercingParseValueException.class, () -> AppSyncScalars.AWS_IP_ADDRESS.getCoercing().parseValue("not-an-ip"));
        assertThrows(CoercingParseValueException.class, () -> AppSyncScalars.AWS_IP_ADDRESS.getCoercing().parseValue("localhost"));
        assertThrows(CoercingParseValueException.class, () -> AppSyncScalars.AWS_IP_ADDRESS.getCoercing().parseValue("google.com"));
    }

    @Test
    void scalar_scalarMap_cached() {
        AppSyncScalarRegistry registry = new AppSyncScalarRegistry();
        var map1 = registry.scalarMap();
        var map2 = registry.scalarMap();
        assertThat(map1, is(sameInstance(map2)));
        assertThat(map1, hasKey("AWSDateTime"));
        assertThat(map1, hasKey("AWSJSON"));
        assertThat(map1.size(), greaterThanOrEqualTo(17));
    }

    @Test
    void scalar_awsboolean_rejectsNonBoolean() {
        assertThat(AppSyncScalars.AWS_BOOLEAN.getCoercing().parseValue(true), is(true));
        assertThat(AppSyncScalars.AWS_BOOLEAN.getCoercing().parseValue(false), is(false));

        assertThrows(CoercingParseValueException.class, () -> AppSyncScalars.AWS_BOOLEAN.getCoercing().parseValue("true"));
        assertThrows(CoercingParseValueException.class, () -> AppSyncScalars.AWS_BOOLEAN.getCoercing().parseValue("xyz"));
        assertThrows(CoercingParseValueException.class, () -> AppSyncScalars.AWS_BOOLEAN.getCoercing().parseValue(1));
        assertThrows(CoercingParseValueException.class, () -> AppSyncScalars.AWS_BOOLEAN.getCoercing().parseValue(0));
    }

    @Test
    void scalar_awsboolean_serialize_rejectsNonBoolean() {
        assertThat(AppSyncScalars.AWS_BOOLEAN.getCoercing().serialize(true), is(true));
        assertThat(AppSyncScalars.AWS_BOOLEAN.getCoercing().serialize(false), is(false));
        assertThat(AppSyncScalars.AWS_BOOLEAN.getCoercing().serialize(null), is(nullValue()));

        assertThrows(CoercingSerializeException.class, () -> AppSyncScalars.AWS_BOOLEAN.getCoercing().serialize("true"));
        assertThrows(CoercingSerializeException.class, () -> AppSyncScalars.AWS_BOOLEAN.getCoercing().serialize(1));
    }
}
