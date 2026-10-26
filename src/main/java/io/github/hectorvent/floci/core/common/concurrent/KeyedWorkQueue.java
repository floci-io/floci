package io.github.hectorvent.floci.core.common.concurrent;

import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Bounded, level-triggered work: a key means "read its current state", not an event to replay.
 * One worker owns a key at a time. A notification during that work guarantees another pass.
 * Callers retain a periodic repair path for notifications rejected at capacity.
 */
public final class KeyedWorkQueue<K> implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(KeyedWorkQueue.class);

    private final Map<K, Work> pending = new LinkedHashMap<>();
    private final ExecutorService workers;
    private final Consumer<K> action;
    private final int concurrency;
    private final int capacity;
    private int active;
    private long completed;
    private long coalesced;
    private long rejected;
    private long failures;
    private boolean closed;

    private static final class Work {
        boolean running;
        boolean dirty = true;
    }

    public record Snapshot(int keys, int active, long completed, long coalesced,
                           long rejected, long failures, boolean closed) {}

    public KeyedWorkQueue(String name, int concurrency, int capacity, Consumer<K> action) {
        if (concurrency < 1 || capacity < concurrency) {
            throw new IllegalArgumentException("Capacity must be at least the positive concurrency");
        }
        this.concurrency = concurrency;
        this.capacity = capacity;
        this.action = Objects.requireNonNull(action);
        this.workers = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(name + "-", 0).factory());
    }

    /**
     * Never runs work on the notifying thread. Duplicate notifications occupy no extra capacity.
     * False means closed or full; authoritative state must remain available to periodic repair.
     */
    public synchronized boolean submit(K key) {
        Objects.requireNonNull(key);
        if (closed) {
            return false;
        }
        Work work = pending.get(key);
        if (work != null) {
            work.dirty = true;
            coalesced++;
            return true;
        }
        if (pending.size() >= capacity) {
            rejected++;
            return false;
        }
        pending.put(key, new Work());
        dispatch();
        return true;
    }

    private void dispatch() {
        for (Map.Entry<K, Work> entry : pending.entrySet()) {
            if (active >= concurrency || closed) {
                return;
            }
            Work work = entry.getValue();
            if (work.running) {
                continue;
            }
            work.running = true;
            work.dirty = false;
            active++;
            K key = entry.getKey();
            try {
                workers.execute(() -> run(key, work));
            } catch (RejectedExecutionException failure) {
                active--;
                work.running = false;
                work.dirty = true;
                throw failure;
            }
        }
    }

    private void run(K key, Work work) {
        try {
            synchronized (this) {
                if (closed) {
                    return;
                }
            }
            action.accept(key);
        } catch (RuntimeException failure) {
            synchronized (this) {
                failures++;
            }
            LOG.warnv(failure, "Background reconciliation failed for {0}; periodic repair can retry", key);
        } finally {
            synchronized (this) {
                active--;
                completed++;
                pending.remove(key);
                if (!closed && work.dirty) {
                    // Reinsert at the tail so a hot key cannot starve other resources.
                    work.running = false;
                    pending.put(key, work);
                }
                dispatch();
            }
        }
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(pending.size(), active, completed, coalesced, rejected, failures, closed);
    }

    /** Discards queued notifications and interrupts admitted work; callers own resource draining. */
    @Override
    public synchronized void close() {
        closed = true;
        pending.clear();
        workers.shutdownNow();
    }
}
