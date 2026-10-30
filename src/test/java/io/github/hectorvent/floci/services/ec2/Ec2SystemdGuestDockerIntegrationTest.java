package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerReachableEndpoint;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.common.docker.PortAllocator;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.net.VpcNetworkManager;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import io.github.hectorvent.floci.testing.DockerAvailability;
import io.github.hectorvent.floci.testing.TestImages;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

/**
 * A systemd guest sees the Docker host's module tree, read-only. The launch goes through the real
 * {@link Ec2ContainerManager} container spec and a real Docker daemon. The image is Alpine rather
 * than a systemd cloud image, which is too heavy to pull in CI: the systemd runtime only decides
 * the container's command and mounts, and Alpine's busybox {@code /sbin/init} stays up as PID 1.
 */
@QuarkusTest
class Ec2SystemdGuestDockerIntegrationTest {

    private static final String REGION = "us-east-1";

    @Inject
    DockerClient docker;

    @Inject
    ContainerBuilder containerBuilder;

    @Inject
    ContainerLifecycleManager lifecycleManager;

    @Inject
    DockerHostResolver dockerHostResolver;

    @Inject
    PortAllocator portAllocator;

    @Inject
    EmulatorConfig config;

    @Inject
    RegionResolver regionResolver;

    @Inject
    ContainerReachableEndpoint reachableEndpoint;

    @BeforeEach
    void requireDocker() throws InterruptedException {
        assumeTrue(DockerAvailability.isAvailable(), "Docker must be available for EC2 systemd guest tests");
    }

    @Test
    void systemdGuestSeesHostModulesReadOnly() {
        Ec2ContainerManager manager = new Ec2ContainerManager(containerBuilder, lifecycleManager,
                mock(ContainerLogStreamer.class), mock(ContainerDetector.class), dockerHostResolver, docker,
                portAllocator, config, mock(Ec2MetadataServer.class), mock(Ec2PortForwardManager.class),
                regionResolver, mock(ContainerNetworkReachability.class), mock(VpcNetworkManager.class),
                reachableEndpoint);
        Instance instance = new Instance();
        instance.setInstanceId("i-systemd" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        instance.setRegion(REGION);
        try {
            manager.launch(instance,
                    new ResolvedAmiImage(TestImages.PYTHON_ALPINE, ResolvedAmiImage.SYSTEMD_RUNTIME, false, null),
                    null, REGION);
            await().atMost(Duration.ofSeconds(60)).until(() -> instance.getDockerContainerId() != null
                    && lifecycleManager.isContainerRunning(instance.getDockerContainerId()));
            String containerId = instance.getDockerContainerId();

            InspectContainerResponse.Mount modules = docker.inspectContainerCmd(containerId).exec().getMounts()
                    .stream().filter(m -> m.getDestination() != null
                            && "/lib/modules".equals(m.getDestination().getPath()))
                    .findFirst().orElse(null);
            assertNotNull(modules, "systemd guest has no /lib/modules mount");
            assertFalse(modules.getRW(), "/lib/modules must be mounted read-only");

            ContainerExec.Result inside = ContainerExec.runMerged(docker, containerId,
                    new String[]{"sh", "-c", "grep ' /lib/modules ' /proc/mounts"}, 30);
            assertEquals(0, inside.exitCode(), "guest cannot see /lib/modules: " + inside.summary());
            assertTrue(inside.stdout().matches("(?s).* /lib/modules \\S+ ro[, ].*"),
                    "guest's /lib/modules is not read-only: " + inside.stdout());
        } finally {
            if (instance.getDockerContainerId() != null) {
                docker.removeContainerCmd(instance.getDockerContainerId()).withForce(true).exec();
            }
        }
    }
}
