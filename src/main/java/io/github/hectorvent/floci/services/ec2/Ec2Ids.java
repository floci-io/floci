package io.github.hectorvent.floci.services.ec2;

import java.util.concurrent.ThreadLocalRandom;

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
}
