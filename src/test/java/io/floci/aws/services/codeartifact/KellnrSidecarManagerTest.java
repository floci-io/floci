package io.floci.aws.services.codeartifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.ContainerTeardown;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.PerKeyContainerPool;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
 * Checks teardown wiring and use leases without a Docker daemon. Containers are seeded directly
 * into the pool; the manager must defer deletion while a request is active and retain failed
 * removal for a later teardown retry. Mirrors {@link BagetterSidecarManagerTest} exactly: same
 * per-repository container lease shape, different sidecar.
 */
class KellnrSidecarManagerTest {

    private final ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
    private final ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
    private final EmulatorConfig config = mock(EmulatorConfig.class);

    @Test
    void implementsContainerTeardownSoStateResetAndNukeCanFindIt() {
        assertInstanceOf(ContainerTeardown.class, manager());
    }

    @Test
    void stopManagedContainersStopsEveryContainerThePoolIsTracking() throws Exception {
        KellnrSidecarManager manager = manager();
        seedPooledContainer(manager, "cargo-repo-1", "tracked-kellnr-1", "http://127.0.0.1:1");
        seedPooledContainer(manager, "cargo-repo-2", "tracked-kellnr-2", "http://127.0.0.1:1");

        manager.stopManagedContainers();

        verify(lifecycleManager).stopAndRemoveStrict("tracked-kellnr-1", null);
        verify(lifecycleManager).stopAndRemoveStrict("tracked-kellnr-2", null);
    }

    @Test
    void stopManagedContainersIsANoOpWhenNothingWasEverStarted() {
        KellnrSidecarManager manager = manager();

        manager.stopManagedContainers();

        verifyNoInteractions(lifecycleManager);
    }

    @Test
    void releaseWaitsForTheLastCargoRequestBeforeStoppingItsContainer() throws Exception {
        KellnrSidecarManager manager = manager();
        seedPooledContainer(manager, "cargo-repo-1", "tracked-kellnr-1", "http://127.0.0.1:1");
        RepositorySidecarManager client = new KellnrSidecarClient(manager, new ObjectMapper(),
                HttpClient.newHttpClient());
        client.registerRepository("cargo-repo-1");
        client.beginUse("cargo-repo-1");

        client.release("cargo-repo-1");
        verifyNoInteractions(lifecycleManager);
        assertThrows(IllegalStateException.class, () -> client.beginUse("cargo-repo-1"));

        client.endUse("cargo-repo-1");
        verify(lifecycleManager).stopAndRemoveStrict("tracked-kellnr-1", null);
    }

    @Test
    void failedRemovalRemainsRetryableAfterTheLastUse() throws Exception {
        KellnrSidecarManager manager = manager();
        seedPooledContainer(manager, "cargo-repo-1", "tracked-kellnr-1", "http://127.0.0.1:1");
        manager.registerRepository("cargo-repo-1");
        manager.beginUse("cargo-repo-1");
        manager.release("cargo-repo-1");
        doThrow(new IllegalStateException("Docker unavailable")).doNothing().when(lifecycleManager)
                .stopAndRemoveStrict("tracked-kellnr-1", null);

        assertDoesNotThrow(() -> manager.endUse("cargo-repo-1"));
        assertTrue(manager.isRunning("cargo-repo-1"));
        assertThrows(IllegalStateException.class, () -> manager.beginUse("cargo-repo-1"));
        assertThrows(IllegalStateException.class, () -> manager.endUse("cargo-repo-1"));

        manager.stopManagedContainers();
        assertFalse(manager.isRunning("cargo-repo-1"));
        verify(lifecycleManager, times(2)).stopAndRemoveStrict("tracked-kellnr-1", null);
    }

    @Test
    void teardownKeepsAnActiveUseUntilTheRequestFinishes() throws Exception {
        KellnrSidecarManager manager = manager();
        seedPooledContainer(manager, "cargo-repo-1", "tracked-kellnr-1", "http://127.0.0.1:1");
        manager.registerRepository("cargo-repo-1");
        manager.beginUse("cargo-repo-1");

        manager.stopManagedContainers();
        verifyNoInteractions(lifecycleManager);
        assertThrows(IllegalStateException.class, () -> manager.beginUse("cargo-repo-1"));

        manager.endUse("cargo-repo-1");
        verify(lifecycleManager).stopAndRemoveStrict("tracked-kellnr-1", null);
    }

    private KellnrSidecarManager manager() {
        return new KellnrSidecarManager(containerBuilder, lifecycleManager, config);
    }

    @SuppressWarnings("unchecked")
    private static void seedPooledContainer(KellnrSidecarManager manager, String key, String containerId,
                                              String url) throws Exception {
        Field poolField = KellnrSidecarManager.class.getDeclaredField("pool");
        poolField.setAccessible(true);
        PerKeyContainerPool pool = (PerKeyContainerPool) poolField.get(manager);

        Field containersField = PerKeyContainerPool.class.getDeclaredField("containers");
        containersField.setAccessible(true);
        ConcurrentHashMap<String, PerKeyContainerPool.StartedContainer> containers =
                (ConcurrentHashMap<String, PerKeyContainerPool.StartedContainer>) containersField.get(pool);
        containers.put(key, new PerKeyContainerPool.StartedContainer(containerId, url));
    }
}
