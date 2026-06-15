package io.github.hectorvent.floci.services.batch;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.services.batch.model.BatchJob;
import io.github.hectorvent.floci.services.batch.model.BatchRunResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class BatchDockerRunnerDockerIntegrationTest {

    private static final String IMAGE = "public.ecr.aws/docker/library/busybox:latest";

    @Inject
    BatchDockerRunner runner;

    @Inject
    DockerClient dockerClient;

    @Inject
    EmulatorConfig config;

    @Inject
    ContainerDetector containerDetector;

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker daemon must be available for Batch Docker runner tests");
    }

    @Test
    void dockerRunnerInjectsContainerReachableEndpoint() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String expectedHostname = containerDetector.isRunningInContainer()
                ? config.hostname().orElse(EmbeddedDnsServer.DEFAULT_SUFFIX)
                : "host.docker.internal";
        String expectedEndpoint = "http://" + expectedHostname + ":" + config.port();

        BatchJob job = new BatchJob();
        job.setJobId("docker-env-" + suffix);
        job.setJobName("docker-env-" + suffix);
        job.setJobDefinitionName("docker-env-test");
        job.setJobQueueName("docker-env-test-queue");
        job.setRegion(config.defaultRegion());
        job.setContainerImage(IMAGE);
        job.setResolvedCommand(List.of("sh", "-c", """
                test "$AWS_ENDPOINT_URL" = "%s" &&
                test "$FLOCI_ENDPOINT" = "%s" &&
                test "$FLOCI_HOSTNAME" = "%s"
                """.formatted(expectedEndpoint, expectedEndpoint, expectedHostname)));

        BatchRunResult result = runner.run(job, 1);

        assertEquals(0, result.exitCode(), result.reason());
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
