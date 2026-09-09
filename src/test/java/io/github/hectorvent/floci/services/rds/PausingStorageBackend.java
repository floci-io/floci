package io.github.hectorvent.floci.services.rds;

import io.github.hectorvent.floci.core.storage.StorageBackend;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * A store that can be stopped in the middle of an operation, so a race can be staged rather than
 * hoped for.
 *
 * <p>A concurrency test that loops and waits for an unlucky interleaving proves nothing when the
 * window is a few instructions wide: it passes with the fix and without it. Holding one thread at a
 * chosen call while another runs to completion makes the interleaving the test claims to cover the
 * one it actually runs.
 */
final class PausingStorageBackend<V> implements StorageBackend<String, V> {

    /** Which call to hold, and on which key. */
    enum Call { PUT, DELETE, SCAN }

    private final StorageBackend<String, V> delegate;
    private volatile Call pauseOn;
    private volatile String pauseKey;
    private final java.util.concurrent.atomic.AtomicInteger skip =
            new java.util.concurrent.atomic.AtomicInteger();
    private final CountDownLatch reached = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    PausingStorageBackend(StorageBackend<String, V> delegate) {
        this.delegate = delegate;
    }

    /** Hold the next matching call until {@link #release()}; {@code key} may be null for SCAN. */
    void pauseOn(Call call, String key) {
        pauseOn(call, key, 0);
    }

    /** As above, after letting {@code skipFirst} matching calls through untouched. */
    void pauseOn(Call call, String key, int skipFirst) {
        this.pauseOn = call;
        this.pauseKey = key;
        this.skip.set(skipFirst);
    }

    /** Waits until the paused call has been reached. */
    void awaitReached() throws InterruptedException {
        if (!reached.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("the paused call was never reached");
        }
    }

    void release() {
        release.countDown();
    }

    private void pauseIfMatching(Call call, String key) {
        // Keys arrive account-prefixed here, since this sits underneath the account-aware layer.
        if (pauseOn != call || (pauseKey != null && (key == null || !key.endsWith(pauseKey)))) {
            return;
        }
        if (skip.getAndDecrement() > 0) {
            return;
        }
        pauseOn = null;
        reached.countDown();
        try {
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the paused call was never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void put(String key, V value) {
        pauseIfMatching(Call.PUT, key);
        delegate.put(key, value);
    }

    @Override
    public Optional<V> get(String key) {
        return delegate.get(key);
    }

    @Override
    public void delete(String key) {
        pauseIfMatching(Call.DELETE, key);
        delegate.delete(key);
    }

    @Override
    public List<V> scan(Predicate<String> keyFilter) {
        pauseIfMatching(Call.SCAN, null);
        return delegate.scan(keyFilter);
    }

    @Override
    public Set<String> keys() {
        return delegate.keys();
    }

    @Override
    public void flush() {
        delegate.flush();
    }

    @Override
    public void load() {
        delegate.load();
    }

    @Override
    public void clear() {
        delegate.clear();
    }
}
