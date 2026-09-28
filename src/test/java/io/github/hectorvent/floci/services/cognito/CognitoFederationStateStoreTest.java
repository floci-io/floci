package io.github.hectorvent.floci.services.cognito;

import io.github.hectorvent.floci.services.cognito.model.CognitoAuthorizationCode;
import io.github.hectorvent.floci.services.cognito.model.CognitoAuthorizationTransaction;
import io.github.hectorvent.floci.services.cognito.model.CognitoManagedLoginSession;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CognitoFederationStateStoreTest {

    private static final Instant NOW = Instant.parse("2026-09-16T12:00:00Z");

    @Test
    void consumeTransaction_returnsStoredUnexpiredTransaction() {
        CognitoFederationStateStore store = storeAt(NOW);
        CognitoAuthorizationTransaction transaction = transaction(NOW.plusSeconds(60));

        String state = store.putTransaction(transaction);

        assertEquals(Optional.of(transaction), store.consumeTransaction(state));
    }

    @Test
    void consumeTransaction_expiredTransactionReturnsEmpty() {
        CognitoFederationStateStore store = storeAt(NOW);
        String state = store.putTransaction(transaction(NOW));

        assertTrue(store.consumeTransaction(state).isEmpty());
    }

    @Test
    void consumeTransaction_expiringAtMapMutationReturnsEmpty() throws ReflectiveOperationException {
        Instant expiresAt = NOW.plusSeconds(1);
        MutableClock clock = new MutableClock(NOW);
        CognitoFederationStateStore store = new CognitoFederationStateStore(clock);
        replaceStoreMap(store, "transactions", new ExpiringAtMutationMap<>(clock, expiresAt));
        String state = store.putTransaction(transaction(expiresAt));

        assertTrue(store.consumeTransaction(state).isEmpty());
    }

    @Test
    void consumeTransaction_concurrentRequestsReturnTransactionOnlyOnce() throws Exception {
        CognitoFederationStateStore store = storeAt(NOW);
        String state = store.putTransaction(transaction(NOW.plusSeconds(60)));
        int consumers = 16;
        CountDownLatch ready = new CountDownLatch(consumers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(consumers);

        try {
            List<Future<Optional<CognitoAuthorizationTransaction>>> results = IntStream.range(0, consumers)
                .mapToObj(ignored -> executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return store.consumeTransaction(state);
                }))
                .toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS));

            start.countDown();

            long successfulConsumptions = 0;
            for (Future<Optional<CognitoAuthorizationTransaction>> result : results) {
                if (result.get(5, TimeUnit.SECONDS).isPresent()) {
                    successfulConsumptions++;
                }
            }
            assertEquals(1, successfulConsumptions);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void consumeAuthorizationCode_returnsStoredUnexpiredCode() {
        CognitoFederationStateStore store = storeAt(NOW);
        CognitoAuthorizationCode authorizationCode = authorizationCode(NOW.plusSeconds(60));

        String code = store.putAuthorizationCode(authorizationCode);

        assertEquals(Optional.of(authorizationCode), store.consumeAuthorizationCode(code));
    }

    @Test
    void consumeAuthorizationCode_replayedCodeReturnsEmpty() {
        CognitoFederationStateStore store = storeAt(NOW);
        CognitoAuthorizationCode authorizationCode = authorizationCode(NOW.plusSeconds(60));
        String code = store.putAuthorizationCode(authorizationCode);

        assertEquals(Optional.of(authorizationCode), store.consumeAuthorizationCode(code));
        assertFalse(store.consumeAuthorizationCode(code).isPresent());
    }

    @Test
    void consumeAuthorizationCode_expiringAtMapMutationReturnsEmpty() throws ReflectiveOperationException {
        Instant expiresAt = NOW.plusSeconds(1);
        MutableClock clock = new MutableClock(NOW);
        CognitoFederationStateStore store = new CognitoFederationStateStore(clock);
        replaceStoreMap(store, "authorizationCodes", new ExpiringAtMutationMap<>(clock, expiresAt));
        String code = store.putAuthorizationCode(authorizationCode(expiresAt));

        assertTrue(store.consumeAuthorizationCode(code).isEmpty());
    }

    @Test
    void findSession_returnsStoredUnexpiredSessionUntilDeleted() {
        CognitoFederationStateStore store = storeAt(NOW);
        CognitoManagedLoginSession session = new CognitoManagedLoginSession("pool-id", "user", NOW.plusSeconds(60));

        String sessionId = store.putSession(session);

        assertEquals(Optional.of(session), store.findSession(sessionId));
        assertEquals(Optional.of(session), store.findSession(sessionId));
        store.deleteSession(sessionId);
        assertTrue(store.findSession(sessionId).isEmpty());
    }

    @Test
    void findSession_expiredSessionReturnsEmptyAndIsRemoved() throws ReflectiveOperationException {
        MutableClock clock = new MutableClock(NOW);
        CognitoFederationStateStore store = new CognitoFederationStateStore(clock);
        String sessionId = store.putSession(new CognitoManagedLoginSession("pool-id", "user", NOW.plusSeconds(60)));

        clock.set(NOW.plusSeconds(60));

        assertTrue(store.findSession(sessionId).isEmpty());
        assertTrue(storeMap(store, "sessions").isEmpty());
    }

    @Test
    void findSession_missingOrUnknownIdReturnsEmpty() {
        CognitoFederationStateStore store = storeAt(NOW);

        assertTrue(store.findSession(null).isEmpty());
        assertTrue(store.findSession("unknown").isEmpty());
        store.deleteSession(null);
    }

    @Test
    void putSession_idsAreOpaqueAndDistinct() {
        CognitoFederationStateStore store = storeAt(NOW);
        CognitoManagedLoginSession session = new CognitoManagedLoginSession("pool-id", "user", NOW.plusSeconds(60));

        String first = store.putSession(session);
        String second = store.putSession(session);

        assertFalse(first.equals(second));
        assertTrue(first.matches("[A-Za-z0-9_-]{43}"), first);
        assertFalse(first.contains("user") || first.contains("pool-id"));
    }

    private CognitoFederationStateStore storeAt(Instant now) {
        return new CognitoFederationStateStore(Clock.fixed(now, ZoneOffset.UTC));
    }

    private CognitoAuthorizationTransaction transaction(Instant expiresAt) {
        return new CognitoAuthorizationTransaction(
            "pool-id", "client-id", "https://example.com/callback", List.of("openid", "email"),
            "nonce", "ExampleOidc", null, null, expiresAt
        );
    }

    private CognitoAuthorizationCode authorizationCode(Instant expiresAt) {
        return new CognitoAuthorizationCode(
            "pool-id", "client-id", "user-id", "https://example.com/callback", List.of("openid", "email"),
            null, null, expiresAt
        );
    }

    private void replaceStoreMap(CognitoFederationStateStore store, String fieldName,
                                 ConcurrentHashMap<?, ?> replacement) throws ReflectiveOperationException {
        Field field = CognitoFederationStateStore.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(store, replacement);
    }

    private ConcurrentHashMap<?, ?> storeMap(CognitoFederationStateStore store, String fieldName)
            throws ReflectiveOperationException {
        Field field = CognitoFederationStateStore.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (ConcurrentHashMap<?, ?>) field.get(store);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class ExpiringAtMutationMap<V> extends ConcurrentHashMap<String, V> {
        private final MutableClock clock;
        private final Instant expiresAt;

        private ExpiringAtMutationMap(MutableClock clock, Instant expiresAt) {
            this.clock = clock;
            this.expiresAt = expiresAt;
        }

        @Override
        public boolean remove(Object key, Object value) {
            clock.set(expiresAt);
            return super.remove(key, value);
        }

        @Override
        public V computeIfPresent(String key,
                                  BiFunction<? super String, ? super V, ? extends V> remappingFunction) {
            clock.set(expiresAt);
            return super.computeIfPresent(key, remappingFunction);
        }
    }
}
