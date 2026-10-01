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
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.ContainerInstance;
import io.github.hectorvent.floci.services.ecs.model.EcsServiceModel;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** DAEMON scheduling: one task per ACTIVE container instance, nothing else. */
class EcsServiceDaemonSchedulingTest {

    private static final String REGION = "us-east-1";

    @Test
    void daemonServiceRunsExactlyOneTaskPerActiveContainerInstance() {
        EcsService service = newMockModeService();
        service.createCluster("daemon-cluster", REGION);
        ContainerInstance a = service.registerContainerInstance("daemon-cluster", null, List.of(), REGION);
        ContainerInstance b = service.registerContainerInstance("daemon-cluster", null, List.of(), REGION);
        registerTaskDef(service);
        service.createService("daemon-cluster", "daemon-svc", "daemon-fam", 1, LaunchType.EC2,
                List.of(), null, null, "DAEMON", null, null, REGION);

        service.reconcileServices();
        service.reconcileServices();

        List<EcsTask> running = runningTasks(service);
        assertEquals(2, running.size());
        assertEquals(Set.of(a.getContainerInstanceArn(), b.getContainerInstanceArn()),
                running.stream().map(EcsTask::getContainerInstanceArn).collect(Collectors.toSet()));
        var svc = service.describeServices("daemon-cluster", List.of("daemon-svc"), REGION).getFirst();
        assertEquals(2, svc.getDesiredCount(), "desiredCount follows the instance count, as AWS reports it");
        assertEquals(2, svc.getRunningCount());

        // A drained instance loses its daemon task; a new one gains one.
        service.updateContainerInstancesState("daemon-cluster", List.of(a.getContainerInstanceArn()), "DRAINING", REGION);
        ContainerInstance c = service.registerContainerInstance("daemon-cluster", null, List.of(), REGION);
        service.reconcileServices();

        running = runningTasks(service);
        assertEquals(2, running.size());
        assertEquals(Set.of(b.getContainerInstanceArn(), c.getContainerInstanceArn()),
                running.stream().map(EcsTask::getContainerInstanceArn).collect(Collectors.toSet()));
    }

    @Test
    void failedDaemonLaunchDoesNotCountAsRunningAndIsRetried() {
        // Docker mode with a container manager that refuses to start anything: launchTasks
        // hands back a task already STOPPED. That must neither cover the instance nor count
        // towards runningCount, or ServicesStable would be satisfied by a dead daemon.
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(false);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        EcsContainerManager containerManager = mock(EcsContainerManager.class);
        when(containerManager.startTask(any(), any(), any(), anyString()))
                .thenThrow(new RuntimeException("no docker here"));
        EcsService service = new EcsService(new RegionResolver(REGION, "000000000000"), containerManager,
                config, mock(EcsLoadBalancerRegistrar.class), new InMemoryStorageFactory(), null);
        service.initializeStorage();
        service.createCluster("daemon-fail", REGION);
        service.registerContainerInstance("daemon-fail", null, List.of(), REGION);
        registerTaskDef(service);
        service.createService("daemon-fail", "daemon-svc", "daemon-fam", 1, LaunchType.EC2,
                List.of(), null, null, "DAEMON", null, null, REGION);

        service.reconcileServices();
        var svc = service.describeServices("daemon-fail", List.of("daemon-svc"), REGION).getFirst();
        assertEquals(1, svc.getDesiredCount());
        assertEquals(0, svc.getRunningCount(), "a task that failed to start is not running");
        assertEquals(0, runningTasks(service).size());

        service.reconcileServices();
        // Both attempts died on launch, so they are the STOPPED tasks: ListTasks only returns the
        // running ones unless a desired status is asked for, as on AWS.
        long attempts = service.describeTasks(null,
                service.listTasks(null, null, "STOPPED", null, REGION), REGION).size();
        assertEquals(2, attempts, "the uncovered instance is retried on the next tick");
    }

