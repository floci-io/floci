package io.floci.aws.services.ecs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.ecs.container.EcsContainerManager;
import io.floci.aws.services.ecs.container.EcsTaskHandle;
import io.floci.aws.services.ecs.model.AwsVpcConfiguration;
import io.floci.aws.services.ecs.model.Container;
import io.floci.aws.services.ecs.model.ContainerDefinition;
import io.floci.aws.services.ecs.model.EcsCluster;
import io.floci.aws.services.ecs.model.EcsTask;
import io.floci.aws.services.ecs.model.LaunchType;
import io.floci.aws.services.ecs.model.ManagedAgent;
import io.floci.aws.services.ecs.model.NetworkConfiguration;
import io.floci.aws.services.ecs.model.NetworkMode;
import io.floci.aws.services.ecs.model.RunTaskRequest;
import io.floci.aws.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.floci.aws.testing.EcsTaskWaits.awaitLastStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RunTask answers before the task's images are pulled, as AWS does: the task comes back
 * PROVISIONING (awsvpc) or PENDING and moves on in the background.
 */
class EcsRunTaskAsyncLaunchServiceTest {

    private static final String REGION = "us-east-1";
    private static final Duration LAUNCH_BUDGET = Duration.ofSeconds(5);

    private final EcsContainerManager containerManager = mock(EcsContainerManager.class);
    private final CountDownLatch startEntered = new CountDownLatch(1);
    private final CountDownLatch releaseStart = new CountDownLatch(1);
    private final List<EcsService> services = new ArrayList<>();

    @BeforeEach
    void stubContainers() {
        // Mockito answers 0 for an Integer, which reads as "every container exited".
        when(containerManager.getExitCodeIfStopped(anyString())).thenReturn(null);
        // The image pull holds until the test releases it.
        when(containerManager.startTask(any(), any(), any(), anyString())).thenAnswer(invocation -> {
            EcsTask task = invocation.getArgument(0);
            startEntered.countDown();
            if (!releaseStart.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the image pull was never released");
            }
            return started(task);
        });
        when(containerManager.stopTaskAndCollectExitCodes(any())).thenAnswer(invocation -> {
            EcsTaskHandle handle = invocation.getArgument(0);
            handle.getContainerIds().keySet().forEach(handle::recordContainerRemoved);
            return Map.of("app", 0);
        });
    }

    @AfterEach
    void tearDown() {
        releaseStart.countDown();
        services.forEach(EcsService::stopManagedContainers);
    }

    @Test
    void runTaskAnswersPendingBeforeTheImageIsPulled() throws Exception {
        EcsService service = newService(false);
        registerTaskDefinition(service, "pending");

        EcsTask response = run(service, "pending");

        assertEquals("PENDING", response.getLastStatus());
        assertEquals("RUNNING", response.getDesiredStatus());
        assertNull(response.getStartedAt());
        assertEquals(List.of("PENDING"), containerStatuses(response));
        assertTrue(startEntered.await(5, TimeUnit.SECONDS));
        assertEquals("PENDING", describe(service, response).getLastStatus());

        releaseStart.countDown();
        awaitLastStatus(() -> describe(service, response), EcsTask::getLastStatus, LAUNCH_BUDGET, "RUNNING");
        assertEquals("PENDING", response.getLastStatus(), "the RunTask response is a snapshot");
        assertEquals(1, runningTasksCount(service));
    }

