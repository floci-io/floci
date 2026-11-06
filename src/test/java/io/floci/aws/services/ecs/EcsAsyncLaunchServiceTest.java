package io.floci.aws.services.ecs;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.ecs.container.EcsContainerManager;
import io.floci.aws.services.ecs.container.EcsTaskHandle;
import io.floci.aws.services.ecs.model.AwsVpcConfiguration;
import io.floci.aws.services.ecs.model.Container;
import io.floci.aws.services.ecs.model.ContainerDefinition;
import io.floci.aws.services.ecs.model.ContainerInstance;
import io.floci.aws.services.ecs.model.EcsTask;
import io.floci.aws.services.ecs.model.LaunchType;
import io.floci.aws.services.ecs.model.NetworkConfiguration;
import io.floci.aws.services.ecs.model.NetworkMode;
import io.floci.aws.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RunTask and StartTask used to answer only once the task's images were pulled and its
 * containers started, so an unreachable registry held the call past the AWS CLI's 60s read
 * timeout (#7670). AWS answers in a second or two with the task PROVISIONING or PENDING and
 * moves the launch to the background. These run the service in Docker mode against a
 * container manager whose start of a task blocks until the test lets it finish, the way a
 * pull from an unreachable registry holds.
 */
class EcsAsyncLaunchServiceTest {

    private static final String REGION = "us-east-1";
    private static final String CLUSTER = "launch-cluster";
    /** Far longer than a background launch takes here, and far shorter than a held pull. */
    private static final Duration LAUNCH_BUDGET = Duration.ofSeconds(5);

    private final EcsContainerManager containerManager = mock(EcsContainerManager.class);
    private final EcsEventPublisher eventPublisher = mock(EcsEventPublisher.class);
    private final List<EcsService> services = new ArrayList<>();
    /** While set, the container manager holds every task's start until {@link #releaseLaunch}. */
    private final AtomicBoolean blockLaunch = new AtomicBoolean();
    /** While set, the container manager fails every task's start, as a pull that cannot finish. */
    private final AtomicBoolean failLaunch = new AtomicBoolean();
    private final CountDownLatch launchEntered = new CountDownLatch(1);
    private final CountDownLatch releaseLaunch = new CountDownLatch(1);

    @BeforeEach
    void stubContainers() {
        when(containerManager.getExitCodeIfStopped(anyString())).thenReturn(null);
        when(containerManager.startTask(any(), any(), any(), anyString())).thenAnswer(invocation -> {
            EcsTask task = invocation.getArgument(0);
            if (failLaunch.get()) {
                throw new IllegalStateException("pull failed: registry unreachable");
            }
            if (blockLaunch.get()) {
                launchEntered.countDown();
                releaseLaunch.await(30, TimeUnit.SECONDS);
            }
            Container container = new Container();
            container.setName("app");
            container.setRuntimeId("docker-" + Integer.toHexString(task.getTaskArn().hashCode()));
            task.setContainers(List.of(container));
            return new EcsTaskHandle(task.getTaskArn(), Map.of("app", container.getRuntimeId()), Map.of());
        });
        when(containerManager.stopTaskAndCollectExitCodes(any())).thenAnswer(invocation -> {
            EcsTaskHandle handle = invocation.getArgument(0);
            handle.getContainerIds().keySet().forEach(handle::recordContainerRemoved);
            return Map.of("app", 0);
        });
    }

    @AfterEach
    void releaseLaunchAndTearDown() {
        releaseLaunch.countDown();
        services.forEach(EcsService::stopManagedContainers);
    }

    @Test
    void runTaskAnswersWhileThePullIsStillRunning() throws Exception {
        EcsService service = newService(false);
        registerTaskDefinition(service, "web", null);
        blockLaunch.set(true);

        CompletableFuture<List<EcsTask>> launched =
                CompletableFuture.supplyAsync(() -> service.runTask(
                        CLUSTER, "web", 1, LaunchType.EC2, null, null, null, null, REGION));
        List<EcsTask> tasks = launched.get(LAUNCH_BUDGET.toMillis(), TimeUnit.MILLISECONDS);
        assertEquals(1, tasks.size());
        EcsTask answered = tasks.getFirst();
        assertEquals("PENDING", answered.getLastStatus());
        assertEquals("RUNNING", answered.getDesiredStatus());
        assertNull(answered.getStartedAt());
        assertEquals(1, answered.getContainers().size());
        assertEquals("PENDING", answered.getContainers().getFirst().getLastStatus());

        // The launch is genuinely still held open, and the live task with it.
        assertTrue(launchEntered.await(LAUNCH_BUDGET.toMillis(), TimeUnit.MILLISECONDS),
                "the launch never started");
        assertEquals("PENDING", lastStatus(service, answered));

        releaseLaunch.countDown();
        await().atMost(LAUNCH_BUDGET).until(() -> "RUNNING".equals(lastStatus(service, answered)));
        // The answer was a snapshot of the task at launch time, not the live task.
        assertEquals("PENDING", answered.getLastStatus());
    }

    @Test
    void runTaskAnswersProvisioningForAnAwsvpcTask() throws Exception {
        EcsService service = newService(false);
        registerTaskDefinition(service, "web", NetworkMode.awsvpc);
        AwsVpcConfiguration awsvpc = new AwsVpcConfiguration();
        awsvpc.setSubnets(List.of("subnet-12345678"));
        NetworkConfiguration networkConfiguration = new NetworkConfiguration();
        networkConfiguration.setAwsvpcConfiguration(awsvpc);
        blockLaunch.set(true);

        CompletableFuture<List<EcsTask>> launched =
                CompletableFuture.supplyAsync(() -> service.runTask(
                        CLUSTER, "web", 1, LaunchType.FARGATE, null, null, null, networkConfiguration, REGION));
        List<EcsTask> tasks = launched.get(LAUNCH_BUDGET.toMillis(), TimeUnit.MILLISECONDS);
        assertEquals(1, tasks.size());
        assertEquals("PROVISIONING", tasks.getFirst().getLastStatus());

        releaseLaunch.countDown();
        await().atMost(LAUNCH_BUDGET).until(() -> "RUNNING".equals(lastStatus(service, tasks.getFirst())));
    }

    @Test
    void stopTaskDuringLaunchLeavesTheTaskPendingUntilTheLaunchLands() throws Exception {
        EcsService service = newService(false);
        registerTaskDefinition(service, "web", null);
        blockLaunch.set(true);

        EcsTask answered = service.runTask(
                CLUSTER, "web", 1, LaunchType.EC2, null, null, null, null, REGION).getFirst();
        assertEquals("PENDING", answered.getLastStatus());
        assertTrue(launchEntered.await(LAUNCH_BUDGET.toMillis(), TimeUnit.MILLISECONDS),
                "the launch never started");

        EcsTask stopped = service.stopTask(CLUSTER, answered.getTaskArn(), "user stop", REGION);
        assertEquals("PENDING", stopped.getLastStatus());
        assertEquals("STOPPED", stopped.getDesiredStatus());
        assertEquals("UserInitiated", stopped.getStopCode());
        assertEquals("user stop", stopped.getStoppedReason());
        assertNotNull(stopped.getStoppingAt());

        releaseLaunch.countDown();
        await().atMost(LAUNCH_BUDGET).until(() -> "STOPPED".equals(lastStatus(service, answered)));
        EcsTask landed = describe(service, answered);
        assertEquals("UserInitiated", landed.getStopCode());
        assertEquals("user stop", landed.getStoppedReason());
        // The task never ran: the pull finishing after the stop must not mark it RUNNING.
        assertNull(landed.getStartedAt());
    }

    @Test
    void aFailedPullLandsTheTaskStopped() {
        EcsService service = newService(false);
        registerTaskDefinition(service, "web", null);
        failLaunch.set(true);

        EcsTask answered = service.runTask(
                CLUSTER, "web", 1, LaunchType.EC2, null, null, null, null, REGION).getFirst();
        assertEquals("PENDING", answered.getLastStatus());

        await().atMost(LAUNCH_BUDGET).until(() -> "STOPPED".equals(lastStatus(service, answered)));
        EcsTask landed = describe(service, answered);
        assertEquals("TaskFailedToStart", landed.getStopCode());
        assertTrue(landed.getStoppedReason().startsWith("Failed to start: "),
                "stopped reason: " + landed.getStoppedReason());
        assertEquals("STOPPED", landed.getContainers().getFirst().getLastStatus());
    }

    @Test
    void mockModeRunTaskAnswersPendingThenRuns() {
        EcsService service = newService(true);
        registerTaskDefinition(service, "web", null);

        EcsTask answered = service.runTask(
                CLUSTER, "web", 1, LaunchType.EC2, null, null, null, null, REGION).getFirst();
        assertEquals("PENDING", answered.getLastStatus());

        await().atMost(LAUNCH_BUDGET).until(() -> "RUNNING".equals(lastStatus(service, answered)));
        assertEquals("PENDING", answered.getLastStatus());
    }

    @Test
    void startTaskAnswersWhileThePullIsStillRunning() throws Exception {
        EcsService service = newService(false);
        registerTaskDefinition(service, "web", null);
        ContainerInstance instance = service.registerContainerInstance(CLUSTER, null, List.of(), REGION);
        blockLaunch.set(true);

        CompletableFuture<List<EcsTask>> launched = CompletableFuture.supplyAsync(() -> service.startTask(
                CLUSTER, List.of(instance.getContainerInstanceArn()), "web", null, null, REGION));
        List<EcsTask> tasks = launched.get(LAUNCH_BUDGET.toMillis(), TimeUnit.MILLISECONDS);
        assertEquals(1, tasks.size());
        assertEquals("PENDING", tasks.getFirst().getLastStatus());

        assertTrue(launchEntered.await(LAUNCH_BUDGET.toMillis(), TimeUnit.MILLISECONDS),
                "the launch never started");
        releaseLaunch.countDown();
        await().atMost(LAUNCH_BUDGET).until(() -> "RUNNING".equals(lastStatus(service, tasks.getFirst())));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private EcsService newService(boolean mockMode) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(mockMode);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        EcsService service = new EcsService(new RegionResolver(REGION, "000000000000"), containerManager,
                config, mock(EcsLoadBalancerRegistrar.class), new InMemoryStorageFactory("000000000000"),
                eventPublisher);
        service.initializeStorage();
        service.createCluster(CLUSTER, REGION);
        services.add(service);
        return service;
    }

    private static void registerTaskDefinition(EcsService service, String family, NetworkMode networkMode) {
        ContainerDefinition container = new ContainerDefinition();
        container.setName("app");
        container.setImage(family + ":1");
        service.registerTaskDefinition(family, List.of(container), networkMode,
                null, null, null, null, List.of(), REGION);
    }

    private static EcsTask describe(EcsService service, EcsTask task) {
        return service.describeTasks(CLUSTER, List.of(task.getTaskArn()), REGION).getFirst();
    }

    private static String lastStatus(EcsService service, EcsTask task) {
        return describe(service, task).getLastStatus();
    }
}
