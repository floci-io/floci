package io.github.hectorvent.floci.services.redshift.proxy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedshiftBackendOwnershipTest {

    // Taking ownership only sets the backend socket's read timeout, so unconnected sockets will do.
    private final Socket client = new Socket();
    private final Socket backend = new Socket();

    @AfterEach
    void closeSockets() throws IOException {
        client.close();
        backend.close();
    }

    /**
     * The pump holds the backend lock for each idle read and takes it back as soon as it releases it. A
     * waiter whose timed attempt runs out while a read is in progress resumes only after the pump has
     * started its next read, so it must stay queued across that read to be handed the backend.
     */
    @Test
    @Timeout(10)
    void retainsItsPlaceWhileAnIdlePumpFinishesReading() throws Exception {
        DelayedWaiterLock lock = new DelayedWaiterLock();
        RedshiftInterceptingBridge bridge = new RedshiftInterceptingBridge(client, backend, lock,
                RedshiftInterceptingBridge.PUMP_PARK_WAIT_MS);
        AtomicBoolean stopped = new AtomicBoolean();
        AtomicReference<Throwable> pumpFailure = new AtomicReference<>();
        Thread pump = Thread.ofVirtual().start(() -> {
            try {
                while (!stopped.get()) {
                    lock.lockInterruptibly();
                    try {
                        lock.readStarted.release();
                        // Outlasts one 200 ms lock attempt (PUMP_READ_TIMEOUT_MS), well inside the 2 s deadline.
                        lock.endRead.tryAcquire(350, TimeUnit.MILLISECONDS);
                    } finally {
                        lock.unlock();
                    }
                }
            } catch (InterruptedException expected) {
                // The test stops the pump by interrupting it.
                Thread.currentThread().interrupt();
            } catch (Throwable failure) {
                pumpFailure.set(failure);
            }
        });
        try {
            assertTrue(lock.readStarted.tryAcquire(1, TimeUnit.SECONDS));
            assertTrue(bridge.runWithBackendOwned(() -> true),
                    "an idle pump must yield the backend before the ownership deadline");
            assertNull(pumpFailure.get());
        } finally {
            stopped.set(true);
            pump.interrupt();
            pump.join(1000);
            assertFalse(pump.isAlive());
        }
    }

    @Test
    @Timeout(10)
    void doesNotRunAnExchangeWhenTheBackendRemainsOwned() throws Exception {
        ReentrantLock lock = new ReentrantLock(true);
        // A short budget is enough to show the call gives up; the handoff case keeps the production one.
        RedshiftInterceptingBridge bridge = new RedshiftInterceptingBridge(client, backend, lock, 100);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread pump = Thread.ofVirtual().start(() -> {
            lock.lock();
            try {
                held.countDown();
                release.await();
            } catch (InterruptedException expected) {
                // Only an interrupt from outside the test could end the wait early; keep its status.
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        });
        try {
            assertTrue(held.await(1, TimeUnit.SECONDS));
            AtomicBoolean exchanged = new AtomicBoolean();
            assertFalse(bridge.runWithBackendOwned(() -> {
                exchanged.set(true);
                return true;
            }));
            assertFalse(exchanged.get());
        } finally {
            release.countDown();
            pump.join(1000);
            assertFalse(pump.isAlive());
        }
    }

    private static final class DelayedWaiterLock extends ReentrantLock {
        private final Semaphore readStarted = new Semaphore(0);
        private final Semaphore endRead = new Semaphore(0);

        private DelayedWaiterLock() {
            super(true);
        }

        @Override
        public boolean tryLock(long timeout, TimeUnit unit) throws InterruptedException {
            boolean acquired = super.tryLock(timeout, unit);
            if (!acquired) {
                // The timed-out waiter resumes only after the pump has finished this read and started the next.
                endRead.release();
                if (!readStarted.tryAcquire(1, TimeUnit.SECONDS)) {
                    throw new AssertionError("pump did not resume");
                }
            }
            return acquired;
        }
    }
}
