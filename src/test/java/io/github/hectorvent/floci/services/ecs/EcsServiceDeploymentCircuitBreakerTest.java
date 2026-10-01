package io.github.hectorvent.floci.services.ecs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.container.EcsTaskHandle;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.CreateServiceRequest;
import io.github.hectorvent.floci.services.ecs.model.Deployment;
import io.github.hectorvent.floci.services.ecs.model.EcsServiceModel;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.services.ecs.model.ServiceDeployment;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * The deployment circuit breaker, driven off the reconciler tick by tick. Tasks fail to start
 * through a container manager that throws, which is how a Docker launch failure surfaces: the
 * task comes back STOPPED without ever reaching RUNNING.
 */
class EcsServiceDeploymentCircuitBreakerTest {

    private static final String REGION = "us-east-1";

    /** Launch outcomes, consumed one per launch; empty means every launch fails. */
    private final Deque<Boolean> launches = new ArrayDeque<>();
    private boolean healthy;
    /** When set, the next launch is stopped by a user while its image is still pulling. */
    private boolean stopDuringPull;
    private EcsService service;

    @Test
    void aDeploymentWhoseTasksNeverStartIsStoppedAtTheDefaultThreshold() {
        EcsService service = newService();
        String id = createService(service, "cb-fail", 1, breaker(true, false)).getDeploymentId();

        // desiredCount 1: 50% of 1 is below the floor, so the threshold is 3.
        service.reconcileServices();
        service.reconcileServices();
        ServiceDeployment twoDown = deploymentOf(service, "cb-fail", id);
        assertEquals("IN_PROGRESS", twoDown.getStatus(), "two failures are below a threshold of 3");
        assertNull(twoDown.getFinishedAt());

        service.reconcileServices();
        ServiceDeployment failed = deploymentOf(service, "cb-fail", id);
        assertEquals("STOPPED", failed.getStatus(),
                "without rollback a failed deployment is stopped; there is no FAILED status");
        assertNotNull(failed.getFinishedAt());
        assertNotNull(failed.getStoppedAt());
        assertEquals("The deployment circuit breaker detected a failure.", failed.getStatusReason());

        Deployment rollout = liveDeployment(service, "cb-fail");
        assertEquals("FAILED", rollout.getRolloutState());
        assertEquals("The deployment circuit breaker detected a failure.",
                rollout.getRolloutStateReason());
        assertEquals(3, rollout.getFailedTasks());

        assertTrue(service.listServiceDeployments("cb-fail", "cb-fail-cluster",
                List.of("IN_PROGRESS"), REGION).isEmpty(), "nothing is reported as rolling out");

        service.reconcileServices();
        service.reconcileServices();
        assertEquals(3, stoppedTasks(service).size(), "a failed deployment launches no more tasks");
        assertEquals("STOPPED", deploymentOf(service, "cb-fail", id).getStatus());
    }

    /** BOUNDED_PERCENT: 50% of 10 is 5, inside the 3..200 bounds, and stops mid-tick. */
    @Test
    void theDefaultThresholdIsHalfTheDesiredCount() {
        EcsService service = newService();
        String id = createService(service, "cb-ten", 10, breaker(true, false)).getDeploymentId();

        service.reconcileServices();

        assertEquals("STOPPED", deploymentOf(service, "cb-ten", id).getStatus());
        assertEquals(5, stoppedTasks(service).size(),
                "the launch that reaches the threshold is the last one");
        assertEquals(5, liveDeployment(service, "cb-ten").getFailedTasks());
    }

    /** 50% of 7 is 3.5, rounded up to 4. */
    @Test
    void aPercentThresholdIsRoundedUp() {
        EcsService service = newService();
        String id = createService(service, "cb-ceil", 7, breaker(true, false)).getDeploymentId();

        service.reconcileServices();

        assertEquals("STOPPED", deploymentOf(service, "cb-ceil", id).getStatus());
        assertEquals(4, liveDeployment(service, "cb-ceil").getFailedTasks());
    }

