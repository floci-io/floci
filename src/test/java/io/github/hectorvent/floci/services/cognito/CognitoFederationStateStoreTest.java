package io.github.hectorvent.floci.services.cognito;

import io.github.hectorvent.floci.services.cognito.model.CognitoAuthorizationCode;
import io.github.hectorvent.floci.services.cognito.model.CognitoAuthorizationTransaction;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

    private CognitoFederationStateStore storeAt(Instant now) {
        return new CognitoFederationStateStore(Clock.fixed(now, ZoneOffset.UTC));
    }

    private CognitoAuthorizationTransaction transaction(Instant expiresAt) {
        return new CognitoAuthorizationTransaction(
            "pool-id", "client-id", "https://example.com/callback", List.of("openid", "email"),
            "nonce", "ExampleOidc", expiresAt
        );
    }

    private CognitoAuthorizationCode authorizationCode(Instant expiresAt) {
        return new CognitoAuthorizationCode(
            "pool-id", "client-id", "user-id", "https://example.com/callback", List.of("openid", "email"),
            expiresAt
        );
    }
}
