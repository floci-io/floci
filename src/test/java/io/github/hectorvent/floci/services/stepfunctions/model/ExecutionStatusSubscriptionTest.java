package io.github.hectorvent.floci.services.stepfunctions.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionStatusSubscriptionTest {
    @Test
    void completionBeforeRegistrationDoesNotPark() throws Exception {
        Execution child = new Execution();
        child.setStatus("SUCCEEDED");
        try (ExecutionStatusSubscription subscription = new ExecutionStatusSubscription(child, null)) {
            assertTrue(subscription.version() > 0);
            assertNull(registrations(child));
        }
    }

    @Test
    void completionBetweenStateReadAndParkingCannotBeLost() throws Exception {
        Execution child = new Execution();
        try (ExecutionStatusSubscription subscription = new ExecutionStatusSubscription(child, null)) {
            long observed = subscription.version();
            child.setOutput("{\"answer\":42}");
            child.setStatus("SUCCEEDED");
            assertTimeoutPreemptively(Duration.ofSeconds(1),
                    () -> assertTrue(subscription.awaitChange(observed, TimeUnit.MINUTES.toNanos(1))));
            assertEquals("{\"answer\":42}", child.getOutput());
            assertNull(registrations(child));
        }
    }

    @Test
    void parentTerminationWakesTheWaitAndCloseReleasesBothRegistrations() throws Exception {
        Execution child = new Execution();
        Execution parent = new Execution();
        try (ExecutionStatusSubscription subscription = new ExecutionStatusSubscription(child, parent);
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            long observed = subscription.version();
            CountDownLatch entered = new CountDownLatch(1);
            CompletableFuture<Boolean> result = CompletableFuture.supplyAsync(() -> {
                entered.countDown();
                try {
                    return subscription.awaitChange(observed, TimeUnit.SECONDS.toNanos(5));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }, executor);
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            parent.setStatus("ABORTED");
            assertTrue(result.get(1, TimeUnit.SECONDS));
            assertEquals("RUNNING", child.getStatus());
        }
        assertNull(registrations(child));
        assertNull(registrations(parent));
    }

    @Test
    void unrelatedExecutionDoesNotChangeTheObservedVersion() throws Exception {
        Execution child = new Execution();
        Execution parent = new Execution();
        try (ExecutionStatusSubscription subscription = new ExecutionStatusSubscription(child, parent)) {
            long observed = subscription.version();
            new Execution().setStatus("SUCCEEDED");
            assertEquals(observed, subscription.version());
            assertFalse(subscription.awaitChange(observed, 0));
        }
    }

    @Test
    void timeoutAndCancellationDoNotManufactureTerminalStatus() throws Exception {
        Execution child = new Execution();
        Execution parent = new Execution();
        try (ExecutionStatusSubscription subscription = new ExecutionStatusSubscription(child, parent)) {
            long observed = subscription.version();
            assertFalse(subscription.awaitChange(observed, TimeUnit.MILLISECONDS.toNanos(1)));
            subscription.cancel();
            assertTrue(subscription.cancelled());
            assertTrue(subscription.awaitChange(observed, TimeUnit.MINUTES.toNanos(1)));
            assertEquals("RUNNING", child.getStatus());
            assertEquals("RUNNING", parent.getStatus());
        }
        assertNull(registrations(child));
        assertNull(registrations(parent));
    }

    @Test
    void wakeWithoutStatusChangeReturnsToTheExecutorsDeadlineClock() throws Exception {
        Execution child = new Execution();
        try (ExecutionStatusSubscription subscription = new ExecutionStatusSubscription(child, null);
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            AtomicReference<Thread> waiter = new AtomicReference<>();
            CompletableFuture<Boolean> result = CompletableFuture.supplyAsync(() -> {
                waiter.set(Thread.currentThread());
                try {
                    return subscription.awaitChange(subscription.version(), TimeUnit.MINUTES.toNanos(1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }, executor);
            try {
                await().atMost(Duration.ofSeconds(2)).until(() -> waiter.get() != null
                        && waiter.get().getState() == Thread.State.TIMED_WAITING);
                synchronized (subscription) {
                    subscription.notifyAll();
                }
                assertFalse(result.get(1, TimeUnit.SECONDS));
                assertEquals("RUNNING", child.getStatus());
            } finally {
                subscription.cancel();
            }
        }
    }

    @Test
    void duplicateTargetsAndRepeatedCloseLeaveNoRegistration() throws Exception {
        Execution child = new Execution();
        ExecutionStatusSubscription subscription = new ExecutionStatusSubscription(child, child);
        subscription.close();
        subscription.close();
        assertNull(registrations(child));
    }

    @Test
    void ephemeralRegistrationsAreAbsentFromPersistedExecution() throws Exception {
        Execution child = new Execution();
        ObjectMapper mapper = new ObjectMapper();
        String before = mapper.writeValueAsString(child);
        try (ExecutionStatusSubscription subscription = new ExecutionStatusSubscription(child, null)) {
            assertEquals(before, mapper.writeValueAsString(child));
            Execution restored = mapper.readValue(before, Execution.class);
            assertNull(registrations(restored));
            assertEquals("RUNNING", restored.getStatus());
        }
    }

    private static Object registrations(Execution execution) throws Exception {
        Field field = Execution.class.getDeclaredField("statusSubscriptions");
        field.setAccessible(true);
        return field.get(execution);
    }
}
