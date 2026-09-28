package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Ports;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ContainerHostNetworkDockerIntegrationTest {

    private static final Logger LOG = Logger.getLogger(ContainerHostNetworkDockerIntegrationTest.class);
    private static final String IMAGE = "public.ecr.aws/docker/library/busybox:1.36";

    @Inject
    DockerClient dockerClient;

    @Inject
    ContainerBuilder containerBuilder;

    @Inject
    ContainerLifecycleManager lifecycleManager;

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(isDockerAvailable(),
                "Docker daemon must be available for host network integration tests");
    }

    @Test
    void portBoundContainerIsCreatedInHostNetworkMode() {
        ContainerSpec spec = containerBuilder.newContainer(IMAGE)
                .withName("floci-host-network-test-" + UUID.randomUUID())
                .withCmd(List.of("sleep", "30"))
                .withNetworkMode("host")
                .withPortBinding(8080, 18080)
                .build();

        String containerId = null;
        try {
            containerId = lifecycleManager.createAndStart(spec).containerId();

            InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
            assertEquals("host", inspect.getHostConfig().getNetworkMode());
            Ports bindings = inspect.getHostConfig().getPortBindings();
            assertTrue(bindings == null || bindings.getBindings().isEmpty(),
                    "host network mode publishes no ports, got " + bindings);
            assertTrue(inspect.getNetworkSettings().getNetworks().containsKey("host"),
                    "container should sit on the host network, got " + inspect.getNetworkSettings().getNetworks().keySet());
        } finally {
            if (containerId != null) {
                dockerClient.removeContainerCmd(containerId).withForce(true).exec();
            }
        }
    }

    private boolean isDockerAvailable() {
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (Exception e) {
            LOG.warn("Docker daemon is not available for the host network integration test", e);
            return false;
        }
    }
}
