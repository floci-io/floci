package io.github.hectorvent.floci.services.ecs.container;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
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
    void applicationStdoutReachesFirelensRouterThroughPublishedHostPort() throws Exception {
        String marker = "firelens-marker-" + UUID.randomUUID();

        ContainerDefinition app = new ContainerDefinition();
        app.setName("app");
        app.setImage(IMAGE);
        app.setCommand(List.of("sh", "-c", "echo " + marker + "; sleep 2"));
        app.setLogConfiguration(new LogConfiguration("awsfirelens", Map.of(), null));

        ContainerDefinition router = new ContainerDefinition();
        router.setName("router");
        router.setImage(IMAGE);
        router.setCommand(List.of("sh", "-c", "while true; do nc -l -p 24224 >> /tmp/received; done"));
        router.setFirelensConfiguration(new FirelensConfiguration("fluentbit", Map.of()));

        TaskDefinition taskDefinition = new TaskDefinition();
        taskDefinition.setFamily("firelens-integration");
        taskDefinition.setContainerDefinitions(List.of(app, router));

        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:us-east-1:000000000000:task/firelens/" + marker);

        taskHandle = containerManager.startTask(task, taskDefinition, List.of(), "us-east-1");

        assertEquals(List.of("router", "app"), taskHandle.getContainerIds().keySet().stream().toList());
        String appId = taskHandle.getContainerIds().get("app");
        String routerId = taskHandle.getContainerIds().get("router");
        dockerClient.waitContainerCmd(appId)
                .exec(new WaitContainerResultCallback())
                .awaitStatusCode(30, TimeUnit.SECONDS);

        String received = awaitRouterBuffer(routerId, marker);
        assertTrue(received.contains(marker), "FireLens router did not receive the app stdout record");
    }

    private String awaitRouterBuffer(String routerId, String marker) throws Exception {
        for (int attempt = 0; attempt < 30; attempt++) {
            ExecResult result = readRouterBuffer(routerId, marker);
            if (result.exitCode() == 0 && result.output().contains(marker)) {
                return result.output();
            }
            Thread.sleep(1000);
        }
        return readRouterBuffer(routerId, marker).output();
    }

    private ExecResult readRouterBuffer(String routerId, String marker) throws Exception {
        String execId = dockerClient.execCreateCmd(routerId)
                .withCmd("sh", "-c", "grep -a " + marker + " /tmp/received")
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec()
                .getId();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CountDownLatch complete = new CountDownLatch(1);
        Closeable callback = dockerClient.execStartCmd(execId).exec(new ExecStartResultCallback() {
            @Override
            public void onNext(Frame frame) {
                try {
                    if (frame.getStreamType() == StreamType.STDOUT) {
                        output.write(frame.getPayload());
                    }
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to capture FireLens output", e);
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
            assertTrue(complete.await(10, TimeUnit.SECONDS), "Timed out reading FireLens router buffer");
            long exitCode = dockerClient.inspectExecCmd(execId).exec().getExitCodeLong();
            return new ExecResult(exitCode, output.toString(StandardCharsets.UTF_8));
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

    private record ExecResult(long exitCode, String output) {
    }
}
