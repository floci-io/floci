package io.github.hectorvent.floci.services.codeartifact;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.ContainerTeardown;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.PerKeyContainerPool;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link PypiserverSidecarManager#stopManagedContainers()} is a thin delegation to
 * {@link PerKeyContainerPool#stopAll()}, whose own behavior is covered by {@code PerKeyContainerPoolTest}.
 * What matters here is the wiring: that the manager implements {@link ContainerTeardown} (so
 * {@code ContainerTeardowns.stopAll} finds it on reset, nuke, and shutdown) and that it stops the
 * containers it actually started. A container is seeded straight into the pool by reflection, so this
 * needs no Docker daemon.
 */
class PypiserverSidecarManagerTest {

    private final ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
    private final ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
    private final EmulatorConfig config = mock(EmulatorConfig.class);

    @Test
    void implementsContainerTeardownSoStateResetAndNukeCanFindIt() {
        assertInstanceOf(ContainerTeardown.class, manager());
    }

    @Test
    void stopManagedContainersStopsEveryContainerThePoolIsTracking() throws Exception {
        PypiserverSidecarManager manager = manager();
        seedPooledContainer(manager, "pypi-repo-1", "tracked-pypiserver-1", "http://127.0.0.1:1");
        seedPooledContainer(manager, "pypi-repo-2", "tracked-pypiserver-2", "http://127.0.0.1:1");

        manager.stopManagedContainers();

        verify(lifecycleManager).stopAndRemove("tracked-pypiserver-1", null);
        verify(lifecycleManager).stopAndRemove("tracked-pypiserver-2", null);
    }

    @Test
    void stopManagedContainersIsANoOpWhenNothingWasEverStarted() {
        PypiserverSidecarManager manager = manager();

        manager.stopManagedContainers();

        verifyNoInteractions(lifecycleManager);
    }

    private PypiserverSidecarManager manager() {
        return new PypiserverSidecarManager(containerBuilder, lifecycleManager, config);
    }

    @SuppressWarnings("unchecked")
    private static void seedPooledContainer(PypiserverSidecarManager manager, String key, String containerId,
                                              String url) throws Exception {
        Field poolField = PypiserverSidecarManager.class.getDeclaredField("pool");
        poolField.setAccessible(true);
        PerKeyContainerPool pool = (PerKeyContainerPool) poolField.get(manager);

        Field containersField = PerKeyContainerPool.class.getDeclaredField("containers");
        containersField.setAccessible(true);
        ConcurrentHashMap<String, PerKeyContainerPool.StartedContainer> containers =
                (ConcurrentHashMap<String, PerKeyContainerPool.StartedContainer>) containersField.get(pool);
        containers.put(key, new PerKeyContainerPool.StartedContainer(containerId, url));
    }
}
