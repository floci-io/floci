package io.github.hectorvent.floci.services.cognito;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Server-side SRP-6a helpers for AWS Cognito USER_SRP_AUTH flow.
 *
 * <p>Implements the "Caldera" variant used by Cognito:
 * <ul>
 *   <li>3072-bit prime N from RFC 5054</li>
 *   <li>g = 2</li>
 *   <li>k = SHA-256(N || pad(g))</li>
 *   <li>x = SHA-256(salt || SHA-256(poolName + username + ":" + password))</li>
 *   <li>Session key derived with HKDF using info = "Caldera Derived Key"</li>
 * </ul>
 */
final class CognitoSrpHelper {

    // RFC 5054 3072-bit prime
    static final String N_HEX =
        "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF" +
        "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF43" +
        "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF" +
        "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF43";

    // Caldera uses the same prime — exact hex from AWS Cognito SDK references
    private static final String PRIME_HEX =
        "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1" +
        "29024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
        "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245" +
        "E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
        "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3D" +
        "C2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
        "83655D23DCA3AD961C62F356208552BB9ED529077096966D" +
        "670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
        "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9" +
        "DE2BCBF6955817183995497CEA956AE515D2261898FA0510" +
        "15728E5A8AAAC42DAD33170D04507A33A85521ABDF1CBA64" +
        "ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7" +
        "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6B" +
        "F12FFA06D98A0864D87602733EC86A64521F2B18177B200C" +
        "BBE117577A615D6C770988C0BAD946E208E24FA074E5AB31" +
        "43DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF";

    static final BigInteger N = new BigInteger(PRIME_HEX, 16);
    static final BigInteger G = BigInteger.valueOf(2);

    // k = SHA-256(N || pad(g)) — Caldera convention
    static final BigInteger K;

    private static final int N_BYTES = (N.bitLength() + 7) / 8; // 384 for 3072-bit

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte[] INFO_BITS = "Caldera Derived Key".getBytes(StandardCharsets.UTF_8);

    static {
        try {
            byte[] nBytes = padTo(N.toByteArray(), N_BYTES);
            byte[] gBytes = padTo(G.toByteArray(), N_BYTES);
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update(nBytes);
            sha256.update(gBytes);
            K = new BigInteger(1, sha256.digest());
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private CognitoSrpHelper() {}

    // ──────────────────────────── Password verifier ────────────────────────────

    /**
     * Generates a 16-byte random salt (hex-encoded).
     */
    static String generateSalt() {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return HexFormat.of().formatHex(salt);
    }

    /**
     * Computes the SRP password verifier v = g^x mod N.
     *
     * @param poolName the portion of the user pool ID after the underscore (e.g. "ABC123456")
     * @param username the Cognito username
     * @param password the plaintext password
     * @param saltHex  hex-encoded salt
     * @return the verifier as a hex string
     */
    static String computeVerifier(String poolName, String username, String password, String saltHex) {
        BigInteger x = computeX(poolName, username, password, saltHex);
        BigInteger v = G.modPow(x, N);
        return v.toString(16);
    }

    // ──────────────────────────── Server B ────────────────────────────

    /**
     * Generates server's ephemeral private b and public B.
     *
     * @param verifierHex the stored SRP verifier (hex)
     * @return array of {bPrivate (hex), B (hex)}
     */
    static String[] generateServerB(String verifierHex) {
        BigInteger v = new BigInteger(verifierHex, 16);
        BigInteger b;
        BigInteger B;
        do {
            b = new BigInteger(256, RANDOM);
            BigInteger gB = G.modPow(b, N);
            B = K.multiply(v).add(gB).mod(N);
        } while (B.mod(N).equals(BigInteger.ZERO));
        return new String[]{b.toString(16), B.toString(16)};
    }

    // ──────────────────────────── Server session key ────────────────────────────

    /**
     * Computes the server-side session key from SRP parameters.
     *
     * @param aHex         client's public A (hex)
     * @param bHex         server's private b (hex)
     * @param bPublicHex   server's public B (hex)
     * @param verifierHex  stored verifier (hex)
     * @return session key bytes (32 bytes)
     */
    static byte[] computeSessionKey(String aHex, String bHex, String bPublicHex, String verifierHex) {
        BigInteger A = new BigInteger(aHex, 16);
        BigInteger b = new BigInteger(bHex, 16);
        BigInteger B = new BigInteger(bPublicHex, 16);
        BigInteger v = new BigInteger(verifierHex, 16);

        BigInteger u = computeU(A, B);
        // S = (A * v^u)^b mod N
        BigInteger base = A.multiply(v.modPow(u, N)).mod(N);
        BigInteger S = base.modPow(b, N);

        // Derive key using Caldera interleaved hash
        return deriveCalderaKey(S);
    }

    // ──────────────────────────── HMAC signature ────────────────────────────

    /**
     * Computes the expected PASSWORD_CLAIM_SIGNATURE.
     *
     * @param sessionKey   derived session key bytes
     * @param userPoolId   full user pool ID (e.g., "us-east-1_ABC123")
     * @param username     Cognito username
     * @param secretBlock  raw bytes of the SECRET_BLOCK
     * @param timestamp    formatted timestamp string sent by the client
     */
    static byte[] computeSignature(byte[] sessionKey, String userPoolId, String username,
                                   byte[] secretBlock, String timestamp) {
        try {
            byte[] hkdfKey = hkdf(sessionKey, INFO_BITS);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hkdfKey, "HmacSHA256"));
            mac.update(userPoolId.getBytes(StandardCharsets.UTF_8));
            mac.update(username.getBytes(StandardCharsets.UTF_8));
            mac.update(secretBlock);
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            return mac.doFinal();
        } catch (Exception e) {
            throw new RuntimeException("SRP signature computation failed", e);
        }
    }

