package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
import com.github.dockerjava.api.model.Frame;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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
                .withCmd(List.of("uname", "-m"))
                .build();

        String containerId = null;
        try {
            containerId = lifecycleManager.create(spec, platform);

            String machine = architectureReportedByContainer(containerId);
            if (machine != null) {
                assertEquals(armHost ? "x86_64" : "aarch64", machine);
                return;
            }
            assertEquals(requestedArchitecture, architectureReportedByDaemon(containerId, hostArchitecture));
        } finally {
            if (containerId != null) {
                dockerClient.removeContainerCmd(containerId).withForce(true).exec();
            }
        }
    }

    /**
     * Runs the created container, whose command is {@code uname -m}, and returns what it printed.
     * This is the only reading that holds on every engine, because the container is the variant
     * the daemon actually selected. Running one built for another architecture needs emulation on
     * the host, which not every machine has: without it the start either throws or the container
     * exits nonzero on an exec-format error, and both return {@code null} for the caller to fall
     * back on.
     */
    private String architectureReportedByContainer(String containerId) {
        try {
            dockerClient.startContainerCmd(containerId).exec();
        } catch (RuntimeException e) {
            LOG.warnv(e, "Could not start the foreign-platform container, falling back to inspect");
            return null;
        }
        Integer status;
        try {
            status = dockerClient.waitContainerCmd(containerId)
                    .exec(new WaitContainerResultCallback())
                    .awaitStatusCode(60, TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            LOG.warnv(e, "Waiting on the foreign-platform container failed, falling back to inspect");
            return null;
        }
        if (status == null || status != 0) {
            LOG.warnv("The foreign-platform container exited with {0}, falling back to inspect: {1}",
                    status, logs(containerId));
            return null;
        }
        return logs(containerId).trim();
    }

    /**
     * The architecture the daemon reports for the image the container was created from. Docker 29's
     * containerd image store, its default, answers for the index rather than the selected variant:
     * an empty string until a second variant of the tag is local, and the host's architecture once
     * one is. Neither can confirm anything here, so both skip the test instead of failing it.
     */
    private String architectureReportedByDaemon(String containerId, String hostArchitecture) {
        String imageId = dockerClient.inspectContainerCmd(containerId).exec().getImageId();
        String reported = dockerClient.inspectImageCmd(imageId).exec().getArch();
        if (reported == null || reported.isBlank()) {
            return Assumptions.abort("this daemon reports no architecture for a foreign-platform image");
        }
        if (reported.equalsIgnoreCase(hostArchitecture)
                || (hostArchitecture.equals("aarch64") && reported.equals("arm64"))
                || (hostArchitecture.equals("x86_64") && reported.equals("amd64"))) {
            return Assumptions.abort(
                    "this daemon reports the host architecture for a foreign-platform image");
        }
        return reported;
    }

    /** Best effort, as in the sibling Docker tests: a read that fails reports itself in the text. */
    private String logs(String containerId) {
        StringBuilder out = new StringBuilder();
        try {
            dockerClient.logContainerCmd(containerId).withStdOut(true).withStdErr(true)
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            out.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                        }
                    }).awaitCompletion(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.append("(interrupted while reading logs)");
        } catch (Exception e) {
            out.append("(could not read logs: ").append(e.getMessage()).append(')');
        }
        return out.toString();
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
