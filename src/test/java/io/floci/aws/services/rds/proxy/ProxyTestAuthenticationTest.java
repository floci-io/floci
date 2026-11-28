package io.floci.aws.services.rds.proxy;

import io.floci.aws.testing.ThreadStates;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyTestAuthenticationTest {
    @Test
    void authenticationIoFailureIsReportedToItsOwningTest() {
        IOException failure = new IOException("authentication failed");
        ProxyTestAuthentication authentication = ProxyTestAuthentication.start(() -> {
            throw failure;
        });
        assertSame(failure, assertThrows(ExecutionException.class, authentication::awaitAuthentication).getCause());
        assertSame(failure, assertThrows(ExecutionException.class, authentication::await).getCause());
        assertFalse(authentication.isAlive());
    }

    @Test
    void authenticationAssertionFailureIsReportedToItsOwningTest() {
        AssertionError failure = new AssertionError("authentication assertion failed");
        ProxyTestAuthentication authentication = ProxyTestAuthentication.start(() -> {
            throw failure;
        });
        assertSame(failure, assertThrows(ExecutionException.class, authentication::awaitAuthentication).getCause());
        assertSame(failure, assertThrows(ExecutionException.class, authentication::await).getCause());
        assertFalse(authentication.isAlive());
    }

    @Test
    void aRefusedSessionCompletesWithoutStartingABridge() throws Exception {
        ProxyTestAuthentication authentication = ProxyTestAuthentication.start(() -> {
            assertTrue(Thread.currentThread().isVirtual());
            return null;
        });
        assertNull(authentication.awaitAuthentication());
        authentication.await();
        assertFalse(authentication.isAlive());
    }

    @Test
    void cleanupWaitsForTheAuthenticatorToFinishItsSocketSetup() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ProxyTestAuthentication authentication = ProxyTestAuthentication.start(() -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        });
        ProxyTestTask<Void> cleanup = ProxyTestTask.start(() -> {
            assertNull(authentication.awaitAuthentication());
            return null;
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(Thread.State.TIMED_WAITING, ThreadStates.awaitState(cleanup.thread(),
                    Set.of(Thread.State.TIMED_WAITING, Thread.State.TERMINATED), Duration.ofSeconds(5),
                    "cleanup waiting for authentication to finish"));
            release.countDown();
            cleanup.await();
            authentication.await();
        } finally {
            release.countDown();
            cleanup.await();
            authentication.await();
        }
        assertFalse(authentication.isAlive());
    }
}
