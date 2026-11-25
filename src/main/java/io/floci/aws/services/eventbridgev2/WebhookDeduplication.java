package io.floci.aws.services.eventbridgev2;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The request hashes each webhook event source saw in the last five minutes, for {@code CONTENT_BASED}
 * deduplication. They are kept in memory only.
 */
final class WebhookDeduplication {
    static final Duration WINDOW = Duration.ofMinutes(5);

    private final Clock clock;
    private final ConcurrentMap<String, Map<String, Instant>> seenBySource = new ConcurrentHashMap<>();

    WebhookDeduplication(Clock clock) {
        this.clock = clock;
    }

    /** Records the hash for the source and returns true, or returns false when the source saw it in the window. */
    boolean firstSeen(String sourceArn, String hash) {
        Instant now = clock.instant();
        Instant oldest = now.minus(WINDOW);
        Map<String, Instant> seen = seenBySource.computeIfAbsent(sourceArn, arn -> new LinkedHashMap<>());
        synchronized (seen) {
            // The hashes are in the order they were first seen, so the old ones are at the head.
            Iterator<Instant> times = seen.values().iterator();
            while (times.hasNext() && !times.next().isAfter(oldest)) {
                times.remove();
            }
            Instant first = seen.get(hash);
            if (first != null && first.isAfter(oldest)) {
                return false;
            }
            seen.remove(hash);
            seen.put(hash, now);
            return true;
        }
    }

    void forget(String sourceArn, String hash) {
        Map<String, Instant> seen = seenBySource.get(sourceArn);
        if (seen != null) {
            synchronized (seen) {
                seen.remove(hash);
            }
        }
    }

    void forget(String sourceArn) {
        seenBySource.remove(sourceArn);
    }

    void clear() {
        seenBySource.clear();
    }
}
