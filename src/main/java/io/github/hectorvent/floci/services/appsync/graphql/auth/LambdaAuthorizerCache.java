package io.github.hectorvent.floci.services.appsync.graphql.auth;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class LambdaAuthorizerCache {

    static final int MAX_CACHEABLE_BYTES = 1_048_576;

    /**
     * Caps the number of distinct (API, token) entries held at once. Callers presenting many
     * distinct authorization tokens would otherwise grow this cache without bound; once the cap
     * is reached, the entry closest to expiry is evicted to make room for the new one.
     */
    static final int MAX_ENTRIES = 1_000;

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

    // Synchronized so the sweep, capacity check, eviction and insert happen as one step;
    // otherwise concurrent puts all see room (or evict the same entry) and overshoot the cap.
    public synchronized void put(String apiId, String token, LambdaAuthorizerResult result, int ttlSeconds) {
        if (result == null || ttlSeconds <= 0 || result.responseSizeBytes() >= MAX_CACHEABLE_BYTES) {
            return;
        }
        Instant now = clock.instant();
        String key = key(apiId, token);
        cache.entrySet().removeIf(entry -> !entry.getValue().expiresAt.isAfter(now));
        if (!cache.containsKey(key) && cache.size() >= MAX_ENTRIES) {
            evictClosestToExpiry();
        }
        cache.put(key, new Entry(result, now.plusSeconds(ttlSeconds)));
    }

    /**
     * Drops every cached entry for an API, e.g. when the API itself is deleted.
     */
    public void evictApi(String apiId) {
        String prefix = apiId + "\0";
        cache.keySet().removeIf(k -> k.startsWith(prefix));
    }

    private void evictClosestToExpiry() {
        cache.entrySet().stream()
                .min(Comparator.comparing(entry -> entry.getValue().expiresAt))
                .ifPresent(entry -> cache.remove(entry.getKey(), entry.getValue()));
    }

    static String key(String apiId, String token) {
        return apiId + "\0" + token;
    }

    int size() {
        return cache.size();
    }

    private record Entry(LambdaAuthorizerResult result, Instant expiresAt) {
    }
}
