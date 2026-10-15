package io.github.hectorvent.floci.services.ecs.container;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse.Mount;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.MountPoint;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.github.hectorvent.floci.services.ecs.model.Volume;
import io.github.hectorvent.floci.testing.TestImages;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(EcsContainerManagerEphemeralVolumesDockerIntegrationTest.VolumeProfile.class)
class EcsContainerManagerEphemeralVolumesDockerIntegrationTest {

    @Inject
    EcsContainerManager manager;

    @Inject
    DockerClient dockerClient;

    private final List<EcsTaskHandle> handles = new ArrayList<>();

    public static class VolumeProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.docker.resource-namespace", "ecs-ephemeral-volumes-test");
        }
    }

    @BeforeEach
    void requireDocker() {
        try {
            dockerClient.pingCmd().exec();
        } catch (Exception unavailable) {
            Assumptions.abort("Docker daemon must be available for ephemeral volume integration tests");
        }
    }

    @AfterEach
    void cleanUpTasks() {
        handles.forEach(manager::stopTask);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void containersShareEphemeralStorageWithinATaskAndRemoveItOnTeardown(boolean emptyHost) throws Exception {
        EcsTaskHandle first = startTask("first-task", emptyHost, 300);
        assertEquals(0, waitFor(first, "reader"), "the reader must see the writer's file through the shared mount");
        Mount firstWriter = dataMount(first, "writer");
        Mount firstReader = dataMount(first, "reader");
        assertEquals(firstWriter.getName(), firstReader.getName());
        assertEquals(Boolean.TRUE, firstWriter.getRW());
        assertEquals(Boolean.FALSE, firstReader.getRW());

        EcsTaskHandle second = startTask("second-task", emptyHost, 1);
        assertEquals(0, waitFor(second, "reader"), "a second task must get its own empty volume");
        Mount secondWriter = dataMount(second, "writer");
        assertNotEquals(firstWriter.getName(), secondWriter.getName());
        assertEquals(0, waitFor(second, "writer"));

        manager.stopTask(first);
        handles.remove(first);
        assertThrows(NotFoundException.class, () -> dockerClient.inspectVolumeCmd(firstWriter.getName()).exec());
        dockerClient.inspectVolumeCmd(secondWriter.getName()).exec();

        manager.cleanupStoppedTask(second);
        handles.remove(second);
        assertThrows(NotFoundException.class, () -> dockerClient.inspectVolumeCmd(secondWriter.getName()).exec());
    }

    @Test
    void nextRunRemovesOrphanVolumesAndContainersWithMatchingOwnership() {
        EcsTaskHandle task = startTask("orphan-task", false, 300);
        String volumeName = task.getTaskVolumeNames().iterator().next();
        Map<String, String> labels = dockerClient.inspectVolumeCmd(volumeName).exec().getLabels();
        assertEquals("ecs", labels.get(ContainerStorageHelper.SERVICE_LABEL));
        assertEquals("ecs-ephemeral-volumes-test/4566", labels.get(ContainerStorageHelper.OWNER_LABEL));
        assertTrue(labels.containsKey(EcsContainerManager.RUN_LABEL));
        try {
            String nextRun = "next-run-" + UUID.randomUUID();
            assertTrue(manager.removeLeftoverContainers(nextRun));
            dockerClient.inspectVolumeCmd(volumeName).exec();
            assertTrue(manager.removeLeftoverTaskVolumes(nextRun));
            assertThrows(NotFoundException.class, () -> dockerClient.inspectVolumeCmd(volumeName).exec());
            for (String containerId : task.getContainerIds().values()) {
                assertThrows(NotFoundException.class, () -> dockerClient.inspectContainerCmd(containerId).exec());
            }
        } finally {
            manager.stopTask(task);
            handles.remove(task);
        }
    }

    private EcsTaskHandle startTask(String marker, boolean emptyHost, int writerSleep) {
        ContainerDefinition writer = definition("writer", false);
        writer.setCommand(List.of("sh", "-c",
                "test ! -e /data/marker || exit 41; echo " + marker + " > /data/marker; exec sleep " + writerSleep));
        ContainerDefinition reader = definition("reader", true);
        reader.setCommand(List.of("sh", "-c", """
                for attempt in 1 2 3 4 5; do
                    if [ "$(cat /data/marker 2>/dev/null)" = "%s" ]; then exit 0; fi
                    sleep 1
                done
                exit 42
                """.formatted(marker)));
        TaskDefinition taskDefinition = new TaskDefinition();
        taskDefinition.setFamily("ephemeral-volume-test");
        taskDefinition.setContainerDefinitions(List.of(writer, reader));
        taskDefinition.setVolumes(List.of(new Volume("shared", null, null,
                emptyHost ? Map.of("host", Map.of()) : null)));
        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:us-east-1:000000000000:task/ephemeral-volume-test/" + UUID.randomUUID());
        EcsTaskHandle handle = manager.startTask(task, taskDefinition, List.of(), "us-east-1");
        handles.add(handle);
        return handle;
    }

    private static ContainerDefinition definition(String name, boolean readOnly) {
        ContainerDefinition definition = new ContainerDefinition();
        definition.setName(name);
        definition.setImage(TestImages.BUSYBOX);
        definition.setStopTimeout(1);
        definition.setMountPoints(List.of(new MountPoint("shared", "/data", readOnly)));
        return definition;
    }

    private Mount dataMount(EcsTaskHandle handle, String container) {
        return dockerClient.inspectContainerCmd(handle.getContainerIds().get(container)).exec().getMounts().stream()
                .filter(mount -> "/data".equals(mount.getDestination().getPath())).findFirst().orElseThrow();
    }

    private Integer waitFor(EcsTaskHandle handle, String container) throws Exception {
        try (WaitContainerResultCallback callback = new WaitContainerResultCallback()) {
            return dockerClient.waitContainerCmd(handle.getContainerIds().get(container)).exec(callback)
                    .awaitStatusCode(20, TimeUnit.SECONDS);
        }
    }
}
