package io.github.hectorvent.floci.services.cognito.verification;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/**
 * Generates, persists, and validates Cognito verification codes used for
 * SignUp confirmation, password reset, MFA SMS, and attribute verification.
 *
 * <p>Codes are stored as SHA-256(code + per-code salt) so even a storage dump
 * cannot reveal a valid code. The plaintext code is returned to the caller
 * once (at {@code issue}) and never persisted.
 *
 * <p>Rate-limiting: re-issuing for the same (poolId, username, purpose) within
 * {@link #RATE_LIMIT_WINDOW} throws {@link VerificationCodeException.Kind#RATE_LIMIT}.
 * Attempts: after {@link #MAX_ATTEMPTS} failed {@code consume} calls the code
 * is invalidated.
 *
 * <p>The {@code ttl} is caller-supplied (per AWS the sign-up confirmation code is valid
 * 24h; the forgot-password code expires sooner, ~1h) — wiring layers must pass the right
 * value per purpose. {@link #RATE_LIMIT_WINDOW} (30s) and {@link #MAX_ATTEMPTS} (5) are
 * emulation heuristics: AWS throttles resends and invalidates after repeated failures, but
 * does not publish the exact thresholds.
 */
public final class VerificationCodeService {

    // Emulation heuristics (not AWS-documented constants): AWS throttles resend frequency and
    // invalidates a code after repeated wrong attempts, but the exact values are not published.
    private static final Duration RATE_LIMIT_WINDOW = Duration.ofSeconds(30);
    private static final int MAX_ATTEMPTS = 5;
    private static final int SALT_BYTES = 16;

    private final StorageBackend<String, VerificationCode> store;
    private final Clock clock;
    private final SecureRandom random;

    public VerificationCodeService(StorageFactory storageFactory, Clock clock) {
        this.store = storageFactory.create(
            "cognito",
            "cognito-verification-codes.json",
            new TypeReference<Map<String, VerificationCode>>() {}
        );
        this.clock = clock;
        this.random = new SecureRandom();
    }

    /**
     * Issue a new code for the given user/purpose. Returns the plaintext code
     * (caller must dispatch via SES/SNS). Subject to {@link #RATE_LIMIT_WINDOW}.
     */
    public String issue(String userPoolId, String username,
                        VerificationCode.Purpose purpose, Duration ttl) {
        String key = VerificationCode.storageKey(userPoolId, username, purpose);
        Optional<VerificationCode> existing = store.get(key);
        if (existing.isPresent() && !existing.get().isConsumed()) {
            Duration since = Duration.between(existing.get().getIssuedAt(), clock.instant());
            if (since.compareTo(RATE_LIMIT_WINDOW) < 0) {
                throw new VerificationCodeException(
                    VerificationCodeException.Kind.RATE_LIMIT,
                    "Attempt limit exceeded, please try again later");
            }
        }

        String code = String.format("%06d", random.nextInt(1_000_000));
        String salt = HexFormat.of().formatHex(randomBytes(SALT_BYTES));
        String hash = sha256(code + salt);
        Instant now = clock.instant();

        VerificationCode vc = new VerificationCode(
            userPoolId, username, purpose, hash, salt, now, now.plus(ttl), MAX_ATTEMPTS
        );
        store.put(key, vc);
        return code;
    }

    /**
     * Validate a code. On success, marks it consumed and removes it. On any
     * failure, throws {@link VerificationCodeException} with the specific
     * {@link VerificationCodeException.Kind}.
     *
     * <p>Note: not thread-safe under concurrent {@code consume()} of the same
     * (poolId, username, purpose) key — two racing wrong-code calls may
     * decrement the attempts counter by 1 instead of 2. Acceptable for the
     * single-user local-dev profile; revisit if floci grows multi-tenant.
     */
    public void consume(String userPoolId, String username,
                        VerificationCode.Purpose purpose, String code) {
        String key = VerificationCode.storageKey(userPoolId, username, purpose);
        VerificationCode vc = store.get(key).orElseThrow(() -> new VerificationCodeException(
            VerificationCodeException.Kind.NOT_FOUND,
            "Invalid verification code provided, please try again"));
        if (vc.isConsumed()) {
            throw new VerificationCodeException(
                VerificationCodeException.Kind.NOT_FOUND,
                "Invalid verification code provided, please try again");
        }
        if (vc.isExpired(clock.instant())) {
            store.delete(key);
            throw new VerificationCodeException(
                VerificationCodeException.Kind.EXPIRED,
                "Invalid code provided, please request a code again");
        }
        if (vc.getAttemptsRemaining() <= 0) {
            store.delete(key);
            // AWS returns the generic invalid-code message here too, never the
            // attempt counter (anti-enumeration). Matches the spec's
            // observation: same message as a wrong-code below.
            throw new VerificationCodeException(
                VerificationCodeException.Kind.MISMATCH,
                "Invalid verification code provided, please try again");
        }

        String expectedHash = sha256(code + vc.getSalt());
        if (!constantTimeEquals(expectedHash, vc.getCodeHash())) {
            vc.decrementAttempts();
            if (vc.getAttemptsRemaining() <= 0) {
                store.delete(key); // exhausted — invalidate so even the real code fails
            } else {
                store.put(key, vc);
            }
            throw new VerificationCodeException(
                VerificationCodeException.Kind.MISMATCH,
                "Invalid verification code provided, please try again");
        }

        vc.markConsumed();
        store.delete(key);
    }

    /** Remove any active code for the (pool, user, purpose). Idempotent. */
    public void invalidatePrevious(String userPoolId, String username,
                                   VerificationCode.Purpose purpose) {
        store.delete(VerificationCode.storageKey(userPoolId, username, purpose));
    }

    private byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return b;
    }

    private String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }
}