    @Test
    void strandedStoppingDaemonTaskStillHoldsItsInstance() {
        EcsService service = newMockModeService();
        service.createCluster("daemon-stopping", REGION);
        ContainerInstance a = service.registerContainerInstance("daemon-stopping", null, List.of(), REGION);
        registerTaskDef(service);
        service.createService("daemon-stopping", "daemon-svc", "daemon-fam", 1, LaunchType.EC2,
                List.of(), null, null, "DAEMON", null, null, REGION);
        service.reconcileServices();
        EcsTask task = runningTasks(service).getFirst();
        assertEquals(a.getContainerInstanceArn(), task.getContainerInstanceArn());

        // Teardown in flight (or stranded there): the slot is still taken, no duplicate.
        task.setLastStatus("STOPPING");
        service.reconcileServices();
        List<EcsTask> all = service.describeTasks(null, service.listTasks(null, null, null, null, REGION), REGION);
        assertEquals(1, all.size(), "no replacement next to a STOPPING daemon task");
        assertEquals(0, service.describeServices("daemon-stopping", List.of("daemon-svc"), REGION)
                .getFirst().getRunningCount());

        // Once it is STOPPED the instance is uncovered and gets its daemon back.
        task.setLastStatus("STOPPED");
        service.reconcileServices();
        List<EcsTask> running = runningTasks(service);
        assertEquals(1, running.size());
        assertEquals(a.getContainerInstanceArn(), running.getFirst().getContainerInstanceArn());
    }

    @Test
    void taskDefinitionChangeReplacesTheDaemonTaskOnEveryInstance() {
        EcsService service = newMockModeService();
        service.createCluster("daemon-roll", REGION);
        ContainerInstance a = service.registerContainerInstance("daemon-roll", null, List.of(), REGION);
        ContainerInstance b = service.registerContainerInstance("daemon-roll", null, List.of(), REGION);
        registerTaskDef(service);
        service.createService("daemon-roll", "daemon-svc", "daemon-fam", 1, LaunchType.EC2,
                List.of(), null, null, "DAEMON", null, null, REGION);
        service.reconcileServices();
        Set<String> oldTasks = taskArns(runningTasks(service));

        String revision2 = registerTaskDef(service).getTaskDefinitionArn();
        service.updateService("daemon-roll", "daemon-svc", "daemon-fam:2", null, null, REGION);
        service.reconcileServices();

        assertConvergedOn(service, "daemon-roll", revision2, Set.of(a, b));
        List<EcsTask> running = runningTasks(service);
        assertTrue(Collections.disjoint(oldTasks, taskArns(running)), "every old-revision task is replaced");
        for (EcsTask old : service.describeTasks(null, List.copyOf(oldTasks), REGION)) {
            assertEquals("STOPPED", old.getLastStatus());
            assertEquals("ServiceSchedulerInitiated", old.getStopCode());
        }

        // Converged: further ticks leave it alone.
        service.reconcileServices();
        assertEquals(taskArns(running), taskArns(runningTasks(service)));
    }

    @Test
    void forceNewDeploymentReplacesTheDaemonTaskOnEveryInstance() {
        EcsService service = newMockModeService();
        service.createCluster("daemon-force", REGION);
        ContainerInstance a = service.registerContainerInstance("daemon-force", null, List.of(), REGION);
        ContainerInstance b = service.registerContainerInstance("daemon-force", null, List.of(), REGION);
        String revision1 = registerTaskDef(service).getTaskDefinitionArn();
        service.createService("daemon-force", "daemon-svc", "daemon-fam", 1, LaunchType.EC2,
                List.of(), null, null, "DAEMON", null, null, REGION);
        service.reconcileServices();
        Set<String> oldTasks = taskArns(runningTasks(service));

        service.updateService("daemon-force", "daemon-svc", null, null, null, null, true, REGION);
        service.reconcileServices();

        assertConvergedOn(service, "daemon-force", revision1, Set.of(a, b));
        assertTrue(Collections.disjoint(oldTasks, taskArns(runningTasks(service))),
                "a forced deployment replaces tasks even on the same revision");
    }

    @Test
    void unchangedDaemonServiceDoesNotChurnItsTasks() {
        EcsService service = newMockModeService();
        service.createCluster("daemon-steady", REGION);
        service.registerContainerInstance("daemon-steady", null, List.of(), REGION);
        service.registerContainerInstance("daemon-steady", null, List.of(), REGION);
        registerTaskDef(service);
        service.createService("daemon-steady", "daemon-svc", "daemon-fam", 1, LaunchType.EC2,
                List.of(), null, null, "DAEMON", null, null, REGION);
        service.reconcileServices();
        Set<String> first = taskArns(runningTasks(service));

        service.reconcileServices();
        service.reconcileServices();
        service.reconcileServices();

        assertEquals(first, taskArns(allTasks(service)), "no task stopped or started once converged");
    }

