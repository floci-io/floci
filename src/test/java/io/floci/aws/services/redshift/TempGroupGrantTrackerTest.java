package io.floci.aws.services.redshift;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TempGroupGrantTrackerTest {

    private TempGroupGrantTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new TempGroupGrantTracker();
    }

    @Test
    void refCountsConcurrentLeasesAndRevokesOnlyOnLastRelease() throws SQLException {
        List<String> events = new ArrayList<>();

        TempGroupGrantTracker.Lease first = tracker.acquire(
                "acc:clus", "analyst", List.of("g1", "g2"),
                group -> events.add("GRANT:" + group));
        TempGroupGrantTracker.Lease second = tracker.acquire(
                "acc:clus", "analyst", List.of("g1"),
                group -> events.add("GRANT:" + group));

        assertEquals(List.of("GRANT:g1", "GRANT:g2"), events);

        first.release(group -> events.add("REVOKE:" + group));
        assertEquals(List.of("GRANT:g1", "GRANT:g2", "REVOKE:g2"), events);

        // Releasing the same lease twice is a no-op.
        first.release(group -> events.add("REVOKE:" + group));
        assertEquals(List.of("GRANT:g1", "GRANT:g2", "REVOKE:g2"), events);

        second.release(group -> events.add("REVOKE:" + group));
        assertEquals(List.of("GRANT:g1", "GRANT:g2", "REVOKE:g2", "REVOKE:g1"), events);
    }

    @Test
    void rollsBackAcquiredGrantsIfASubsequentGroupGrantFails() throws SQLException {
        List<String> events = new ArrayList<>();

        assertThrows(SQLException.class, () -> tracker.acquire(
                "acc:clus", "analyst", List.of("g1", "bad"),
                group -> {
                    if ("bad".equals(group)) {
                        throw new SQLException("boom");
                    }
                    events.add("GRANT:" + group);
                }));

        // After rollback, a new acquire for g1 must grant it again because ref-count returned to 0.
        tracker.acquire("acc:clus", "analyst", List.of("g1"), group -> events.add("GRANT2:" + group));
        assertEquals(List.of("GRANT:g1", "GRANT2:g1"), events);
    }
}
