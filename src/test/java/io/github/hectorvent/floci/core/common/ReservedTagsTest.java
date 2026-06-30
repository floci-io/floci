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
