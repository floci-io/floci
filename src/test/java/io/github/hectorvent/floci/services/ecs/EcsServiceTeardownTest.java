package io.github.hectorvent.floci.services.ecs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.container.EcsTaskHandle;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import org.junit.jupiter.api.Test;

import java.io.Closeable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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
                new SingleUseStorageFactory(),
                null);
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

    @Test
    void stoppedTaskRetriesTeardownUntilItsRemainingLogStreamIsReleased() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(false); // docker mode
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");

        EcsContainerManager containerManager = mock(EcsContainerManager.class);
        EcsTaskHandle handle = new EcsTaskHandle("task-arn", Map.of("app", "docker-id"),
                Map.of("docker-id", mock(Closeable.class)));
        when(containerManager.startTask(any(), any(), any(), anyString())).thenReturn(handle);
        AtomicInteger teardownAttempts = new AtomicInteger();
        when(containerManager.stopTaskAndCollectExitCodes(handle)).thenAnswer(ignored -> {
            if (teardownAttempts.incrementAndGet() == 2) {
                handle.removeLogStream("docker-id");
            }
            return Map.of();
        });

        EcsService service = new EcsService(
                new RegionResolver(REGION, "000000000000"),
                containerManager,
                config,
                mock(EcsLoadBalancerRegistrar.class),
                new SingleUseStorageFactory(),
                null);
        service.initializeStorage();

        ContainerDefinition cd = new ContainerDefinition();
        cd.setName("app");
        cd.setImage("nginx:alpine");
        service.registerTaskDefinition("retry-fam", List.of(cd), null, null, null,
                null, null, List.of(), REGION);
        String taskArn = service.runTask(null, "retry-fam", 1, LaunchType.FARGATE, null, null,
                List.of(), null, REGION).getFirst().getTaskArn();

        service.stopTask(null, taskArn, null, REGION);
        verify(containerManager, times(1)).stopTaskAndCollectExitCodes(handle);

        service.reconcile();
        verify(containerManager, times(2)).stopTaskAndCollectExitCodes(handle);

        service.reconcile();
        verify(containerManager, times(2)).stopTaskAndCollectExitCodes(handle);
    }

    private static final class SingleUseStorageFactory extends StorageFactory {
        private final Map<String, StorageBackend<String, ?>> stores = new HashMap<>();

        private SingleUseStorageFactory() {
            super(null, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String serviceName,
                                                    String fileName,
                                                    TypeReference<Map<String, V>> typeReference) {
            return (AccountAwareStorageBackend<V>) stores.computeIfAbsent(fileName, ignored -> AccountAwareStorageBackend.inMemory("000000000000"));
        }
    }
}
