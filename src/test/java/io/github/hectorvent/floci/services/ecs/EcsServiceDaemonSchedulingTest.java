package io.github.hectorvent.floci.services.ecs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.ContainerInstance;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        long attempts = service.describeTasks(null, service.listTasks(null, null, null, null, REGION), REGION).size();
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

    private static List<EcsTask> runningTasks(EcsService service) {
        return service.describeTasks(null, service.listTasks(null, null, null, null, REGION), REGION).stream()
                .filter(t -> "RUNNING".equals(t.getLastStatus()))
                .toList();
    }

    private static void registerTaskDef(EcsService service) {
        ContainerDefinition cd = new ContainerDefinition();
        cd.setName("agent");
        cd.setImage("agent:1");
        service.registerTaskDefinition("daemon-fam", List.of(cd), null, null, null, null, null, List.of(), REGION);
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
