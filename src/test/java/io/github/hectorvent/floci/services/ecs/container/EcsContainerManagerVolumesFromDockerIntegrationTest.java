package io.github.hectorvent.floci.services.ecs.container;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.core.command.BuildImageResultCallback;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
import io.github.hectorvent.floci.services.ecs.model.Container;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.github.hectorvent.floci.services.ecs.model.VolumeFrom;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Docker-backed regression coverage for ECS {@code volumesFrom}. The source image declares a
 * volume containing the consumer's entrypoint, matching the failure reported in #3806: without
 * Docker volume inheritance the app cannot even start because {@code /shared/wrapper} is absent.
 */
@QuarkusTest
class EcsContainerManagerVolumesFromDockerIntegrationTest {

    private static final String BUSYBOX_IMAGE = "public.ecr.aws/docker/library/busybox:latest";

    @Inject
    EcsContainerManager containerManager;

    @Inject
    DockerClient dockerClient;

    private String sourceImage;

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(isDockerAvailable(),
                "Docker daemon must be available for ECS volumesFrom integration tests");
    }

    @AfterEach
    void removeSourceImage() {
        if (sourceImage != null) {
            try {
                dockerClient.removeImageCmd(sourceImage).withForce(true).exec();
            } catch (NotFoundException ignored) {
                // A failed build may not have produced an image to remove.
            }
        }
    }

    @Test
    void appEntrypointCanComeFromSourceContainerVolume() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String sourceImageTag = "floci-ecs-volumes-from-test:" + suffix;
        buildSourceImage(sourceImageTag);
        sourceImage = sourceImageTag;

        ContainerDefinition app = definition("app", BUSYBOX_IMAGE);
        app.setEntryPoint(List.of("/shared/wrapper"));
        app.setCommand(List.of("sh", "-c", "test -x /shared/wrapper && echo volumes-from-ok"));
        app.setVolumesFrom(List.of(new VolumeFrom("source", true)));

        ContainerDefinition source = definition("source", sourceImage);
        source.setCommand(List.of("true"));

        TaskDefinition taskDefinition = new TaskDefinition();
        taskDefinition.setFamily("volumes-from-docker-" + suffix);
        // Put the consumer first to prove the manager derives launch order from volumesFrom,
        // while the ECS response still preserves task-definition order.
        taskDefinition.setContainerDefinitions(List.of(app, source));

        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:us-east-1:000000000000:task/volumes-from/" + suffix);

        EcsTaskHandle handle = containerManager.startTask(task, taskDefinition, List.of(), "us-east-1");
        try {
            String appId = handle.getContainerIds().get("app");
            Integer status = dockerClient.waitContainerCmd(appId)
                    .exec(new WaitContainerResultCallback())
                    .awaitStatusCode(60, TimeUnit.SECONDS);
            String output = logs(appId);

            assertEquals(0, status, output);
            assertTrue(output.contains("volumes-from-ok"), output);
            assertEquals(List.of("app", "source"),
                    task.getContainers().stream().map(Container::getName).toList());
        } finally {
            containerManager.stopTask(handle);
        }
    }

    private void buildSourceImage(String tag) throws Exception {
        String dockerfile = """
                FROM public.ecr.aws/docker/library/busybox:latest
                RUN mkdir -p /shared && printf '#!/bin/sh\\nexec "$@"\\n' > /shared/wrapper \
                    && chmod +x /shared/wrapper
                VOLUME ["/shared"]
                """;

        byte[] dockerfileBytes = dockerfile.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream context = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(context)) {
            TarArchiveEntry entry = new TarArchiveEntry("Dockerfile");
            entry.setSize(dockerfileBytes.length);
            tar.putArchiveEntry(entry);
            tar.write(dockerfileBytes);
            tar.closeArchiveEntry();
        }

        try (BuildImageResultCallback callback = new BuildImageResultCallback()) {
            String imageId = dockerClient.buildImageCmd(new ByteArrayInputStream(context.toByteArray()))
                    .withTags(Set.of(tag))
                    .withRemove(true)
                    .exec(callback)
                    .awaitImageId(180, TimeUnit.SECONDS);
            assertFalse(imageId == null || imageId.isBlank(), "Docker must return the built image ID");
        }
    }

    private String logs(String containerId) throws InterruptedException {
        StringBuilder output = new StringBuilder();
        dockerClient.logContainerCmd(containerId)
                .withStdOut(true)
                .withStdErr(true)
                .exec(new ResultCallback.Adapter<Frame>() {
                    @Override
                    public void onNext(Frame frame) {
                        output.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                    }
                })
                .awaitCompletion(10, TimeUnit.SECONDS);
        return output.toString();
    }

    private boolean isDockerAvailable() {
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static ContainerDefinition definition(String name, String image) {
        ContainerDefinition definition = new ContainerDefinition();
        definition.setName(name);
        definition.setImage(image);
        return definition;
    }
}
