package io.github.hectorvent.floci.core.common;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;

/**
 * Time-based one-time passwords as specified in RFC 6238: HMAC-SHA1 over the number of 30-second
 * steps since the epoch, truncated to six digits by RFC 4226's dynamic truncation.
 *
 * <p>Lifted out of {@code CognitoTotp}, which is now a thin view over it, because the same
 * arithmetic is needed wherever a service hands a secret to an authenticator app and then verifies
 * what the app produces: Cognito's software-token MFA, and IAM's virtual MFA devices through
 * {@code VirtualMfaCodes}.
 *
 * <p>The secret is real randomness and the codes are really derived from it, so a caller that does
 * not hold the secret cannot produce an accepted code. Secrets cross the wire as RFC 4648 base32,
 * which is the form authenticator apps accept.
 *
 * <p>Everything here is JDK-only: {@link SecureRandom} for the secret and {@code javax.crypto}'s
 * HMAC-SHA1 for the codes.
 */
public final class Totp {

    /** The RFC 6238 time step. A code is valid for the 30-second window it was generated in. */
    static final int STEP_SECONDS = 30;

    /** RFC 4648 base32, the alphabet authenticator apps expect. */
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    /** 160 bits, the secret size RFC 4226 recommends for HMAC-SHA1. */
    private static final int SECRET_BYTES = 20;
    private static final int DIGITS = 6;
    private static final int DIGITS_MODULUS = 1_000_000;

    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    /** A fresh base32-encoded secret, the form handed to an authenticator app. */
    public static String newSecret() {
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        return base32Encode(secret);
    }

    /** The step counter current at {@code now}, which is what {@link #codeAt} is keyed on. */
    public static long stepAt(Instant now) {
        return Math.floorDiv(now.getEpochSecond(), STEP_SECONDS);
    }

    /** The six-digit password for a given step counter. */
    public static String codeAt(String base32Secret, long counter) {
        byte[] hash;
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(base32Decode(base32Secret), "HmacSHA1"));
            hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(counter).array());
        } catch (GeneralSecurityException e) {
            // HmacSHA1 is required of every JDK, so this is not a runtime condition.
            throw new IllegalStateException("HMAC-SHA1 is unavailable", e);
        }
        int offset = hash[hash.length - 1] & 0x0F;
        int binary = ((hash[offset] & 0x7F) << 24)
                | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8)
                | (hash[offset + 3] & 0xFF);
        return String.format(Locale.ROOT, "%06d", binary % DIGITS_MODULUS);
    }

    /**
     * Whether {@code code} is the password for {@code base32Secret} at any step within
     * {@code driftSteps} either side of the one current at {@code now}, which absorbs clock skew
     * between the authenticator and this process.
     */
    public static boolean matchesCode(String base32Secret, String code, int driftSteps, Instant now) {
        if (base32Secret == null || !isSixDigits(code)) {
            return false;
        }
        long current = stepAt(now);
        for (long counter = current - driftSteps; counter <= current + driftSteps; counter++) {
            if (codeEquals(codeAt(base32Secret, counter), code)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the value has the shape of a code at all, before any secret is touched. */
    public static boolean isSixDigits(String code) {
        if (code == null || code.length() != DIGITS) {
            return false;
        }
        for (int i = 0; i < code.length(); i++) {
            if (code.charAt(i) < '0' || code.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    /** Length-independent comparison, so a wrong code does not leak its prefix through timing. */
    public static boolean codeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII));
    }

    static String base32Encode(byte[] data) {
        StringBuilder encoded = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                encoded.append(BASE32.charAt((buffer >> (bitsLeft - 5)) & 0x1F));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            encoded.append(BASE32.charAt((buffer << (5 - bitsLeft)) & 0x1F));
        }
        // No '=' padding: authenticator apps accept the unpadded form, and AWS's own secrets
        // carry none.
        return encoded.toString();
    }

    public static byte[] base32Decode(String encoded) {
        int buffer = 0;
        int bitsLeft = 0;
        byte[] decoded = new byte[encoded.length() * 5 / 8];
        int written = 0;
        for (int i = 0; i < encoded.length(); i++) {
            int value = BASE32.indexOf(Character.toUpperCase(encoded.charAt(i)));
            if (value < 0) {
                throw new IllegalArgumentException("Not a base32 character: " + encoded.charAt(i));
            }
            buffer = (buffer << 5) | value;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                decoded[written++] = (byte) ((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }
        return decoded;
    }
}
