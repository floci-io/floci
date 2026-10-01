package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The point of these is that the codes are interoperable, not merely self-consistent: a secret
 * Floci hands out has to produce the same six digits an authenticator app would, or verifying a
 * code is only checking this implementation against itself.
 */
class TotpTest {

    /**
     * RFC 6238's test secret, the ASCII string {@code 12345678901234567890}, in base32. The
     * expectations below come from that document's published table, not from this implementation.
     */
    private static final String RFC6238_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    void base32EncodesTheRfc6238TestSecret() {
        assertEquals(RFC6238_SECRET,
                Totp.base32Encode("12345678901234567890".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void base32DecodeIsTheInverseOfEncode() {
        byte[] original = "12345678901234567890".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(original, Totp.base32Decode(Totp.base32Encode(original)));
    }

    /**
     * RFC 6238 Appendix B, the SHA-1 rows, truncated to the six digits AWS asks for: at T=59 the
     * document gives 94287082, at T=1111111109 07081804, and so on.
     */
    @Test
    void codesMatchTheRfc6238TestVectors() {
        assertEquals("287082", Totp.codeAt(RFC6238_SECRET, Totp.stepAt(Instant.ofEpochSecond(59))));
        assertEquals("081804", Totp.codeAt(RFC6238_SECRET, Totp.stepAt(Instant.ofEpochSecond(1111111109L))));
        assertEquals("050471", Totp.codeAt(RFC6238_SECRET, Totp.stepAt(Instant.ofEpochSecond(1111111111L))));
        assertEquals("005924", Totp.codeAt(RFC6238_SECRET, Totp.stepAt(Instant.ofEpochSecond(1234567890L))));
        assertEquals("279037", Totp.codeAt(RFC6238_SECRET, Totp.stepAt(Instant.ofEpochSecond(2000000000L))));
        assertEquals("353130", Totp.codeAt(RFC6238_SECRET, Totp.stepAt(Instant.ofEpochSecond(20000000000L))));
    }

    @Test
    void generatedSecretsAreDistinctAndDecodeToAFullLengthKey() {
        String first = Totp.newSecret();
        String second = Totp.newSecret();
        assertNotEquals(first, second);
        assertTrue(first.matches("[A-Z2-7]{32}"));
        assertEquals(20, Totp.base32Decode(first).length, "a 160-bit HMAC-SHA1 secret");
    }

    @Test
    void matchesCodeAcceptsTheCurrentWindow() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        assertTrue(Totp.matchesCode(RFC6238_SECRET, Totp.codeAt(RFC6238_SECRET, Totp.stepAt(now)), 0, now));
    }

    /** The drift window's edges, so a widening or narrowing of it cannot pass unnoticed. */
    @Test
    void matchesCodeHonoursTheDriftWindowExactly() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        String twoStepsBack = Totp.codeAt(RFC6238_SECRET, Totp.stepAt(now) - 2);

        assertFalse(Totp.matchesCode(RFC6238_SECRET, twoStepsBack, 1, now),
                "two steps back is outside a one-step allowance");
        assertTrue(Totp.matchesCode(RFC6238_SECRET, twoStepsBack, 2, now),
                "and inside a two-step one");
    }

    @Test
    void matchesCodeRejectsACodeFromADifferentSecret() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        String other = Totp.newSecret();
        assertFalse(Totp.matchesCode(RFC6238_SECRET, Totp.codeAt(other, Totp.stepAt(now)), 1, now));
    }

    @Test
    void anythingThatIsNotSixDigitsIsNotACode() {
        assertTrue(Totp.isSixDigits("000000"));
        assertFalse(Totp.isSixDigits(null));
        assertFalse(Totp.isSixDigits("12345"));
        assertFalse(Totp.isSixDigits("1234567"));
        assertFalse(Totp.isSixDigits("12345a"));
        assertFalse(Totp.isSixDigits("12 456"));
    }

    /** A negative epoch must floor rather than truncate toward zero, or codes shift by a step. */
    @Test
    void stepAtFloorsBelowTheEpoch() {
        assertEquals(-1, Totp.stepAt(Instant.ofEpochSecond(-1)));
        assertEquals(-1, Totp.stepAt(Instant.ofEpochSecond(-30)));
        assertEquals(-2, Totp.stepAt(Instant.ofEpochSecond(-31)));
        assertEquals(0, Totp.stepAt(Instant.EPOCH));
    }
}