    @Test
    void staleDaemonTaskStuckStoppingDelaysItsReplacement() {
        // AWS caps a DAEMON service's maximumPercent at 100: the old task has to be gone before its
        // replacement starts on that instance. A Docker teardown that fails leaves it STOPPING.
        EcsService service = newDockerModeServiceWhoseStopsFail();
        service.createCluster("daemon-slow", REGION);
        ContainerInstance a = service.registerContainerInstance("daemon-slow", null, List.of(), REGION);
        registerTaskDef(service);
        service.createService("daemon-slow", "daemon-svc", "daemon-fam", 1, LaunchType.EC2,
                List.of(), null, null, "DAEMON", null, null, REGION);
        service.reconcileServices();
        EcsTask old = runningTasks(service).getFirst();

        String revision2 = registerTaskDef(service).getTaskDefinitionArn();
        service.updateService("daemon-slow", "daemon-svc", "daemon-fam:2", null, null, REGION);
        service.reconcileServices();

        assertEquals("STOPPING", old.getLastStatus());
        assertEquals(List.of(old.getTaskArn()), List.copyOf(taskArns(allTasks(service))),
                "no replacement next to a daemon task still stopping");

        old.setLastStatus("STOPPED");
        service.reconcileServices();
        assertConvergedOn(service, "daemon-slow", revision2, Set.of(a));
    }

    @Test
    void currentDaemonTaskWinsItsInstanceOverAStaleOneThatWillNotStop() {
        // Map order decides which of two RUNNING tasks is seen first; build until the stale one is.
        for (int attempt = 0; attempt < 50; attempt++) {
            EcsService service = newDockerModeServiceWhoseStopsFail();
            service.createCluster("daemon-pair", REGION);
            ContainerInstance a = service.registerContainerInstance("daemon-pair", null, List.of(), REGION);
            registerTaskDef(service);
            service.createService("daemon-pair", "daemon-svc", "daemon-fam", 1, LaunchType.EC2,
                    List.of(), null, null, "DAEMON", null, null, REGION);
            service.reconcileServices();
            EcsTask stale = runningTasks(service).getFirst();

            registerTaskDef(service);
            service.updateService("daemon-pair", "daemon-svc", "daemon-fam:2", null, null, REGION);
            ContainerInstance b = service.registerContainerInstance("daemon-pair", null, List.of(), REGION);
            service.reconcileServices();
            EcsTask current = runningTasks(service).getFirst();
            assertEquals(b.getContainerInstanceArn(), current.getContainerInstanceArn());

            // Both RUNNING on instance a.
            stale.setLastStatus("RUNNING");
            stale.setDesiredStatus("RUNNING");
            current.setContainerInstanceArn(a.getContainerInstanceArn());
            service.updateContainerInstancesState("daemon-pair", List.of(b.getContainerInstanceArn()), "DRAINING", REGION);
            if (!service.listTasks(null, null, null, null, REGION).getFirst().equals(stale.getTaskArn())) {
                continue;
            }

            service.reconcileServices();
            assertEquals("RUNNING", current.getLastStatus(), "the current task keeps the instance");
            assertEquals("STOPPING", stale.getLastStatus());
            assertEquals(1, service.describeServices("daemon-pair", List.of("daemon-svc"), REGION)
                    .getFirst().getRunningCount());
            return;
        }
        fail("never built the stale-first ordering");
    }

    @Test
    void daemonIsRejectedForFargateAndForNonEcsDeploymentControllers() {
        EcsService service = newMockModeService();
        service.createCluster("daemon-reject", REGION);
        registerTaskDef(service);

        AwsException fargate = assertThrows(AwsException.class, () -> service.createService("daemon-reject",
                "s1", "daemon-fam", 1, LaunchType.FARGATE, List.of(), null, null, "DAEMON", null, null, REGION));
        assertEquals("InvalidParameterException", fargate.getErrorCode());

        AwsException external = assertThrows(AwsException.class, () -> service.createService("daemon-reject",
                "s2", "daemon-fam", 1, LaunchType.EC2, List.of(), null, null, "DAEMON", "EXTERNAL", null, REGION));
        assertEquals("InvalidParameterException", external.getErrorCode());
    }

