package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ses.model.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit test for the shared SES tag validation. Rules and messages are probe-confirmed against real AWS
 * SES v2 (2026-08-23): max 50 tags, unique keys, key 1–128 / value 0–256 chars, and a restricted
 * character set on both key and value.
 */
class SesTagsTest {

    private static Tag tag(String k, String v) {
        return new Tag(k, v);
    }

    @Test
    void validList_and_emptyOrNull_ok() {
        assertDoesNotThrow(() -> SesTags.validate((List<Tag>) null));
        assertDoesNotThrow(() -> SesTags.validate(List.of()));
        assertDoesNotThrow(() -> SesTags.validate(List.of(tag("env", "dev"), tag("owner", "alice"))));
        // Empty value and the allowed special characters are accepted.
        assertDoesNotThrow(() -> SesTags.validate(List.of(tag("k", ""))));
        assertDoesNotThrow(() -> SesTags.validate(List.of(tag("k", "_ . : / = + - @ a1"))));
    }

    @Test
    void tooManyTags_reportsMaxFifty() {
        List<Tag> tags = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            tags.add(tag("k" + i, "v"));
        }
        AwsException e = assertThrows(AwsException.class, () -> SesTags.validate(tags));
        assertEquals("BadRequestException", e.getErrorCode());
        assertEquals("Maximum of 50 user tags are allowed per resource, consider reducing the number "
                + "of tags in the request", e.getMessage());
    }

    @Test
    void fiftyTags_ok() {
        List<Tag> tags = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            tags.add(tag("k" + i, "v"));
        }
        assertDoesNotThrow(() -> SesTags.validate(tags));
    }

    @Test
    void duplicateKeys_rejected() {
        AwsException e = assertThrows(AwsException.class,
                () -> SesTags.validate(List.of(tag("dup", "1"), tag("dup", "2"))));
        assertEquals("Cannot provide multiple tags with the same key", e.getMessage());
    }

    @Test
    void nullValue_isCharsetViolation() {
        AwsException e = assertThrows(AwsException.class, () -> SesTags.validate(List.of(tag("k", null))));
        assertEquals("Tags can only contain letters, numbers, spaces, and the following special "
                + "characters: _ . : / = + - @", e.getMessage());
    }

    @Test
    void invalidChar_inValueOrKey_isCharsetViolation() {
        String msg = "Tags can only contain letters, numbers, spaces, and the following special "
                + "characters: _ . : / = + - @";
        assertEquals(msg, assertThrows(AwsException.class,
                () -> SesTags.validate(List.of(tag("k", "bad!value")))).getMessage());
        assertEquals(msg, assertThrows(AwsException.class,
                () -> SesTags.validate(List.of(tag("bad!key", "v")))).getMessage());
    }

    @Test
    void keyTooLong_smithyLengthMessage() {
        AwsException e = assertThrows(AwsException.class,
                () -> SesTags.validate(List.of(tag("k".repeat(129), "v"))));
        assertEquals("1 validation error detected: Value at 'tags.1.member.key' failed to satisfy "
                + "constraint: Member must have length less than or equal to 128", e.getMessage());
    }

    @Test
    void valueTooLong_smithyLengthMessage() {
        AwsException e = assertThrows(AwsException.class,
                () -> SesTags.validate(List.of(tag("k", "v".repeat(257)))));
        assertEquals("1 validation error detected: Value at 'tags.1.member.value' failed to satisfy "
                + "constraint: Member must have length less than or equal to 256", e.getMessage());
    }

    @Test
    void multipleLengthViolations_aggregatedValueThenKey() {
        AwsException e = assertThrows(AwsException.class,
                () -> SesTags.validate(List.of(tag("k".repeat(129), "v".repeat(257)))));
        assertEquals("2 validation errors detected: Value at 'tags.1.member.value' failed to satisfy "
                + "constraint: Member must have length less than or equal to 256; Value at "
                + "'tags.1.member.key' failed to satisfy constraint: Member must have length less than "
                + "or equal to 128", e.getMessage());
    }

    @Test
    void charsetBeatsLength() {
        // key too long AND value has an invalid char -> the character-set error wins (probe-confirmed).
        AwsException e = assertThrows(AwsException.class,
                () -> SesTags.validate(List.of(tag("k".repeat(129), "bad!"))));
        assertEquals("Tags can only contain letters, numbers, spaces, and the following special "
                + "characters: _ . : / = + - @", e.getMessage());
    }

    @Test
    void maxCountBeatsDuplicate() {
        List<Tag> tags = new ArrayList<>();
        tags.add(tag("dup", "1"));
        tags.add(tag("dup", "2"));
        for (int i = 0; i < 49; i++) {
            tags.add(tag("k" + i, "v"));
        }
        AwsException e = assertThrows(AwsException.class, () -> SesTags.validate(tags));
        assertEquals("Maximum of 50 user tags are allowed per resource, consider reducing the number "
                + "of tags in the request", e.getMessage());
    }

    @Test
    void emptyKey_rejectedWithNullMessage() {
        AwsException e = assertThrows(AwsException.class, () -> SesTags.validate(List.of(tag("", "v"))));
        assertEquals("BadRequestException", e.getErrorCode());
        assertNull(e.getMessage());
    }

    @Test
    void awsPrefixKey_rejectedAsSystemTag() {
        AwsException e = assertThrows(AwsException.class,
                () -> SesTags.validate(List.of(tag("aws:foo", "v"))));
        assertEquals("BadRequestException", e.getErrorCode());
        assertEquals("Caller is an end user and not allowed to mutate system tags", e.getMessage());
    }

    @Test
    void awsPrefixKey_isCaseInsensitive() {
        AwsException e = assertThrows(AwsException.class,
                () -> SesTags.validate(List.of(tag("AWS:foo", "v"))));
        assertEquals("Caller is an end user and not allowed to mutate system tags", e.getMessage());
    }

    @Test
    void awsPrefix_onlyAppliesToKey_valueAndNoColonAreAllowed() {
        // A value starting with aws: is allowed; only a key with the reserved prefix is rejected.
        assertDoesNotThrow(() -> SesTags.validate(List.of(tag("k", "aws:foo"))));
        // "aws" / "awsfoo" without the colon are ordinary keys.
        assertDoesNotThrow(() -> SesTags.validate(List.of(tag("aws", "v"))));
        assertDoesNotThrow(() -> SesTags.validate(List.of(tag("awsfoo", "v"))));
    }

    @Test
    void charsetBeatsAwsPrefix() {
        // aws: reserved key AND an invalid char in the value -> the character-set error wins.
        AwsException e = assertThrows(AwsException.class,
                () -> SesTags.validate(List.of(tag("aws:foo", "bad!"))));
        assertEquals("Tags can only contain letters, numbers, spaces, and the following special "
                + "characters: _ . : / = + - @", e.getMessage());
    }

    @Test
    void validateMergedCount_overFifty_usesMergeMessage() {
        AwsException e = assertThrows(AwsException.class, () -> SesTags.validateMergedCount(51));
        assertEquals("BadRequestException", e.getErrorCode());
        assertEquals("Maximum of 50 user tags are allowed per resource, consider reducing the number "
                + "of tags in the request or delete existing tags and retry", e.getMessage());
    }

    @Test
    void validateMergedCount_fiftyOrFewer_ok() {
        assertDoesNotThrow(() -> SesTags.validateMergedCount(50));
        assertDoesNotThrow(() -> SesTags.validateMergedCount(0));
    }

    @Test
    void merge_overwritesExistingKeys_appendsNewOnes_preservingInsertionOrder() {
        List<Tag> merged = SesTags.merge(
                List.of(tag("env", "dev"), tag("owner", "alice")),
                List.of(tag("env", "prod"), tag("team", "mail")));
        assertEquals(List.of(tag("env", "prod"), tag("owner", "alice"), tag("team", "mail")), merged);
    }

    @Test
    void merge_updatingExistingKeyAtFifty_ok() {
        List<Tag> existing = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            existing.add(tag("k" + i, "v"));
        }
        List<Tag> merged = assertDoesNotThrow(() -> SesTags.merge(existing, List.of(tag("k0", "v2"))));
        assertEquals(50, merged.size());
        assertEquals(tag("k0", "v2"), merged.get(0));
    }

    @Test
    void merge_overFiftyUniqueKeys_rejectedWithMergeMessage() {
        List<Tag> existing = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            existing.add(tag("k" + i, "v"));
        }
        AwsException e = assertThrows(AwsException.class,
                () -> SesTags.merge(existing, List.of(tag("k50", "v"))));
        assertEquals("BadRequestException", e.getErrorCode());
        assertEquals("Maximum of 50 user tags are allowed per resource, consider reducing the number "
                + "of tags in the request or delete existing tags and retry", e.getMessage());
    }

    @Test
    void unicodeWhitespace_isAllowed() {
        // U+00A0 (no-break space) is a Unicode separator (\p{Z}), not the ASCII space. AWS accepts it
        // in both key and value (probe-confirmed), so the charset rule must allow it.
        assertDoesNotThrow(() -> SesTags.validate(List.of(tag("a\u00A0b", "c\u00A0d"))));
    }

    @Test
    void length_countsUnicodeCodePointsNotUtf16Units() {
        // U+1D400 (MATHEMATICAL BOLD CAPITAL A) is a letter (\p{L}, charset-allowed) that occupies one
        // code point but two UTF-16 code units. Smithy length constraints count code points, so 128 of
        // them is exactly the key limit (would be 256 UTF-16 units).
        String cp = "\uD835\uDC00";
        assertDoesNotThrow(() -> SesTags.validate(List.of(tag(cp.repeat(128), "v"))));
        AwsException e = assertThrows(AwsException.class,
                () -> SesTags.validate(List.of(tag(cp.repeat(129), "v"))));
        assertEquals("1 validation error detected: Value at 'tags.1.member.key' failed to satisfy "
                + "constraint: Member must have length less than or equal to 128", e.getMessage());
    }
}
