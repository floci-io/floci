package io.floci.aws.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.ContainerReachableEndpoint;
import io.floci.aws.core.common.docker.DockerHostResolver;
import io.floci.aws.core.common.docker.PortAllocator;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.net.VpcNetworkManager;
import io.floci.aws.services.ec2.portforward.Ec2PortForwardManager;
import io.floci.aws.testing.DockerAvailability;
import io.floci.aws.testing.TestImages;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

/**
 * A systemd guest sees the Docker host's module tree, read-only, and runs in a private cgroup
 * namespace. The launch goes through the real {@link Ec2ContainerManager} container spec and a
 * real Docker daemon. The image is Alpine rather
 * than a systemd cloud image, which is too heavy to pull in CI: the systemd runtime only decides
 * the container's command and mounts, and Alpine's busybox {@code /sbin/init} stays up as PID 1.
 */
@QuarkusTest
class Ec2SystemdGuestDockerIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final Logger LOG = Logger.getLogger(Ec2SystemdGuestDockerIntegrationTest.class);

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
    void requireDocker() {
        assumeTrue(DockerAvailability.answers(docker), "Docker must be available for EC2 systemd guest tests");
    }

    @Test
    void systemdGuestSeesHostModulesReadOnlyInPrivateCgroupNamespace() {
        Ec2ContainerManager manager = new Ec2ContainerManager(containerBuilder, lifecycleManager,
                mock(ContainerLogStreamer.class), mock(ContainerDetector.class), dockerHostResolver, docker,
                portAllocator, config, mock(Ec2MetadataServer.class), mock(Ec2PortForwardManager.class),
                regionResolver, mock(ContainerNetworkReachability.class), mock(VpcNetworkManager.class),
                reachableEndpoint);
        Instance instance = new Instance();
        instance.setInstanceId("i-systemd" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        instance.setRegion(REGION);
        String modulesDir = "/lib/modules/" + docker.infoCmd().exec().getKernelVersion();
        try {
            manager.launch(instance,
                    new ResolvedAmiImage(TestImages.PYTHON_ALPINE, ResolvedAmiImage.SYSTEMD_RUNTIME, false, null),
                    null, REGION);
            await().atMost(Duration.ofSeconds(60)).until(() -> instance.getDockerContainerId() != null
                    && lifecycleManager.isContainerRunning(instance.getDockerContainerId()));
            String containerId = instance.getDockerContainerId();

            InspectContainerResponse.Mount modules = docker.inspectContainerCmd(containerId).exec().getMounts()
                    .stream().filter(m -> m.getDestination() != null
                            && modulesDir.equals(m.getDestination().getPath()))
                    .findFirst().orElse(null);
            assertNotNull(modules, "systemd guest has no " + modulesDir + " mount");
            assertFalse(modules.getRW(), modulesDir + " must be mounted read-only");

            ContainerExec.Result inside = ContainerExec.runMerged(docker, containerId,
                    new String[]{"sh", "-c", "grep ' " + modulesDir + " ' /proc/mounts"}, 30);
            assertEquals(0, inside.exitCode(), "guest cannot see " + modulesDir + ": " + inside.summary());
            assertTrue(inside.stdout().matches("(?s).* " + Pattern.quote(modulesDir) + " \\S+ ro[, ].*"),
                    "guest's " + modulesDir + " is not read-only: " + inside.stdout());

            // The rest of the guest's /lib/modules must stay writable (package managers own it).
            ContainerExec.Result writable = ContainerExec.runMerged(docker, containerId,
                    new String[]{"sh", "-c", "touch /lib/modules/.floci-w && rm /lib/modules/.floci-w"}, 30);
            assertEquals(0, writable.exitCode(),
                    "guest's /lib/modules outside " + modulesDir + " is not writable: " + writable.summary());

            // A privileged guest gets its own cgroup namespace: it sits at the namespace root, and
            // Docker mounts that cgroupfs read-write so the guest's systemd can create slices.
            // Assumes cgroup v2: on v1/hybrid hosts there is no "0::" line.
            ContainerExec.Result cgroup =ContainerExec.runMerged(docker, containerId,
                    new String[]{"cat", "/proc/self/cgroup"}, 30);
            assertEquals(0, cgroup.exitCode(), "cannot read the guest's cgroup: " + cgroup.summary());
            assertTrue(cgroup.stdout().lines().anyMatch("0::/"::equals),
                    "guest is not at the root of a private cgroup namespace: " + cgroup.stdout());
            // Checks only that the cgroupfs is writable; the host namespace is writable too, so the
            // "0::/" line above is what distinguishes a private namespace.
            ContainerExec.Result cgroupfs =ContainerExec.runMerged(docker, containerId,
                    new String[]{"sh", "-c", "mkdir /sys/fs/cgroup/.floci-w && rmdir /sys/fs/cgroup/.floci-w"}, 30);
            assertEquals(0, cgroupfs.exitCode(), "guest's cgroupfs is not writable: " + cgroupfs.summary());
        } finally {
            // terminate() removes the container and releases the SSH port in the shared allocator.
            CountDownLatch torndown = new CountDownLatch(1);
            manager.terminate(instance, torndown::countDown);
            try {
                torndown.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
