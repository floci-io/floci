package io.github.hectorvent.floci.services.codeartifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.ContainerTeardown;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.PerKeyContainerPool;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Checks Verdaccio's container teardown and use accounting without a Docker daemon. Containers
 * are seeded into its pool by reflection; only the removal-failure test uses the real lifecycle.
 */
class VerdaccioSidecarManagerTest {

    private final ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
    private final ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
    private final EmulatorConfig config = mock(EmulatorConfig.class);

    @Test
    void implementsContainerTeardownSoStateResetAndNukeCanFindIt() {
        assertInstanceOf(ContainerTeardown.class, manager());
    }

    @Test
    void stopManagedContainersStopsEveryContainerThePoolIsTracking() throws Exception {
        VerdaccioSidecarManager manager = manager();
        seedPooledContainer(manager, "npm-repo-1", "tracked-verdaccio-1", "http://127.0.0.1:1");
        seedPooledContainer(manager, "npm-repo-2", "tracked-verdaccio-2", "http://127.0.0.1:1");

        manager.stopManagedContainers();

        verify(lifecycleManager).stopAndRemoveStrict("tracked-verdaccio-1", null);
        verify(lifecycleManager).stopAndRemoveStrict("tracked-verdaccio-2", null);
    }

    @Test
    void stopManagedContainersIsANoOpWhenNothingWasEverStarted() {
        manager().stopManagedContainers();

        verifyNoInteractions(lifecycleManager);
    }

    @Test
    void releaseDefersStopUntilTheLastActiveUseEnds() throws Exception {
        VerdaccioSidecarManager manager = manager();
        seedPooledContainer(manager, "npm-repo-1", "tracked-verdaccio-1", "http://127.0.0.1:1");
        RepositorySidecarManager client = new VerdaccioSidecarClient(manager, config, new ObjectMapper(),
                HttpClient.newHttpClient());
        client.registerRepository("npm-repo-1");
        client.beginUse("npm-repo-1");
        client.beginUse("npm-repo-1");

        client.release("npm-repo-1");
        assertThrows(IllegalStateException.class, () -> client.beginUse("npm-repo-1"));
        client.endUse("npm-repo-1");
        verifyNoInteractions(lifecycleManager);
        client.endUse("npm-repo-1");
        verify(lifecycleManager).stopAndRemoveStrict("tracked-verdaccio-1", null);
        assertThrows(IllegalStateException.class, () -> client.beginUse("npm-repo-1"));
        assertEquals(0, trackedUseCount(manager));
    }

    @Test
    void releaseReturnsBeforeAUseNeedsTheCallersLockToFinish() throws Exception {
        VerdaccioSidecarManager manager = manager();
        seedPooledContainer(manager, "npm-repo-1", "tracked-verdaccio-1", "http://127.0.0.1:1");
        Object serviceLock = new Object();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            manager.registerRepository("npm-repo-1");
            manager.beginUse("npm-repo-1");
            Future<?> delete = executor.submit(() -> {
                synchronized (serviceLock) {
                    manager.release("npm-repo-1");
                }
            });
            delete.get(5, TimeUnit.SECONDS);
            Future<?> callback = executor.submit(() -> {
                synchronized (serviceLock) {
                    manager.endUse("npm-repo-1");
                }
            });
            callback.get(5, TimeUnit.SECONDS);
            verify(lifecycleManager).stopAndRemoveStrict("tracked-verdaccio-1", null);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void failedStopRetainsTheContainerForAReleaseRetry() throws Exception {
        VerdaccioSidecarManager manager = manager();
        seedPooledContainer(manager, "npm-repo-1", "tracked-verdaccio-1", "http://127.0.0.1:1");
        doThrow(new IllegalStateException("Docker unavailable")).doNothing().when(lifecycleManager)
                .stopAndRemoveStrict("tracked-verdaccio-1", null);

        assertThrows(IllegalStateException.class, () -> manager.release("npm-repo-1"));
        assertTrue(manager.isRunning("npm-repo-1"));
        assertThrows(IllegalStateException.class, () -> manager.beginUse("npm-repo-1"));

        manager.release("npm-repo-1");
        assertFalse(manager.isRunning("npm-repo-1"));
        verify(lifecycleManager, times(2)).stopAndRemoveStrict("tracked-verdaccio-1", null);
        assertEquals(0, trackedUseCount(manager));
    }

    @Test
    void failedDockerRemovalKeepsTheContainerTrackedForRetry() throws Exception {
        DockerClient docker = mock(DockerClient.class);
        StopContainerCmd stop = mock(StopContainerCmd.class);
        when(docker.stopContainerCmd("tracked-verdaccio-1")).thenReturn(stop);
        when(stop.withTimeout(5)).thenReturn(stop);
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class);
        when(docker.removeContainerCmd("tracked-verdaccio-1")).thenReturn(remove);
        when(remove.withForce(true)).thenReturn(remove);
        doThrow(new IllegalStateException("Docker removal failed")).doNothing().when(remove).exec();
        ContainerLifecycleManager realLifecycle = new ContainerLifecycleManager(docker, null, null, null);
        VerdaccioSidecarManager manager = new VerdaccioSidecarManager(containerBuilder, realLifecycle, config);
        seedPooledContainer(manager, "npm-repo-1", "tracked-verdaccio-1", "http://127.0.0.1:1");

        assertThrows(IllegalStateException.class, () -> manager.release("npm-repo-1"));
        assertTrue(manager.isRunning("npm-repo-1"));
        assertEquals(1, trackedUseCount(manager));
        manager.stopManagedContainers();
        assertFalse(manager.isRunning("npm-repo-1"));
        verify(remove, times(2)).exec();
        assertEquals(0, trackedUseCount(manager));
    }

