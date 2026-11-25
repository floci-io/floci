package io.floci.aws.services.eventbridgev2;

import io.floci.aws.core.common.Base64Url;
import io.floci.aws.core.common.Digests;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * The host id and the path token of a webhook URL. The token holds 32 random bytes and the first 16 bytes of the
 * SHA-256 of the host id and those bytes.
 */
final class WebhookTokens {
    private static final int HOST_ID_LENGTH = 38;
    private static final String TOKEN_PREFIX = "k1";

    private static final String HOST_ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz234567";
    private static final int RANDOM_BYTES = 32;
    private static final int CHECK_BYTES = 16;

    private WebhookTokens() {
    }

    static String newHostId(SecureRandom random) {
        StringBuilder id = new StringBuilder(HOST_ID_LENGTH).append('a');
        while (id.length() < HOST_ID_LENGTH) {
            id.append(HOST_ID_ALPHABET.charAt(random.nextInt(HOST_ID_ALPHABET.length())));
        }
        return id.toString();
    }

    static String newToken(String hostId, SecureRandom random) {
        byte[] payload = new byte[RANDOM_BYTES];
        random.nextBytes(payload);
        byte[] token = Arrays.copyOf(payload, RANDOM_BYTES + CHECK_BYTES);
        System.arraycopy(check(hostId, payload), 0, token, RANDOM_BYTES, CHECK_BYTES);
        return TOKEN_PREFIX + Base64Url.encode(token);
    }

    /** Whether {@code token} is one {@link #newToken} built for {@code hostId}. */
    static boolean isValid(String hostId, String token) {
        if (!token.startsWith(TOKEN_PREFIX)) {
            return false;
        }
        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(token.substring(TOKEN_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (decoded.length != RANDOM_BYTES + CHECK_BYTES) {
            return false;
        }
        byte[] expected = check(hostId, Arrays.copyOf(decoded, RANDOM_BYTES));
        return MessageDigest.isEqual(expected, Arrays.copyOfRange(decoded, RANDOM_BYTES, decoded.length));
    }

    private static byte[] check(String hostId, byte[] payload) {
        byte[] hostBytes = hostId.getBytes(StandardCharsets.US_ASCII);
        byte[] input = Arrays.copyOf(hostBytes, hostBytes.length + payload.length);
        System.arraycopy(payload, 0, input, hostBytes.length, payload.length);
        return Arrays.copyOf(Digests.sha256(input), CHECK_BYTES);
    }
}
