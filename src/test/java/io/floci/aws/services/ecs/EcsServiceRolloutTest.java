package io.floci.aws.services.ecs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ecs.container.EcsContainerManager;
import io.floci.aws.services.ecs.model.CapacityProviderStrategyItem;
import io.floci.aws.services.ecs.model.ContainerDefinition;
import io.floci.aws.services.ecs.model.CreateServiceRequest;
import io.floci.aws.services.ecs.model.EcsServiceModel;
import io.floci.aws.services.ecs.model.EcsTask;
import io.floci.aws.services.ecs.model.LaunchType;
import io.floci.aws.services.ecs.model.TaskDefinition;
import io.floci.aws.services.ecs.model.TaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    private static Stream<Arguments> mixedCapacityProviderStrategies() {
        return Stream.of(
                Arguments.of(List.of(new CapacityProviderStrategyItem("FARGATE", 1, 0),
                                new CapacityProviderStrategyItem("FARGATE_SPOT", 1, 0)),
                        Map.of("FARGATE", 1L, "FARGATE_SPOT", 1L),
                        Map.of("FARGATE", 2L, "FARGATE_SPOT", 2L)),
                Arguments.of(List.of(new CapacityProviderStrategyItem("FARGATE", 0, 1),
                                new CapacityProviderStrategyItem("FARGATE_SPOT", 1, 0)),
                        Map.of("FARGATE", 1L, "FARGATE_SPOT", 1L),
                        Map.of("FARGATE", 1L, "FARGATE_SPOT", 5L)),
                Arguments.of(List.of(new CapacityProviderStrategyItem("FARGATE", 1, 1),
                                new CapacityProviderStrategyItem("FARGATE_SPOT", 3, 0)),
                        Map.of("FARGATE", 2L, "FARGATE_SPOT", 3L),
                        Map.of("FARGATE", 3L, "FARGATE_SPOT", 6L)));
    }

    @ParameterizedTest
    @MethodSource("mixedCapacityProviderStrategies")
    void mixedProviderServicesKeepTheirSplitOnScaleOutReplacementAndRollout(
            List<CapacityProviderStrategyItem> strategy, Map<String, Long> initial, Map<String, Long> scaled) {
        EcsService service = newMockModeService();
        service.createCluster("roll-cluster", REGION);
        registerTaskDef(service, "roll-fam", "app:1");
        CreateServiceRequest request = new CreateServiceRequest();
        request.setCluster("roll-cluster");
        request.setServiceName("roll-svc");
        request.setTaskDefinition("roll-fam");
        request.setDesiredCount(initial.values().stream().mapToInt(Long::intValue).sum());
        request.setCapacityProviderStrategy(strategy);
        service.createService(request, REGION);

        service.reconcileServices();
        assertEquals(initial, providerCounts(service));

        service.updateService("roll-cluster", "roll-svc", null,
                scaled.values().stream().mapToInt(Long::intValue).sum(), null, REGION);
        service.reconcileServices();
        assertEquals(scaled, providerCounts(service));

        for (String provider : scaled.keySet()) {
            EcsTask stopped = runningTasks(service).stream()
                    .filter(task -> provider.equals(task.getCapacityProviderName())).findFirst().orElseThrow();
            service.stopTask("roll-cluster", stopped.getTaskArn(), "replacement test", REGION);
            service.reconcileServices();
            assertEquals(scaled, providerCounts(service));
            assertTrue(runningTasks(service).stream().noneMatch(task -> task.getTaskArn().equals(stopped.getTaskArn())));
        }

        TaskDefinition revision = registerTaskDef(service, "roll-fam", "app:2");
        service.updateService("roll-cluster", "roll-svc", revision.getTaskDefinitionArn(), null, null, REGION);
        service.reconcileServices();
        service.reconcileServices();
        assertEquals(scaled, providerCounts(service));
        assertTrue(runningTasks(service).stream()
                .allMatch(task -> revision.getTaskDefinitionArn().equals(task.getTaskDefinitionArn())));
    }

    private static Map<String, Long> providerCounts(EcsService service) {
        return runningTasks(service).stream()
                .collect(Collectors.groupingBy(EcsTask::getCapacityProviderName, Collectors.counting()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"FARGATE", "FARGATE_SPOT"})
    void capacityProviderServiceStartsAndReplacesStoppedTasks(String provider) {
        EcsService service = newMockModeService();
        service.createCluster("roll-cluster", REGION);
        registerTaskDef(service, "roll-fam", "app:1");
        CreateServiceRequest request = new CreateServiceRequest();
        request.setCluster("roll-cluster");
        request.setServiceName("roll-svc");
        request.setTaskDefinition("roll-fam");
        request.setDesiredCount(2);
        request.setCapacityProviderStrategy(List.of(new CapacityProviderStrategyItem(provider, 1, 0)));
        EcsServiceModel svc = service.createService(request, REGION);

        service.reconcileServices();
        List<EcsTask> first = runningTasks(service);
        assertEquals(2, first.size());
        for (EcsTask task : first) {
            assertEquals(provider, task.getCapacityProviderName());
            assertEquals(LaunchType.FARGATE, task.getLaunchType());
            assertEquals(svc.getServiceArn(), task.getOwningServiceArn());
        }

        service.stopTask("roll-cluster", first.getFirst().getTaskArn(), "replacement test", REGION);
        service.reconcileServices();
        List<EcsTask> after = runningTasks(service);
        assertEquals(2, after.size());
        assertTrue(after.stream().noneMatch(task -> task.getTaskArn().equals(first.getFirst().getTaskArn())));
        assertTrue(after.stream().allMatch(task -> provider.equals(task.getCapacityProviderName())));
    }

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

        EcsServiceModel svc = service.createService("pin-cluster", "pin-svc", "pin-fam", 0,
                LaunchType.FARGATE, List.of(), null, REGION);

        assertEquals(rev1.getTaskDefinitionArn(), svc.getTaskDefinition());
    }

    @Test
    void serviceHoldingARawReferenceIsPinnedOnItsFirstTickInsteadOfRelaunchingForever() {
        EcsService service = newMockModeService();
        service.createCluster("legacy-cluster", REGION);
        TaskDefinition rev1 = registerTaskDef(service, "legacy-fam", "app:1");
        EcsServiceModel svc = service.createService("legacy-cluster", "legacy-svc", "legacy-fam", 1,
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
        EcsServiceModel created = service.createService("force-cluster", "force-svc", "force-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION);
        String firstDeploymentId = created.getDeploymentId();
        assertNotNull(firstDeploymentId, "createService assigns a deploymentId");

        service.reconcileServices();
        List<EcsTask> before = runningTasks(service);
        assertEquals(1, before.size());
        assertEquals(firstDeploymentId, before.getFirst().getDeploymentId());

        // Same task definition, force flag set.
        EcsServiceModel updated = service.updateService("force-cluster", "force-svc", null, null, null,
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

        service.reconcileServices(); // task starts and can become RUNNING in this tick
        verify(publisher, never())
                .emitDeploymentStateChange(any(), eq("SERVICE_DEPLOYMENT_COMPLETED"), any(), eq(REGION));
        service.reconcileServices(); // completion event is emitted on the next reconciliation
        verify(publisher, times(1))
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
