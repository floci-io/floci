package io.floci.aws.services.dsql;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.services.dsql.dataplane.DsqlDatabases;
import io.floci.aws.services.dsql.dataplane.DsqlEndpoint;
import io.floci.aws.services.dsql.model.DsqlCluster;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** How the data plane treats a cluster whose database is still starting when Floci stops. */
class DsqlDataPlaneTest {

    private final DsqlDatabases databases = mock(DsqlDatabases.class);
    private final DsqlEndpoint endpoint = mock(DsqlEndpoint.class);
    private final CountDownLatch startBegun = new CountDownLatch(1);
    private final CountDownLatch startMayFinish = new CountDownLatch(1);
    private final List<Boolean> readies = new CopyOnWriteArrayList<>();

    @Test
    void withInMemoryStorageAClusterStillStartingAtShutdownIsDroppedAndNeverServed() throws Exception {
        DsqlDataPlane plane = new DsqlDataPlane(config("memory"), databases, endpoint);
        DsqlCluster cluster = cluster();
        startsSlowly(cluster);

        plane.provision(cluster, readies::add);
        assertTrue(startBegun.await(10, TimeUnit.SECONDS));
        CompletableFuture<Void> stopped = CompletableFuture.runAsync(() -> plane.onStop(null));
        startMayFinish.countDown();
        stopped.get(10, TimeUnit.SECONDS);

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
        CompletableFuture<Void> stopped = CompletableFuture.runAsync(() -> plane.onStop(null));
        startMayFinish.countDown();
        stopped.get(10, TimeUnit.SECONDS);

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

        plane.provision(cluster, readies::add);
        assertTrue(startBegun.await(10, TimeUnit.SECONDS));
        CompletableFuture<Void> stopped = CompletableFuture.runAsync(() -> plane.onStop(null));

        Thread.sleep(300);
        assertFalse(stopped.isDone(), "the start is still running");
        startMayFinish.countDown();
        stopped.get(10, TimeUnit.SECONDS);
        verify(databases).stop(cluster, true);
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
        CountDownLatch gone = new CountDownLatch(1);

        plane.deprovision(cluster, false, roles -> { }, gone::countDown);
        assertTrue(startBegun.await(10, TimeUnit.SECONDS));
        CompletableFuture<Void> stopped = CompletableFuture.runAsync(() -> plane.onStop(null));

        Thread.sleep(300);
        assertFalse(stopped.isDone(), "the deletion is still running");
        startMayFinish.countDown();
        stopped.get(10, TimeUnit.SECONDS);
        assertEquals(0, gone.getCount(), "it finished before Floci stopped");
        verify(databases).stop(cluster, true);
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
