package io.github.hectorvent.floci.core.common;

import java.time.Instant;

/** Converts Instants and epoch milliseconds to millisecond-precision epoch seconds for AWS JSON timestamps. */
public final class EpochSeconds {

    private EpochSeconds() {
    }

    public static double fromInstant(Instant instant) {
        return fromMillis(instant.toEpochMilli());
    }

    public static double fromMillis(long epochMillis) {
        return epochMillis / 1000.0;
    }
}
