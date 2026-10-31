package io.github.hectorvent.floci.services.cognito;

import io.github.hectorvent.floci.core.common.Totp;

import java.time.Instant;
import java.util.OptionalLong;

/**
 * Cognito's software-token MFA view of {@link Totp}: a single code, accepted one step either side
 * of the current window, plus the attempt budget a challenge gets.
 */
final class CognitoTotp {
    // Match verification-code emulation; AWS does not publish an exact attempt threshold.
    static final int MAX_FAILED_ATTEMPTS = 5;
    /** Cognito accepts the adjacent windows as well as the current one. */
    private static final int DRIFT_STEPS = 1;

    private CognitoTotp() {}

    static String newSecret() {
        return Totp.newSecret();
    }

    static boolean validCode(String secret, String code, Instant now) {
        return Totp.matchesCode(secret, code, DRIFT_STEPS, now);
    }

    /** The step {@code code} belongs to, or empty when it is not a valid code for {@code secret}. */
    static OptionalLong matchingStep(String secret, String code, Instant now) {
        return Totp.matchingStep(secret, code, DRIFT_STEPS, now);
    }

    static String code(String secret, Instant now) {
        return Totp.codeAt(secret, Totp.stepAt(now));
    }
}