    @Test
    void runTaskAnswersProvisioningForAnAwsvpcTask() throws Exception {
        EcsService service = newService(false);
        ContainerDefinition container = new ContainerDefinition();
        container.setName("app");
        container.setImage("busybox:latest");
        service.registerTaskDefinition("vpc", List.of(container), NetworkMode.awsvpc, "256", "512",
                null, null, List.of("FARGATE"), REGION);
        AwsVpcConfiguration vpc = new AwsVpcConfiguration();
        vpc.setSubnets(List.of("subnet-12345678"));
        NetworkConfiguration network = new NetworkConfiguration();
        network.setAwsvpcConfiguration(vpc);
        CountDownLatch monitorHeld = new CountDownLatch(1);
        CountDownLatch releaseMonitor = new CountDownLatch(1);
        // Holds the new task's monitor so its launch cannot move it past PROVISIONING until released.
        doAnswer(invocation -> {
            EcsTask live = invocation.getArgument(0);
            new Thread(() -> {
                synchronized (live) {
                    monitorHeld.countDown();
                    try {
                        releaseMonitor.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }).start();
            assertTrue(monitorHeld.await(5, TimeUnit.SECONDS));
            return null;
        }).when(containerManager).attachTaskNetwork(any(), any(), anyString());

        EcsTask response = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> service.runTask(
                null, "vpc", 1, LaunchType.FARGATE, null, null, List.of(), network, REGION).getFirst());

        assertEquals("PROVISIONING", response.getLastStatus());
        assertEquals("PRECREATED", response.getAttachmentStatus());
        assertEquals("PROVISIONING", describe(service, response).getLastStatus());
        // ECS counts a task as pending while it is PROVISIONING as well as PENDING.
        assertPendingCounts(service, 1);
        releaseMonitor.countDown();
        assertTrue(startEntered.await(5, TimeUnit.SECONDS));
        EcsTask launching = describe(service, response);
        assertEquals("PENDING", launching.getLastStatus());
        assertEquals("ATTACHED", launching.getAttachmentStatus());
        assertTrue(launching.getVersion() > response.getVersion());
        assertPendingCounts(service, 1);

        releaseStart.countDown();
        awaitLastStatus(() -> describe(service, response), EcsTask::getLastStatus, LAUNCH_BUDGET, "RUNNING");
        assertPendingCounts(service, 0);
    }

    @Test
    void mockModeAnswersPendingAndRunsInTheBackground() {
        EcsService service = newService(true);
        registerTaskDefinition(service, "mock");

        EcsTask response = run(service, "mock");

        assertEquals("PENDING", response.getLastStatus());
        assertEquals(List.of("PENDING"), containerStatuses(response));
        EcsTask running = awaitLastStatus(() -> describe(service, response), EcsTask::getLastStatus,
                LAUNCH_BUDGET, "RUNNING");
        assertEquals(List.of("RUNNING"), containerStatuses(running));
        assertEquals(1, runningTasksCount(service));
        verify(containerManager, never()).startTask(any(), any(), any(), anyString());
    }

    @Test
    void theResponseCarriesTheContainersMemoryReservation() {
        EcsService service = newService(false);
        ContainerDefinition container = new ContainerDefinition();
        container.setName("app");
        container.setImage("busybox:latest");
        container.setMemoryReservation(128);
        service.registerTaskDefinition("reserved", List.of(container), null, null, null,
                null, null, List.of(), REGION);

        EcsTask response = run(service, "reserved");

        assertEquals("128", response.getContainers().getFirst().getMemoryReservation());
    }

    @Test
    void theExecAgentIsPendingUntilTheTaskRuns() {
        EcsService service = newService(true);
        registerTaskDefinition(service, "exec");
        RunTaskRequest request = new RunTaskRequest();
        request.setTaskDefinition("exec");
        request.setEnableExecuteCommand(true);

        EcsTask response = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> service.runTask(request, REGION).getFirst());

        ManagedAgent pending = response.getContainers().getFirst().getManagedAgents().getFirst();
        assertEquals("ExecuteCommandAgent", pending.name());
        assertEquals("PENDING", pending.lastStatus());
        assertNull(pending.lastStartedAt());
        EcsTask launched = awaitLastStatus(() -> describe(service, response), EcsTask::getLastStatus,
                LAUNCH_BUDGET, "RUNNING");
        ManagedAgent running = launched.getContainers().getFirst().getManagedAgents().getFirst();
        assertEquals("RUNNING", running.lastStatus());
        assertNotNull(running.lastStartedAt());
    }

    @Test
    void aFailedPullDuringAStopKeepsTheUsersReason() throws Exception {
        doAnswer(invocation -> {
            startEntered.countDown();
            assertTrue(releaseStart.await(5, TimeUnit.SECONDS));
            throw new IllegalStateException("CannotPullContainerError: pull access denied");
        }).when(containerManager).startTask(any(), any(), any(), anyString());
        EcsService service = newService(false);
        registerTaskDefinition(service, "stop-then-fail");
        EcsTask response = run(service, "stop-then-fail");
        assertTrue(startEntered.await(5, TimeUnit.SECONDS));

        service.stopTask(null, response.getTaskArn(), "scaling in", REGION);
        releaseStart.countDown();

        EcsTask stopped = awaitLastStatus(() -> describe(service, response), EcsTask::getLastStatus,
                LAUNCH_BUDGET, "STOPPED");
        assertEquals("UserInitiated", stopped.getStopCode());
        assertEquals("scaling in", stopped.getStoppedReason());
    }

