package io.github.hectorvent.floci.services.cognito;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class CognitoTotp {
    // Match verification-code emulation; AWS does not publish an exact attempt threshold.
    static final int MAX_FAILED_ATTEMPTS = 5;
    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private CognitoTotp() {}

    static String newSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        StringBuilder encoded = new StringBuilder(32);
        int buffer = 0;
        int bits = 0;
        for (byte value : bytes) {
            buffer = (buffer << 8) | (value & 0xff);
            bits += 8;
            while (bits >= 5) {
                encoded.append(BASE32[(buffer >>> (bits - 5)) & 31]);
                bits -= 5;
            }
        }
        return encoded.toString();
    }

    static boolean validCode(String secret, String code, Instant now) {
        if (secret == null || code == null || !code.matches("[0-9]{6}")) {
            return false;
        }
        byte[] key = decode(secret);
        long step = Math.floorDiv(now.getEpochSecond(), 30);
        for (long candidate = step - 1; candidate <= step + 1; candidate++) {
            byte[] expected = codeAt(key, candidate).getBytes(StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(expected, code.getBytes(StandardCharsets.US_ASCII))) {
                return true;
            }
        }
        return false;
    }

    static String code(String secret, Instant now) {
        return codeAt(decode(secret), Math.floorDiv(now.getEpochSecond(), 30));
    }

    private static String codeAt(byte[] key, long counter) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] digest = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(counter).array());
            int offset = digest[digest.length - 1] & 15;
            int binary = ((digest[offset] & 127) << 24)
                    | ((digest[offset + 1] & 255) << 16)
                    | ((digest[offset + 2] & 255) << 8)
                    | (digest[offset + 3] & 255);
            return String.format(Locale.ROOT, "%06d", binary % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("TOTP HMAC is unavailable", e);
        }
    }

    private static byte[] decode(String secret) {
        byte[] decoded = new byte[secret.length() * 5 / 8];
        int buffer = 0;
        int bits = 0;
        int index = 0;
        for (int i = 0; i < secret.length(); i++) {
            int value = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(secret.charAt(i));
            if (value < 0) {
                throw new IllegalArgumentException("Invalid TOTP secret");
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                decoded[index++] = (byte) (buffer >>> (bits - 8));
                bits -= 8;
            }
        }
        return decoded;
    }
}
