package io.floci.aws.core.common;

import java.time.Instant;

/**
 * What one request's MFA members amount to: nothing claimed, a device presented and authenticated
 * at an instant, or a claim that failed.
 *
 * <p>AWS "provides two API operations that let users pass MFA information: GetSessionToken and
 * AssumeRole", and a claim either holds or is refused: "if a policy includes an MFA condition, a
 * request is denied if users have not been MFA authenticated, or if they provide an invalid MFA
 * device identifier or invalid TOTP".
 *
 * <p>It is read twice in one request and computed once, which is why it is held on the request
 * rather than recomputed where it is needed. The enforcement filter needs it because the policy for
 * the call is evaluated there, against the request that carries the device; the STS operation needs
 * it because it answers the refusal and mints the session, and because it runs whether or not
 * enforcement is on. Computing it twice would not do: a code is spent when it authenticates, so the
 * second reading would find the step taken and refuse what the first allowed.
 */
public record RequestMfaClaim(boolean claimed, Instant authenticatedAt) {

    /** A request that named neither member, which claims nothing and refuses nothing. */
    public static final RequestMfaClaim NONE = new RequestMfaClaim(false, null);

    /** A claim this emulator could not make good, which is the operation's to answer. */
    public boolean failed() {
        return claimed && authenticatedAt == null;
    }
}
