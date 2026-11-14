package io.floci.aws.services.redshift;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

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
