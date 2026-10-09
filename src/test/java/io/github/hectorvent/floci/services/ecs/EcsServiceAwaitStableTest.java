package io.github.hectorvent.floci.services.ecs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.container.EcsTaskHandle;
import io.github.hectorvent.floci.services.ecs.model.AwsVpcConfiguration;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsServiceModel;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.services.ecs.model.NetworkConfiguration;
import io.github.hectorvent.floci.services.ecs.model.NetworkMode;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.github.hectorvent.floci.services.ecs.model.TaskStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The wait CloudFormation runs before it reports an {@code AWS::ECS::Service} complete. These
 * services are built without CDI, so no scheduled tick ever runs: every task the tests see was
 * started by a reconcile pass the wait itself asked for.
 */
class EcsServiceAwaitStableTest {

    private static final String REGION = "us-east-1";
    private static final Duration GENEROUS = Duration.ofSeconds(30);

    private final List<EcsService> created = new ArrayList<>();

    @AfterEach
    void stopReconcilers() {
        created.forEach(EcsService::stopManagedContainers);
    }

    @Test
    void awaitServiceStable_newService_returnsOnceDescribeServicesReportsTheDesiredTasks() {
        EcsService service = mockModeService();
        service.createCluster("stable-cluster", REGION);
        registerTaskDef(service, "stable-fam", "app:1");
        EcsServiceModel svc = service.createService("stable-cluster", "stable-svc", "stable-fam", 2,
                LaunchType.FARGATE, List.of(), null, REGION);

        service.awaitServiceStable(svc, GENEROUS);

        EcsServiceModel described = describe(service, "stable-cluster", "stable-svc");
        assertEquals(2, described.getRunningCount(), "DescribeServices reports the tasks the wait waited for");
        assertEquals(2, service.deploymentsFor(described).getFirst().getRunningCount());
        assertEquals(2, runningTasks(service).size());
    }

    @Test
    void awaitServiceStable_desiredCountZero_returnsAtOnceWithoutStartingATask() {
        EcsService service = mockModeService();
        service.createCluster("zero-cluster", REGION);
        registerTaskDef(service, "zero-fam", "app:1");
        EcsServiceModel svc = service.createService("zero-cluster", "zero-svc", "zero-fam", 0,
                LaunchType.FARGATE, List.of(), null, REGION);

        // A zero timeout leaves no room for a reconcile pass: only a service that is already
        // stable when the wait starts gets through.
        service.awaitServiceStable(svc, Duration.ZERO);

        assertTrue(runningTasks(service).isEmpty());
    }