    @Test
    void teardownRetainsFailedRemovalButContinuesWithOtherContainers() throws Exception {
        VerdaccioSidecarManager manager = manager();
        seedPooledContainer(manager, "npm-repo-1", "tracked-verdaccio-1", "http://127.0.0.1:1");
        seedPooledContainer(manager, "npm-repo-2", "tracked-verdaccio-2", "http://127.0.0.1:1");
        doThrow(new IllegalStateException("Docker unavailable")).doNothing().when(lifecycleManager)
                .stopAndRemoveStrict("tracked-verdaccio-1", null);

        assertThrows(IllegalStateException.class, manager::stopManagedContainers);
        assertTrue(manager.isRunning("npm-repo-1"));
        assertFalse(manager.isRunning("npm-repo-2"));
        verify(lifecycleManager).stopAndRemoveStrict("tracked-verdaccio-2", null);

        manager.stopManagedContainers();
        assertFalse(manager.isRunning("npm-repo-1"));
        verify(lifecycleManager, times(2)).stopAndRemoveStrict("tracked-verdaccio-1", null);
    }

    @Test
    void neverUsedRepositoriesAndRepeatedDeletesDoNotRetainUseState() throws Exception {
        VerdaccioSidecarManager manager = manager();
        for (int i = 0; i < 100; i++) {
            String id = "npm-repo-" + i;
            manager.registerRepository(id);
            manager.release(id);
            manager.release(id);
        }

        assertEquals(0, trackedUseCount(manager));
        verifyNoInteractions(lifecycleManager);
    }

    @Test
    void teardownDiscardsIdleUseStateAndRejectsTheOldId() throws Exception {
        VerdaccioSidecarManager manager = manager();
        manager.registerRepository("npm-repo-1");

        manager.stopManagedContainers();

        assertEquals(0, trackedUseCount(manager));
        assertThrows(IllegalStateException.class, () -> manager.beginUse("npm-repo-1"));
    }

    @Test
    void teardownKeepsAnActiveUseUntilItFinishes() throws Exception {
        VerdaccioSidecarManager manager = manager();
        seedPooledContainer(manager, "npm-repo-1", "tracked-verdaccio-1", "http://127.0.0.1:1");
        manager.registerRepository("npm-repo-1");
        manager.beginUse("npm-repo-1");

        manager.stopManagedContainers();
        assertThrows(IllegalStateException.class, () -> manager.beginUse("npm-repo-1"));
        assertEquals(1, trackedUseCount(manager));
        assertTrue(manager.isRunning("npm-repo-1"));
        verifyNoInteractions(lifecycleManager);
        manager.endUse("npm-repo-1");
        verify(lifecycleManager).stopAndRemoveStrict("tracked-verdaccio-1", null);
        assertEquals(0, trackedUseCount(manager));
    }

    @Test
    void failedDeferredRemovalDoesNotFailFinishedUseAndRemainsRetryableAtTeardown() throws Exception {
        VerdaccioSidecarManager manager = manager();
        seedPooledContainer(manager, "npm-repo-1", "tracked-verdaccio-1", "http://127.0.0.1:1");
        manager.registerRepository("npm-repo-1");
        manager.beginUse("npm-repo-1");
        manager.release("npm-repo-1");
        doThrow(new IllegalStateException("Docker unavailable")).doNothing().when(lifecycleManager)
                .stopAndRemoveStrict("tracked-verdaccio-1", null);

        assertDoesNotThrow(() -> manager.endUse("npm-repo-1"));
        assertEquals(1, trackedUseCount(manager));
        assertTrue(manager.isRunning("npm-repo-1"));
        assertThrows(IllegalStateException.class, () -> manager.beginUse("npm-repo-1"));
        assertThrows(IllegalStateException.class, () -> manager.endUse("npm-repo-1"));

        manager.stopManagedContainers();
        assertEquals(0, trackedUseCount(manager));
        assertFalse(manager.isRunning("npm-repo-1"));
        verify(lifecycleManager, times(2)).stopAndRemoveStrict("tracked-verdaccio-1", null);
    }

    private VerdaccioSidecarManager manager() {
        return new VerdaccioSidecarManager(containerBuilder, lifecycleManager, config);
    }

    @SuppressWarnings("unchecked")
    private static int trackedUseCount(VerdaccioSidecarManager manager) throws Exception {
        Field leaseField = VerdaccioSidecarManager.class.getDeclaredField("lease");
        leaseField.setAccessible(true);
        SidecarRepositoryLease lease = (SidecarRepositoryLease) leaseField.get(manager);
        Field usesField = SidecarRepositoryLease.class.getDeclaredField("uses");
        usesField.setAccessible(true);
        return ((ConcurrentHashMap<String, ?>) usesField.get(lease)).size();
    }

    @SuppressWarnings("unchecked")
    private static void seedPooledContainer(VerdaccioSidecarManager manager, String key, String containerId,
                                              String url) throws Exception {
        Field poolField = VerdaccioSidecarManager.class.getDeclaredField("pool");
        poolField.setAccessible(true);
        PerKeyContainerPool pool = (PerKeyContainerPool) poolField.get(manager);

        Field containersField = PerKeyContainerPool.class.getDeclaredField("containers");
        containersField.setAccessible(true);
        ConcurrentHashMap<String, PerKeyContainerPool.StartedContainer> containers =
                (ConcurrentHashMap<String, PerKeyContainerPool.StartedContainer>) containersField.get(pool);
        containers.put(key, new PerKeyContainerPool.StartedContainer(containerId, url));
    }
}