    /** 50% of 402 is 201, clamped to the 200 maximum. */
    @Test
    void aBoundedPercentThresholdIsCappedAt200() {
        EcsService service = newService();
        String id = createService(service, "cb-cap", 402, breaker(true, false)).getDeploymentId();

        service.reconcileServices();

        assertEquals("STOPPED", deploymentOf(service, "cb-cap", id).getStatus());
        assertEquals(200, liveDeployment(service, "cb-cap").getFailedTasks());
    }

    /** UNBOUNDED_PERCENT has no 200 cap: 100% of 201 is 201. */
    @Test
    void anUnboundedPercentThresholdCanExceed200() {
        EcsService service = newService();
        Map<String, Object> breaker = breaker(true, false);
        breaker.put("thresholdConfiguration", Map.of("type", "UNBOUNDED_PERCENT", "value", 100));
        String id = createService(service, "cb-unb", 201, breaker).getDeploymentId();

        service.reconcileServices();

        assertEquals("STOPPED", deploymentOf(service, "cb-unb", id).getStatus());
        assertEquals(201, liveDeployment(service, "cb-unb").getFailedTasks());
    }

    @Test
    void aCountThresholdIsUsedAsIs() {
        EcsService service = newService();
        Map<String, Object> breaker = breaker(true, false);
        breaker.put("thresholdConfiguration", Map.of("type", "COUNT", "value", 5));
        String id = createService(service, "cb-count", 2, breaker).getDeploymentId();

        service.reconcileServices();
        service.reconcileServices();
        assertEquals("IN_PROGRESS", deploymentOf(service, "cb-count", id).getStatus(),
                "four failures, and the bounded default for 2 would already have tripped at 3");

        service.reconcileServices();
        assertEquals("STOPPED", deploymentOf(service, "cb-count", id).getStatus());
        assertEquals(5, stoppedTasks(service).size());
    }

    /** Without the circuit breaker ECS keeps trying, so the deployment stays in progress. */
    @Test
    void withTheCircuitBreakerOffTheDeploymentKeepsTrying() {
        EcsService service = newService();
        String id = createService(service, "cb-off", 1, breaker(false, false)).getDeploymentId();

        for (int i = 0; i < 10; i++) {
            service.reconcileServices();
        }

        ServiceDeployment deployment = deploymentOf(service, "cb-off", id);
        assertEquals("IN_PROGRESS", deployment.getStatus());
        assertNull(deployment.getFinishedAt());
        assertNull(deployment.getStatusReason());
        assertEquals("IN_PROGRESS", liveDeployment(service, "cb-off").getRolloutState());
        assertEquals(10, stoppedTasks(service).size(), "every tick tries again");
    }

    @Test
    void withNoDeploymentConfigurationTheDeploymentKeepsTrying() {
        EcsService service = newService();
        String id = createService(service, "cb-none", 1, null).getDeploymentId();

        for (int i = 0; i < 5; i++) {
            service.reconcileServices();
        }

        assertEquals("IN_PROGRESS", deploymentOf(service, "cb-none", id).getStatus());
        assertEquals("IN_PROGRESS", liveDeployment(service, "cb-none").getRolloutState());
    }

    /**
     * With rollback on, AWS rolls back rather than stopping (ROLLBACK_IN_PROGRESS, then
     * ROLLBACK_SUCCESSFUL or ROLLBACK_FAILED). Rollback is not modelled, so the deployment must
     * not claim the STOPPED outcome that only applies without it.
     */
    @Test
    void withRollbackOnTheDeploymentIsNotReportedStopped() {
        EcsService service = newService();
        String id = createService(service, "cb-rb", 1, breaker(true, true)).getDeploymentId();

        for (int i = 0; i < 5; i++) {
            service.reconcileServices();
        }

        ServiceDeployment deployment = deploymentOf(service, "cb-rb", id);
        assertEquals("IN_PROGRESS", deployment.getStatus());
        assertNull(deployment.getStoppedAt());
    }

