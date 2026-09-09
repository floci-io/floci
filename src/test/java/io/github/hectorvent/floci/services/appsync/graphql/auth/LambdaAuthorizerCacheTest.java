package io.github.hectorvent.floci.services.appsync.graphql.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

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
