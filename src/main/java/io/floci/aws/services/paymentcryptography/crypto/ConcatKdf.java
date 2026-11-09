package io.floci.aws.services.paymentcryptography.crypto;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * The NIST SP 800-56A concatenation KDF, as the service applies it.
 *
 * <p>It is the single-step KDF of SP 800-56A with the counter, the shared
 * secret and the shared information concatenated in that order:
 *
 * <pre>
 *   K = H(counter || Z || OtherInfo) for counter = 1, 2, ... truncated to length
 * </pre>
 *
 * <p>The other information is what binds the derived key to the two parties and
 * to the context, so the same shared secret derived under different shared
 * information gives a different key.
 */
public final class ConcatKdf {

    private ConcatKdf() {
    }

    /**
     * Derive key material from a shared secret.
     *
     * @param hash the digest algorithm: {@code SHA-256}, {@code SHA-384} or
     *              {@code SHA-512}
     * @param secret the shared secret
     * @param otherInfo the shared information
     * @param length how many bytes to derive
     * @return the derived key
     */
    public static byte[] derive(String hash, byte[] secret, byte[] otherInfo, int length) {
        return derive(hash, secret, otherInfo, length, true);
    }

    /**
     * The ANSI X9.63 key derivation.
     *
     * <p>The same construction as the SP 800-108 concatenation KDF, but with
     * the shared secret and the counter the other way round:
     *
     * <pre>
     *   K = H(Z || counter || OtherInfo)
     * </pre>
     *
     * <p>So the two derivations over the same secret give different keys, and
     * a caller has to say which one it means.
     */
    public static byte[] deriveX963(String hash, byte[] secret, byte[] otherInfo, int length) {
        return derive(hash, secret, otherInfo, length, false);
    }

    private static byte[] derive(String hash, byte[] secret, byte[] otherInfo, int length,
                                  boolean counterFirst) {
        if (length <= 0) {
            throw new IllegalArgumentException("the derived length must be positive");
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(digestFor(hash));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalArgumentException("unsupported digest '" + hash + "'", e);
        }
        int hashBytes = digest.getDigestLength();
        int reps = (length + hashBytes - 1) / hashBytes;
        byte[] out = new byte[reps * hashBytes];
        for (int i = 1; i <= reps; i++) {
            digest.reset();
            if (counterFirst) {
                digest.update(counter(i));
                digest.update(secret);
            } else {
                digest.update(secret);
                digest.update(counter(i));
            }
            digest.update(otherInfo);
            byte[] block = digest.digest();
            System.arraycopy(block, 0, out, (i - 1) * hashBytes, hashBytes);
        }
        return Arrays.copyOf(out, length);
    }

    /** The four-byte big-endian counter, starting at one. */
    private static byte[] counter(int n) {
        return new byte[] {
            (byte) (n >>> 24), (byte) (n >>> 16), (byte) (n >>> 8), (byte) n,
        };
    }

    /**
     * The JCE digest name for a wire hash name.
     *
     * <p>The service names the digests with an underscore, and the JCE knows
     * them with a hyphen.
     */
    private static String digestFor(String hash) {
        if ("SHA_256".equals(hash)) {
            return "SHA-256";
        }
        if ("SHA_384".equals(hash)) {
            return "SHA-384";
        }
        if ("SHA_512".equals(hash)) {
            return "SHA-512";
        }
        throw new IllegalArgumentException("unsupported digest '" + hash
                + "' (SHA_256, SHA_384, SHA_512)");
    }
}
