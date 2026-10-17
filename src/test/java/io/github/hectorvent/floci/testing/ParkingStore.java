package io.github.hectorvent.floci.testing;

import io.github.hectorvent.floci.core.storage.InMemoryStorage;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An in-memory store that, once armed, parks the next read (after reading) or the next write
 * (before writing) until released, so a test can hold one request inside its read-modify-write
 * window while a second one arrives. Parks once per instance. An interrupt, such as the
 * {@code shutdownNow} a test runs in its {@code finally}, releases a parked thread.
 */
public final class ParkingStore<V> extends InMemoryStorage<String, V> {

    private final AtomicBoolean parkNextRead = new AtomicBoolean();
    private final AtomicBoolean parkNextWrite = new AtomicBoolean();
    private final CountDownLatch parked = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);

    /** Parks the next {@code get}, after it has read the value. */
    public void parkNextRead() {
        parkNextRead.set(true);
    }

    /** Parks the next {@code put}, before it writes the value. */
    public void parkNextWrite() {
        parkNextWrite.set(true);
    }

    /** Waits up to two seconds for the armed call to park; false if it never did. */
    public boolean awaitParked() throws InterruptedException {
        return parked.await(2, TimeUnit.SECONDS);
    }

    public void release() {
        released.countDown();
    }

    @Override
    public Optional<V> get(String key) {
        Optional<V> value = super.get(key);
        if (parkNextRead.getAndSet(false)) {
            park();
        }
        return value;
    }

    @Override
    public void put(String key, V value) {
        if (parkNextWrite.getAndSet(false)) {
            park();
        }
        super.put(key, value);
    }

    private void park() {
        parked.countDown();
        try {
            released.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
