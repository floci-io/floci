package io.github.hectorvent.floci.services.codeartifact;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link VerdaccioSidecarManager#stopManagedContainers()} is a thin delegation to
 * {@link PerKeyContainerPool#stopAll()}, whose own behavior is covered by {@code PerKeyContainerPoolTest}.
 * What matters here is the wiring: that the manager implements {@link ContainerTeardown} (so
 * {@code ContainerTeardowns.stopAll} finds it on reset, nuke, and shutdown) and that it stops the
 * containers it actually started. A container is seeded straight into the pool by reflection, so this
 * needs no Docker daemon.
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

        verify(lifecycleManager).stopAndRemove("tracked-verdaccio-1", null);
        verify(lifecycleManager).stopAndRemove("tracked-verdaccio-2", null);
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
        client.beginUse("npm-repo-1");
        client.beginUse("npm-repo-1");

        client.release("npm-repo-1");
        assertThrows(IllegalStateException.class, () -> client.beginUse("npm-repo-1"));
        client.endUse("npm-repo-1");
        verifyNoInteractions(lifecycleManager);
        client.endUse("npm-repo-1");
        verify(lifecycleManager).stopAndRemove("tracked-verdaccio-1", null);
        assertThrows(IllegalStateException.class, () -> client.beginUse("npm-repo-1"));
    }

    @Test
    void releaseReturnsBeforeAUseNeedsTheCallersLockToFinish() throws Exception {
        VerdaccioSidecarManager manager = manager();
        seedPooledContainer(manager, "npm-repo-1", "tracked-verdaccio-1", "http://127.0.0.1:1");
        Object serviceLock = new Object();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
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
            verify(lifecycleManager).stopAndRemove("tracked-verdaccio-1", null);
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
                .stopAndRemove("tracked-verdaccio-1", null);

        assertThrows(IllegalStateException.class, () -> manager.release("npm-repo-1"));
        assertTrue(manager.isRunning("npm-repo-1"));
        assertThrows(IllegalStateException.class, () -> manager.beginUse("npm-repo-1"));

        manager.release("npm-repo-1");
        assertFalse(manager.isRunning("npm-repo-1"));
        verify(lifecycleManager, times(2)).stopAndRemove("tracked-verdaccio-1", null);
    }

    private VerdaccioSidecarManager manager() {
        return new VerdaccioSidecarManager(containerBuilder, lifecycleManager, config);
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
