package io.github.hectorvent.floci.services.ecs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.container.EcsTaskHandle;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies emulator-shutdown teardown stops the Docker containers of running tasks
 * exactly once. Without it, task containers outlive the process as orphans (task
 * state is transient, so nothing reclaims them on the next start).
 */
class EcsServiceTeardownTest {

    private static final String REGION = "us-east-1";

    @Test
    void stopManagedContainersStopsEachRunningTaskOnce() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(false); // docker mode
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");

        EcsContainerManager containerManager = mock(EcsContainerManager.class);
        EcsTaskHandle handle = mock(EcsTaskHandle.class);
        when(containerManager.startTask(any(), any(), any(), anyString())).thenReturn(handle);

        EcsService service = new EcsService(
                new RegionResolver(REGION, "000000000000"),
                containerManager,
                config,
                mock(EcsLoadBalancerRegistrar.class),
                new SingleUseStorageFactory());
        service.initializeStorage();

        ContainerDefinition cd = new ContainerDefinition();
        cd.setName("app");
        cd.setImage("nginx:alpine");
        service.registerTaskDefinition("teardown-fam", List.of(cd), null, null, null,
                null, null, List.of(), REGION);
        service.runTask(null, "teardown-fam", 1, LaunchType.FARGATE, null, null,
                List.of(), null, REGION);

        service.stopManagedContainers();
        verify(containerManager, times(1)).stopTask(handle);

        // The reconciler must be stopped before handles are drained, or a tick could
        // restart the drained tasks between teardown and the final storage flush.
        assertTrue(service.isReconcilerShutdown());

        // Handles are claimed on the first pass; a second invocation must be a no-op.
        service.stopManagedContainers();
        verify(containerManager, times(1)).stopTask(handle);
    }

    private static final class SingleUseStorageFactory extends StorageFactory {
        private final Map<String, StorageBackend<String, ?>> stores = new HashMap<>();

        private SingleUseStorageFactory() {
            super(null, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> StorageBackend<String, V> create(String serviceName,
                                                    String fileName,
                                                    TypeReference<Map<String, V>> typeReference) {
            return (StorageBackend<String, V>) stores.computeIfAbsent(fileName, ignored -> new InMemoryStorage<>());
        }
    }
}
