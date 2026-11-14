package io.floci.aws.services.ecs;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.ecs.container.EcsContainerManager;
import io.floci.aws.services.ecs.container.EcsTaskHandle;
import io.floci.aws.services.ecs.exec.EcsExecSessionRegistry;
import io.floci.aws.services.ecs.model.Container;
import io.floci.aws.services.ecs.model.ContainerDefinition;
import io.floci.aws.services.ecs.model.EcsLoadBalancer;
import io.floci.aws.services.ecs.model.EcsServiceModel;
import io.floci.aws.services.ecs.model.EcsTask;
import io.floci.aws.services.ecs.model.LaunchType;
import io.floci.aws.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EcsEssentialContainerExitServiceTest {

    private static final String REGION = "us-east-1";
    private static final String CLUSTER = "essential-exit";
    private static final Instant APP_FINISHED = Instant.parse("2026-01-01T12:00:00Z");
    private static final Instant SIDECAR_FINISHED = APP_FINISHED.plusSeconds(1);

    private final EcsContainerManager manager = mock(EcsContainerManager.class);
    private final EcsLoadBalancerRegistrar lbRegistrar = mock(EcsLoadBalancerRegistrar.class);
    private final Queue<Runnable> launches = new ArrayDeque<>();
    private final Queue<Runnable> stops = new ArrayDeque<>();
    private EcsService service;
    private EcsTaskHandle handle;

    @BeforeEach
    void setUp() throws InterruptedException {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(false);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        service = new EcsService(new RegionResolver(REGION, "000000000000"), manager, config,
                lbRegistrar, new InMemoryStorageFactory("000000000000"), null,
                new EcsExecSessionRegistry(), null, null, Clock.systemUTC(),
                queuedExecutor(launches), queuedExecutor(stops));
        service.initializeStorage();
        service.createCluster(CLUSTER, REGION);
        when(manager.getExitCodeIfStopped(anyString())).thenReturn(null);
        when(manager.startTask(any(), any(), any(), anyString())).thenAnswer(invocation -> {
            EcsTask task = invocation.getArgument(0);
            task.getContainers().forEach(container -> {
                container.setRuntimeId(container.getName() + "-id");
                container.setLastStatus("RUNNING");
            });
            // Inspect the live sidecar first, so an early return cannot hide the app's exit.
            Map<String, String> ids = new LinkedHashMap<>();
            ids.put("sidecar", "sidecar-id");
            ids.put("app", "app-id");
            handle = new EcsTaskHandle(task.getTaskArn(), ids, Map.of());
            return handle;
        });
        when(manager.stopTaskAndCollectExitCodes(any())).thenAnswer(invocation -> completeDockerStop());
        doAnswer(invocation -> {
            EcsTaskHandle stopped = invocation.getArgument(0);
            stopped.getContainerIds().keySet().forEach(stopped::recordContainerRemoved);
            return null;
        }).when(manager).cleanupStoppedTask(any());
    }

    @AfterEach
    void tearDown() {
        service.stopManagedContainers();
    }

    @ParameterizedTest
    @CsvSource({"false, 0", "true, 0", "true, 17"})
    void essentialExitStopsALiveSidecarAsynchronously(boolean explicitEssential, int exitCode) {
        EcsTask task = runningTask(explicitEssential);
        when(manager.getExitCodeIfStopped("app-id")).thenReturn(exitCode);
        when(manager.getFinishedAtIfStopped("app-id")).thenReturn(APP_FINISHED);

        service.reconcile();

        assertEquals("STOPPING", task.getLastStatus());
        assertEquals("STOPPED", task.getDesiredStatus());
        assertEquals("Essential container in task exited", task.getStoppedReason());
        assertEquals("EssentialContainerExited", task.getStopCode());
        assertEquals(1, runningTasksCount());
        assertEquals(1, stops.size());
        verify(manager, never()).stopTaskAndCollectExitCodes(any());
        service.reconcile();
        assertEquals(1, stops.size(), "a pending stop must not be queued twice");

        // Docker may forget the naturally exited container before the stopper runs.
        when(manager.getExitCodeIfStopped("app-id")).thenReturn(null);
        when(manager.getFinishedAtIfStopped("app-id")).thenReturn(null);
        stops.remove().run();

        assertStopped(task, exitCode);
        service.reconcile();
        assertTrue(stops.isEmpty());
        assertEquals(0, runningTasksCount());
        verify(manager, times(1)).stopTaskAndCollectExitCodes(handle);
        verify(manager, times(1)).releaseTaskNetwork(task, REGION);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 5})
    void nonessentialExitUpdatesTheContainerWithoutStoppingTheTask(int exitCode) {
        EcsTask task = runningTask(false);
        when(manager.getExitCodeIfStopped("sidecar-id")).thenReturn(exitCode);
        when(manager.getFinishedAtIfStopped("sidecar-id")).thenReturn(SIDECAR_FINISHED);

        service.reconcile();
        service.reconcile();

        assertEquals("RUNNING", task.getLastStatus());
        assertEquals("RUNNING", task.getDesiredStatus());
        assertNull(task.getStopCode());
        assertEquals("RUNNING", container(task, "app").getLastStatus());
        assertEquals("STOPPED", container(task, "sidecar").getLastStatus());
        assertEquals(exitCode, container(task, "sidecar").getExitCode());
        assertEquals(SIDECAR_FINISHED, container(task, "sidecar").getFinishedAt());
        assertEquals(1, runningTasksCount());
        assertTrue(stops.isEmpty());
        verify(manager, never()).stopTaskAndCollectExitCodes(any());
        verify(manager, never()).cleanupStoppedTask(any());
        verify(manager, never()).releaseTaskNetwork(any(), anyString());
    }

    @Test
    void essentialExitStopsALiveSidecarImmediatelyWhileLoadBalancerTargetsDrain() {
        EcsTask task = runningTask(false);
        EcsLoadBalancer loadBalancer = new EcsLoadBalancer();
        loadBalancer.setTargetGroupArn("arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/app/123");
        loadBalancer.setContainerName("app");
        loadBalancer.setContainerPort(8080);
        // Isolate this task's teardown from the scheduler launching a replacement.
        EcsServiceModel owner = service.createService(CLUSTER, "load-balanced", "essential-exit", 0,
                LaunchType.FARGATE, List.of(loadBalancer), null, REGION);
        task.setOwningServiceArn(owner.getServiceArn());
        task.setDeploymentId(owner.getDeploymentId());
        when(lbRegistrar.deregisterTask(task, owner, REGION)).thenReturn(Duration.ofSeconds(300));
        when(manager.getExitCodeIfStopped("app-id")).thenReturn(17);
        when(manager.getFinishedAtIfStopped("app-id")).thenReturn(APP_FINISHED);

        service.reconcile();

        verify(lbRegistrar, times(1)).deregisterTask(task, owner, REGION);
        assertEquals("STOPPING", task.getLastStatus());
        assertEquals("STOPPED", task.getDesiredStatus());
        assertEquals("EssentialContainerExited", task.getStopCode());
        assertEquals(1, stops.size(), "a live sidecar must stop without waiting for target draining");
        verify(manager, never()).stopTaskAndCollectExitCodes(any());
        service.reconcile();
        assertEquals(1, stops.size());

        when(manager.getExitCodeIfStopped("app-id")).thenReturn(null);
        when(manager.getFinishedAtIfStopped("app-id")).thenReturn(null);
        stops.remove().run();

        assertStopped(task, 17);
        service.reconcile();
        assertTrue(stops.isEmpty());
        verify(lbRegistrar, times(1)).deregisterTask(task, owner, REGION);
        verify(manager, times(1)).stopTaskAndCollectExitCodes(handle);
        verify(manager, times(1)).releaseTaskNetwork(task, REGION);
    }

    @Test
    void essentialExitRetriesAFailedBackgroundStopWithoutLosingNaturalExitMetadata() {
        EcsTask task = runningTask(false);
        when(manager.getExitCodeIfStopped("app-id")).thenReturn(23);
        when(manager.getFinishedAtIfStopped("app-id")).thenReturn(APP_FINISHED);
        when(manager.stopTaskAndCollectExitCodes(any()))
                .thenThrow(new IllegalStateException("Docker stop failed"))
                .thenAnswer(invocation -> completeDockerStop());

        service.reconcile();
        assertEquals(1, stops.size());
        stops.remove().run();

        assertEquals("STOPPING", task.getLastStatus());
        assertEquals(1, runningTasksCount());
        assertEquals("EssentialContainerExited", task.getStopCode());
        verify(manager, never()).releaseTaskNetwork(any(), anyString());
        service.reconcile();
        service.reconcile();
        assertEquals(1, stops.size(), "the failed stop is retried only once per task");
        stops.remove().run();

        assertStopped(task, 23);
        service.reconcile();
        assertTrue(stops.isEmpty());
        verify(manager, times(2)).stopTaskAndCollectExitCodes(handle);
        verify(manager, times(1)).releaseTaskNetwork(task, REGION);
    }

    @Test
    void aPreviouslyObservedNonessentialExitSurvivesLaterDockerInspectionAndTaskExit() {
        EcsTask task = runningTask(false);
        when(manager.getExitCodeIfStopped("sidecar-id")).thenReturn(5);
        when(manager.getFinishedAtIfStopped("sidecar-id")).thenReturn(SIDECAR_FINISHED);

        service.reconcile();

        assertEquals("RUNNING", task.getLastStatus());
        assertEquals(5, container(task, "sidecar").getExitCode());
        assertEquals(SIDECAR_FINISHED, container(task, "sidecar").getFinishedAt());
        when(manager.getExitCodeIfStopped("sidecar-id")).thenReturn(0);
        when(manager.getFinishedAtIfStopped("sidecar-id")).thenReturn(SIDECAR_FINISHED.plusSeconds(10));

        service.reconcile();

        assertEquals("RUNNING", task.getLastStatus());
        assertEquals(5, container(task, "sidecar").getExitCode());
        assertEquals(SIDECAR_FINISHED, container(task, "sidecar").getFinishedAt());
        when(manager.getExitCodeIfStopped("app-id")).thenReturn(0);
        when(manager.getFinishedAtIfStopped("app-id")).thenReturn(APP_FINISHED);

        service.reconcile();
        service.reconcile();

        assertEquals("STOPPED", task.getLastStatus());
        assertEquals("EssentialContainerExited", task.getStopCode());
        assertEquals(5, container(task, "sidecar").getExitCode());
        assertEquals(SIDECAR_FINISHED, container(task, "sidecar").getFinishedAt());
        assertEquals(0, runningTasksCount());
        verify(manager, times(1)).cleanupStoppedTask(handle);
        verify(manager, times(1)).releaseTaskNetwork(task, REGION);
    }

    @Test
    void allExitedContainersAreCleanedUpOnceAndKeepTheirNaturalExitMetadata() {
        EcsTask task = runningTask(false);
        when(manager.getExitCodeIfStopped("app-id")).thenReturn(9);
        when(manager.getFinishedAtIfStopped("app-id")).thenReturn(APP_FINISHED);
        when(manager.getExitCodeIfStopped("sidecar-id")).thenReturn(0);
        when(manager.getFinishedAtIfStopped("sidecar-id")).thenReturn(SIDECAR_FINISHED);

        service.reconcile();
        service.reconcile();

        assertEquals("STOPPED", task.getLastStatus());
        assertEquals("EssentialContainerExited", task.getStopCode());
        assertEquals("Essential container in task exited", task.getStoppedReason());
        assertEquals(9, container(task, "app").getExitCode());
        assertEquals(APP_FINISHED, container(task, "app").getFinishedAt());
        assertEquals(0, container(task, "sidecar").getExitCode());
        assertEquals(SIDECAR_FINISHED, container(task, "sidecar").getFinishedAt());
        assertEquals(0, runningTasksCount());
        assertTrue(stops.isEmpty());
        verify(manager, times(1)).cleanupStoppedTask(handle);
        verify(manager, never()).stopTaskAndCollectExitCodes(any());
        verify(manager, times(1)).releaseTaskNetwork(task, REGION);
    }

    private EcsTask runningTask(boolean explicitEssential) {
        ContainerDefinition app = new ContainerDefinition();
        app.setName("app");
        app.setImage("app:1");
        if (explicitEssential) {
            app.setEssential(true);
        }
        ContainerDefinition sidecar = new ContainerDefinition();
        sidecar.setName("sidecar");
        sidecar.setImage("sidecar:1");
        sidecar.setEssential(false);
        service.registerTaskDefinition("essential-exit", List.of(app, sidecar), null, null, null,
                null, null, List.of(), REGION);
        String taskArn = service.runTask(CLUSTER, "essential-exit", 1, LaunchType.FARGATE, null, null,
                List.of(), null, REGION).getFirst().getTaskArn();
        launches.remove().run();
        EcsTask task = service.describeTasks(CLUSTER, List.of(taskArn), REGION).getFirst();
        assertEquals("RUNNING", task.getLastStatus());
        assertEquals(1, runningTasksCount());
        return task;
    }

    private Map<String, Integer> completeDockerStop() {
        Map<String, Integer> codes = new LinkedHashMap<>();
        codes.put("app", handle.getRecordedExitCode("app"));
        codes.put("sidecar", 143);
        handle.recordFinishedAt("sidecar", SIDECAR_FINISHED);
        handle.getContainerIds().keySet().forEach(handle::recordContainerRemoved);
        return codes;
    }

    private void assertStopped(EcsTask task, int appExitCode) {
        assertEquals("STOPPED", task.getLastStatus());
        assertEquals("STOPPED", task.getDesiredStatus());
        assertEquals("Essential container in task exited", task.getStoppedReason());
        assertEquals("EssentialContainerExited", task.getStopCode());
        assertEquals("STOPPED", container(task, "app").getLastStatus());
        assertEquals(appExitCode, container(task, "app").getExitCode());
        assertEquals(APP_FINISHED, container(task, "app").getFinishedAt());
        assertEquals("STOPPED", container(task, "sidecar").getLastStatus());
        assertEquals(143, container(task, "sidecar").getExitCode());
        assertEquals(SIDECAR_FINISHED, container(task, "sidecar").getFinishedAt());
        assertEquals(0, runningTasksCount());
    }

    private Container container(EcsTask task, String name) {
        return task.getContainers().stream().filter(container -> name.equals(container.getName()))
                .findFirst().orElseThrow();
    }

    private int runningTasksCount() {
        return service.describeClusters(List.of(CLUSTER), REGION).getFirst().getRunningTasksCount();
    }

    private ExecutorService queuedExecutor(Queue<Runnable> queue) throws InterruptedException {
        ExecutorService executor = mock(ExecutorService.class);
        doAnswer(invocation -> {
            queue.add(invocation.getArgument(0));
            return null;
        }).when(executor).execute(any(Runnable.class));
        when(executor.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(true);
        return executor;
    }
}
