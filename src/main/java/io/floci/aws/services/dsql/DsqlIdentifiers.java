package io.floci.aws.services.dsql;

import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Aurora DSQL's identifiers: a UUID written as 26 characters of lowercase RFC 4648 base32 without
 * padding, the shape of cluster ids, job ids and session ids (measured: the UUID
 * {@code 00000000-0000-0000-0000-000000000001} is {@code aaaaaaaaaaaaaaaaaaaaaaaaae}). The 128 bits
 * leave two zero bits in the last character, so a string of the right alphabet and length can still
 * name no UUID, which DSQL refuses with its own message.
 */
public final class DsqlIdentifiers {

    /** The constraint DSQL's model puts on a cluster id, and the message it gives for one that fails it. */
    public static final Pattern CLUSTER_ID = Pattern.compile("[a-z0-9]{26}");

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz234567";

    private DsqlIdentifiers() {
    }

    public static String newId() {
        return encode(UUID.randomUUID());
    }

    public static String encode(UUID uuid) {
        ByteBuffer bytes = ByteBuffer.allocate(16);
        bytes.putLong(uuid.getMostSignificantBits());
        bytes.putLong(uuid.getLeastSignificantBits());
        StringBuilder out = new StringBuilder(26);
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes.array()) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        out.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        return out.toString();
    }

    /** The UUID {@code id} names, or empty when it is not 26 base32 characters naming one. */
    public static Optional<UUID> decode(String id) {
        if (id == null || id.length() != 26) {
            return Optional.empty();
        }
        long high = 0;
        long low = 0;
        int bitIndex = 0;
        for (char c : id.toLowerCase(Locale.ROOT).toCharArray()) {
            int value = ALPHABET.indexOf(c);
            if (value < 0) {
                return Optional.empty();
            }
            for (int i = 4; i >= 0; i--) {
                long bit = (value >> i) & 1;
                if (bitIndex < 64) {
                    high |= bit << (63 - bitIndex);
                } else if (bitIndex < 128) {
                    low |= bit << (127 - bitIndex);
                } else if (bit != 0) {
                    return Optional.empty();
                }
                bitIndex++;
            }
        }
        return Optional.of(new UUID(high, low));
    }
}
