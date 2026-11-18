package io.floci.aws.services.dsql;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.services.dsql.dataplane.DsqlDatabases;
import io.floci.aws.services.dsql.dataplane.DsqlEndpoint;
import io.floci.aws.services.dsql.model.DsqlCluster;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How the data plane treats a cluster whose database is still starting when Floci stops, and one
 * whose server refuses it.
 */
class DsqlDataPlaneTest {

    private final DsqlDatabases databases = mock(DsqlDatabases.class);
    private final DsqlEndpoint endpoint = mock(DsqlEndpoint.class);
    private final CountDownLatch startBegun = new CountDownLatch(1);
    private final CountDownLatch startMayFinish = new CountDownLatch(1);
    private final List<Boolean> readies = new CopyOnWriteArrayList<>();
    private final AtomicReference<CompletableFuture<Void>> shutdown = new AtomicReference<>();
    private final List<Boolean> shutdownReturnedWhenDropped = new CopyOnWriteArrayList<>();

    @Test
    void withInMemoryStorageAClusterStillStartingAtShutdownIsDroppedAndNeverServed() throws Exception {
        DsqlDataPlane plane = new DsqlDataPlane(config("memory"), databases, endpoint);
        DsqlCluster cluster = cluster();
        startsSlowly(cluster);

        plane.provision(cluster, readies::add);
        assertTrue(startBegun.await(10, TimeUnit.SECONDS));
        stopFlociOnceTheStartHasBegun(plane);
        shutdown.get().get(10, TimeUnit.SECONDS);

        verify(databases, timeout(5_000)).stop(cluster, true);
        verify(endpoint, never()).register(any(), any());
        assertEquals(List.of(), readies, "a cluster stopped with Floci is neither ready nor FAILED");
    }

    @Test
    void withPersistentStorageAClusterStillStartingAtShutdownKeepsItsDataAndIsNeverServed() throws Exception {
        DsqlDataPlane plane = new DsqlDataPlane(config("persistent"), databases, endpoint);
        DsqlCluster cluster = cluster();
        startsSlowly(cluster);

        plane.provision(cluster, readies::add);
        assertTrue(startBegun.await(10, TimeUnit.SECONDS));
        stopFlociOnceTheStartHasBegun(plane);
        shutdown.get().get(10, TimeUnit.SECONDS);

        verify(databases, timeout(5_000)).stop(cluster, false);
        verify(databases, never()).stop(cluster, true);
        verify(endpoint, never()).register(any(), any());
        assertEquals(List.of(), readies, "so the next start of Floci starts it again");
    }

    @Test
    void shutdownWaitsForAStartStillRunning() throws Exception {
        DsqlDataPlane plane = new DsqlDataPlane(config("memory"), databases, endpoint);
        DsqlCluster cluster = cluster();
        startsSlowly(cluster);
        recordWhetherShutdownReturnedWhenDropped(cluster);

        plane.provision(cluster, readies::add);
        assertTrue(startBegun.await(10, TimeUnit.SECONDS));
        stopFlociOnceTheStartHasBegun(plane);

        verify(databases, timeout(5_000)).stop(cluster, true);
        shutdown.get().get(10, TimeUnit.SECONDS);
        assertEquals(List.of(false), shutdownReturnedWhenDropped, "the start finished before Floci stopped");
    }

    @Test
    void shutdownWaitsForADeletionStillRunning() throws Exception {
        DsqlDataPlane plane = new DsqlDataPlane(config("memory"), databases, endpoint);
        DsqlCluster cluster = cluster();
        when(databases.rolesToDrop(cluster)).thenAnswer(invocation -> {
            startBegun.countDown();
            assertTrue(startMayFinish.await(10, TimeUnit.SECONDS));
            return List.of();
        });
        recordWhetherShutdownReturnedWhenDropped(cluster);
        CountDownLatch gone = new CountDownLatch(1);

        plane.deprovision(cluster, false, roles -> { }, gone::countDown);
        assertTrue(startBegun.await(10, TimeUnit.SECONDS));
        stopFlociOnceTheStartHasBegun(plane);

        shutdown.get().get(10, TimeUnit.SECONDS);
        assertEquals(List.of(false), shutdownReturnedWhenDropped, "the deletion finished before Floci stopped");
        assertEquals(0, gone.getCount());
    }

