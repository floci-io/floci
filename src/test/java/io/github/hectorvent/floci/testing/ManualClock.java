package io.github.hectorvent.floci.testing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** A thread-safe clock that moves only when advanced; zoned views share the same timeline. */
public final class ManualClock extends Clock {
    private final AtomicReference<Instant> current;
    private final ZoneId zone;

    public ManualClock(Instant initial) {
        this(new AtomicReference<>(Objects.requireNonNull(initial)), ZoneOffset.UTC);
    }

    private ManualClock(AtomicReference<Instant> current, ZoneId zone) {
        this.current = current;
        this.zone = Objects.requireNonNull(zone);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new ManualClock(current, zone);
    }

    @Override
    public Instant instant() {
        return current.get();
    }

    public void advance(Duration duration) {
        current.updateAndGet(now -> now.plus(duration));
    }
}
