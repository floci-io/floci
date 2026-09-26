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
 * {@link VerdaccioSidecarManager#stopManagedContainers()} is a thin delegation to
 * {@link PerKeyContainerPool#stopAll()}, whose own behavior (stop every tracked container,
 * tolerate a failure in one, forget them all) is already covered by
 * {@code PerKeyContainerPoolTest}. What matters here, and what a Docker-gated integration test
 * cannot isolate from every other moving part, is the wiring: that the manager genuinely
 * implements {@link ContainerTeardown} (so {@code ContainerTeardowns.stopAll} finds it on
 * {@code /state/reset}, {@code /state/nuke}, and process shutdown) and that it hands the pool
 * container ids it actually started rather than something it invented. A container is seeded
 * directly into the pool's tracking map by reflection, the same way
 * {@code ReposiliteSidecarManagerTest} seeds a stale endpoint, so this needs no Docker daemon.
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
        seedPooledContainer(manager, "npm-repo-1", "tracked-verdaccio-1");
        seedPooledContainer(manager, "npm-repo-2", "tracked-verdaccio-2");

        manager.stopManagedContainers();

        verify(lifecycleManager).stopAndRemove("tracked-verdaccio-1", null);
        verify(lifecycleManager).stopAndRemove("tracked-verdaccio-2", null);
    }

    @Test
    void stopManagedContainersIsANoOpWhenNothingWasEverStarted() {
        VerdaccioSidecarManager manager = manager();

        manager.stopManagedContainers();

        verifyNoInteractions(lifecycleManager);
    }

    private VerdaccioSidecarManager manager() {
        return new VerdaccioSidecarManager(containerBuilder, lifecycleManager, config);
    }

    @SuppressWarnings("unchecked")
    private static void seedPooledContainer(VerdaccioSidecarManager manager, String key, String containerId)
            throws Exception {
        Field poolField = VerdaccioSidecarManager.class.getDeclaredField("pool");
        poolField.setAccessible(true);
        PerKeyContainerPool pool = (PerKeyContainerPool) poolField.get(manager);

        Field containersField = PerKeyContainerPool.class.getDeclaredField("containers");
        containersField.setAccessible(true);
        ConcurrentHashMap<String, PerKeyContainerPool.StartedContainer> containers =
                (ConcurrentHashMap<String, PerKeyContainerPool.StartedContainer>) containersField.get(pool);
        containers.put(key, new PerKeyContainerPool.StartedContainer(containerId, "http://127.0.0.1:1"));
    }
}
