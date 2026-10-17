package io.github.hectorvent.floci.services.lambdaweb;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LambdaWebRateLimiterTest {

    private final AtomicLong now = new AtomicLong();
    private final LambdaWebRateLimiter limiter = new LambdaWebRateLimiter(now::get);

    @Test
    void aBurstUpToTheRatePassesAndTheNextRequestIsThrottled() {
        assertEquals(100, admitted("ep", 100, 150));
    }

    @Test
    void theBucketRefillsAtTheRatePerSecond() {
        assertEquals(100, admitted("ep", 100, 100));
        assertFalse(limiter.tryAcquire("ep", 100));
        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(500));
        assertEquals(50, admitted("ep", 100, 80));
        now.addAndGet(TimeUnit.SECONDS.toNanos(10));
        assertEquals(100, admitted("ep", 100, 150), "the bucket holds at most one second of requests");
    }

    @Test
    void endpointsHaveTheirOwnBucketsAndANewRateStartsAFullOne() {
        assertEquals(100, admitted("a", 100, 100));
        assertTrue(limiter.tryAcquire("b", 100));
        assertFalse(limiter.tryAcquire("a", 100));
        assertEquals(200, admitted("a", 200, 250));
    }

    @Test
    void aClockReadOutOfOrderTakesNoTokensBack() {
        now.set(1_000);
        assertTrue(limiter.tryAcquire("ep", 2));
        now.set(0);
        assertTrue(limiter.tryAcquire("ep", 2));
        assertFalse(limiter.tryAcquire("ep", 2));
    }

    @Test
    void bucketsThatRefilledAreDropped() {
        assertEquals(100, admitted("a", 100, 100));
        assertTrue(limiter.tryAcquire("b", 100));
        assertEquals(2, limiter.size());
        now.addAndGet(TimeUnit.SECONDS.toNanos(1));
        assertTrue(limiter.tryAcquire("c", 100));
        assertEquals(1, limiter.size());
        assertEquals(100, admitted("a", 100, 150));
    }

    private int admitted(String endpoint, int rate, int requests) {
        int admitted = 0;
        for (int i = 0; i < requests; i++) {
            if (limiter.tryAcquire(endpoint, rate)) {
                admitted++;
            }
        }
        return admitted;
    }
}
