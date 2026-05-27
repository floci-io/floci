package io.github.hectorvent.floci.testing;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

@Alternative
@Priority(1)
@ApplicationScoped
public class MutableClock extends Clock {

    private static final Instant DEFAULT_INSTANT = Instant.parse("2026-01-01T00:00:00Z");

    private final AtomicReference<Instant> current = new AtomicReference<>(DEFAULT_INSTANT);
    private final ZoneId zone = ZoneOffset.UTC;

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return current.getAndUpdate(now -> now.plusMillis(1));
    }

    public void reset() {
        current.set(DEFAULT_INSTANT);
    }

    public void advance(Duration duration) {
        current.updateAndGet(now -> now.plus(duration));
    }
}
