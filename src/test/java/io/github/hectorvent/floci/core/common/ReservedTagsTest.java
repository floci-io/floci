package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReservedTagsTest {

    @Test
    void stripReservedTagsReturnsEmptyMapForNullInput() {
        assertTrue(ReservedTags.stripReservedTags(null).isEmpty());
    }

    @Test
    void stripReservedTagsReturnsEmptyMapForEmptyInput() {
        assertTrue(ReservedTags.stripReservedTags(Map.of()).isEmpty());
    }

    @Test
    void stripReservedTagsKeepsNonReservedTags() {
        Map<String, String> tags = Map.of("env", "test", "team", "platform");

        assertEquals(tags, ReservedTags.stripReservedTags(tags));
    }

    @Test
    void stripReservedTagsRemovesOnlyReservedTags() {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("env", "test");
        tags.put(ReservedTags.OVERRIDE_ID_KEY, "my-id");
        tags.put("floci:internal", "hidden");
        tags.put("team", "platform");

        Map<String, String> stripped = ReservedTags.stripReservedTags(tags);

        assertEquals(Map.of("env", "test", "team", "platform"), stripped);
    }

    @Test
    void stripReservedTagsRemovesAllReservedTags() {
        Map<String, String> tags = Map.of(
                ReservedTags.OVERRIDE_ID_KEY, "my-id",
                "floci:internal", "hidden"
        );

        assertTrue(ReservedTags.stripReservedTags(tags).isEmpty());
    }

    @Test
    void extractOverrideUserPoolIdReturnsNullForNullInput() {
        assertNull(ReservedTags.extractOverrideUserPoolId(null));
    }

    @Test
    void extractOverrideIdReturnsReservedOverrideUserPoolOnly() {
        Map<String, String> tags = Map.of(
                ReservedTags.OVERRIDE_ID_KEY, "my-id",
                "floci:internal", "hidden",
                "env", "test"
        );

        assertEquals("my-id", ReservedTags.extractOverrideUserPoolId(tags));
    }

    @Test
    void rejectReservedTagsOnUpdateAllowsNormalTags() {
        assertDoesNotThrow(() -> ReservedTags.rejectReservedTagsOnUpdate(Map.of("env", "test")));
    }

    @Test
    void rejectReservedTagsOnUpdateRejectsReservedTags() {
        AwsException exception = assertThrows(
                AwsException.class,
                () -> ReservedTags.rejectReservedTagsOnUpdate(Map.of(ReservedTags.OVERRIDE_ID_KEY, "my-id"))
        );

        assertEquals("ValidationException", exception.getErrorCode());
    }

    // ──────────────────────────── API Gateway override id ────────────────────────────

    @Test
    void extractOverrideApiIdReturnsNullForNullInput() {
        assertNull(ReservedTags.extractOverrideApiId(null));
    }

    @Test
    void extractOverrideApiIdReturnsNullWhenNeitherKeyPresent() {
        assertNull(ReservedTags.extractOverrideApiId(Map.of("env", "test")));
    }

    @Test
    void extractOverrideApiIdReadsReservedOverrideKey() {
        assertEquals("my-api", ReservedTags.extractOverrideApiId(
                Map.of(ReservedTags.OVERRIDE_ID_KEY, "my-api", "env", "test")));
    }

    @Test
    void extractOverrideApiIdFallsBackToDeprecatedCustomIdKey() {
        assertEquals("legacy-api", ReservedTags.extractOverrideApiId(
                Map.of(ReservedTags.DEPRECATED_API_GATEWAY_CUSTOM_ID_KEY, "legacy-api")));
    }

    @Test
    void extractOverrideApiIdPrefersReservedKeyOverDeprecatedKey() {
        assertEquals("winner", ReservedTags.extractOverrideApiId(Map.of(
                ReservedTags.OVERRIDE_ID_KEY, "winner",
                ReservedTags.DEPRECATED_API_GATEWAY_CUSTOM_ID_KEY, "loser")));
    }

    @Test
    void extractOverrideApiIdRejectsBlankValueWithApiGatewayErrorCode() {
        AwsException exception = assertThrows(
                AwsException.class,
                () -> ReservedTags.extractOverrideApiId(Map.of(ReservedTags.OVERRIDE_ID_KEY, "   "))
        );

        assertEquals("BadRequestException", exception.getErrorCode());
        assertEquals(400, exception.getHttpStatus());
    }

    @Test
    void extractOverrideApiIdRejectsUnsupportedCharacters() {
        for (String bad : new String[] {"has/slash", "has?query", "has#fragment"}) {
            AwsException exception = assertThrows(
                    AwsException.class,
                    () -> ReservedTags.extractOverrideApiId(Map.of(ReservedTags.OVERRIDE_ID_KEY, bad)),
                    "expected rejection for " + bad
            );
            assertEquals("BadRequestException", exception.getErrorCode());
        }
    }

    @Test
    void extractOverrideApiIdValidatesTheDeprecatedKeyToo() {
        AwsException exception = assertThrows(
                AwsException.class,
                () -> ReservedTags.extractOverrideApiId(
                        Map.of(ReservedTags.DEPRECATED_API_GATEWAY_CUSTOM_ID_KEY, "has/slash"))
        );

        assertEquals("BadRequestException", exception.getErrorCode());
    }

    @Test
    void stripApiGatewayReservedTagsRemovesBothOverrideKeys() {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("env", "test");
        tags.put(ReservedTags.OVERRIDE_ID_KEY, "my-api");
        tags.put(ReservedTags.DEPRECATED_API_GATEWAY_CUSTOM_ID_KEY, "legacy-api");
        tags.put("team", "platform");

        assertEquals(Map.of("env", "test", "team", "platform"),
                ReservedTags.stripApiGatewayReservedTags(tags));
    }

    @Test
    void stripReservedTagsLeavesDeprecatedCustomIdForOtherServices() {
        // _custom_id_ is API Gateway specific. KMS and Cognito use the shared strip and must keep it.
        Map<String, String> tags = Map.of(ReservedTags.DEPRECATED_API_GATEWAY_CUSTOM_ID_KEY, "legacy-api");

        assertEquals(tags, ReservedTags.stripReservedTags(tags));
    }

    @Test
    void rejectApiGatewayReservedTagsOnUpdateAllowsNormalTags() {
        assertDoesNotThrow(() -> ReservedTags.rejectApiGatewayReservedTagsOnUpdate(Map.of("env", "test")));
        assertDoesNotThrow(() -> ReservedTags.rejectApiGatewayReservedTagsOnUpdate(null));
    }

    @Test
    void rejectApiGatewayReservedTagsOnUpdateRejectsBothOverrideKeys() {
        for (String key : new String[] {
                ReservedTags.OVERRIDE_ID_KEY, ReservedTags.DEPRECATED_API_GATEWAY_CUSTOM_ID_KEY}) {
            AwsException exception = assertThrows(
                    AwsException.class,
                    () -> ReservedTags.rejectApiGatewayReservedTagsOnUpdate(Map.of(key, "too-late")),
                    "expected rejection for " + key
            );
            assertEquals("BadRequestException", exception.getErrorCode());
        }
    }

    @Test
    void rejectReservedTagsOnUpdateIgnoresDeprecatedCustomIdForOtherServices() {
        assertDoesNotThrow(() -> ReservedTags.rejectReservedTagsOnUpdate(
                Map.of(ReservedTags.DEPRECATED_API_GATEWAY_CUSTOM_ID_KEY, "legacy-api")));
    }

    @Test
    void rejectUnknownReservedTagsRejectsReservedTagsWithUserPoolTaggingException() {
        AwsException exception = assertThrows(
                AwsException.class,
                () -> ReservedTags.rejectUnknownReservedTags(Map.of(ReservedTags.RESERVED_PREFIX + "unknown-override", "something"), "UserPoolTaggingException")
        );

        assertEquals("UserPoolTaggingException", exception.getErrorCode());
    }

    @Test
    void rejectUnknownReservedTagsRejectsReservedTagsWithTagException() {
        AwsException exception = assertThrows(
                AwsException.class,
                () -> ReservedTags.rejectUnknownReservedTags(Map.of(ReservedTags.RESERVED_PREFIX + "unknown-override", "something"), "TagException")
        );

        assertEquals("TagException", exception.getErrorCode());
    }
}
