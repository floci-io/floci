package io.github.hectorvent.floci.services.apigateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The v2 mapping id has to name exactly one stored record.
 *
 * <p>It is derived from the base path a record is stored under, which is the only thing that
 * identifies it. Not its canonical form, and not the record's own field: writes canonicalise now,
 * so no endpoint creates a record under "/" or "", but state written before that can hold one — and
 * `BasePathMapping` normalises an empty base path to "(none)" in its constructor, so such a record
 * reports "(none)" while living under the key "". Deriving identity from either would give several
 * records one id, and a read or a delete would pick between them.
 */
class ApiMappingIdTest {

    @Test
    void rootLikeKeysThatWerePersistedSeparatelyKeepSeparateIds() {
        String canonical = ApiGatewayController.apiMappingId("(none)");
        String slash = ApiGatewayController.apiMappingId("/");
        String empty = ApiGatewayController.apiMappingId("");

        assertNotEquals(canonical, slash);
        assertNotEquals(canonical, empty);
        assertNotEquals(slash, empty);
    }

    @Test
    void everyIdIsNonEmptyAndStable() {
        // A record stored under an empty key must still produce an id a caller can put in a URL.
        assertFalse(ApiGatewayController.apiMappingId("").isEmpty());
        assertFalse(ApiGatewayController.apiMappingId(null).isEmpty());

        assertEquals(ApiGatewayController.apiMappingId("orders"),
                ApiGatewayController.apiMappingId("orders"));
    }

    @Test
    void distinctKeysSharingAJavaHashKeepDistinctIds() {
        assertEquals("Aa".hashCode(), "BB".hashCode());
        assertNotEquals(ApiGatewayController.apiMappingId("Aa"),
                ApiGatewayController.apiMappingId("BB"));
    }
}
