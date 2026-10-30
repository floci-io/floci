package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.container.EcsTaskHandle;
import io.github.hectorvent.floci.services.ecs.model.Container;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The service scheduler's Docker stops run on the task stopper's own threads, which have no
 * request context, and account-aware storage reads the account from that context. Only a running
 * Arc container has one, so this runs in Quarkus, in Docker mode over a container manager that
 * stops nothing for real: a stop that landed in the wrong account would leave the task's own
 * cluster still counting it.
 */
@QuarkusTest
@TestProfile(EcsBackgroundTaskStopIntegrationTest.DockerModeProfile.class)
class EcsBackgroundTaskStopIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "111122223333";

    /** Docker mode, with no sweep of a previous run's containers, which the mock would refuse. */
    public static final class DockerModeProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.ecs.mock", "false",
                    "floci.services.ecs.reconcile-containers-on-startup", "false");
        }
    }

    @Inject
    EcsService ecsService;

    @InjectMock
    EcsContainerManager containerManager;

    @Test
    void aServiceTaskStoppedInTheBackgroundLandsInItsOwnAccount() {
        AtomicReference<String> stoppedOn = new AtomicReference<>();
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
            stoppedOn.set(Thread.currentThread().getName());
            EcsTaskHandle handle = invocation.getArgument(0);
            handle.getContainerIds().keySet().forEach(handle::recordContainerRemoved);
            return Map.of("app", 137);
        });
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String cluster = "stopper-" + suffix;
        String service = "stopper-svc-" + suffix;
        RequestScopes.runAs(ACCOUNT, () -> {
            ecsService.createCluster(cluster, REGION);
            ContainerDefinition app = new ContainerDefinition();
            app.setName("app");
            app.setImage("app:1");
            ecsService.registerTaskDefinition(service, List.of(app), null, null, null,
                    null, null, List.of(), REGION);
            ecsService.createService(cluster, service, service, 1, LaunchType.FARGATE, List.of(), null, REGION);
        });

        ecsService.reconcile();
        EcsTask task = RequestScopes.callAs(ACCOUNT, () -> {
            List<EcsTask> running = ecsService.describeTasks(cluster,
                    ecsService.listTasks(cluster, null, null, service, REGION), REGION);
            assertEquals(1, running.size(), "the service's task must be running");
            return running.getFirst();
        });
        assertEquals(1, runningTasksCount(cluster));

        RequestScopes.runAs(ACCOUNT, () -> ecsService.updateService(cluster, service, null, 0, null, REGION));
        ecsService.reconcile();
        // A stop lands whole under the task's monitor, so a STOPPED read there sees its exit codes
        // and cluster count too.
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            synchronized (task) {
                return "STOPPED".equals(task.getLastStatus());
            }
        });

        assertTrue(stoppedOn.get().startsWith("ecs-task-stopper-"), "stopped on " + stoppedOn.get());
        assertEquals(137, task.getContainers().getFirst().getExitCode());
        assertEquals(0, runningTasksCount(cluster), "the stop must land in the task's own account");
        RequestScopes.runAs(ACCOUNT, () -> ecsService.deleteService(cluster, service, true, REGION));
    }

    private int runningTasksCount(String cluster) {
        return RequestScopes.callAs(ACCOUNT, () ->
                ecsService.describeClusters(List.of(cluster), REGION).getFirst().getRunningTasksCount());
    }
}
