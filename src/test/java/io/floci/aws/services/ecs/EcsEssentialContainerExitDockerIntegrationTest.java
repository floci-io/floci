package io.floci.aws.services.ecs;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Frame;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.DockerAvailability;
import io.floci.aws.testing.TestImages;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.Container;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;
import software.amazon.awssdk.services.ecs.model.RunTaskResponse;
import software.amazon.awssdk.services.ecs.model.Task;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.floci.aws.testing.EcsTaskWaits.awaitLastStatus;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@QuarkusTest
@TestProfile(EcsEssentialContainerExitDockerIntegrationTest.DockerEcsProfile.class)
class EcsEssentialContainerExitDockerIntegrationTest {

    public static final class DockerEcsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.ecs.mock", "false",
                    "floci.services.ecs.reconcile-containers-on-startup", "false",
                    "floci.docker.resource-namespace", "ecs-essential-exit-it");
        }
    }

    @Inject
    DockerClient docker;

    private EcsClient client;
    private String cluster;
    private String definition;
    private String taskArn;

    @BeforeEach
    void setUp() {
        assumeTrue(DockerAvailability.answers(docker), "Docker must be available for the ECS exit test");
        client = AwsTestClients.create(EcsClient.builder(), "test", Region.US_EAST_1);
        String name = "essential-exit-" + UUID.randomUUID();
        cluster = client.createCluster(request -> request.clusterName(name)).cluster().clusterArn();
    }

    @AfterEach
    void cleanUp() {
        if (client == null) {
            return;
        }
        try {
            if (taskArn != null) {
                client.stopTask(request -> request.cluster(cluster).task(taskArn).reason("test cleanup"));
            }
            if (definition != null) {
                client.deregisterTaskDefinition(request -> request.taskDefinition(definition));
                client.deleteTaskDefinitions(request -> request.taskDefinitions(definition));
            }
            if (cluster != null) {
                client.deleteCluster(request -> request.cluster(cluster));
            }
        } finally {
            client.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void sdkReportsContainerExitsAndStopsSiblingsOnlyForAnEssentialExit(boolean appEssential) throws Exception {
        definition = client.registerTaskDefinition(request -> request.family("exit-" + UUID.randomUUID())
                .networkMode("bridge").containerDefinitions(
                        containerDefinition("app", appEssential, 17),
                        containerDefinition("sidecar", !appEssential, 0)))
                .taskDefinition().taskDefinitionArn();
        RunTaskResponse launched = client.runTask(request -> request.cluster(cluster).taskDefinition(definition).count(1));
        assertTrue(launched.failures().isEmpty(), launched.failures().toString());
        taskArn = launched.tasks().getFirst().taskArn();
        Task running = awaitLastStatus(this::describeTask, Task::lastStatus,
                Duration.ofSeconds(45), "RUNNING");
        String appId = container(running, "app").runtimeId();
        String sidecarId = container(running, "sidecar").runtimeId();

        release(appId);

        if (!appEssential) {
            await("the nonessential container to be reported stopped").atMost(Duration.ofSeconds(20))
                    .untilAsserted(() -> assertEquals("STOPPED", container(describeTask(), "app").lastStatus()));
            Task stillRunning = describeTask();
            assertEquals("RUNNING", stillRunning.lastStatus());
            assertEquals("RUNNING", container(stillRunning, "sidecar").lastStatus());
            assertEquals(17, container(stillRunning, "app").exitCode());
            // The task must retain the exit code even after Docker forgets this container.
            docker.removeContainerCmd(appId).exec();
            release(sidecarId);
        }

        Task stopped = awaitLastStatus(this::describeTask, Task::lastStatus,
                Duration.ofSeconds(45), "STOPPED");
        assertEquals("STOPPED", stopped.desiredStatus());
        assertEquals("EssentialContainerExited", stopped.stopCodeAsString());
        assertEquals("Essential container in task exited", stopped.stoppedReason());
        assertEquals("STOPPED", container(stopped, "app").lastStatus());
        assertEquals("STOPPED", container(stopped, "sidecar").lastStatus());
        assertEquals(17, container(stopped, "app").exitCode());
        assertThrows(NotFoundException.class, () -> docker.inspectContainerCmd(appId).exec());
        assertThrows(NotFoundException.class, () -> docker.inspectContainerCmd(sidecarId).exec());
    }

    private ContainerDefinition containerDefinition(String name, boolean essential, int exitCode) {
        return ContainerDefinition.builder().name(name).image(TestImages.BUSYBOX).essential(essential)
                .command("sh", "-c", "until [ -f /tmp/exit ]; do sleep 0.1; done; exit " + exitCode).build();
    }

    private Task describeTask() {
        return client.describeTasks(request -> request.cluster(cluster).tasks(taskArn)).tasks().getFirst();
    }

    private Container container(Task task, String name) {
        return task.containers().stream().filter(container -> name.equals(container.name())).findFirst().orElseThrow();
    }

    private void release(String containerId) throws Exception {
        String execId = docker.execCreateCmd(containerId).withCmd("touch", "/tmp/exit").exec().getId();
        try (ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>()) {
            assertTrue(docker.execStartCmd(execId).exec(callback).awaitCompletion(10, TimeUnit.SECONDS));
        }
    }
}
