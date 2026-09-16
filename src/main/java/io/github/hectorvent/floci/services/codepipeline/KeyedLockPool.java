package io.github.hectorvent.floci.services.codepipeline;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

final class KeyedLockPool {
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    <T> T withLock(String key, Supplier<T> action) {
        // Count queued callers before they wait so the shared monitor cannot be replaced underneath them.
        Entry entry = retain(key);
        try {
            synchronized (entry.monitor) {
                return action.get();
            }
        } finally {
            release(key, entry);
        }
    }

    void withLock(String key, Runnable action) {
        withLock(key, () -> {
            action.run();
            return null;
        });
    }

    int size() {
        return entries.size();
    }

    private Entry retain(String key) {
        return entries.compute(key, (ignored, current) -> {
            Entry entry = current == null ? new Entry() : current;
            entry.users++;
            return entry;
        });
    }

    private void release(String key, Entry entry) {
        entries.compute(key, (ignored, current) -> {
            if (current != entry) {
                throw new IllegalStateException("Keyed lock entry changed while in use");
            }
            entry.users--;
            return entry.users == 0 ? null : entry;
        });
    }

    private static final class Entry {
        private final Object monitor = new Object();
        private int users;
    }
}
