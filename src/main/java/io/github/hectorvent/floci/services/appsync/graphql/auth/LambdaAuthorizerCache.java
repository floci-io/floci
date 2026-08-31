package io.github.hectorvent.floci.services.appsync.graphql.auth;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class LambdaAuthorizerCache {

    static final int MAX_CACHEABLE_BYTES = 1_048_576;

    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();
    private final Clock clock;

    public LambdaAuthorizerCache() {
        this(Clock.systemUTC());
    }

    LambdaAuthorizerCache(Clock clock) {
        this.clock = clock;
    }

    public Optional<LambdaAuthorizerResult> get(String apiId, String token) {
        Entry entry = cache.get(key(apiId, token));
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAt.isBefore(clock.instant()) || entry.expiresAt.equals(clock.instant())) {
            cache.remove(key(apiId, token), entry);
            return Optional.empty();
        }
        return Optional.of(entry.result);
    }

    public void put(String apiId, String token, LambdaAuthorizerResult result, int ttlSeconds) {
        if (result == null || ttlSeconds <= 0 || result.responseSizeBytes() >= MAX_CACHEABLE_BYTES) {
            return;
        }
        Instant expiresAt = clock.instant().plusSeconds(ttlSeconds);
        cache.put(key(apiId, token), new Entry(result, expiresAt));
    }

    static String key(String apiId, String token) {
        return apiId + "\0" + token;
    }

    private record Entry(LambdaAuthorizerResult result, Instant expiresAt) {
    }
}
