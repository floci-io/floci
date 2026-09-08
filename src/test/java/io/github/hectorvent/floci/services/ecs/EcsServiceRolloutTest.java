package io.github.hectorvent.floci.services.ecs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.github.hectorvent.floci.services.ecs.model.TaskStatus;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A service's running tasks must follow its task definition. {@code UpdateService} with a new
 * {@code taskDefinition} used to record a deployment and leave the old tasks running forever,
 * so a redeploy (new image tag, new environment) never reached the containers.
 */
class EcsServiceRolloutTest {

    private static final String REGION = "us-east-1";

    @Test
    void updatedTaskDefinitionReplacesRunningTasksReplacementsFirst() {
        EcsService service = newMockModeService();
        service.createCluster("roll-cluster", REGION);
        TaskDefinition rev1 = registerTaskDef(service, "roll-fam", "app:1");
        // A bare family reference, as the CLI and most clients send it: the service must pin
        // the resolved ARN, or every task would look stale on every tick.
        service.createService("roll-cluster", "roll-svc", "roll-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION);

        service.reconcileServices();
        List<EcsTask> first = runningTasks(service);
        assertEquals(1, first.size());
        assertEquals(rev1.getTaskDefinitionArn(), first.getFirst().getTaskDefinitionArn());

        TaskDefinition rev2 = registerTaskDef(service, "roll-fam", "app:2");
        service.updateService("roll-cluster", "roll-svc", "roll-fam:" + rev2.getRevision(), null, null, REGION);

        // Tick 1: the replacement comes up before anything is drained (desiredCount 1 is still met).
        service.reconcileServices();
        List<EcsTask> during = runningTasks(service);
        assertEquals(2, during.size(), "replacement starts before the stale task is stopped");
        assertTrue(during.stream().anyMatch(t -> rev2.getTaskDefinitionArn().equals(t.getTaskDefinitionArn())));

        // Tick 2: the stale task is drained, the replacement stays.
        service.reconcileServices();
        List<EcsTask> after = runningTasks(service);
        assertEquals(1, after.size());
        assertEquals(rev2.getTaskDefinitionArn(), after.getFirst().getTaskDefinitionArn());

        EcsTask stopped = service.describeTasks("roll-cluster", List.of(first.getFirst().getTaskArn()), REGION).getFirst();
        assertEquals("STOPPED", stopped.getLastStatus());
        assertTrue(stopped.getStoppedReason().contains(rev1.getTaskDefinitionArn()),
                "stopped reason names the replaced task definition: " + stopped.getStoppedReason());

        // Steady state: nothing else changes on later ticks.
        service.reconcileServices();
        assertEquals(1, runningTasks(service).size());
    }

    @Test
    void serviceReportsThePinnedTaskDefinitionArn() {
        EcsService service = newMockModeService();
        service.createCluster("pin-cluster", REGION);
        TaskDefinition rev1 = registerTaskDef(service, "pin-fam", "app:1");

        var svc = service.createService("pin-cluster", "pin-svc", "pin-fam", 0,
                LaunchType.FARGATE, List.of(), null, REGION);

        assertEquals(rev1.getTaskDefinitionArn(), svc.getTaskDefinition());
    }

    @Test
    void serviceHoldingARawReferenceIsPinnedOnItsFirstTickInsteadOfRelaunchingForever() {
        EcsService service = newMockModeService();
        service.createCluster("legacy-cluster", REGION);
        TaskDefinition rev1 = registerTaskDef(service, "legacy-fam", "app:1");
        var svc = service.createService("legacy-cluster", "legacy-svc", "legacy-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION);
        service.reconcileServices();
        assertEquals(1, runningTasks(service).size());

        // Simulate a service persisted before the ARN was pinned.
        svc.setTaskDefinition("legacy-fam:1");

        service.reconcileServices();
        service.reconcileServices();

        assertEquals(1, runningTasks(service).size(), "the raw reference must not read as stale");
        assertEquals(rev1.getTaskDefinitionArn(), svc.getTaskDefinition());
    }

    @Test
    void unchangedTaskDefinitionIsNotTouchedByTheReconciler() {
        EcsService service = newMockModeService();
        service.createCluster("steady-cluster", REGION);
        TaskDefinition rev1 = registerTaskDef(service, "steady-fam", "app:1");
        service.createService("steady-cluster", "steady-svc", rev1.getTaskDefinitionArn(), 2,
                LaunchType.FARGATE, List.of(), null, REGION);

        service.reconcileServices();
        List<EcsTask> first = runningTasks(service);
        service.reconcileServices();
        List<EcsTask> second = runningTasks(service);

        assertEquals(2, second.size());
        assertEquals(first.stream().map(EcsTask::getTaskArn).sorted().toList(),
                second.stream().map(EcsTask::getTaskArn).sorted().toList());
    }

    @Test
    void forceNewDeploymentSameTaskDefinitionMintsNewIdAndReplacesTasks() {
        EcsService service = newMockModeService();
        service.createCluster("force-cluster", REGION);
        TaskDefinition rev1 = registerTaskDef(service, "force-fam", "app:1");
        var created = service.createService("force-cluster", "force-svc", "force-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION);
        String firstDeploymentId = created.getDeploymentId();
        assertNotNull(firstDeploymentId, "createService assigns a deploymentId");

        service.reconcileServices();
        List<EcsTask> before = runningTasks(service);
        assertEquals(1, before.size());
        assertEquals(firstDeploymentId, before.getFirst().getDeploymentId());

        // Same task definition, force flag set.
        var updated = service.updateService("force-cluster", "force-svc", null, null, null,
                null, true, REGION);
        assertNotEquals(firstDeploymentId, updated.getDeploymentId(),
                "forceNewDeployment mints a new deployment id");

        // Tick 1: replacement on the new deployment comes up while desiredCount is still met.
        service.reconcileServices();
        assertEquals(2, runningTasks(service).size());

        // Tick 2: the task from the old deployment is drained.
        service.reconcileServices();
        List<EcsTask> after = runningTasks(service);
        assertEquals(1, after.size());
        assertEquals(updated.getDeploymentId(), after.getFirst().getDeploymentId());

        EcsTask stopped = service.describeTasks("force-cluster",
                List.of(before.getFirst().getTaskArn()), REGION).getFirst();
        assertEquals("STOPPED", stopped.getLastStatus());
    }

    @Test
    void serviceTaskStartAndStopEmitTaskStateChangeLadders() {
        EcsEventPublisher publisher = mock(EcsEventPublisher.class);
        EcsService service = newMockModeService(publisher);
        service.createCluster("evt-cluster", REGION);
        registerTaskDef(service, "evt-fam", "app:1");
        service.createService("evt-cluster", "evt-svc", "evt-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION);

        service.reconcileServices();
        verify(publisher).emitTaskLadder(any(), eq(TaskStatus.PENDING), eq(TaskStatus.RUNNING), eq(REGION));

        String taskArn = runningTasks(service).getFirst().getTaskArn();
        service.stopTask("evt-cluster", taskArn, "test", REGION);
        verify(publisher).emitTaskLadder(any(), eq(TaskStatus.RUNNING), eq(TaskStatus.STOPPED), eq(REGION));
    }

    @Test
    void deploymentEmitsStartedThenCompleted() {
        EcsEventPublisher publisher = mock(EcsEventPublisher.class);
        EcsService service = newMockModeService(publisher);
        service.createCluster("dep-cluster", REGION);
        registerTaskDef(service, "dep-fam", "app:1");

        service.createService("dep-cluster", "dep-svc", "dep-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION);
        verify(publisher).emitDeploymentStateChange(any(), eq("SERVICE_DEPLOYMENT_STARTED"), any(), eq(REGION));

        service.reconcileServices(); // task starts, still converging or converged same tick
        service.reconcileServices(); // converged
        verify(publisher, atLeastOnce())
                .emitDeploymentStateChange(any(), eq("SERVICE_DEPLOYMENT_COMPLETED"), any(), eq(REGION));

        // COMPLETED is not re-emitted on further steady-state ticks.
        service.reconcileServices();
        verify(publisher, times(1))
                .emitDeploymentStateChange(any(), eq("SERVICE_DEPLOYMENT_COMPLETED"), any(), eq(REGION));
    }

    private static List<EcsTask> runningTasks(EcsService service) {
        return service.describeTasks(null, service.listTasks(null, null, null, null, REGION), REGION).stream()
                .filter(t -> "RUNNING".equals(t.getLastStatus()))
                .toList();
    }

    private static TaskDefinition registerTaskDef(EcsService service, String family, String image) {
        ContainerDefinition cd = new ContainerDefinition();
        cd.setName("app");
        cd.setImage(image);
        return service.registerTaskDefinition(family, List.of(cd), null, null, null,
                null, null, List.of(), REGION);
    }

    private static EcsService newMockModeService() {
        return newMockModeService(null);
    }

    private static EcsService newMockModeService(EcsEventPublisher publisher) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(true);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        EcsService service = new EcsService(
                new RegionResolver(REGION, "000000000000"),
                mock(EcsContainerManager.class),
                config,
                mock(EcsLoadBalancerRegistrar.class),
                new InMemoryStorageFactory(),
                publisher);
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
