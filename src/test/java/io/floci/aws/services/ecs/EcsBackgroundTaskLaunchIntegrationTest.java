package io.floci.aws.services.ecs;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.ecs.container.EcsContainerManager;
import io.floci.aws.services.ecs.container.EcsTaskHandle;
import io.floci.aws.services.ecs.model.ContainerDefinition;
import io.floci.aws.services.ecs.model.EcsTask;
import io.floci.aws.services.ecs.model.LaunchType;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static io.floci.aws.testing.EcsTaskWaits.awaitLastStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * RunTask's launch runs on the task launcher's own threads, which have no request context, and
 * account-aware storage reads the account from that context. Only a running Arc container has one,
 * so this runs in Quarkus, in Docker mode over a container manager that starts nothing for real.
 */
@QuarkusTest
@TestProfile(EcsBackgroundTaskStopIntegrationTest.DockerModeProfile.class)
class EcsBackgroundTaskLaunchIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "111122223333";

    @Inject
    EcsService ecsService;

    @Inject
    RegionResolver regionResolver;

    @InjectMock
    EcsContainerManager containerManager;

    @Test
    void aTaskLaunchedInTheBackgroundLandsInItsOwnAccount() {
        AtomicReference<String> startedAs = new AtomicReference<>();
        when(containerManager.getExitCodeIfStopped(anyString())).thenReturn(null);
        when(containerManager.startTask(any(), any(), any(), anyString())).thenAnswer(invocation -> {
            startedAs.set(regionResolver.getAccountId());
            EcsTask task = invocation.getArgument(0);
            return new EcsTaskHandle(task.getTaskArn(), Map.of(), Map.of());
        });
        String cluster = "launcher-" + UUID.randomUUID().toString().substring(0, 8);
        RequestScopes.runAs(ACCOUNT, () -> {
            ecsService.createCluster(cluster, REGION);
            ContainerDefinition app = new ContainerDefinition();
            app.setName("app");
            app.setImage("app:1");
            ecsService.registerTaskDefinition(cluster, List.of(app), null, null, null,
                    null, null, List.of(), REGION);
        });

        String taskArn = RequestScopes.callAs(ACCOUNT, () -> ecsService.runTask(cluster, cluster, 1,
                LaunchType.FARGATE, null, null, List.of(), null, REGION).getFirst().getTaskArn());
        awaitLastStatus(() -> RequestScopes.callAs(ACCOUNT, () ->
                        ecsService.describeTasks(cluster, List.of(taskArn), REGION).getFirst()),
                EcsTask::getLastStatus, Duration.ofSeconds(10), "RUNNING");

        assertEquals(ACCOUNT, startedAs.get(), "the launch must start the task in its own account");
        assertEquals(1, RequestScopes.callAs(ACCOUNT, () ->
                ecsService.describeClusters(List.of(cluster), REGION).getFirst().getRunningTasksCount()));
    }
}