    /**
     * Verifies the client's PASSWORD_CLAIM_SIGNATURE.
     */
    static boolean verifySignature(byte[] sessionKey, String userPoolId, String username,
                                   byte[] secretBlock, String timestamp, String claimSignatureBase64) {
        byte[] expected = computeSignature(sessionKey, userPoolId, username, secretBlock, timestamp);
        byte[] claimed;
        try {
            claimed = Base64.getDecoder().decode(claimSignatureBase64);
        } catch (Exception e) {
            return false;
        }
        return MessageDigest.isEqual(expected, claimed);
    }

    // ──────────────────────────── Helpers ────────────────────────────

    /**
     * Extracts the pool name (part after the underscore) from the full pool ID.
     * e.g., "us-east-1_ABC123" → "ABC123"
     */
    static String extractPoolName(String userPoolId) {
        int idx = userPoolId.indexOf('_');
        return idx >= 0 ? userPoolId.substring(idx + 1) : userPoolId;
    }

    // ──────────────────────────── Private ────────────────────────────

    private static BigInteger computeX(String poolName, String username, String password, String saltHex) {
        try {
            // inner = SHA-256(poolName + username + ":" + password)
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update(poolName.getBytes(StandardCharsets.UTF_8));
            sha256.update(username.getBytes(StandardCharsets.UTF_8));
            sha256.update(":".getBytes(StandardCharsets.UTF_8));
            sha256.update(password.getBytes(StandardCharsets.UTF_8));
            byte[] innerHash = sha256.digest();

            // x = SHA-256(pad(salt) || innerHash)
            byte[] saltBytes = HexFormat.of().parseHex(saltHex);
            sha256.reset();
            sha256.update(saltBytes);
            sha256.update(innerHash);
            return new BigInteger(1, sha256.digest());
        } catch (Exception e) {
            throw new RuntimeException("SRP x computation failed", e);
        }
    }

    private static BigInteger computeU(BigInteger A, BigInteger B) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update(padTo(A.toByteArray(), N_BYTES));
            sha256.update(padTo(B.toByteArray(), N_BYTES));
            return new BigInteger(1, sha256.digest());
        } catch (Exception e) {
            throw new RuntimeException("SRP u computation failed", e);
        }
    }

    /**
     * Caldera interleaved hash to derive session key from S.
     * SHA-256 is applied to even-indexed and odd-indexed bytes of S separately,
     * then interleaved.
     */
    private static byte[] deriveCalderaKey(BigInteger S) {
        try {
            byte[] sBytes = padTo(S.toByteArray(), N_BYTES);

            // Split into even/odd positions
            byte[] even = new byte[N_BYTES / 2];
            byte[] odd = new byte[N_BYTES / 2];
            for (int i = 0; i < N_BYTES / 2; i++) {
                even[i] = sBytes[i * 2];
                odd[i] = sBytes[i * 2 + 1];
            }

            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hashEven = sha256.digest(even);
            sha256.reset();
            byte[] hashOdd = sha256.digest(odd);

            // Interleave the two hashes
            byte[] result = new byte[64];
            for (int i = 0; i < 32; i++) {
                result[i * 2] = hashEven[i];
                result[i * 2 + 1] = hashOdd[i];
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Caldera key derivation failed", e);
        }
    }

    /**
     * HKDF extract-and-expand using SHA-256 (salt = zeroes, no extract step).
     * Compatible with Cognito's "Caldera Derived Key" derivation.
     */
    private static byte[] hkdf(byte[] ikm, byte[] info) throws Exception {
        // Extract: PRK = HMAC-SHA256(salt=zeroes_32, IKM)
        byte[] salt = new byte[32];
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);

        // Expand: T(1) = HMAC-SHA256(PRK, info || 0x01)
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update(info);
        mac.update((byte) 1);
        byte[] t1 = mac.doFinal();
        return Arrays.copyOf(t1, 32);
    }

    /**
     * Left-pads a byte array to the given length.
     * If the array has a leading 0x00 sign byte, it is stripped before padding.
     */
    static byte[] padTo(byte[] bytes, int length) {
        // Strip sign byte if present
        if (bytes.length > length && bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        if (bytes.length == length) {
            return bytes;
        }
        byte[] padded = new byte[length];
        int offset = length - bytes.length;
        System.arraycopy(bytes, 0, padded, offset, bytes.length);
        return padded;
    }
}
