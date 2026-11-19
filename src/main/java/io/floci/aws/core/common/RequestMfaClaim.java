package io.floci.aws.core.common;

import java.time.Instant;

/**
 * The MFA a request claimed and whether this emulator could make the claim good, computed once per
 * request because a one time code is spent when it authenticates.
 *
 * @param claimed whether the request named either MFA member at all
 * @param authenticatedAt when the device was used, or null when the claim failed
 * @param failure which half of the claim failed, null unless it did
 */
public record RequestMfaClaim(boolean claimed, Instant authenticatedAt, Failure failure) {

    /**
     * Which half of an MFA claim failed, because AWS answers the two with different sentences: a
     * serial that is not the caller's device is one refusal, a code that does not match it
     * another, and a caller cannot tell them apart from the code or the status, both being 403
     * {@code AccessDenied}.
     */
    public enum Failure {

        /** No device of the caller's answers to the serial the request named. */
        SERIAL,

        /** The device answered, and the code was not one of its outputs or was already spent. */
        CODE
    }

    /** A request that named neither member, which claims nothing and refuses nothing. */
    public static final RequestMfaClaim NONE = new RequestMfaClaim(false, null, null);

    /** A claim this emulator made good at {@code authenticatedAt}. */
    public static RequestMfaClaim authenticated(Instant authenticatedAt) {
        return new RequestMfaClaim(true, authenticatedAt, null);
    }

    /** A claim naming a serial no device of the caller's answers to. */
    public static RequestMfaClaim unknownSerial() {
        return new RequestMfaClaim(true, null, Failure.SERIAL);
    }

    /** A claim whose code the caller's device did not produce, or produced for a spent step. */
    public static RequestMfaClaim invalidCode() {
        return new RequestMfaClaim(true, null, Failure.CODE);
    }

    /** A claim this emulator could not make good, which is the operation's to answer. */
    public boolean failed() {
        return claimed && authenticatedAt == null;
    }
}
