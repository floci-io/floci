package io.github.hectorvent.floci.services.ecs.container;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.github.dockerjava.core.command.LogContainerResultCallback;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.FirelensConfiguration;
import io.github.hectorvent.floci.services.ecs.model.LogConfiguration;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class EcsContainerManagerFirelensDockerIntegrationTest {

    private static final String IMAGE = "public.ecr.aws/docker/library/busybox:latest";
    /** A real FireLens router image: Floci writes the generated config to its own
     * {@code /fluent-bit/etc}, which no minimal image carries. */
    private static final String ROUTER_IMAGE = "public.ecr.aws/aws-observability/aws-for-fluent-bit:3";

    @Inject
    EcsContainerManager containerManager;

    @Inject
    DockerClient dockerClient;

    private EcsTaskHandle taskHandle;

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(isDockerAvailable(),
                "Docker daemon must be available for FireLens integration tests");
    }

    @AfterEach
    void cleanUpTask() {
        if (taskHandle != null) {
            containerManager.stopTask(taskHandle);
            taskHandle = null;
        }
    }

    @Test
    void applicationRecordReachesTheRouterThroughTheGeneratedConfig() throws Exception {
        String marker = "firelens-marker-" + UUID.randomUUID();

        ContainerDefinition app = new ContainerDefinition();
        app.setName("app");
        app.setImage(IMAGE);
        app.setCommand(List.of("sh", "-c", "while true; do echo " + marker + "; sleep 1; done"));
        app.setLogConfiguration(new LogConfiguration("awsfirelens", Map.of("Name", "stdout"), null));

        ContainerDefinition router = new ContainerDefinition();
        router.setName("router");
        router.setImage(ROUTER_IMAGE);
        router.setFirelensConfiguration(new FirelensConfiguration("fluentbit", Map.of()));

        TaskDefinition taskDefinition = new TaskDefinition();
        taskDefinition.setFamily("firelens-integration");
        taskDefinition.setContainerDefinitions(List.of(app, router));

        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:us-east-1:000000000000:task/firelens/" + UUID.randomUUID());

        taskHandle = containerManager.startTask(task, taskDefinition, List.of(), "us-east-1");

        assertEquals(List.of("router", "app"), taskHandle.getContainerIds().keySet().stream().toList());
        String appId = taskHandle.getContainerIds().get("app");
        String routerId = taskHandle.getContainerIds().get("router");

        // The Docker daemon, not the application container, makes this connection, so the
        // address has to be a path on the host: the socket the router creates inside the
        // task's own volume, which the daemon reaches at the volume's mountpoint.
        assertEquals("unix://" + firelensVolumeMountpoint() + "/fluent.sock", appLogDriverAddress(appId));

        String routerLogs = awaitRouterLogs(routerId, marker);
        assertTrue(routerLogs.contains(marker),
                "FireLens router did not print the application record routed by the generated config: "
                        + routerLogs);
        assertTrue(routerLogs.contains("ecs_task_arn"),
                "The generated config's ECS metadata filter did not run: " + routerLogs);
    }

    private String firelensVolumeMountpoint() throws Exception {
        return dockerClient.inspectVolumeCmd(taskHandle.getFirelensVolumeName())
                .exec()
                .getMountpoint();
    }

    private String appLogDriverAddress(String appId) {
        return dockerClient.inspectContainerCmd(appId)
                .exec()
                .getHostConfig()
                .getLogConfig()
                .getConfig()
                .get("fluentd-address");
    }

    private String awaitRouterLogs(String routerId, String marker) throws Exception {
        String logs = "";
        for (int attempt = 0; attempt < 30; attempt++) {
            logs = readContainerLogs(routerId);
            if (logs.contains(marker)) {
                return logs;
            }
            Thread.sleep(1000);
        }
        return logs;
    }

    private String readContainerLogs(String containerId) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CountDownLatch complete = new CountDownLatch(1);
        Closeable callback = dockerClient.logContainerCmd(containerId)
                .withStdOut(true)
                .withStdErr(true)
                .exec(new LogContainerResultCallback() {
                    @Override
                    public void onNext(Frame frame) {
                        try {
                            if (frame.getStreamType() == StreamType.STDOUT
                                    || frame.getStreamType() == StreamType.STDERR) {
                                output.write(frame.getPayload());
                            }
                        } catch (IOException e) {
                            throw new IllegalStateException("Failed to capture router logs", e);
                        }
                    }

                    @Override
                    public void onComplete() {
                        complete.countDown();
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        complete.countDown();
                    }
                });
        try {
            complete.await(10, TimeUnit.SECONDS);
            return output.toString(StandardCharsets.UTF_8);
        } finally {
            callback.close();
        }
    }

    private boolean isDockerAvailable() {
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

}
