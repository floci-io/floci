package io.floci.aws.core.common;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** In-memory maps that stay within a fixed number of entries. */
public final class BoundedMaps {

    private BoundedMaps() {
    }

    /**
     * A thread-safe map of at most {@code limit} entries: adding one past the limit evicts the entry
     * least recently read or written.
     */
    public static <K, V> Map<K, V> leastRecentlyUsed(int limit) {
        return Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > limit;
            }
        });
    }
}
