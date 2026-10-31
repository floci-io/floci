package io.github.hectorvent.floci.core.common;

import java.time.Instant;

/** Converts an Instant to millisecond-precision epoch seconds for AWS JSON timestamps. */
public final class EpochSeconds {

    private EpochSeconds() {
    }

    public static double fromInstant(Instant instant) {
        return instant.toEpochMilli() / 1000.0;
    }
}