    /** Only a task that failed to start counts; one a user stopped while it pulled did not fail. */
    @Test
    void aTaskAUserStopsDuringItsPullIsNotAFailure() {
        EcsService service = newService();
        Map<String, Object> breaker = breaker(true, false);
        breaker.put("thresholdConfiguration", Map.of("type", "COUNT", "value", 1));
        healthy = true;
        stopDuringPull = true;
        String id = createService(service, "cb-user", 1, breaker).getDeploymentId();

        service.reconcileServices();

        EcsTask stopped = stoppedTasks(service).getFirst();
        assertEquals(EcsService.STOP_CODE_USER_INITIATED, stopped.getStopCode(),
                "precondition: the launch came back stopped by the user, not failed");
        assertEquals("IN_PROGRESS", deploymentOf(service, "cb-user", id).getStatus(),
                "a threshold of 1 would have tripped had it counted");
        assertEquals(0, liveDeployment(service, "cb-user").getFailedTasks());

        service.reconcileServices();
        assertEquals("SUCCESSFUL", deploymentOf(service, "cb-user", id).getStatus());
    }

    @Test
    void aDeploymentThatStartsBelowTheThresholdSucceeds() {
        EcsService service = newService();
        String id = createService(service, "cb-late", 1, breaker(true, false)).getDeploymentId();

        service.reconcileServices();
        service.reconcileServices();
        healthy = true;
        service.reconcileServices();

        ServiceDeployment deployment = deploymentOf(service, "cb-late", id);
        assertEquals("SUCCESSFUL", deployment.getStatus());
        assertNull(deployment.getStoppedAt());
        assertNull(deployment.getStatusReason());
        assertEquals("COMPLETED", liveDeployment(service, "cb-late").getRolloutState());
    }

    /** resetOnHealthyTask defaults to true: only consecutive failures count. */
    @Test
    void aTaskThatStartsResetsTheFailureCount() {
        EcsService service = newService();
        Map<String, Object> breaker = breaker(true, false);
        breaker.put("thresholdConfiguration", Map.of("type", "COUNT", "value", 3));
        launches.addAll(List.of(false, false, true, false, false, true, true));
        String id = createService(service, "cb-reset", 3, breaker).getDeploymentId();

        service.reconcileServices();
        service.reconcileServices();
        service.reconcileServices();

        assertEquals("SUCCESSFUL", deploymentOf(service, "cb-reset", id).getStatus(),
                "four failures in all, but never three in a row");
    }

    @Test
    void withoutResetOnHealthyTaskEveryFailureCounts() {
        EcsService service = newService();
        Map<String, Object> breaker = breaker(true, false);
        breaker.put("resetOnHealthyTask", false);
        breaker.put("thresholdConfiguration", Map.of("type", "COUNT", "value", 3));
        launches.addAll(List.of(false, false, true, false, false, true, true));
        String id = createService(service, "cb-cumul", 3, breaker).getDeploymentId();

        service.reconcileServices();
        service.reconcileServices();

        assertEquals("STOPPED", deploymentOf(service, "cb-cumul", id).getStatus(),
                "the third failure trips it although a task started in between");
    }

    /**
     * The breaker only watches a deployment in flight. One whose task came up has finished, even
     * unread, and replacements failing afterwards do not turn it into a failure.
     */
    @Test
    void aCompletedDeploymentIsNotFailedByLaterLaunchFailures() {
        EcsService service = newService();
        healthy = true;
        String id = createService(service, "cb-done", 1, breaker(true, false)).getDeploymentId();
        service.reconcileServices();

        healthy = false;
        EcsTask task = runningTasks(service).getFirst();
        service.stopTask("cb-done-cluster", task.getTaskArn(), "test kill", REGION);
        for (int i = 0; i < 4; i++) {
            service.reconcileServices();
        }

        ServiceDeployment deployment = deploymentOf(service, "cb-done", id);
        assertEquals("SUCCESSFUL", deployment.getStatus());
        assertNull(deployment.getStatusReason());
    }

