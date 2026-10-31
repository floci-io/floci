package io.github.hectorvent.floci.services.ecs.container;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Event;
import com.github.dockerjava.api.model.EventType;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.command.BuildImageResultCallback;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.services.ecs.model.Container;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.ContainerDependency;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.github.hectorvent.floci.services.ecs.model.VolumeFrom;
import io.github.hectorvent.floci.testing.TestImages;
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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Docker-backed regression coverage for ECS {@code volumesFrom}. The source image declares a
 * volume containing the consumer's entrypoint, matching the failure reported in #3806: without
 * Docker volume inheritance the app cannot even start because {@code /shared/wrapper} is absent.
 */
@QuarkusTest
class EcsContainerManagerVolumesFromDockerIntegrationTest {

    private static final String BUSYBOX_IMAGE = TestImages.BUSYBOX;

    @Inject
    EcsContainerManager containerManager;

    @Inject
    DockerClient dockerClient;

    @Inject
    EmulatorConfig config;

    private String sourceImage;
    private final List<String> ownedVolumes = new ArrayList<>();

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(isDockerAvailable(),
                "Docker daemon must be available for ECS volumesFrom integration tests");
    }

    @AfterEach
    void removeSourceImage() {
        try {
            for (String volume : ownedVolumes) {
                try {
                    dockerClient.removeVolumeCmd(volume).exec();
                } catch (NotFoundException ignored) {
                    // Successful task teardown already removed its anonymous volume.
                }
            }
        } finally {
            if (sourceImage != null) {
                try {
                    dockerClient.removeImageCmd(sourceImage).withForce(true).exec();
                } catch (NotFoundException ignored) {
                    // A failed build may not have produced an image to remove.
                }
            }
        }
    }

    @Test
    void appEntrypointCanComeFromSourceContainerVolume() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String sourceImageTag = "floci-ecs-volumes-from-test:" + suffix;
        sourceImage = sourceImageTag;
        buildSourceImage(sourceImageTag);

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
        String sharedVolume;
        try {
            String sourceId = handle.getContainerIds().get("source");
            sharedVolume = dockerClient.inspectContainerCmd(sourceId).exec().getMounts().stream()
                    .filter(mount -> "/shared".equals(mount.getDestination().getPath()))
                    .findFirst().orElseThrow().getName();
            ownedVolumes.add(sharedVolume);
            String appId = handle.getContainerIds().get("app");
            Integer status;
            try (WaitContainerResultCallback callback = new WaitContainerResultCallback()) {
                status = dockerClient.waitContainerCmd(appId).exec(callback)
                        .awaitStatusCode(60, TimeUnit.SECONDS);
            }
            String output = logs(appId);

            assertEquals(0, status, output);
            assertTrue(output.contains("volumes-from-ok"), output);
            assertEquals(List.of("app", "source"),
                    task.getContainers().stream().map(Container::getName).toList());
        } finally {
            containerManager.stopTask(handle);
        }
        assertThrows(NotFoundException.class, () -> dockerClient.inspectVolumeCmd(sharedVolume).exec(),
                "stopping every container must reclaim the inherited anonymous volume");
    }

    @Test
    void stoppingTaskPreservesAnExplicitlyNamedVolume() {
        String volume = "floci-ecs-named-volume-test-" + UUID.randomUUID();
        ownedVolumes.add(volume);
        dockerClient.createVolumeCmd().withName(volume).exec();
        String containerId = dockerClient.createContainerCmd(BUSYBOX_IMAGE)
                .withHostConfig(HostConfig.newHostConfig().withBinds(new Bind(volume, new Volume("/data"))))
                .withCmd("true").exec().getId();
        try {
            dockerClient.startContainerCmd(containerId).exec();
            containerManager.stopTask(new EcsTaskHandle("named-volume-task",
                    Map.of("app", containerId), Map.of()));
            assertEquals(volume, dockerClient.inspectVolumeCmd(volume).exec().getName());
            assertThrows(NotFoundException.class, () -> dockerClient.inspectContainerCmd(containerId).exec());
        } finally {
            try {
                dockerClient.removeContainerCmd(containerId).withForce(true).withRemoveVolumes(true).exec();
            } catch (NotFoundException ignored) {
                // Successful task teardown already removed the container.
            }
        }
    }

    @Test
    void cleanupStoppedTaskReclaimsAnonymousVolumeAndPreservesNamedVolume() throws Exception {
        assertStoppedContainerVolumesCleaned(false);
    }

    @Test
    void previousRunCleanupReclaimsAnonymousVolumeAndPreservesNamedVolume() throws Exception {
        assertStoppedContainerVolumesCleaned(true);
    }

    private void assertStoppedContainerVolumesCleaned(boolean previousRun) throws Exception {
        String suffix = UUID.randomUUID().toString();
        sourceImage = "floci-ecs-volumes-from-test:" + suffix;
        buildSourceImage(sourceImage);
        String namedVolume = "floci-ecs-named-volume-test-" + suffix;
        ownedVolumes.add(namedVolume);
        dockerClient.createVolumeCmd().withName(namedVolume).exec();
        String containerId = dockerClient.createContainerCmd(sourceImage)
                .withLabels(Map.of(ContainerStorageHelper.SERVICE_LABEL, "ecs",
                        ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config),
                        EcsContainerManager.RUN_LABEL, "previous-" + suffix))
                .withHostConfig(HostConfig.newHostConfig()
                        .withBinds(new Bind(namedVolume, new Volume("/data"))))
                .withCmd("true").exec().getId();
        try {
            String anonymousVolume = dockerClient.inspectContainerCmd(containerId).exec().getMounts().stream()
                    .filter(mount -> "/shared".equals(mount.getDestination().getPath()))
                    .findFirst().orElseThrow().getName();
            ownedVolumes.add(anonymousVolume);
            dockerClient.startContainerCmd(containerId).exec();
            try (WaitContainerResultCallback callback = new WaitContainerResultCallback()) {
                assertEquals(0, dockerClient.waitContainerCmd(containerId).exec(callback)
                        .awaitStatusCode(60, TimeUnit.SECONDS));
            }
            if (previousRun) {
                assertTrue(containerManager.removeLeftoverContainers("current-" + suffix));
            } else {
                containerManager.cleanupStoppedTask(new EcsTaskHandle("stopped-volume-task-" + suffix,
                        Map.of("app", containerId), Map.of()));
            }
            assertThrows(NotFoundException.class, () -> dockerClient.inspectContainerCmd(containerId).exec());
            assertEquals(namedVolume, dockerClient.inspectVolumeCmd(namedVolume).exec().getName());
            assertThrows(NotFoundException.class, () -> dockerClient.inspectVolumeCmd(anonymousVolume).exec(),
                    "cleanup must reclaim image-declared anonymous volumes after the container exits");
        } finally {
            try {
                dockerClient.removeContainerCmd(containerId).withForce(true).withRemoveVolumes(true).exec();
            } catch (NotFoundException ignored) {
                // The cleanup path under test already removed this container.
            }
        }
    }

    @Test
    void failedDependencyReclaimsTheObservedSourceContainersAnonymousVolume() throws Exception {
        String suffix = UUID.randomUUID().toString();
        sourceImage = "floci-ecs-volumes-from-test:" + suffix;
        buildSourceImage(sourceImage);
        ContainerDefinition source = definition("source", sourceImage);
        source.setCommand(List.of("sleep", "300"));
        ContainerDefinition app = definition("app", BUSYBOX_IMAGE);
        app.setCommand(List.of("true"));
        app.setDependsOn(List.of(new ContainerDependency("source", ContainerDependency.SUCCESS)));
        app.setStartTimeout(60);
        TaskDefinition definition = new TaskDefinition();
        definition.setFamily("failed-dependency-" + suffix);
        definition.setContainerDefinitions(List.of(app, source));
        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:us-east-1:000000000000:task/cleanup/" + suffix);
        String sourceName = ContainerStorageHelper.dockerName(config, "ecs-" + suffix + "-source");
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<String> observedContainer = new AtomicReference<>();
        try (ResultCallback.Adapter<Event> events = dockerClient.eventsCmd()
                .withEventTypeFilter(EventType.CONTAINER)
                .withEventFilter("start")
                .withSince(String.valueOf(Instant.now().getEpochSecond()))
                .exec(new ResultCallback.Adapter<Event>() {
                    @Override
                    public void onNext(Event event) {
                        if (event.getActor() != null
                                && sourceName.equals(event.getActor().getAttributes().get("name"))) {
                            observedContainer.set(event.getActor().getId());
                            started.countDown();
                        }
                    }
                });
                ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<EcsTaskHandle> launch = executor.submit(
                    () -> containerManager.startTask(task, definition, List.of(), "us-east-1"));
            try {
                assertTrue(started.await(60, TimeUnit.SECONDS), "the owned source container must start");
                String containerId = observedContainer.get();
                String anonymousVolume = dockerClient.inspectContainerCmd(containerId).exec().getMounts().stream()
                        .filter(mount -> "/shared".equals(mount.getDestination().getPath()))
                        .findFirst().orElseThrow().getName();
                ownedVolumes.add(anonymousVolume);
                dockerClient.killContainerCmd(containerId).exec();
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> launch.get(60, TimeUnit.SECONDS));
                AwsException cause = assertInstanceOf(AwsException.class, failure.getCause());
                assertTrue(cause.getMessage().contains("SUCCESS"), cause.getMessage());
                assertThrows(NotFoundException.class, () -> dockerClient.inspectContainerCmd(containerId).exec());
                assertThrows(NotFoundException.class, () -> dockerClient.inspectVolumeCmd(anonymousVolume).exec(),
                        "failed partial launch must reclaim its source container's anonymous volume");
            } finally {
                launch.cancel(true);
            }
        } finally {
            String containerId = observedContainer.get();
            if (containerId != null) {
                try {
                    dockerClient.removeContainerCmd(containerId).withForce(true).withRemoveVolumes(true).exec();
                } catch (NotFoundException ignored) {
                    // Successful rollback already removed the observed source container.
                }
            }
        }
    }

    @Test
    void rejectedContainerStartReclaimsItsAnonymousVolume() throws Exception {
        String suffix = UUID.randomUUID().toString();
        sourceImage = "floci-ecs-volumes-from-test:" + suffix;
        buildSourceImage(sourceImage);
        ContainerDefinition app = definition("app", sourceImage);
        app.setEntryPoint(List.of("/missing-entrypoint-" + suffix));
        TaskDefinition definition = new TaskDefinition();
        definition.setFamily("rejected-start-" + suffix);
        definition.setContainerDefinitions(List.of(app));
        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:us-east-1:000000000000:task/rejected-start/" + suffix);
        String containerName = ContainerStorageHelper.dockerName(config, "ecs-" + suffix + "-app");
        AtomicReference<String> observedContainer = new AtomicReference<>();
        AtomicReference<String> observedVolume = new AtomicReference<>();
        CountDownLatch mounted = new CountDownLatch(1);
        try (ResultCallback.Adapter<Event> events = dockerClient.eventsCmd()
                .withSince(String.valueOf(Instant.now().getEpochSecond()))
                .exec(new ResultCallback.Adapter<Event>() {
                    @Override
                    public void onNext(Event event) {
                        if (event.getActor() == null) {
                            return;
                        }
                        Map<String, String> attributes = event.getActor().getAttributes();
                        if (event.getType() == EventType.CONTAINER
                                && "create".equals(event.getAction())
                                && containerName.equals(attributes.get("name"))) {
                            observedContainer.set(event.getActor().getId());
                        }
                        if (event.getType() == EventType.VOLUME
                                && "mount".equals(event.getAction())
                                && attributes.get("container") != null
                                && attributes.get("container").equals(observedContainer.get())) {
                            observedVolume.set(event.getActor().getId());
                            mounted.countDown();
                        }
                    }
                })) {
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> containerManager.startTask(task, definition, List.of(), "us-east-1"));
            assertTrue(failure.getMessage().contains("missing-entrypoint-" + suffix), failure.getMessage());
            assertTrue(mounted.await(30, TimeUnit.SECONDS), "observe the owned rejected container's volume mount");
            String anonymousVolume = observedVolume.get();
            ownedVolumes.add(anonymousVolume);
            assertThrows(NotFoundException.class,
                    () -> dockerClient.inspectContainerCmd(observedContainer.get()).exec());
            assertThrows(NotFoundException.class, () -> dockerClient.inspectVolumeCmd(anonymousVolume).exec(),
                    "rejected start must remove its anonymous volume before ECS has a task handle");
        } finally {
            String containerId = observedContainer.get();
            if (containerId != null) {
                try {
                    dockerClient.removeContainerCmd(containerId).withForce(true).withRemoveVolumes(true).exec();
                } catch (NotFoundException ignored) {
                    // Failed-start cleanup already removed this owned container.
                }
            }
        }
    }

    private void buildSourceImage(String tag) throws Exception {
        String dockerfile = """
                FROM %s
                RUN mkdir -p /shared && printf '#!/bin/sh\\nexec "$@"\\n' > /shared/wrapper \
                    && chmod +x /shared/wrapper
                VOLUME ["/shared"]
                """.formatted(TestImages.BUSYBOX);

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

    private String logs(String containerId) throws InterruptedException, IOException {
        StringBuilder output = new StringBuilder();
        try (ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
            @Override
            public void onNext(Frame frame) {
                output.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
            }
        }) {
            dockerClient.logContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .exec(callback)
                    .awaitCompletion(10, TimeUnit.SECONDS);
        }
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
