package io.github.hectorvent.floci.services.appsync.graphql.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LambdaAuthorizerCacheTest {

    @Test
    void cacheHitReturnsSameResultWithinTtl() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        LambdaAuthorizerCache cache = new LambdaAuthorizerCache(clock);
        LambdaAuthorizerResult result = new LambdaAuthorizerResult(
                true, List.of("Query.hello"), Map.of("apple", "green"), 10, 100);
        cache.put("api-1", "tok", result, 30);

        assertEquals(result.deniedFields(), cache.get("api-1", "tok").orElseThrow().deniedFields());
        assertEquals("green", cache.get("api-1", "tok").orElseThrow().resolverContext().get("apple"));
    }

    @Test
    void oversizedResponseIsNotCached() {
        LambdaAuthorizerCache cache = new LambdaAuthorizerCache();
        LambdaAuthorizerResult result = new LambdaAuthorizerResult(
                true, List.of(), Map.of(), 10, LambdaAuthorizerCache.MAX_CACHEABLE_BYTES);
        cache.put("api-1", "tok", result, 30);
        assertTrue(cache.get("api-1", "tok").isEmpty());
    }

    @Test
    void ttlOverrideZeroDoesNotCache() {
        LambdaAuthorizerCache cache = new LambdaAuthorizerCache();
        LambdaAuthorizerResult result = new LambdaAuthorizerResult(true, List.of(), Map.of(), 0, 10);
        cache.put("api-1", "tok", result, 0);
        assertTrue(cache.get("api-1", "tok").isEmpty());
    }

    @Test
    void expiredEntryIsMiss() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        LambdaAuthorizerCache cache = new LambdaAuthorizerCache(clock);
        cache.put("api-1", "tok", new LambdaAuthorizerResult(true, List.of(), Map.of(), 5, 10), 5);
        clock.advance(Duration.ofSeconds(6));
        assertTrue(cache.get("api-1", "tok").isEmpty());
    }

    @Test
    void distinctTokensNeverExceedCapacity() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        LambdaAuthorizerCache cache = new LambdaAuthorizerCache(clock);
        LambdaAuthorizerResult result = new LambdaAuthorizerResult(true, List.of(), Map.of(), 300, 10);

        for (int i = 0; i < LambdaAuthorizerCache.MAX_ENTRIES + 50; i++) {
            cache.put("api-1", "tok-" + i, result, 300);
        }

        assertTrue(cache.size() <= LambdaAuthorizerCache.MAX_ENTRIES,
                "cache grew to " + cache.size() + " entries, cap is " + LambdaAuthorizerCache.MAX_ENTRIES);
    }

    @Test
    void concurrentDistinctTokensNeverExceedCapacity() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        LambdaAuthorizerCache cache = new LambdaAuthorizerCache(clock);
        LambdaAuthorizerResult result = new LambdaAuthorizerResult(true, List.of(), Map.of(), 300, 10);
        int threads = 16;
        int putsPerThread = 500;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> puts = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int thread = t;
                puts.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < putsPerThread; i++) {
                        cache.put("api-1", "tok-" + thread + "-" + i, result, 300);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> put : puts) {
                put.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertTrue(cache.size() <= LambdaAuthorizerCache.MAX_ENTRIES,
                "cache grew to " + cache.size() + " entries, cap is " + LambdaAuthorizerCache.MAX_ENTRIES);
    }

    @Test
    void expiredEntriesAreSweptOnPutEvenWithoutBeingRead() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        LambdaAuthorizerCache cache = new LambdaAuthorizerCache(clock);
        LambdaAuthorizerResult result = new LambdaAuthorizerResult(true, List.of(), Map.of(), 5, 10);
        for (int i = 0; i < 10; i++) {
            cache.put("api-1", "tok-" + i, result, 5);
        }
        assertEquals(10, cache.size());

        clock.advance(Duration.ofSeconds(6));
        cache.put("api-1", "tok-new", result, 5);

        // The 10 short-lived entries expired without ever being read again; a put still sweeps
        // them, so only the freshly-added entry remains.
        assertEquals(1, cache.size());
    }

    @Test
    void evictApiRemovesOnlyThatApisEntries() {
        LambdaAuthorizerCache cache = new LambdaAuthorizerCache();
        LambdaAuthorizerResult result = new LambdaAuthorizerResult(true, List.of(), Map.of(), 300, 10);
        cache.put("api-1", "tok", result, 300);
        cache.put("api-2", "tok", result, 300);

        cache.evictApi("api-1");

        assertTrue(cache.get("api-1", "tok").isEmpty());
        assertTrue(cache.get("api-2", "tok").isPresent());
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private MutableClock(Instant instant) {
            this.instant = instant;
        }
        void advance(Duration duration) {
            instant = instant.plus(duration);
        }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