    @Test
    void aFailedPullShowsOnDescribeTasksNotInTheResponse() {
        doThrow(new IllegalStateException("CannotPullContainerError: pull access denied"))
                .when(containerManager).startTask(any(), any(), any(), anyString());
        EcsService service = newService(false);
        registerTaskDefinition(service, "bad-image");

        EcsTask response = run(service, "bad-image");

        assertEquals("PENDING", response.getLastStatus());
        EcsTask stopped = awaitLastStatus(() -> describe(service, response), EcsTask::getLastStatus,
                LAUNCH_BUDGET, "STOPPED");
        assertEquals("STOPPED", stopped.getDesiredStatus());
        assertEquals("TaskFailedToStart", stopped.getStopCode());
        assertEquals("Failed to start: CannotPullContainerError: pull access denied", stopped.getStoppedReason());
        assertNull(stopped.getStartedAt());
        assertNotNull(stopped.getStoppingAt());
        assertNotNull(stopped.getStoppedAt());
        assertNotNull(stopped.getExecutionStoppedAt());
        assertEquals(List.of("STOPPED"), containerStatuses(stopped));
        assertEquals(0, runningTasksCount(service));
    }

    @Test
    void stopTaskDuringThePullAnswersWithTheTaskStillLaunching() throws Exception {
        EcsService service = newService(false);
        registerTaskDefinition(service, "stop-during-pull");
        EcsTask response = run(service, "stop-during-pull");
        assertTrue(startEntered.await(5, TimeUnit.SECONDS));

        EcsTask stopping = service.stopTask(null, response.getTaskArn(), null, REGION);

        assertEquals("PENDING", stopping.getLastStatus());
        assertEquals("STOPPED", stopping.getDesiredStatus());
        assertEquals("UserInitiated", stopping.getStopCode());
        assertNotNull(stopping.getStoppingAt());
        releaseStart.countDown();
        awaitLastStatus(() -> describe(service, response), EcsTask::getLastStatus, LAUNCH_BUDGET, "STOPPED");
        verify(containerManager).stopTaskAndCollectExitCodes(any());
        assertEquals(0, runningTasksCount(service));
    }

