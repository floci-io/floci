package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.container.EcsTaskHandle;
import io.github.hectorvent.floci.services.ecs.model.Container;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.services.ecs.model.TaskStatus;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A task's graceful stop waits out its containers' {@code stopTimeout}, up to two minutes a
 * container. The service scheduler used to wait for it on the single reconciler thread, so one
 * slow stop held up every other task and service, and several ran one after another (#6619).
 * These run the reconciler in Docker mode against a container manager whose stop of one chosen
 * task blocks until the test lets it finish, the way a container that ignores SIGTERM holds its
 * stop until the timeout kills it.
 */
class EcsGracefulStopConcurrencyServiceTest {

    private static final String REGION = "us-east-1";
    private static final String CLUSTER = "stop-cluster";
    /** Far longer than a tick takes here, and far shorter than the stop the tests hold open. */
    private static final Duration TICK_BUDGET = Duration.ofSeconds(5);

    private final EcsContainerManager containerManager = mock(EcsContainerManager.class);
    private final EcsEventPublisher eventPublisher = mock(EcsEventPublisher.class);
    private final List<EcsService> services = new ArrayList<>();
    /** The task whose stop blocks until {@link #releaseSlowStop}; every other task stops at once. */
    private final AtomicReference<String> slowTaskArn = new AtomicReference<>();
    private final CountDownLatch slowStopEntered = new CountDownLatch(1);
    private final CountDownLatch releaseSlowStop = new CountDownLatch(1);
    /** The task whose next {@link #stopFailures} stops throw, as a stop does when Docker is unreachable. */
    private final AtomicReference<String> failingTaskArn = new AtomicReference<>();
    private final AtomicInteger stopFailures = new AtomicInteger();

    @BeforeEach
    void stubContainers() {
        // Mockito answers 0 for an Integer, which reads as "every container exited".
        when(containerManager.getExitCodeIfStopped(anyString())).thenReturn(null);
        when(containerManager.startTask(any(), any(), any(), anyString())).thenAnswer(invocation -> {
            EcsTask task = invocation.getArgument(0);
            Container container = new Container();
            container.setName("app");
            container.setRuntimeId("docker-" + Integer.toHexString(task.getTaskArn().hashCode()));
            task.setContainers(List.of(container));
            return new EcsTaskHandle(task.getTaskArn(), Map.of("app", container.getRuntimeId()), Map.of());
        });
        when(containerManager.stopTaskAndCollectExitCodes(any())).thenAnswer(invocation -> {
            EcsTaskHandle handle = invocation.getArgument(0);
            if (handle.getTaskArn().equals(failingTaskArn.get()) && stopFailures.getAndDecrement() > 0) {
                throw new IllegalStateException("Cannot connect to the Docker daemon");
            }
            int exitCode = 0;
            if (handle.getTaskArn().equals(slowTaskArn.get())) {
                slowStopEntered.countDown();
                // A container that ignores SIGTERM goes only when its stopTimeout runs out, as SIGKILL.
                releaseSlowStop.await(30, TimeUnit.SECONDS);
                exitCode = 137;
            }
            handle.getContainerIds().keySet().forEach(handle::recordContainerRemoved);
            return Map.of("app", exitCode);
        });
    }

    @AfterEach
    void releaseStopsAndTearDown() {
        releaseSlowStop.countDown();
        services.forEach(EcsService::stopManagedContainers);
    }

    @Test
    void anUnrelatedServiceRollsOutWhileAnotherTaskWaitsOutItsStopTimeout() throws Exception {
        EcsService service = newService();
        createService(service, "slow", 1);
        createService(service, "fast", 1);
        tick(service);
        EcsTask slowOld = onlyRunningTask(service, "slow");
        EcsTask fastOld = onlyRunningTask(service, "fast");
        slowTaskArn.set(slowOld.getTaskArn());

        // The slow service rolls: its replacement starts, then the next tick drains the old task,
        // whose container takes its whole stopTimeout to go.
        service.updateService(CLUSTER, "slow", null, null, null, null, true, REGION);
        tick(service);
        FutureTask<Void> drain = startTick(service);
        try {
            assertTrue(slowStopEntered.await(5, TimeUnit.SECONDS), "the slow task's stop never started");

            // Meanwhile an unrelated service rolls all the way through.
            service.updateService(CLUSTER, "fast", null, null, null, null, true, REGION);
            tick(service);
            tick(service);
            await().atMost(TICK_BUDGET).until(() -> "STOPPED".equals(lastStatus(service, fastOld)));
            assertNotEquals(fastOld.getTaskArn(), onlyRunningTask(service, "fast").getTaskArn());

            assertEquals("STOPPING", lastStatus(service, slowOld), "the slow task is still in its grace period");
            drain.get(TICK_BUDGET.toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            releaseSlowStop.countDown();
        }
        await().atMost(TICK_BUDGET).until(() -> "STOPPED".equals(lastStatus(service, slowOld)));
    }

    @Test
    void ticksDuringASlowStopNeitherRepeatItNorLandItTwice() throws Exception {
        EcsService service = newService();
        createService(service, "slow", 1);
        tick(service);
        EcsTask old = onlyRunningTask(service, "slow");
        slowTaskArn.set(old.getTaskArn());
        service.updateService(CLUSTER, "slow", null, null, null, null, true, REGION);
        tick(service);
        EcsTask replacement = runningTasks(service, "slow").stream()
                .filter(task -> !task.getTaskArn().equals(old.getTaskArn()))
                .findFirst().orElseThrow();

        try {
            tick(service);
            assertTrue(slowStopEntered.await(5, TimeUnit.SECONDS), "the slow task's stop never started");
            tick(service);
            tick(service);
            tick(service);
            verify(containerManager, times(1)).stopTaskAndCollectExitCodes(handleOf(old));
            assertEquals("STOPPING", lastStatus(service, old));
        } finally {
            releaseSlowStop.countDown();
        }
        await().atMost(TICK_BUDGET).until(() -> "STOPPED".equals(lastStatus(service, old)));
        tick(service);
        tick(service);

        EcsTask stopped = describe(service, old);
        assertEquals(137, stopped.getContainers().getFirst().getExitCode());
        assertEquals(EcsService.STOP_CODE_SERVICE_SCHEDULER_INITIATED, stopped.getStopCode());
        verify(containerManager, times(1)).stopTaskAndCollectExitCodes(handleOf(old));
        verify(eventPublisher, times(1)).emitTaskLadder(argThat(task -> old.getTaskArn().equals(task.getTaskArn())),
                eq(TaskStatus.RUNNING), eq(TaskStatus.STOPPED), eq(REGION));
        assertEquals(List.of(replacement.getTaskArn()),
                runningTasks(service, "slow").stream().map(EcsTask::getTaskArn).toList());
        assertEquals(2, allTasks(service, "slow").size(), "a slow stop starts no second replacement");
        assertEquals(1, service.describeClusters(List.of(CLUSTER), REGION).getFirst().getRunningTasksCount());
    }

    @Test
    void aStopThatFailsIsRetriedOnALaterTick() throws Exception {
        EcsService service = newService();
        createService(service, "flaky", 1);
        tick(service);
        EcsTask task = onlyRunningTask(service, "flaky");
        failingTaskArn.set(task.getTaskArn());
        stopFailures.set(1);

        service.updateService(CLUSTER, "flaky", null, 0, null, REGION);
        tick(service);
        await().atMost(TICK_BUDGET).untilAsserted(() ->
                verify(containerManager).stopTaskAndCollectExitCodes(handleOf(task)));
        assertEquals("STOPPING", lastStatus(service, task), "a stop that failed leaves the task STOPPING");

        await().atMost(TICK_BUDGET).until(() -> {
            tick(service);
            return "STOPPED".equals(lastStatus(service, task));
        });
        verify(containerManager, times(2)).stopTaskAndCollectExitCodes(handleOf(task));
    }

    @Test
    void teardownLetsAStopAlreadyRunningFinishInsteadOfStoppingTheTaskAgain() throws Exception {
        EcsService service = newService();
        createService(service, "slow", 1);
        tick(service);
        EcsTask old = onlyRunningTask(service, "slow");
        slowTaskArn.set(old.getTaskArn());
        service.updateService(CLUSTER, "slow", null, null, null, null, true, REGION);
        tick(service);
        EcsTask replacement = runningTasks(service, "slow").stream()
                .filter(task -> !task.getTaskArn().equals(old.getTaskArn()))
                .findFirst().orElseThrow();

        FutureTask<Void> drain = startTick(service);
        FutureTask<Void> teardown;
        try {
            assertTrue(slowStopEntered.await(5, TimeUnit.SECONDS), "the slow task's stop never started");
            // A reset or shutdown arrives while that container is still in its grace period.
            teardown = new FutureTask<>(service::stopManagedContainers, null);
            Thread teardownThread = new Thread(teardown, "test-ecs-teardown");
            teardownThread.setDaemon(true);
            teardownThread.start();
            assertThrows(TimeoutException.class, () -> teardown.get(500, TimeUnit.MILLISECONDS),
                    "the teardown waits for the stop already running");
        } finally {
            releaseSlowStop.countDown();
        }
        teardown.get(TICK_BUDGET.toMillis(), TimeUnit.MILLISECONDS);
        drain.get(TICK_BUDGET.toMillis(), TimeUnit.MILLISECONDS);

        assertEquals("STOPPED", lastStatus(service, old));
        assertEquals(137, describe(service, old).getContainers().getFirst().getExitCode());
        verify(containerManager, times(1)).stopTaskAndCollectExitCodes(handleOf(old));
        verify(containerManager, never()).stopTask(handleOf(old));
        verify(containerManager, times(1)).stopTask(handleOf(replacement));
    }

    @Test
    void aResetBringsTheTaskStopperBackWithTheReconciler() throws Exception {
        EcsService service = newService();
        createService(service, "after-reset", 1);
        tick(service);
        EcsTask task = onlyRunningTask(service, "after-reset");

        service.stopManagedContainers();
        service.afterReset();
        service.updateService(CLUSTER, "after-reset", null, 0, null, REGION);
        tick(service);

        await().atMost(TICK_BUDGET).until(() -> "STOPPED".equals(lastStatus(service, task)));
    }

    @Test
    void stopTaskWaitsForTheStopTheSchedulerAlreadyStarted() throws Exception {
        EcsService service = newService();
        createService(service, "slow", 1);
        tick(service);
        EcsTask task = onlyRunningTask(service, "slow");
        slowTaskArn.set(task.getTaskArn());
        service.updateService(CLUSTER, "slow", null, 0, null, REGION);

        FutureTask<Void> scaleIn = startTick(service);
        CompletableFuture<EcsTask> userStop;
        try {
            assertTrue(slowStopEntered.await(5, TimeUnit.SECONDS), "the scale-in stop never started");
            userStop = CompletableFuture.supplyAsync(() ->
                    service.stopTask(CLUSTER, task.getTaskArn(), "user stop", REGION));
            assertThrows(TimeoutException.class, () -> userStop.get(500, TimeUnit.MILLISECONDS),
                    "StopTask answers once the stop already running has landed");
        } finally {
            releaseSlowStop.countDown();
        }

        EcsTask stopped = userStop.get(TICK_BUDGET.toMillis(), TimeUnit.MILLISECONDS);
        scaleIn.get(TICK_BUDGET.toMillis(), TimeUnit.MILLISECONDS);
        assertEquals("STOPPED", stopped.getLastStatus());
        assertEquals(EcsService.STOP_CODE_SERVICE_SCHEDULER_INITIATED, stopped.getStopCode());
        verify(containerManager, times(1)).stopTaskAndCollectExitCodes(handleOf(task));
    }

    @Test
    void stopTaskWaitsForATickRetryOfAStopThatFailedWhileItWaited() throws Exception {
        EcsService service = newService();
        createService(service, "flaky", 1);
        tick(service);
        EcsTask task = onlyRunningTask(service, "flaky");
        CountDownLatch firstStopEntered = new CountDownLatch(1);
        CountDownLatch failFirstStop = new CountDownLatch(1);
        CountDownLatch retryEntered = new CountDownLatch(1);
        CountDownLatch releaseRetry = new CountDownLatch(1);
        AtomicInteger stops = new AtomicInteger();
        doAnswer(invocation -> {
            EcsTaskHandle handle = invocation.getArgument(0);
            if (stops.incrementAndGet() == 1) {
                firstStopEntered.countDown();
                failFirstStop.await(30, TimeUnit.SECONDS);
                throw new IllegalStateException("Cannot connect to the Docker daemon");
            }
            retryEntered.countDown();
            releaseRetry.await(30, TimeUnit.SECONDS);
            handle.getContainerIds().keySet().forEach(handle::recordContainerRemoved);
            return Map.of("app", 137);
        }).when(containerManager).stopTaskAndCollectExitCodes(handleOf(task));
        service.updateService(CLUSTER, "flaky", null, 0, null, REGION);

        tick(service);
        CompletableFuture<EcsTask> userStop;
        try {
            assertTrue(firstStopEntered.await(5, TimeUnit.SECONDS), "the scale-in stop never started");
            userStop = CompletableFuture.supplyAsync(() ->
                    service.stopTask(CLUSTER, task.getTaskArn(), "user stop", REGION));
            assertThrows(TimeoutException.class, () -> userStop.get(500, TimeUnit.MILLISECONDS),
                    "StopTask waits for the stop already running");
            // Holding the task's monitor while the first stop fails lets a tick retry it before
            // StopTask, woken by the failure, can take the monitor back.
            synchronized (task) {
                failFirstStop.countDown();
                for (int i = 0; i < 100 && !retryEntered.await(50, TimeUnit.MILLISECONDS); i++) {
                    service.reconcile();
                }
                assertEquals(0, retryEntered.getCount(), "no tick retried the failed stop");
            }
            assertThrows(TimeoutException.class, () -> userStop.get(500, TimeUnit.MILLISECONDS),
                    "StopTask answers once the retry has landed, not while it runs");
        } finally {
            failFirstStop.countDown();
            releaseRetry.countDown();
        }

        EcsTask stopped = userStop.get(TICK_BUDGET.toMillis(), TimeUnit.MILLISECONDS);
        assertEquals("STOPPED", stopped.getLastStatus());
        assertEquals(137, stopped.getContainers().getFirst().getExitCode());
        assertEquals(2, stops.get());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** One full reconcile tick, on a thread of its own, so a tick stuck behind a stop fails rather than hangs. */
    private static void tick(EcsService service) throws Exception {
        FutureTask<Void> pass = startTick(service);
        try {
            pass.get(TICK_BUDGET.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            fail("a reconcile tick waited for another task's graceful stop");
        }
    }

    private static FutureTask<Void> startTick(EcsService service) {
        FutureTask<Void> pass = new FutureTask<>(service::reconcile, null);
        Thread thread = new Thread(pass, "test-ecs-reconcile-tick");
        thread.setDaemon(true);
        thread.start();
        return pass;
    }

    private EcsService newService() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(false);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        EcsService service = new EcsService(new RegionResolver(REGION, "000000000000"), containerManager,
                config, mock(EcsLoadBalancerRegistrar.class), new InMemoryStorageFactory("000000000000"), eventPublisher);
        service.initializeStorage();
        service.createCluster(CLUSTER, REGION);
        services.add(service);
        return service;
    }

    private static void createService(EcsService service, String name, int desiredCount) {
        ContainerDefinition container = new ContainerDefinition();
        container.setName("app");
        container.setImage(name + ":1");
        service.registerTaskDefinition(name, List.of(container), null, null, null,
                null, null, List.of(), REGION);
        service.createService(CLUSTER, name, name, desiredCount, LaunchType.FARGATE, List.of(), null, REGION);
    }

    private static EcsTaskHandle handleOf(EcsTask task) {
        return argThat(handle -> handle != null && task.getTaskArn().equals(handle.getTaskArn()));
    }

    private static EcsTask onlyRunningTask(EcsService service, String serviceName) {
        List<EcsTask> running = runningTasks(service, serviceName);
        assertEquals(1, running.size(), "running tasks of " + serviceName);
        return running.getFirst();
    }

    private static List<EcsTask> runningTasks(EcsService service, String serviceName) {
        return tasks(service, serviceName, "RUNNING").stream()
                .filter(task -> "RUNNING".equals(task.getLastStatus()))
                .toList();
    }

    private static List<EcsTask> allTasks(EcsService service, String serviceName) {
        List<EcsTask> all = new ArrayList<>(tasks(service, serviceName, "RUNNING"));
        all.addAll(tasks(service, serviceName, "STOPPED"));
        return all;
    }

    private static List<EcsTask> tasks(EcsService service, String serviceName, String desiredStatus) {
        return service.describeTasks(CLUSTER,
                service.listTasks(CLUSTER, null, desiredStatus, serviceName, REGION), REGION);
    }

    private static EcsTask describe(EcsService service, EcsTask task) {
        return service.describeTasks(CLUSTER, List.of(task.getTaskArn()), REGION).getFirst();
    }

    private static String lastStatus(EcsService service, EcsTask task) {
        return describe(service, task).getLastStatus();
    }
}
