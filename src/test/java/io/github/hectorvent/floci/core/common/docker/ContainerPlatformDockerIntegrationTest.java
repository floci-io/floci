package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class ContainerPlatformDockerIntegrationTest {

    private static final Logger LOG = Logger.getLogger(ContainerPlatformDockerIntegrationTest.class);
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
                "Docker daemon must be available for container platform integration tests");
    }

    @Test
    void createUsesRequestedForeignPlatformImage() {
        String hostArchitecture = dockerClient.infoCmd().exec().getArchitecture()
                .toLowerCase(Locale.ROOT);
        boolean armHost = hostArchitecture.equals("arm64") || hostArchitecture.equals("aarch64");
        String requestedArchitecture = armHost ? "amd64" : "arm64";
        String platform = "linux/" + requestedArchitecture;
        ContainerSpec spec = containerBuilder.newContainer(IMAGE)
                .withName("floci-platform-test-" + UUID.randomUUID())
                .build();

        String containerId = null;
        try {
            containerId = lifecycleManager.create(spec, platform);
            String imageId = dockerClient.inspectContainerCmd(containerId).exec().getImageId();

            assertEquals(requestedArchitecture,
                    dockerClient.inspectImageCmd(imageId).exec().getArch());
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
            LOG.warn("Docker daemon is not available for the container platform integration test", e);
            return false;
        }
    }
}
