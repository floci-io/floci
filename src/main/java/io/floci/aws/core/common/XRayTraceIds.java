package io.floci.aws.core.common;

import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

/** Ids in the formats X-Ray uses in an {@code X-Amzn-Trace-Id} header. */
public final class XRayTraceIds {

    private static final int TRACE_RANDOM_BYTES = 12;

    private XRayTraceIds() {
    }

    /** {@code 1-<8 hex digits of epoch seconds>-<24 random hex digits>}. */
    public static String traceId(long epochSeconds) {
        byte[] id = new byte[TRACE_RANDOM_BYTES];
        ThreadLocalRandom.current().nextBytes(id);
        return "1-" + String.format("%08x", epochSeconds) + "-" + HexFormat.of().formatHex(id);
    }

    /** 16 random lowercase hex digits, never all zeros. */
    public static String segmentId() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long value;
        do {
            value = random.nextLong();
        } while (value == 0);
        return HexFormat.of().toHexDigits(value);
    }
}
