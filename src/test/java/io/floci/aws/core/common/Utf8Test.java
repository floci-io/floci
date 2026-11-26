package io.floci.aws.core.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Utf8Test {

    @Test
    void returnsInputUnchangedWhenWithinByteBudget() {
        assertEquals("", Utf8.truncate("", 10));
        assertEquals("hello", Utf8.truncate("hello", 5));
        assertEquals("café😀", Utf8.truncate("café😀", 9));
    }

    @Test
    void cutsOnCodePointBoundariesWithoutSplittingMultiByteOrSurrogatePairs() {
        assertEquals("abc", Utf8.truncate("abcdef", 3));
        assertEquals("caf", Utf8.truncate("café", 4));
        assertEquals("café", Utf8.truncate("café", 5));
        assertEquals("a", Utf8.truncate("a中b", 3));
        assertEquals("a中", Utf8.truncate("a中b", 4));
        // A 4-byte emoji is a surrogate pair; budgets of 1..3 bytes into it must drop the whole emoji.
        String withEmoji = "ab😀cd";
        assertEquals("ab", Utf8.truncate(withEmoji, 3));
        assertEquals("ab", Utf8.truncate(withEmoji, 4));
        assertEquals("ab", Utf8.truncate(withEmoji, 5));
        assertEquals("ab😀", Utf8.truncate(withEmoji, 6));
        assertTrue(Utf8.truncate(withEmoji, 5).getBytes(StandardCharsets.UTF_8).length <= 5);
    }

    @Test
    void returnsEmptyWhenBudgetIsZeroOrNegative() {
        assertEquals("", Utf8.truncate("abc", 0));
        assertEquals("", Utf8.truncate("abc", -5));
    }
}
