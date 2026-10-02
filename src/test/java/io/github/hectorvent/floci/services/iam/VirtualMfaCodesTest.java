package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.Totp;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IAM's consecutive-pair rule. The underlying arithmetic is covered against RFC 6238's published
 * vectors in {@code TotpTest}; what matters here is that two codes have to be successive outputs
 * of the same seed, which is what AWS asks the caller of EnableMFADevice for.
 */
class VirtualMfaCodesTest {

    /** RFC 6238's test secret, so the pairs below are reproducible. */
    private static final String SEED = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    private static final Instant NOW = Instant.ofEpochSecond(1_700_000_000L);

    private static boolean matchesPair(long firstOffset, int driftSteps) {
        long step = Totp.stepAt(NOW);
        return VirtualMfaCodes.matchesConsecutiveCodes(SEED,
                Totp.codeAt(SEED, step + firstOffset),
                Totp.codeAt(SEED, step + firstOffset + 1), driftSteps, NOW);
    }

    @Test
    void acceptsTheCurrentPairOfConsecutiveCodes() {
        assertTrue(matchesPair(-1, 1));
    }

    /** The same code in both fields is what a caller pasting one code twice would send. */
    @Test
    void rejectsTheSameCodeSubmittedTwice() {
        String code = Totp.codeAt(SEED, Totp.stepAt(NOW));
        assertFalse(VirtualMfaCodes.matchesConsecutiveCodes(SEED, code, code, 1, NOW));
    }

    @Test
    void rejectsTwoValidCodesThatAreNotConsecutive() {
        long step = Totp.stepAt(NOW);
        assertFalse(VirtualMfaCodes.matchesConsecutiveCodes(SEED,
                Totp.codeAt(SEED, step - 5), Totp.codeAt(SEED, step), 1, NOW));
    }

    /** A correctly ordered pair must not pass when reversed. */
    @Test
    void rejectsAConsecutivePairInTheWrongOrder() {
        long step = Totp.stepAt(NOW);
        assertFalse(VirtualMfaCodes.matchesConsecutiveCodes(SEED,
                Totp.codeAt(SEED, step), Totp.codeAt(SEED, step - 1), 1, NOW));
    }

    @Test
    void rejectsCodesFromADifferentSeed() {
        String otherSeed = Totp.newSecret();
        long step = Totp.stepAt(NOW);
        assertFalse(VirtualMfaCodes.matchesConsecutiveCodes(SEED,
                Totp.codeAt(otherSeed, step - 1), Totp.codeAt(otherSeed, step), 1, NOW));
    }

    @Test
    void rejectsAnythingThatIsNotSixDigits() {
        assertFalse(VirtualMfaCodes.matchesConsecutiveCodes(SEED, null, "123456", 1, NOW));
        assertFalse(VirtualMfaCodes.matchesConsecutiveCodes(SEED, "123456", null, 1, NOW));
        assertFalse(VirtualMfaCodes.matchesConsecutiveCodes(SEED, "12345", "123456", 1, NOW));
    }

    /**
     * The drift window is what separates enabling a device from resyncing one, so its edges are
     * worth pinning: a pair one step outside the allowance must fail at the width that excludes it
     * and pass at the width that includes it.
     */
    @Test
    void driftWindowBoundsAreExact() {
        assertFalse(matchesPair(-4, 2), "three steps behind is outside a two-step allowance");
        assertTrue(matchesPair(-4, 3), "and inside a three-step one");
    }
}
