package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DynamoDbTableNamesTest {

    @ParameterizedTest
    @CsvSource({
            "Orders, Orders, ",
            "orders_v2, orders_v2, ",
            "arn:aws:dynamodb:us-east-1:000000000000:table/Orders, Orders, us-east-1",
            "arn:aws:dynamodb:eu-west-1:123456789012:table/orders_v2, orders_v2, eu-west-1"
    })
    void resolveAcceptsShortNamesAndArns(String input, String expectedName, String expectedRegion) {
        DynamoDbTableNames.ResolvedTableRef ref = DynamoDbTableNames.resolveWithRegion(
                input,
                expectedRegion == null || expectedRegion.isEmpty() ? "us-east-1" : expectedRegion
        );

        assertEquals(expectedName, ref.name());
        assertEquals(emptyToNull(expectedRegion), ref.region());
    }

    @Test
    void resolveReturnsCanonicalShortName() {
        assertEquals("Orders",
                DynamoDbTableNames.resolve("arn:aws:dynamodb:us-east-1:000000000000:table/Orders"));
    }

    @Test
    void resolveWithRegionRejectsRegionMismatch() {
        AwsException ex = assertThrows(AwsException.class, () ->
                DynamoDbTableNames.resolveWithRegion(
                        "arn:aws:dynamodb:eu-west-1:000000000000:table/Orders",
                        "us-east-1"));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            " ",
            "ab",
            "name with spaces",
            "arn:aws:dynamodb:us-east-1:000000000000:table/",
            "arn:aws:dynamodb::000000000000:table/Orders",
            "arn:aws:dynamodb:us-east-1:abc:table/Orders",
            "arn:aws:dynamodb:us-east-1:000000000000:index/Orders",
            "arn:aws:dynamodb:us-east-1:000000000000:table/Orders/index/by-status",
            "arn:aws:dynamodb:us-east-1:000000000000:table/Orders/stream/2026-04-24T00:00:00.000",
            "arn:aws:s3:us-east-1:000000000000:table/Orders",
            "arn:aws:dynamodb:us-east-1:000000000000:table/Orders/extra"
    })
    void resolveRejectsMalformedInputs(String input) {
        AwsException ex = assertThrows(AwsException.class, () -> DynamoDbTableNames.resolve(input));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void resolveAcceptsShortNameWithoutRegion() {
        DynamoDbTableNames.ResolvedTableRef ref = DynamoDbTableNames.resolveWithRegion("Orders", "us-east-1");
        assertEquals("Orders", ref.name());
        assertNull(ref.region());
    }

    @Test
    void requireShortNameAcceptsValidShortName() {
        assertEquals("Orders", DynamoDbTableNames.requireShortName("Orders"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "arn:aws:dynamodb:us-east-1:000000000000:table/Orders",
            "arn:aws:dynamodb:us-east-1:000000000000:table/Orders/stream/2026-04-24T00:00:00.000"
    })
    void requireShortNameRejectsArnInput(String arn) {
        AwsException ex = assertThrows(AwsException.class, () -> DynamoDbTableNames.requireShortName(arn));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "ab", "name with spaces"})
    void requireShortNameRejectsMalformedInput(String input) {
        AwsException ex = assertThrows(AwsException.class, () -> DynamoDbTableNames.requireShortName(input));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }
}