    @Test
    void stopTaskBeforeTheLaunchLandsTheTaskWithoutStartingIt() {
        EcsService service = newService(false);
        registerTaskDefinition(service, "stop-first");
        CountDownLatch monitorHeld = new CountDownLatch(1);
        CountDownLatch runTaskAnswered = new CountDownLatch(1);
        AtomicReference<String> statusStopTaskAnswered = new AtomicReference<>();
        // Holds the new task's monitor from before the launch is handed over until a StopTask has
        // been answered, so the launch can only begin after the stop.
        doAnswer(invocation -> {
            EcsTask live = invocation.getArgument(0);
            Thread stopper = new Thread(() -> {
                synchronized (live) {
                    monitorHeld.countDown();
                    try {
                        runTaskAnswered.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    statusStopTaskAnswered.set(service.stopTask(null, live.getTaskArn(), null, REGION).getLastStatus());
                }
            });
            stopper.start();
            assertTrue(monitorHeld.await(5, TimeUnit.SECONDS));
            return null;
        }).when(containerManager).attachTaskNetwork(any(), any(), anyString());

        EcsTask response = run(service, "stop-first");
        runTaskAnswered.countDown();

        EcsTask stopped = awaitLastStatus(() -> describe(service, response), EcsTask::getLastStatus,
                LAUNCH_BUDGET, "STOPPED");
        assertEquals("PENDING", statusStopTaskAnswered.get());
        assertEquals("UserInitiated", stopped.getStopCode());
        assertNull(stopped.getStartedAt());
        assertEquals(List.of("STOPPED"), containerStatuses(stopped));
        assertEquals(0, runningTasksCount(service));
        verify(containerManager, never()).startTask(any(), any(), any(), anyString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void teardownInterruptsAPullAndAfterResetLaunchesAgain(boolean prepareOnly) throws Exception {
        doAnswer(invocation -> {
            startEntered.countDown();
            try {
                // A pull that only the teardown's interrupt ends.
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("image pull interrupted", e);
            }
            throw new IllegalStateException("the pull ended without an interrupt");
        }).when(containerManager).startTask(any(), any(), any(), anyString());
        EcsService service = newService(false);
        registerTaskDefinition(service, "teardown");
        EcsTask response = run(service, "teardown");
        assertTrue(startEntered.await(5, TimeUnit.SECONDS));

        assertTimeoutPreemptively(LAUNCH_BUDGET,
                prepareOnly ? service::beginTeardown : service::stopManagedContainers);
        if (prepareOnly) {
            verify(containerManager, never()).stopTask(any());
        }

        EcsTask stopped = describe(service, response);
        assertEquals("STOPPED", stopped.getLastStatus());
        assertEquals("TaskFailedToStart", stopped.getStopCode());

        doAnswer(invocation -> started(invocation.getArgument(0)))
                .when(containerManager).startTask(any(), any(), any(), anyString());
        service.afterReset();
        EcsTask again = run(service, "teardown");
        awaitLastStatus(() -> describe(service, again), EcsTask::getLastStatus, LAUNCH_BUDGET, "RUNNING");
    }

    @Test
    void aLaunchDuringTeardownFailsToStartInsteadOfStayingPending() {
        EcsService service = newService(false);
        registerTaskDefinition(service, "after-teardown");
        service.stopManagedContainers();

        EcsTask response = run(service, "after-teardown");

        assertEquals("PENDING", response.getLastStatus());
        EcsTask stopped = describe(service, response);
        assertEquals("STOPPED", stopped.getLastStatus());
        assertEquals("TaskFailedToStart", stopped.getStopCode());
        verify(containerManager, never()).startTask(any(), any(), any(), anyString());
    }

    private EcsService newService(boolean mockMode) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(mockMode);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        EcsService service = new EcsService(new RegionResolver(REGION, "000000000000"), containerManager,
                config, mock(EcsLoadBalancerRegistrar.class), new InMemoryStorageFactory("000000000000"), null);
        service.initializeStorage();
        services.add(service);
        return service;
    }

    private static void registerTaskDefinition(EcsService service, String family) {
        ContainerDefinition container = new ContainerDefinition();
        container.setName("app");
        container.setImage("busybox:latest");
        service.registerTaskDefinition(family, List.of(container), null, null, null,
                null, null, List.of(), REGION);
    }

    /** RunTask, failing rather than hanging if it waits for the launch. */
    private static EcsTask run(EcsService service, String family) {
        return assertTimeoutPreemptively(Duration.ofSeconds(2), () -> service.runTask(null, family, 1,
                LaunchType.FARGATE, null, null, List.of(), null, REGION).getFirst(),
                "RunTask waited for the task's launch");
    }

    private static EcsTaskHandle started(EcsTask task) {
        Container container = new Container();
        container.setName("app");
        container.setRuntimeId("docker-app");
        task.setContainers(List.of(container));
        return new EcsTaskHandle(task.getTaskArn(), Map.of("app", "docker-app"), Map.of());
    }

    private static EcsTask describe(EcsService service, EcsTask task) {
        return service.describeTasks(null, List.of(task.getTaskArn()), REGION).getFirst();
    }

    private static int runningTasksCount(EcsService service) {
        return service.describeClusters(List.of("default"), REGION).getFirst().getRunningTasksCount();
    }

    /** DescribeClusters' pendingTasksCount and its Fargate statistic, as rendered, which must agree. */
    private static void assertPendingCounts(EcsService service, long expected) {
        EcsCluster cluster = service.describeClusters(List.of("default"), REGION).getFirst();
        ObjectNode rendered = new EcsResponseWriter(service, new ObjectMapper())
                .clusterNode(cluster, Set.of("STATISTICS"));
        assertEquals(expected, rendered.get("pendingTasksCount").asLong());
        String pendingFargate = null;
        for (JsonNode statistic : rendered.get("statistics")) {
            if ("pendingFargateTasksCount".equals(statistic.get("name").asText())) {
                pendingFargate = statistic.get("value").asText();
            }
        }
        assertEquals(String.valueOf(expected), pendingFargate);
    }

    private static List<String> containerStatuses(EcsTask task) {
        return task.getContainers().stream().map(Container::getLastStatus).toList();
    }
}
