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
import io.github.hectorvent.floci.services.ecs.model.Container;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import org.junit.jupiter.api.Test;

import java.io.Closeable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
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
    void startupTransitionWaitsForTheTaskLock() throws Exception {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(false);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        EcsContainerManager containerManager = mock(EcsContainerManager.class);
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch finishStart = new CountDownLatch(1);
        AtomicReference<EcsTask> startingTask = new AtomicReference<>();
        when(containerManager.startTask(any(), any(), any(), anyString())).thenAnswer(invocation -> {
            EcsTask task = invocation.getArgument(0);
            startingTask.set(task);
            startEntered.countDown();
            if (!finishStart.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("container startup was not released");
            }
            return new EcsTaskHandle(task.getTaskArn(), Map.of("app", "docker-id"), Map.of());
        });
        EcsService service = new EcsService(new RegionResolver(REGION, "000000000000"),
                containerManager, config, mock(EcsLoadBalancerRegistrar.class),
                new SingleUseStorageFactory(), null);
        service.initializeStorage();
        ContainerDefinition definition = new ContainerDefinition();
        definition.setName("app");
        definition.setImage("nginx:alpine");
        service.registerTaskDefinition("locked-start", List.of(definition), null, null, null,
                null, null, List.of(), REGION);
        AtomicReference<Throwable> launchFailure = new AtomicReference<>();
        Thread launch = new Thread(() -> {
            try {
                service.runTask(null, "locked-start", 1, LaunchType.FARGATE, null, null,
                        List.of(), null, REGION);
            } catch (Throwable failure) {
                launchFailure.set(failure);
            }
        });
        launch.start();
        try {
            assertTrue(startEntered.await(5, TimeUnit.SECONDS));
            synchronized (startingTask.get()) {
                finishStart.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (launch.getState() != Thread.State.BLOCKED) {
                    assertTrue(launch.isAlive(), "startup finished without acquiring the task lock");
                    assertTrue(System.nanoTime() < deadline, "startup did not wait for the task lock");
                    Thread.onSpinWait();
                }
            }
            launch.join(5000);
            assertFalse(launch.isAlive());
            assertNull(launchFailure.get());
            assertEquals("RUNNING", startingTask.get().getLastStatus());
        } finally {
            finishStart.countDown();
            service.stopManagedContainers();
        }
    }

    @Test
    void stopDuringContainerStartupCompletesAfterTheHandleArrives() throws Exception {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(false);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        EcsContainerManager containerManager = mock(EcsContainerManager.class);
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch finishStart = new CountDownLatch(1);
        AtomicReference<String> taskArn = new AtomicReference<>();
        EcsTaskHandle handle = new EcsTaskHandle("task-arn", Map.of("app", "docker-id"), Map.of());
        when(containerManager.startTask(any(), any(), any(), anyString())).thenAnswer(invocation -> {
            EcsTask task = invocation.getArgument(0);
            taskArn.set(task.getTaskArn());
            startEntered.countDown();
            if (!finishStart.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("container startup was not released");
            }
            Container container = new Container();
            container.setName("app");
            container.setRuntimeId("docker-id");
            task.setContainers(List.of(container));
            return handle;
        });
        when(containerManager.stopTaskAndCollectExitCodes(handle)).thenAnswer(ignored -> {
            handle.recordContainerRemoved("app");
            return Map.of("app", 0);
        });

        EcsService service = new EcsService(
                new RegionResolver(REGION, "000000000000"), containerManager, config,
                mock(EcsLoadBalancerRegistrar.class), new SingleUseStorageFactory(), null);
        service.initializeStorage();
        ContainerDefinition definition = new ContainerDefinition();
        definition.setName("app");
        definition.setImage("nginx:alpine");
        service.registerTaskDefinition("startup-stop", List.of(definition), null, null, null,
                null, null, List.of(), REGION);

        CompletableFuture<List<EcsTask>> launch = CompletableFuture.supplyAsync(() ->
                service.runTask(null, "startup-stop", 1, LaunchType.FARGATE, null, null,
                        List.of(), null, REGION));
        try {
            assertTrue(startEntered.await(5, TimeUnit.SECONDS));
            assertEquals("STOPPING", service.stopTask(null, taskArn.get(), null, REGION).getLastStatus());
        } finally {
            finishStart.countDown();
        }

        launch.get(5, TimeUnit.SECONDS);
        assertEquals("STOPPED", service.describeTasks(null, List.of(taskArn.get()), REGION).getFirst().getLastStatus());
        verify(containerManager).stopTaskAndCollectExitCodes(handle);
        service.stopManagedContainers();
    }

    @Test
    void stopTaskRecoversContainerIdsWhenTheRuntimeHandleIsMissing() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(false);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");

        EcsContainerManager containerManager = mock(EcsContainerManager.class);
        EcsTaskHandle original = new EcsTaskHandle("task-arn", Map.of("app", "docker-id"), Map.of());
        when(containerManager.startTask(any(), any(), any(), anyString())).thenAnswer(invocation -> {
            EcsTask task = invocation.getArgument(0);
            Container container = new Container();
            container.setName("app");
            container.setDockerId("docker-id");
            task.setContainers(List.of(container));
            return original;
        });
        when(containerManager.stopTaskAndCollectExitCodes(any())).thenAnswer(invocation -> {
            EcsTaskHandle stoppingHandle = invocation.getArgument(0);
            stoppingHandle.recordContainerRemoved("app");
            return Map.of("app", 0);
        });

        EcsService service = new EcsService(
                new RegionResolver(REGION, "000000000000"), containerManager, config,
                mock(EcsLoadBalancerRegistrar.class), new SingleUseStorageFactory(), null);
        service.initializeStorage();
        ContainerDefinition definition = new ContainerDefinition();
        definition.setName("app");
        definition.setImage("nginx:alpine");
        service.registerTaskDefinition("recover-handle", List.of(definition), null, null, null,
                null, null, List.of(), REGION);
        String taskArn = service.runTask(null, "recover-handle", 1, LaunchType.FARGATE, null, null,
                List.of(), null, REGION).getFirst().getTaskArn();

        // A no-op Docker test double leaves the container alive while shutdown drops its handle.
        service.stopManagedContainers();
        service.afterReset();
        service.stopTask(null, taskArn, null, REGION);

        verify(containerManager).stopTaskAndCollectExitCodes(argThat(
                handle -> "docker-id".equals(handle.getContainerIds().get("app"))));
        assertEquals("STOPPED", service.describeTasks(null, List.of(taskArn), REGION).getFirst().getLastStatus());
        service.stopManagedContainers();
    }

    @Test
    void failedContainerRemovalKeepsTaskStoppingUntilReconciliationRetries() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(false);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");

        EcsContainerManager containerManager = mock(EcsContainerManager.class);
        EcsTaskHandle handle = new EcsTaskHandle("task-arn", Map.of("app", "docker-id"), Map.of());
        when(containerManager.startTask(any(), any(), any(), anyString())).thenReturn(handle);
        Map<String, Integer> failed = new HashMap<>();
        failed.put("app", null);
        AtomicInteger attempts = new AtomicInteger();
        when(containerManager.stopTaskAndCollectExitCodes(handle)).thenAnswer(ignored -> {
            if (attempts.incrementAndGet() == 1) {
                return failed;
            }
            handle.recordContainerRemoved("app");
            return Map.of("app", 0);
        });

        EcsService service = new EcsService(
                new RegionResolver(REGION, "000000000000"), containerManager, config,
                mock(EcsLoadBalancerRegistrar.class), new SingleUseStorageFactory(), null);
        service.initializeStorage();
        ContainerDefinition definition = new ContainerDefinition();
        definition.setName("app");
        definition.setImage("nginx:alpine");
        service.registerTaskDefinition("retry-removal", List.of(definition), null, null, null,
                null, null, List.of(), REGION);
        String taskArn = service.runTask(null, "retry-removal", 1, LaunchType.FARGATE, null, null,
                List.of(), null, REGION).getFirst().getTaskArn();

        service.stopTask(null, taskArn, null, REGION);
        assertEquals("STOPPING", service.describeTasks(null, List.of(taskArn), REGION).getFirst().getLastStatus());

        service.reconcile();
        assertEquals("STOPPED", service.describeTasks(null, List.of(taskArn), REGION).getFirst().getLastStatus());
        verify(containerManager, times(2)).stopTaskAndCollectExitCodes(handle);
    }

    @Test
    void removedContainerWithUnknownExitCodeStopsWithoutInventingSuccess() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(false);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        EcsContainerManager containerManager = mock(EcsContainerManager.class);
        EcsTaskHandle handle = new EcsTaskHandle("task-arn", Map.of("app", "docker-id"), Map.of());
        when(containerManager.startTask(any(), any(), any(), anyString())).thenAnswer(invocation -> {
            EcsTask task = invocation.getArgument(0);
            Container container = new Container();
            container.setName("app");
            task.setContainers(List.of(container));
            return handle;
        });
        when(containerManager.stopTaskAndCollectExitCodes(handle)).thenAnswer(ignored -> {
            handle.recordContainerRemoved("app");
            Map<String, Integer> codes = new HashMap<>();
            codes.put("app", null);
            return codes;
        });

        EcsService service = new EcsService(new RegionResolver(REGION, "000000000000"),
                containerManager, config, mock(EcsLoadBalancerRegistrar.class),
                new SingleUseStorageFactory(), null);
        service.initializeStorage();
        ContainerDefinition definition = new ContainerDefinition();
        definition.setName("app");
        definition.setImage("nginx:alpine");
        service.registerTaskDefinition("unknown-exit", List.of(definition), null, null, null,
                null, null, List.of(), REGION);
        String taskArn = service.runTask(null, "unknown-exit", 1, LaunchType.FARGATE, null, null,
                List.of(), null, REGION).getFirst().getTaskArn();

        EcsTask stopped = service.stopTask(null, taskArn, null, REGION);
        assertEquals("STOPPED", stopped.getLastStatus());
        assertNull(stopped.getContainers().getFirst().getExitCode());
        service.reconcile();
        verify(containerManager, times(1)).stopTaskAndCollectExitCodes(handle);
        service.stopManagedContainers();
    }

    @Test
    void concurrentStopsTeardownTheTaskOnlyOnce() throws Exception {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(false);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        EcsContainerManager containerManager = mock(EcsContainerManager.class);
        EcsTaskHandle handle = new EcsTaskHandle("task-arn", Map.of("app", "docker-id"), Map.of());
        when(containerManager.startTask(any(), any(), any(), anyString())).thenReturn(handle);
        CountDownLatch teardownEntered = new CountDownLatch(1);
        CountDownLatch finishTeardown = new CountDownLatch(1);
        when(containerManager.stopTaskAndCollectExitCodes(handle)).thenAnswer(ignored -> {
            teardownEntered.countDown();
            if (!finishTeardown.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("teardown was not released");
            }
            handle.recordContainerRemoved("app");
            return Map.of("app", 0);
        });

        EcsService service = new EcsService(
                new RegionResolver(REGION, "000000000000"), containerManager, config,
                mock(EcsLoadBalancerRegistrar.class), new SingleUseStorageFactory(), null);
        service.initializeStorage();
        ContainerDefinition definition = new ContainerDefinition();
        definition.setName("app");
        definition.setImage("nginx:alpine");
        service.registerTaskDefinition("concurrent-stop", List.of(definition), null, null, null,
                null, null, List.of(), REGION);
        String taskArn = service.runTask(null, "concurrent-stop", 1, LaunchType.FARGATE, null, null,
                List.of(), null, REGION).getFirst().getTaskArn();

        CompletableFuture<EcsTask> first = CompletableFuture.supplyAsync(() ->
                service.stopTask(null, taskArn, null, REGION));
        try {
            assertTrue(teardownEntered.await(5, TimeUnit.SECONDS));
            AtomicReference<Throwable> secondFailure = new AtomicReference<>();
            Thread second = new Thread(() -> {
                try {
                    service.stopTask(null, taskArn, null, REGION);
                } catch (Throwable failure) {
                    secondFailure.set(failure);
                }
            });
            second.start();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (second.getState() != Thread.State.BLOCKED) {
                assertTrue(second.isAlive(), "second stop finished without waiting for the first");
                assertTrue(System.nanoTime() < deadline, "second stop never queued on the task");
                Thread.onSpinWait();
            }

            finishTeardown.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.join(5000);
            assertFalse(second.isAlive(), "second stop did not finish");
            assertNull(secondFailure.get());
            verify(containerManager, times(1)).stopTaskAndCollectExitCodes(handle);
            assertEquals("STOPPED", service.describeTasks(null, List.of(taskArn), REGION).getFirst().getLastStatus());
        } finally {
            finishTeardown.countDown();
        }
    }

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
    void afterResetRestartsTheReconcilerThatAResetTeardownStopped() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(false); // docker mode
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        EcsService service = new EcsService(
                new RegionResolver(REGION, "000000000000"),
                mock(EcsContainerManager.class),
                config,
                mock(EcsLoadBalancerRegistrar.class),
                new SingleUseStorageFactory(),
                null);
        service.initializeStorage();

        // A reset runs the teardown, then clear(), and afterReset() last, even when the wipe or
        // a clear() threw; shutdown runs only the teardown. afterReset() alone must bring the
        // reconciler back.
        service.stopManagedContainers();
        assertTrue(service.isReconcilerShutdown());

        service.afterReset();
        assertFalse(service.isReconcilerShutdown());
        service.afterReset();
        assertFalse(service.isReconcilerShutdown());

        service.stopManagedContainers();
        assertTrue(service.isReconcilerShutdown());
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
            handle.recordContainerRemoved("app");
            if (teardownAttempts.incrementAndGet() == 2) {
                handle.removeLogStream("docker-id");
            }
            return Map.of("app", 0);
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
