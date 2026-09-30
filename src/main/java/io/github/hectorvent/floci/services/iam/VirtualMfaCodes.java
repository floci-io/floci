package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.Totp;

import java.time.Instant;

/**
 * The one authentication-code rule specific to IAM. EnableMFADevice and ResyncMFADevice each take
 * two codes, and AWS asks for the second to be "a subsequent authentication code", so the pair has
 * to be consecutive outputs of the device's seed. Checking each code on its own would accept the
 * same code submitted twice, which no real authenticator would produce.
 *
 * <p>The seed and the code arithmetic itself are {@link Totp}, shared with Cognito's
 * software-token MFA.
 */
final class VirtualMfaCodes {

    private VirtualMfaCodes() {
    }

    /**
     * Whether {@code code1} and {@code code2} are two consecutive passwords from
     * {@code base32Seed}, with the pair allowed to start up to {@code driftSteps} windows either
     * side of the one current at {@code now}.
     */
    static boolean matchesConsecutiveCodes(String base32Seed, String code1, String code2,
                                           int driftSteps, Instant now) {
        if (base32Seed == null || !Totp.isSixDigits(code1) || !Totp.isSixDigits(code2)) {
            return false;
        }
        long current = Totp.stepAt(now);
        // The pair is read as (previous window, current window), so the undrifted case has code1
        // one step back; drift widens the search symmetrically around that.
        for (long offset = -driftSteps - 1; offset <= driftSteps - 1; offset++) {
            if (Totp.codeEquals(Totp.codeAt(base32Seed, current + offset), code1)
                    && Totp.codeEquals(Totp.codeAt(base32Seed, current + offset + 1), code2)) {
                return true;
            }
        }
        return false;
    }
}
