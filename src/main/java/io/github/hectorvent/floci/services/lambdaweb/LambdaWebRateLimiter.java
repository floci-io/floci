package io.github.hectorvent.floci.services.lambdaweb;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * The {@code rateLimit} of each endpoint: a bucket of {@code rateLimit} requests that refills at
 * {@code rateLimit} per second, so a brief burst above the rate passes and a sustained one does not.
 */
final class LambdaWebRateLimiter {

    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

    private record Bucket(int rate, double tokens, long at) {
    }

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final LongSupplier nanoClock;
    private final AtomicLong sweptAt;

    LambdaWebRateLimiter() {
        this(System::nanoTime);
    }

    LambdaWebRateLimiter(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
        this.sweptAt = new AtomicLong(nanoClock.getAsLong());
    }

    /** Whether one more request to {@code endpointKey} fits under {@code rate} requests per second. */
    boolean tryAcquire(String endpointKey, int rate) {
        sweep();
        AtomicBoolean admitted = new AtomicBoolean();
        buckets.compute(endpointKey, (key, bucket) -> {
            double tokens;
            long now = nanoClock.getAsLong();
            if (bucket == null || bucket.rate() != rate) {
                tokens = rate;
            } else {
                now = Math.max(now, bucket.at());
                double refill = rate * (double) (now - bucket.at()) / SECOND;
                tokens = Math.min(rate, bucket.tokens() + refill);
            }
            if (tokens >= 1) {
                admitted.set(true);
                tokens -= 1;
            }
            return new Bucket(rate, tokens, now);
        });
        return admitted.get();
    }

    int size() {
        return buckets.size();
    }

    /** A bucket untouched for a second is full again, the same as no bucket. */
    private void sweep() {
        long now = nanoClock.getAsLong();
        long last = sweptAt.get();
        if (now - last >= SECOND && sweptAt.compareAndSet(last, now)) {
            buckets.values().removeIf(bucket -> now - bucket.at() >= SECOND);
        }
    }
}