    @Test
    void awaitServiceStable_rollingUpdate_returnsOnlyOnceThePreviousDeploymentsTasksHaveStopped() {
        EcsService service = mockModeService();
        service.createCluster("roll-cluster", REGION);
        registerTaskDef(service, "roll-fam", "app:1");
        EcsServiceModel svc = service.createService("roll-cluster", "roll-svc", "roll-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION);
        service.awaitServiceStable(svc, GENEROUS);
        TaskDefinition rev2 = registerTaskDef(service, "roll-fam", "app:2");

        EcsServiceModel updated = service.updateService("roll-cluster", "roll-svc",
                rev2.getTaskDefinitionArn(), 2, null, REGION);
        service.awaitServiceStable(updated, GENEROUS);

        List<EcsTask> running = runningTasks(service);
        assertEquals(2, running.size());
        assertThat(running.stream().map(EcsTask::getTaskDefinitionArn).toList(),
                everyItem(equalTo(rev2.getTaskDefinitionArn())));
        assertEquals(2, describe(service, "roll-cluster", "roll-svc").getRunningCount());
    }

    @Test
    void awaitServiceStable_reconcilesOnTheReconcilerThreadNotTheCallers() {
        List<String> launchThreads = Collections.synchronizedList(new ArrayList<>());
        EcsService service = dockerModeService(dockerContainerManager(
                task -> launchThreads.add(Thread.currentThread().getName())));
        service.createCluster("thread-cluster", REGION);
        registerTaskDef(service, "thread-fam", "app:1");
        EcsServiceModel svc = service.createService("thread-cluster", "thread-svc", "thread-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION);

        service.awaitServiceStable(svc, GENEROUS);

        assertEquals(List.of("ecs-reconciler"), launchThreads,
                "the pass runs on the single reconciler thread, so it never overlaps a scheduled tick");
    }

    @Test
    void awaitServiceStable_serviceThatNeverStabilizes_throwsNotStabilizedOnTimeout() {
        EcsContainerManager containerManager = dockerContainerManager(task -> { });
        // Every container has exited by the time it is inspected: each pass finds the task gone and
        // starts another, so the service never settles and nothing fails to launch.
        when(containerManager.getExitCodeIfStopped(anyString())).thenReturn(1);
        EcsService service = dockerModeService(containerManager);
        service.createCluster("stuck-cluster", REGION);
        registerTaskDef(service, "stuck-fam", "app:1");
        EcsServiceModel svc = service.createService("stuck-cluster", "stuck-svc", "stuck-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION);

        AwsException error = assertThrows(AwsException.class,
                () -> service.awaitServiceStable(svc, Duration.ofMillis(300)));

        assertEquals("NotStabilized", error.getErrorCode());
        assertEquals("Service " + svc.getServiceArn() + " did not stabilize.", error.getMessage());
    }

    @Test
    void awaitServiceStable_taskCannotBeLaunched_failsWithTheLaunchFailureLongBeforeTheTimeout() {
        EcsContainerManager containerManager = mock(EcsContainerManager.class);
        // What the container manager raises for an awsvpc task whose subnet EC2 does not know.
        doAnswer(invocation -> {
            EcsTask task = invocation.getArgument(0);
            String subnet = task.getNetworkConfiguration().getAwsvpcConfiguration().getSubnets().getFirst();
            throw new AwsException("InvalidParameterException", "The subnet ID '" + subnet + "' does not exist", 400);
        }).when(containerManager).attachTaskNetwork(any(), any(), anyString());
        EcsService service = mockModeService(containerManager);
        service.createCluster("net-cluster", REGION);
        ContainerDefinition container = new ContainerDefinition();
        container.setName("app");
        container.setImage("app:1");
        service.registerTaskDefinition("net-fam", List.of(container), NetworkMode.awsvpc, null, null,
                null, null, List.of(), REGION);
        AwsVpcConfiguration awsvpc = new AwsVpcConfiguration();
        awsvpc.setSubnets(List.of("subnet-does-not-exist"));
        NetworkConfiguration network = new NetworkConfiguration();
        network.setAwsvpcConfiguration(awsvpc);
        EcsServiceModel svc = service.createService("net-cluster", "net-svc", "net-fam", 1,
                LaunchType.FARGATE, List.of(), network, REGION);

        long started = System.nanoTime();
        AwsException error = assertThrows(AwsException.class,
                () -> service.awaitServiceStable(svc, Duration.ofMinutes(1)));
        Duration waited = Duration.ofNanos(System.nanoTime() - started);

        assertEquals("NotStabilized", error.getErrorCode());
        assertEquals("Service " + svc.getServiceArn() + " did not stabilize. Task launch failed: "
                + "The subnet ID 'subnet-does-not-exist' does not exist", error.getMessage());
        assertTrue(waited.compareTo(Duration.ofSeconds(10)) < 0, "the wait took " + waited);
    }

    @Test
    void awaitServiceStable_taskFailsToStart_failsWithTheStoppedReasonBeforeTheTimeout() {
        EcsService service = dockerModeService(dockerContainerManager(task -> {
            throw new IllegalStateException("CannotPullContainerError: pull access denied for no-such-image");
        }));
        service.createCluster("fail-cluster", REGION);
        registerTaskDef(service, "fail-fam", "no-such-image");
        EcsServiceModel svc = service.createService("fail-cluster", "fail-svc", "fail-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION);

        AwsException error = assertThrows(AwsException.class, () -> service.awaitServiceStable(svc, GENEROUS));

        assertEquals("NotStabilized", error.getErrorCode());
        assertThat(error.getMessage(), containsString("Service " + svc.getServiceArn() + " did not stabilize."));
        assertThat(error.getMessage(), containsString("CannotPullContainerError: pull access denied for no-such-image"));
    }

    @Test
    void awaitServiceStable_launchFailureOfThePreviousDeployment_doesNotEndTheWait() throws InterruptedException {
        CountDownLatch staleLaunching = new CountDownLatch(1);
        CountDownLatch releaseStale = new CountDownLatch(1);
        EcsContainerManager containerManager = mock(EcsContainerManager.class);
        EcsService service = mockModeService(containerManager);
        Thread stalePass = new Thread(service::reconcile);
        stalePass.setDaemon(true);
        // The pass that began before the update is still launching for the previous deployment.
        doAnswer(invocation -> {
            if (Thread.currentThread() == stalePass) {
                staleLaunching.countDown();
                releaseStale.await();
                throw new AwsException("InvalidParameterException", "stale launch failure", 400);
            }
            return null;
        }).when(containerManager).attachTaskNetwork(any(), any(), anyString());
        // Its launch fails only once the wait has cleared the service's launch failure and started
        // the new deployment's task: when the wait's second pass stops the previous deployment's.
        doAnswer(invocation -> {
            releaseStale.countDown();
            stalePass.join();
            return null;
        }).when(containerManager).releaseTaskNetwork(any(), anyString());
        service.createCluster("race-cluster", REGION);
        registerTaskDef(service, "race-fam", "app:1");
        EcsServiceModel svc = service.createService("race-cluster", "race-svc", "race-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION);
        service.awaitServiceStable(svc, GENEROUS);
        service.updateService("race-cluster", "race-svc", null, 2, null, REGION);
        stalePass.start();
        assertTrue(staleLaunching.await(10, TimeUnit.SECONDS));
        TaskDefinition rev2 = registerTaskDef(service, "race-fam", "app:2");
        EcsServiceModel updated = service.updateService("race-cluster", "race-svc",
                rev2.getTaskDefinitionArn(), 1, null, REGION);

        service.awaitServiceStable(updated, GENEROUS);

        assertEquals(List.of(rev2.getTaskDefinitionArn()),
                runningTasks(service).stream().map(EcsTask::getTaskDefinitionArn).toList());
    }

    @Test
    void awaitServiceStable_launchOfThePreviousDeploymentOvertakenByAnUpdate_isReplaced()
            throws InterruptedException {
        CountDownLatch staleLaunching = new CountDownLatch(1);
        CountDownLatch releaseStale = new CountDownLatch(1);
        EcsContainerManager containerManager = mock(EcsContainerManager.class);
        EcsService service = mockModeService(containerManager);
        Thread stalePass = new Thread(service::reconcile);
        stalePass.setDaemon(true);
        // The pass launching the first revision's task is still attaching its network when the update lands.
        doAnswer(invocation -> {
            if (Thread.currentThread() == stalePass) {
                staleLaunching.countDown();
                releaseStale.await();
            }
            return null;
        }).when(containerManager).attachTaskNetwork(any(), any(), anyString());
        service.createCluster("overtaken-cluster", REGION);
        TaskDefinition rev1 = registerTaskDef(service, "overtaken-fam", "app:1");
        service.createService("overtaken-cluster", "overtaken-svc", rev1.getTaskDefinitionArn(), 1,
                LaunchType.FARGATE, List.of(), null, REGION);
        stalePass.start();
        assertTrue(staleLaunching.await(10, TimeUnit.SECONDS));
        TaskDefinition rev2 = registerTaskDef(service, "overtaken-fam", "app:2");
        EcsServiceModel updated = service.updateService("overtaken-cluster", "overtaken-svc",
                rev2.getTaskDefinitionArn(), 1, null, REGION);
        releaseStale.countDown();
        stalePass.join(TimeUnit.SECONDS.toMillis(10));

        service.awaitServiceStable(updated, GENEROUS);

        assertEquals(List.of(rev2.getTaskDefinitionArn()),
                runningTasks(service).stream().map(EcsTask::getTaskDefinitionArn).toList());
    }

    @Test
    void deleteService_forgetsTheServicesLaunchFailure() {
        EcsContainerManager containerManager = mock(EcsContainerManager.class);
        doThrow(new AwsException("InvalidParameterException", "no such subnet", 400))
                .when(containerManager).attachTaskNetwork(any(), any(), anyString());
        EcsService service = mockModeService(containerManager);
        service.createCluster("gone-cluster", REGION);
        registerTaskDef(service, "gone-fam", "app:1");
        EcsServiceModel svc = service.createService("gone-cluster", "gone-svc", "gone-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION);
        service.reconcile();
        assertTrue(service.hasLaunchFailure(svc.getServiceArn()));

        service.deleteService("gone-cluster", "gone-svc", true, REGION);

        assertFalse(service.hasLaunchFailure(svc.getServiceArn()));
    }

    private EcsService mockModeService() {
        return mockModeService(mock(EcsContainerManager.class));
    }

    private EcsService mockModeService(EcsContainerManager containerManager) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(true);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        return track(new EcsService(new RegionResolver(REGION, "000000000000"), containerManager,
                config, mock(EcsLoadBalancerRegistrar.class), new InMemoryStorageFactory(), null));
    }

    /** A container manager that runs {@code onStart} in {@code startTask}; its containers keep running. */
    private static EcsContainerManager dockerContainerManager(Consumer<EcsTask> onStart) {
        EcsContainerManager containerManager = mock(EcsContainerManager.class);
        // Mockito answers 0 for an Integer, which reads as "every container exited".
        when(containerManager.getExitCodeIfStopped(anyString())).thenReturn(null);
        when(containerManager.startTask(any(), any(), any(), anyString())).thenAnswer(invocation -> {
            EcsTask task = invocation.getArgument(0);
            onStart.accept(task);
            return new EcsTaskHandle(task.getTaskArn(), Map.of("app", "docker-" + task.getTaskArn().hashCode()),
                    Map.of());
        });
        return containerManager;
    }

    private EcsService dockerModeService(EcsContainerManager containerManager) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(false);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        return track(new EcsService(new RegionResolver(REGION, "000000000000"), containerManager,
                config, mock(EcsLoadBalancerRegistrar.class), new InMemoryStorageFactory(), null));
    }

    private EcsService track(EcsService service) {
        service.initializeStorage();
        created.add(service);
        return service;
    }

    private static TaskDefinition registerTaskDef(EcsService service, String family, String image) {
        ContainerDefinition container = new ContainerDefinition();
        container.setName("app");
        container.setImage(image);
        container.setEssential(true);
        return service.registerTaskDefinition(family, List.of(container), null, null, null,
                null, null, List.of(), REGION);
    }

    private static EcsServiceModel describe(EcsService service, String cluster, String name) {
        return service.describeServices(cluster, List.of(name), REGION).getFirst();
    }

    private static List<EcsTask> runningTasks(EcsService service) {
        return service.describeTasks(null, service.listTasks(null, null, null, null, REGION), REGION).stream()
                .filter(t -> TaskStatus.RUNNING.name().equals(t.getLastStatus()))
                .toList();
    }

    private static final class InMemoryStorageFactory extends StorageFactory {
        private final Map<String, StorageBackend<String, ?>> stores = new HashMap<>();

        private InMemoryStorageFactory() {
            super(null, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                        TypeReference<Map<String, V>> typeReference) {
            return (AccountAwareStorageBackend<V>) stores.computeIfAbsent(fileName,
                    ignored -> AccountAwareStorageBackend.inMemory("000000000000"));
        }
    }
}
