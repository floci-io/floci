package io.floci.aws.services.paymentcryptography.crypto;

import org.bouncycastle.crypto.Digest;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.digests.SHA384Digest;
import org.bouncycastle.crypto.digests.SHA512Digest;

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
        Digest digest = digestFor(hash);
        int hashBytes = digest.getDigestSize();
        int reps = (length + hashBytes - 1) / hashBytes;
        byte[] out = new byte[reps * hashBytes];
        byte[] block = new byte[hashBytes];
        for (int i = 1; i <= reps; i++) {
            digest.reset();
            if (counterFirst) {
                digest.update(counter(i), 0, 4);
                digest.update(secret, 0, secret.length);
            } else {
                digest.update(secret, 0, secret.length);
                digest.update(counter(i), 0, 4);
            }
            digest.update(otherInfo, 0, otherInfo.length);
            digest.doFinal(block, 0);
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
     * The digest for a wire hash name.
     *
     * <p>The service names the digests with an underscore, and the library
     * knows them as the SHA-2 family.
     */
    private static Digest digestFor(String hash) {
        if ("SHA_256".equals(hash)) {
            return new SHA256Digest();
        }
        if ("SHA_384".equals(hash)) {
            return new SHA384Digest();
        }
        if ("SHA_512".equals(hash)) {
            return new SHA512Digest();
        }
        throw new IllegalArgumentException("unsupported digest '" + hash
                + "' (SHA_256, SHA_384, SHA_512)");
    }
}
