package io.github.hectorvent.floci.services.ecs;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.services.ecs.model.NetworkMode;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.github.hectorvent.floci.testing.TestImages;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Floci that ends without a graceful shutdown leaves its task containers running, and the next
 * run knows no task, since task state is memory-only. Those containers must go at startup, or the
 * service scheduler starts a second task beside each one. The sweep recognises them by the owner
 * label a task container is created with, so a container of another Floci sharing the daemon stays,
 * and tells this run's containers from a previous run's by the run label rather than by time.
 */
@QuarkusTest
@TestProfile(EcsServiceDiscoveryDockerIntegrationTest.DockerEcsProfile.class)
class EcsLeftoverContainersDockerIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String BUSYBOX_IMAGE = TestImages.BUSYBOX;

    @Inject
    EcsService ecsService;

    @Inject
    EcsContainerManager containerManager;

    @Inject
    EmulatorConfig config;

    @Inject
    DockerClient dockerClient;

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(isDockerAvailable(),
                "Docker daemon must be available for the ECS leftover container test");
    }

    @Test
    void theStartupSweepRemovesTaskContainersAndKeepsAnotherFlocisContainers() {
        String clusterName = unique("leftover-cluster");
        ecsService.createCluster(clusterName, REGION);
        ContainerDefinition app = new ContainerDefinition();
        app.setName("app");
        app.setImage(BUSYBOX_IMAGE);
        app.setCommand(List.of("sleep", "120"));
        TaskDefinition taskDef = ecsService.registerTaskDefinition(unique("leftover-td"), List.of(app),
                NetworkMode.bridge, null, null, null, null, null, REGION);

        EcsTask task = ecsService.runTask(clusterName, taskDef.getTaskDefinitionArn(), 1, LaunchType.EC2, null, null,
                null, null, REGION).getFirst();
        String foreignId = null;
        String previousRunId = null;
        try {
            String taskContainerId = task.getContainers().getFirst().getDockerId();
            assertNotNull(taskContainerId, "the task must have started a container: " + task.getStoppedReason());
            assertEquals(ContainerStorageHelper.ownerIdentity(config), labels(taskContainerId)
                    .get(ContainerStorageHelper.OWNER_LABEL), "a task container must carry its owner");

            foreignId = createEcsContainer(Map.of(ContainerStorageHelper.OWNER_LABEL, "another-floci/4566"));
            // Created a moment ago, as by a run killed within the second this one started.
            previousRunId = createEcsContainer(Map.of(ContainerStorageHelper.OWNER_LABEL,
                    ContainerStorageHelper.ownerIdentity(config), EcsContainerManager.RUN_LABEL, "a-previous-run"));

            assertTrue(containerManager.removeLeftoverContainers(), "the sweep must succeed");
            assertTrue(exists(taskContainerId), "a task container this run started must stay");
            assertFalse(exists(previousRunId), "a previous run's container must be removed however recent");

            assertTrue(containerManager.removeLeftoverContainers("the-next-run"), "the sweep must succeed");
            assertFalse(exists(taskContainerId), "the leftover task container must be removed");
            assertTrue(exists(foreignId), "another Floci's container must stay");
        } finally {
            ecsService.stopTask(clusterName, task.getTaskArn(), "test teardown", REGION);
            for (String containerId : new String[] {foreignId, previousRunId}) {
                if (containerId != null && exists(containerId)) {
                    dockerClient.removeContainerCmd(containerId).withForce(true).exec();
                }
            }
        }
    }

    /** An ECS container carrying {@code ownership}, as another Floci or another run creates. */
    private String createEcsContainer(Map<String, String> ownership) {
        Map<String, String> labels = new HashMap<>(ownership);
        labels.put("io.floci.service", "ecs");
        CreateContainerResponse created = dockerClient.createContainerCmd(BUSYBOX_IMAGE)
                .withName(unique("floci-other-ecs"))
                .withCmd("sleep", "120")
                .withLabels(labels)
                .exec();
        return created.getId();
    }

    private Map<String, String> labels(String containerId) {
        return dockerClient.inspectContainerCmd(containerId).exec().getConfig().getLabels();
    }

    private boolean exists(String containerId) {
        try {
            dockerClient.inspectContainerCmd(containerId).exec();
            return true;
        } catch (NotFoundException e) {
            return false;
        }
    }

    private boolean isDockerAvailable() {
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
