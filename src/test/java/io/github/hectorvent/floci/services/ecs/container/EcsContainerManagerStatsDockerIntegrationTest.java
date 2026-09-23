package io.github.hectorvent.floci.services.ecs.container;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Statistics;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sample behind the task metadata endpoint's {@code /stats} paths, taken from a real daemon.
 * AWS returns Docker's own stats document there, so what matters is that a sample arrives at all
 * and carries the counters a client reads; the serialized member names are pinned down in
 * {@code EcsTaskMetadataControllerTest}.
 */
@QuarkusTest
class EcsContainerManagerStatsDockerIntegrationTest {

    private static final String BUSYBOX_IMAGE = "public.ecr.aws/docker/library/busybox:latest";

    @Inject
    EcsContainerManager containerManager;

    @Inject
    DockerClient dockerClient;

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(isDockerAvailable(),
                "Docker daemon must be available for ECS stats integration tests");
    }

    @Test
    void aRunningContainerReportsTheDaemonsStats() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        TaskDefinition taskDefinition = new TaskDefinition();
        taskDefinition.setFamily("stats-docker-" + suffix);
        taskDefinition.setContainerDefinitions(List.of(sleeper("app")));

        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:us-east-1:000000000000:task/stats/" + suffix);

        EcsTaskHandle handle = containerManager.startTask(task, taskDefinition, List.of(), "us-east-1");
        try {
            Optional<EcsContainerManager.ContainerStats> sample =
                    containerManager.sampleContainerStats(handle.getContainerIds().get("app"));

            assertTrue(sample.isPresent(), "a running container must have stats to sample");
            Statistics statistics = sample.get().statistics();
            assertNotNull(statistics.getRead(), "the sample carries the instant Docker read it");
            assertNotNull(statistics.getCpuStats(), "the sample carries the container's CPU stats");
            assertNotNull(statistics.getMemoryStats(), "the sample carries the container's memory stats");
            assertNotNull(statistics.getPreCpuStats(),
                    "the second sample carries the previous CPU reading a client computes against");
            // A container on a Docker network reports interfaces, so the rate delta is measurable.
            assertNotNull(sample.get().rxBytesPerSecond(), "two samples make the receive rate measurable");
            assertNotNull(sample.get().txBytesPerSecond(), "two samples make the transmit rate measurable");
        } finally {
            containerManager.stopTask(handle);
        }
    }

    /**
     * The daemon holds a stats stream open per container, so a task's containers are sampled in one
     * pass with every stream open at once. That the real daemon serves them alongside each other is
     * what this covers; the cost of the pass is pinned down in {@code EcsContainerManagerStatsSamplingTest}.
     */
    @Test
    void aTasksContainersAreAllSampledInOnePass() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        TaskDefinition taskDefinition = new TaskDefinition();
        taskDefinition.setFamily("stats-pass-" + suffix);
        taskDefinition.setContainerDefinitions(List.of(
                sleeper("app"), sleeper("sidecar"), sleeper("logger")));

        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:us-east-1:000000000000:task/stats/" + suffix);

        EcsTaskHandle handle = containerManager.startTask(task, taskDefinition, List.of(), "us-east-1");
        try {
            List<String> dockerIds = List.of(handle.getContainerIds().get("app"),
                    handle.getContainerIds().get("sidecar"),
                    handle.getContainerIds().get("logger"));

            Map<String, EcsContainerManager.ContainerStats> sampled =
                    containerManager.sampleContainerStats(dockerIds);

            assertEquals(dockerIds.size(), sampled.size(), "every container in the task is sampled");
            for (String dockerId : dockerIds) {
                assertNotNull(sampled.get(dockerId).statistics().getRead(),
                        "each sample carries the instant Docker read it");
            }
        } finally {
            containerManager.stopTask(handle);
        }
    }

    @Test
    void aContainerThatIsGoneReportsNoSampleRatherThanFailing() {
        assertTrue(containerManager.sampleContainerStats("floci-no-such-container").isEmpty());
        assertTrue(containerManager.sampleContainerStats((String) null).isEmpty());
    }

    private static ContainerDefinition sleeper(String name) {
        ContainerDefinition definition = new ContainerDefinition();
        definition.setName(name);
        definition.setImage(BUSYBOX_IMAGE);
        // PID 1 gets no default SIGTERM handler, so a bare "sleep 60" sits out the whole
        // stopTimeout grace period on teardown, once per container in the task.
        definition.setCommand(List.of("sh", "-c", "trap 'exit 0' TERM; sleep 60 & wait"));
        return definition;
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