    /**
     * A deployment can converge without a launch, here by scaling in to the task that did start.
     * The tick settles it all the same, so the failures that follow cannot count against it.
     */
    @Test
    void aDeploymentThatConvergesByScalingInIsNotFailedLater() {
        EcsService service = newService();
        Map<String, Object> breaker = breaker(true, false);
        breaker.put("thresholdConfiguration", Map.of("type", "COUNT", "value", 3));
        launches.addAll(List.of(true, false));
        String id = createService(service, "cb-scale", 2, breaker).getDeploymentId();
        service.reconcileServices();

        // No new deployment: a desired-count change rolls nothing.
        service.updateService("cb-scale-cluster", "cb-scale", null, 1, null, REGION);
        service.reconcileServices();

        EcsTask task = runningTasks(service).getFirst();
        service.stopTask("cb-scale-cluster", task.getTaskArn(), "test kill", REGION);
        for (int i = 0; i < 4; i++) {
            service.reconcileServices();
        }

        assertEquals("SUCCESSFUL", deploymentOf(service, "cb-scale", id).getStatus());
    }

    /**
     * The realistic case: a good deployment, then an update to a revision that cannot start. The
     * update fails, the earlier deployment keeps its record and its task, and the next update
     * starts from scratch.
     */
    @Test
    void anUpdateThatCannotStartFailsWithoutTouchingTheDeploymentBeforeIt() {
        EcsService service = newService();
        healthy = true;
        String first = createService(service, "cb-upd", 1, breaker(true, false)).getDeploymentId();
        service.reconcileServices();
        EcsTask original = runningTasks(service).getFirst();

        healthy = false;
        TaskDefinition broken = registerTaskDef(service, "cb-upd-fam", "app:broken");
        String second = service.updateService("cb-upd-cluster", "cb-upd",
                "cb-upd-fam:" + broken.getRevision(), null, null, REGION).getDeploymentId();
        for (int i = 0; i < 3; i++) {
            service.reconcileServices();
        }

        assertEquals("STOPPED", deploymentOf(service, "cb-upd", second).getStatus());
        assertEquals("SUCCESSFUL", deploymentOf(service, "cb-upd", first).getStatus());
        assertEquals(List.of(original.getTaskArn()),
                runningTasks(service).stream().map(EcsTask::getTaskArn).toList(),
                "the previous revision's task keeps serving");

        healthy = true;
        TaskDefinition fixed = registerTaskDef(service, "cb-upd-fam", "app:fixed");
        String third = service.updateService("cb-upd-cluster", "cb-upd",
                "cb-upd-fam:" + fixed.getRevision(), null, null, REGION).getDeploymentId();
        service.reconcileServices();

        assertEquals("SUCCESSFUL", deploymentOf(service, "cb-upd", third).getStatus(),
                "a new deployment is not held back by the failed one before it");
        ServiceDeployment failed = deploymentOf(service, "cb-upd", second);
        assertEquals("STOPPED", failed.getStatus());
        assertTrue(failed.getStatusReason().contains("circuit breaker"),
                "superseding it later does not rewrite why it ended; was: " + failed.getStatusReason());
    }