    /** Exactly one live task per instance, each RUNNING the given revision on the current deployment. */
    private static void assertConvergedOn(EcsService service, String cluster, String taskDefinitionArn,
                                          Set<ContainerInstance> instances) {
        List<EcsTask> live = allTasks(service).stream()
                .filter(t -> !"STOPPED".equals(t.getLastStatus()))
                .toList();
        assertEquals(instances.stream().map(ContainerInstance::getContainerInstanceArn).collect(Collectors.toSet()),
                live.stream().map(EcsTask::getContainerInstanceArn).collect(Collectors.toSet()));
        assertEquals(instances.size(), live.size(), "one daemon task per instance");
        EcsServiceModel svc = service.describeServices(cluster, List.of("daemon-svc"), REGION).getFirst();
        for (EcsTask t : live) {
            assertEquals("RUNNING", t.getLastStatus());
            assertEquals(taskDefinitionArn, t.getTaskDefinitionArn());
            assertEquals(svc.getDeploymentId(), t.getDeploymentId());
        }
        assertEquals(taskDefinitionArn, svc.getTaskDefinition());
        assertEquals(instances.size(), svc.getRunningCount());
    }

    private static List<EcsTask> allTasks(EcsService service) {
        List<String> arns = new ArrayList<>(service.listTasks(null, null, null, null, REGION));
        arns.addAll(service.listTasks(null, null, "STOPPED", null, REGION));
        return service.describeTasks(null, arns, REGION);
    }

    private static Set<String> taskArns(List<EcsTask> tasks) {
        return tasks.stream().map(EcsTask::getTaskArn).collect(Collectors.toSet());
    }

    private static List<EcsTask> runningTasks(EcsService service) {
        return service.describeTasks(null, service.listTasks(null, null, null, null, REGION), REGION).stream()
                .filter(t -> "RUNNING".equals(t.getLastStatus()))
                .toList();
    }

    private static TaskDefinition registerTaskDef(EcsService service) {
        ContainerDefinition cd = new ContainerDefinition();
        cd.setName("agent");
        cd.setImage("agent:1");
        return service.registerTaskDefinition("daemon-fam", List.of(cd), null, null, null, null, null, List.of(), REGION);
    }

    private static EcsService newDockerModeServiceWhoseStopsFail() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(false);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        EcsContainerManager containerManager = mock(EcsContainerManager.class);
        when(containerManager.removeLeftoverContainers()).thenReturn(true);
        when(containerManager.startTask(any(), any(), any(), anyString())).thenAnswer(inv ->
                new EcsTaskHandle(inv.<EcsTask>getArgument(0).getTaskArn(), Map.of("agent", "cid"), Map.of()));
        when(containerManager.stopTaskAndCollectExitCodes(any()))
                .thenThrow(new RuntimeException("docker unavailable"));
        EcsService service = new EcsService(new RegionResolver(REGION, "000000000000"), containerManager,
                config, mock(EcsLoadBalancerRegistrar.class), new InMemoryStorageFactory(), null);
        service.initializeStorage();
        return service;
    }

    private static EcsService newMockModeService() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(true);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        EcsService service = new EcsService(
                new RegionResolver(REGION, "000000000000"),
                mock(EcsContainerManager.class),
                config,
                mock(EcsLoadBalancerRegistrar.class),
                new InMemoryStorageFactory(),
                null);
        service.initializeStorage();
        return service;
    }

    private static final class InMemoryStorageFactory extends StorageFactory {
        private final Map<String, StorageBackend<String, ?>> stores = new HashMap<>();

        private InMemoryStorageFactory() {
            super(null, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String serviceName,
                                                    String fileName,
                                                    TypeReference<Map<String, V>> typeReference) {
            return (AccountAwareStorageBackend<V>) stores.computeIfAbsent(fileName,
                    ignored -> AccountAwareStorageBackend.inMemory("000000000000"));
        }
    }
}
