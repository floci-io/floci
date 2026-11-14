package io.floci.aws.services.redshift;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TempRolePasswordTrackerTest {

    private final TempRolePasswordTracker tracker = new TempRolePasswordTracker();
    private final List<String> writes = new ArrayList<>();

    @Test
    void concurrentSessionsShareOnePasswordAndLastReleaseRestoresTheStoredHash() throws Exception {
        TempRolePasswordTracker.Lease first = tracker.acquire("k", "analyst", () -> "SCRAM-SHA-256$stored", writes::add);
        TempRolePasswordTracker.Lease second = tracker.acquire("k", "analyst", () -> "SCRAM-SHA-256$stored", writes::add);

        assertEquals(first.password(), second.password());
        assertEquals(1, writes.size());

        first.release(writes::add);
        assertEquals(1, writes.size());

        second.release(writes::add);
        assertEquals(List.of(first.password(), "SCRAM-SHA-256$stored"), writes);
    }

    @Test
    void roleWithoutPasswordIsRestoredToNull() throws Exception {
        TempRolePasswordTracker.Lease lease = tracker.acquire("k", "analyst", () -> null, writes::add);

        lease.release(writes::add);
        lease.release(writes::add);

        assertEquals(2, writes.size());
        assertNull(writes.get(1));
    }

    @Test
    void concurrentAcquireAndReleaseAlwaysEndWithTheStoredHashRestored() throws Exception {
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        int threads = 8;
        int rounds = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < rounds; i++) {
                        TempRolePasswordTracker.Lease lease = tracker.acquire(
                                "k", "analyst", () -> "stored", log::add);
                        lease.release(log::add);
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        // The stored hash is only ever read from the role itself, never from a temporary password.
        assertEquals("stored", log.get(log.size() - 1));
        long restores = log.stream().filter("stored"::equals).count();
        assertEquals(log.size() - restores, restores);
    }

    @Test
    void failedRestoreKeepsTheStoredHashForTheNextSession() throws Exception {
        TempRolePasswordTracker.Lease lease = tracker.acquire("k", "analyst", () -> "stored", writes::add);
        lease.release(value -> {
            throw new SQLException("backend down");
        });

        TempRolePasswordTracker.Lease next = tracker.acquire("k", "analyst", () -> lease.password(), writes::add);
        assertNotEquals(lease.password(), next.password());
        next.release(writes::add);

        assertEquals("stored", writes.get(writes.size() - 1));
    }
}
