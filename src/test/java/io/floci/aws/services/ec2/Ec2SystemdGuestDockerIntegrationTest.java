package io.floci.aws.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.core.command.BuildImageResultCallback;
import com.github.dockerjava.core.command.ExecStartResultCallback;
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
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
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

    @TempDir
    Path imageContext;

    @Inject
    ContainerReachableEndpoint reachableEndpoint;

    @BeforeEach
    void requireDocker() {
        assumeTrue(DockerAvailability.answers(docker), "Docker must be available for EC2 systemd guest tests");
    }

    @Test
    void systemdLaunchMasksConsoleDevicesWithoutBreakingPseudoTerminals() throws Exception {
        String imageId = buildConsoleGuardImage();
        Ec2ContainerManager manager = new Ec2ContainerManager(containerBuilder, lifecycleManager,
                mock(ContainerLogStreamer.class), mock(ContainerDetector.class), dockerHostResolver, docker,
                portAllocator, config, mock(Ec2MetadataServer.class), mock(Ec2PortForwardManager.class),
                regionResolver, mock(ContainerNetworkReachability.class), mock(VpcNetworkManager.class),
                reachableEndpoint);
        Instance instance = new Instance();
        instance.setInstanceId("i-console" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        instance.setRegion(REGION);
        try {
            manager.launch(instance,
                    new ResolvedAmiImage(imageId, ResolvedAmiImage.SYSTEMD_RUNTIME, false, null), null, REGION);
            await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                assertNotNull(instance.getDockerContainerId(), "EC2 must create the guarded guest");
                String containerId = instance.getDockerContainerId();
                ContainerExec.Result guard;
                try {
                    guard = ContainerExec.runMerged(docker, containerId,
                            new String[]{"test", "-f", "/run/floci-console-guard-passed"}, 10);
                } catch (ConflictException e) {
                    InspectContainerResponse.ContainerState state = docker.inspectContainerCmd(containerId).exec().getState();
                    throw new IllegalStateException("console guard container exited with code " + state.getExitCodeLong()
                            + ": " + containerLogs(containerId), e);
                }
                assertEquals(0, guard.exitCode(), "console guard did not admit the launch: " + guard.summary());
            });

            String execId = docker.execCreateCmd(instance.getDockerContainerId()).withTty(true)
                    .withAttachStdout(true).withAttachStderr(true)
                    .withCmd("sh", "-c", "test -t 0 && test -t 1 && tty").exec().getId();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ExecStartResultCallback callback = new ExecStartResultCallback(output, output)) {
                assertTrue(docker.execStartCmd(execId).withTty(true).exec(callback)
                        .awaitCompletion(10, TimeUnit.SECONDS), "PTY probe timed out");
            }
            assertEquals(0L, docker.inspectExecCmd(execId).exec().getExitCodeLong());
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("/dev/pts/"),
                    "guest must retain working pseudo-terminals: " + output.toString(StandardCharsets.UTF_8));
        } finally {
            CountDownLatch tornDown = new CountDownLatch(1);
            manager.terminate(instance, tornDown::countDown);
            try {
                assertTrue(tornDown.await(60, TimeUnit.SECONDS), "guarded guest cleanup timed out");
            } finally {
                manager.stop();
                docker.removeImageCmd(imageId).exec();
            }
        }
    }

    @Test
    void consoleGuardRejectsAnUnprotectedLaunch() throws Exception {
        String imageId = buildConsoleGuardImage();
        String containerId = null;
        try {
            containerId = docker.createContainerCmd(imageId).withCmd("/sbin/init").exec().getId();
            docker.startContainerCmd(containerId).exec();
            String rejectedId = containerId;
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                InspectContainerResponse.ContainerState state = docker.inspectContainerCmd(rejectedId).exec().getState();
                assertFalse(state.getRunning(), "unprotected fixture must exit rather than boot an init");
                assertEquals(78L, state.getExitCodeLong());
            });
        } finally {
            if (containerId != null) {
                docker.removeContainerCmd(containerId).withForce(true).exec();
            }
            docker.removeImageCmd(imageId).exec();
        }
    }

    private String buildConsoleGuardImage() throws Exception {
        // The guard checks the production launch settings but never runs systemd or a getty.
        String dockerfile = """
                FROM %s
                RUN rm /sbin/init
                COPY systemd-console-guard.sh /sbin/init
                RUN chmod 0755 /sbin/init
                """.formatted(TestImages.PYTHON_ALPINE);
        Files.writeString(imageContext.resolve("Dockerfile"), dockerfile);
        try (InputStream guard = getClass().getResourceAsStream("/ec2/systemd-console-guard.sh")) {
            assertNotNull(guard);
            Files.copy(guard, imageContext.resolve("systemd-console-guard.sh"));
        }
        try (BuildImageResultCallback callback = new BuildImageResultCallback()) {
            String imageId = docker.buildImageCmd(imageContext.toFile())
                    .withTags(Set.of("floci-console-guard-test:" + UUID.randomUUID()))
                    .withRemove(true).exec(callback).awaitImageId(180, TimeUnit.SECONDS);
            assertNotNull(imageId, "Docker must build the console guard fixture");
            return imageId;
        }
    }

    private String containerLogs(String containerId) throws Exception {
        StringBuilder output = new StringBuilder();
        try (ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
            @Override
            public void onNext(Frame frame) {
                output.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
            }
        }) {
            assertTrue(docker.logContainerCmd(containerId).withStdOut(true).withStdErr(true)
                    .exec(callback).awaitCompletion(10, TimeUnit.SECONDS), "guard log retrieval timed out");
        }
        return output.toString();
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