    /**
     * Services persist across a restart but deployment records live only in memory. The failed
     * deployment must stay halted anyway, or the reconciler retries its tasks forever with no
     * record left to count them against.
     */
    @Test
    void aFailedDeploymentStaysHaltedAcrossARestart() {
        InMemoryStorageFactory storage = new InMemoryStorageFactory();
        EcsService before = newService(storage);
        createService(before, "cb-restart", 1, breaker(true, false));
        for (int i = 0; i < 3; i++) {
            before.reconcileServices();
        }
        assertEquals(3, stoppedTasks(before).size());

        EcsService after = newService(storage);
        after.reconcileServices();
        after.reconcileServices();

        assertTrue(stoppedTasks(after).isEmpty(), "a restart does not resume a failed deployment");
        assertEquals("FAILED", liveDeployment(after, "cb-restart").getRolloutState());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Map<String, Object> breaker(boolean enable, boolean rollback) {
        Map<String, Object> breaker = new HashMap<>();
        breaker.put("enable", enable);
        breaker.put("rollback", rollback);
        return breaker;
    }

    private static EcsServiceModel createService(EcsService service, String name, int desiredCount,
                                                 Map<String, Object> circuitBreaker) {
        service.createCluster(name + "-cluster", REGION);
        registerTaskDef(service, name + "-fam", "app:1");
        CreateServiceRequest request = new CreateServiceRequest();
        request.setCluster(name + "-cluster");
        request.setServiceName(name);
        request.setTaskDefinition(name + "-fam");
        request.setDesiredCount(desiredCount);
        request.setLaunchType(LaunchType.FARGATE);
        if (circuitBreaker != null) {
            request.setDeploymentConfiguration(Map.of("deploymentCircuitBreaker", circuitBreaker));
        }
        return service.createService(request, REGION);
    }

    /** The deployment carrying {@code deploymentId}, found by the task set id in its revision ARN. */
    private static ServiceDeployment deploymentOf(EcsService service, String name, String deploymentId) {
        String taskSetId = deploymentId.substring(deploymentId.indexOf('/') + 1);
        return service.listServiceDeploymentsDetailed(name, name + "-cluster", null, REGION).stream()
                .filter(d -> d.getTargetServiceRevisionArn() != null
                        && d.getTargetServiceRevisionArn().endsWith("/" + taskSetId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no deployment for " + deploymentId));
    }

    private static Deployment liveDeployment(EcsService service, String name) {
        EcsServiceModel svc = service.serviceByArn(
                service.describeServices(name + "-cluster", List.of(name), REGION)
                        .getFirst().getServiceArn());
        return service.deploymentsFor(svc).getFirst();
    }

    private static List<EcsTask> runningTasks(EcsService service) {
        return service.describeTasks(null, service.listTasks(null, null, null, null, REGION), REGION)
                .stream().filter(t -> "RUNNING".equals(t.getLastStatus())).toList();
    }

    private static List<EcsTask> stoppedTasks(EcsService service) {
        return service.describeTasks(null, service.listTasks(null, null, "STOPPED", null, REGION), REGION);
    }

    private static TaskDefinition registerTaskDef(EcsService service, String family, String image) {
        ContainerDefinition cd = new ContainerDefinition();
        cd.setName("app");
        cd.setImage(image);
        return service.registerTaskDefinition(family, List.of(cd), null, null, null,
                null, null, List.of(), REGION);
    }

    /** Docker mode over a container manager whose launches succeed or throw as the test says. */
    private EcsService newService() {
        return newService(new InMemoryStorageFactory());
    }

    /** A service over {@code storage}; a second one over the same storage is a restart. */
    private EcsService newService(InMemoryStorageFactory storage) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(false);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        EcsContainerManager containerManager = mock(EcsContainerManager.class);
        when(containerManager.startTask(any(), any(), any(), anyString())).thenAnswer(invocation -> {
            Boolean scripted = launches.poll();
            if (scripted != null ? !scripted : !healthy) {
                throw new RuntimeException("CannotPullContainerError: image not found");
            }
            EcsTask task = invocation.getArgument(0);
            if (stopDuringPull) {
                stopDuringPull = false;
                service.stopTask(null, task.getTaskArn(), "user stop", REGION);
            }
            return new EcsTaskHandle(task.getTaskArn(), Map.of("app", "docker-id"), Map.of());
        });
        service = new EcsService(
                new RegionResolver(REGION, "000000000000"),
                containerManager,
                config,
                mock(EcsLoadBalancerRegistrar.class),
                storage,
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
