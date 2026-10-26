package io.github.hectorvent.floci.core.common.concurrent;

import io.github.hectorvent.floci.core.common.AwsException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One write lock per key: writers of the same key exclude one another, writers of distinct
 * keys never wait for each other. A semaphore of one permit rather than a monitor, because
 * a writer may release the lock from a different thread than it took it on, which a
 * synchronized block and a {@code ReentrantLock} both forbid.
 *
 * <p>Entries are keyed and reference-counted. Every participant, the holder and each
 * waiter alike, is counted on its key's entry from before it tries the semaphore until it
 * has released the permit or given up, and each count is taken and dropped inside the
 * map's own per-key computation. No entry is removed or replaced while any participant
 * still coordinates on it, so every writer reaching a key in that window meets the same
 * instance and a waiter can never be mutual-excluded against a fresh instance a later
 * arrival takes. An entry is collected once its last participant departs, which keeps a
 * key written once, or a key whose resource later goes away, from leaving an entry behind
 * for the life of the pool.
 */
public final class KeyedWriteLocks<K> {

    private final Map<K, Entry> entries = new ConcurrentHashMap<>();

    /**
     * Takes the key's lock, waiting for its current holder to release it. Interruptible:
     * the waiter's count departs and the interruption surfaces as a {@code ServerException},
     * the error AWS services report for an internal failure.
     */
    public void acquire(K key) {
        Entry entry = retain(key);
        try {
            entry.permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            depart(key, entry);
            throw new AwsException("ServerException",
                    "Interrupted while waiting for the write lock on " + key, 500);
        }
    }

    /**
     * One try at {@link #acquire}, for callers that must not block a thread. The caller
     * that succeeds owns the entry's count until its {@link #release}.
     */
    public boolean tryAcquire(K key) {
        Entry entry = retain(key);
        if (entry.permits.tryAcquire()) {
            return true;
        }
        depart(key, entry);
        return false;
    }

    /**
     * Ends one writer's hold: the permit goes back and, with it, the writer's count on
     * the entry. The holder's own count keeps the entry in the map until here, so the
     * lookup meets the instance the writer acquired. May run on a thread other than the
     * one the lock was taken on.
     */
    public void release(K key) {
        Entry entry = entries.get(key);
        entry.permits.release();
        depart(key, entry);
    }

    /** The entries still counting participants, for tests. */
    public int size() {
        return entries.size();
    }

    /**
     * The key's entry with this participant counted on it: from before the participant
     * tries the semaphore until it releases the permit or gives up, the entry cannot be
     * removed, so every participant reaching the key in that window meets the same
     * instance and a waiter can never be mutual-excluded against a fresh instance a later
     * arrival takes.
     */
    private Entry retain(K key) {
        return entries.compute(key, (ignored, existing) -> {
            Entry entry = existing != null ? existing : new Entry();
            entry.participants.incrementAndGet();
            return entry;
        });
    }

    /**
     * Ends one participant's count on the entry. The decrement and the removal decision
     * run inside the map's own per-key computation, so a participant arriving on the same
     * key cannot slip between the count reaching zero and the entry leaving, and cannot
     * end up counted on an instance the map no longer holds.
     */
    private void depart(K key, Entry entry) {
        entries.compute(key, (ignored, existing) ->
                entry.participants.decrementAndGet() > 0 || existing != entry ? existing : null);
    }

    /** One key's write semaphore and the count of participants still coordinating on it. */
    private static final class Entry {
        private final Semaphore permits = new Semaphore(1);
        private final AtomicInteger participants = new AtomicInteger();
    }
}
