package io.floci.aws.services.ec2;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.CRC32;

/** The random parts of the resource ids EC2 mints. */
final class Ec2Ids {

    private static final String HEX = "0123456789abcdef";

    private Ec2Ids() {
    }

    /** {@code length} lowercase hex digits, as the suffix of an id like {@code subnet-0a1b2c3d}. */
    static String randomHex(int length) {
        StringBuilder sb = new StringBuilder(length);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < length; i++) {
            sb.append(HEX.charAt(random.nextInt(HEX.length())));
        }
        return sb.toString();
    }

    /** Deterministic 17-hex-digit suffix from a seed string, stable across restarts. */
    static String stableHex(String seed) {
        CRC32 crc1 = new CRC32();
        crc1.update(seed.getBytes(StandardCharsets.UTF_8));
        CRC32 crc2 = new CRC32();
        crc2.update((seed + "|second").getBytes(StandardCharsets.UTF_8));
        long combined = (crc1.getValue() << 32) ^ crc2.getValue();
        return String.format("%017x", combined & 0x1FFFFFFFFFFFFFFFL);
    }

    /** Deterministic ENI id from a seed string, stable across restarts. */
    static String eniIdFromSeed(String seed) {
        String hex = UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8))
                .toString().replace("-", "");
        return "eni-" + hex.substring(0, 17);
    }
}