    /** A start the server refuses, as it refuses a wrong superuser password, leaves the cluster not ready. */
    @Test
    void aStartTheServerRefusesLeavesTheClusterNotReady() throws Exception {
        DsqlDataPlane plane = new DsqlDataPlane(config("memory"), databases, endpoint);
        DsqlCluster cluster = cluster();
        when(databases.start(cluster)).thenThrow(new SQLException("password authentication failed", "28P01"));
        CountDownLatch reported = new CountDownLatch(1);

        plane.provision(cluster, ready -> {
            readies.add(ready);
            reported.countDown();
        });

        assertTrue(reported.await(10, TimeUnit.SECONDS));
        assertEquals(List.of(false), readies, "so the cluster is FAILED");
        verify(endpoint, never()).register(any(), any());
    }

    /**
     * A FAILED cluster whose server refuses the deletion is deleted anyway once its backend names
     * what may remain there, as AWS deletes a FAILED cluster.
     */
    @Test
    void aFailedClusterWhoseServerRefusesTheDeletionIsDeletedAnyway() throws Exception {
        DsqlDataPlane plane = new DsqlDataPlane(config("memory"), databases, endpoint);
        DsqlCluster cluster = cluster();
        when(databases.rolesToDrop(cluster)).thenThrow(new SQLException("password authentication failed", "28P01"));
        when(databases.leftoversOfFailed(cluster)).thenReturn(Optional.of("database dsql_abcdefghijklmnopqrstuvwxyz"));
        CountDownLatch gone = new CountDownLatch(1);

        plane.deprovision(cluster, true, roles -> { }, gone::countDown);

        assertTrue(gone.await(10, TimeUnit.SECONDS), "the deletion finishes");
        verify(databases, never()).stop(any(), anyBoolean());
    }

    /**
     * Stops Floci on another thread and lets the start or deletion go on once the shutdown has
     * stopped the endpoint, so the shutdown is past its first step when the work finishes.
     */
    private void stopFlociOnceTheStartHasBegun(DsqlDataPlane plane) {
        doAnswer(invocation -> {
            startMayFinish.countDown();
            return null;
        }).when(endpoint).stop();
        shutdown.set(CompletableFuture.runAsync(() -> plane.onStop(null)));
    }

    /** Records, when the cluster's data is dropped, whether the shutdown had already returned. */
    private void recordWhetherShutdownReturnedWhenDropped(DsqlCluster cluster) {
        doAnswer(invocation -> {
            CompletableFuture<Void> stopping = shutdown.get();
            shutdownReturnedWhenDropped.add(stopping != null && stopping.isDone());
            return null;
        }).when(databases).stop(cluster, true);
    }

    private void startsSlowly(DsqlCluster cluster) throws Exception {
        when(databases.start(cluster)).thenAnswer(invocation -> {
            startBegun.countDown();
            assertTrue(startMayFinish.await(10, TimeUnit.SECONDS));
            return null;
        });
        when(databases.rolesToDrop(cluster)).thenReturn(List.of());
    }

    private static EmulatorConfig config(String storageMode) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().dsql().mock()).thenReturn(false);
        when(config.storage().mode()).thenReturn(storageMode);
        return config;
    }

    private static DsqlCluster cluster() {
        DsqlCluster cluster = new DsqlCluster();
        cluster.setIdentifier("abcdefghijklmnopqrstuvwxyz");
        cluster.setArn("arn:aws:dsql:eu-west-1:111122223333:cluster/abcdefghijklmnopqrstuvwxyz");
        cluster.setEndpoint("abcdefghijklmnopqrstuvwxyz.dsql.eu-west-1.on.aws");
        return cluster;
    }
}
