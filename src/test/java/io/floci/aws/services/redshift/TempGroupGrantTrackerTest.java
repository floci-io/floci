package io.floci.aws.services.redshift;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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

    @Test
    void checksPermanentMembershipInsideLockSoConcurrentSessionAcquiresLease() throws SQLException {
        List<String> events = new ArrayList<>();
        AtomicBoolean pgHasMembership = new AtomicBoolean(false);

        TempGroupGrantTracker.Lease first = tracker.acquire(
                "acc:clus", "analyst", List.of("g1"),
                group -> {
                    pgHasMembership.set(true);
                    events.add("GRANT:" + group);
                },
                null,
                group -> pgHasMembership.get());

        // Even though PostgreSQL now reports analyst in g1, session 2 must still take a lease
        // because session 1 holds an active temporary grant.
        TempGroupGrantTracker.Lease second = tracker.acquire(
                "acc:clus", "analyst", List.of("g1"),
                group -> events.add("GRANT2:" + group),
                null,
                group -> pgHasMembership.get());

        first.release(group -> {
            pgHasMembership.set(false);
            events.add("REVOKE1:" + group);
        });
        assertEquals(List.of("GRANT:g1"), events);

        second.release(group -> {
            pgHasMembership.set(false);
            events.add("REVOKE2:" + group);
        });
        assertEquals(List.of("GRANT:g1", "REVOKE2:g1"), events);
    }

    @Test
    void preservesPendingRevokeStateWhenRevokeFailsAndRetriesOnNextSession() throws SQLException {
        List<String> events = new ArrayList<>();
        AtomicBoolean pgHasMembership = new AtomicBoolean(false);

        TempGroupGrantTracker.Lease first = tracker.acquire(
                "acc:clus", "analyst", List.of("g1"),
                group -> {
                    pgHasMembership.set(true);
                    events.add("GRANT1:" + group);
                },
                null,
                group -> pgHasMembership.get());

        assertThrows(SQLException.class, () -> first.release(group -> {
            throw new SQLException("transient network error");
        }));

        // Because the previous REVOKE failed, the grant still exists in PostgreSQL.
        // Next session must adopt it (not treat it as permanent) and revoke it on close.
        TempGroupGrantTracker.Lease second = tracker.acquire(
                "acc:clus", "analyst", List.of("g1"),
                group -> events.add("GRANT2:" + group),
                null,
                group -> pgHasMembership.get());
        second.release(group -> {
            pgHasMembership.set(false);
            events.add("REVOKE2:" + group);
        });

        assertEquals(List.of("GRANT1:g1", "REVOKE2:g1"), events);
    }

    @Test
    void toleratesRollbackRevokeFailureAndAdoptsLeftoverGrantOnRetry() throws SQLException {
        List<String> events = new ArrayList<>();
        AtomicBoolean pgHasMembership = new AtomicBoolean(false);

        assertThrows(SQLException.class, () -> tracker.acquire(
                "acc:clus", "analyst", List.of("g1", "bad"),
                group -> {
                    if ("bad".equals(group)) {
                        throw new SQLException("grant failed");
                    }
                    pgHasMembership.set(true);
                    events.add("GRANT1:" + group);
                },
                group -> {
                    throw new SQLException("rollback revoke failed");
                },
                group -> "g1".equals(group) && pgHasMembership.get()));

        TempGroupGrantTracker.Lease retry = tracker.acquire(
                "acc:clus", "analyst", List.of("g1"),
                group -> events.add("GRANT2:" + group),
                null,
                group -> pgHasMembership.get());
        retry.release(group -> {
            pgHasMembership.set(false);
            events.add("REVOKE2:" + group);
        });

        assertEquals(List.of("GRANT1:g1", "REVOKE2:g1"), events);
    }
}
