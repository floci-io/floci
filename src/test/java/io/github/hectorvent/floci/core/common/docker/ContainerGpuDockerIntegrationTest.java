package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves a GPU device request survives the round trip to a real daemon and that the
 * container can see the device. Opt-in, because it needs hardware no CI runner has:
 * run it with {@code -Dgpu.integration.enabled=true}. Without that flag it skips, so the
 * request-construction coverage in {@link ContainerBuilderTest} and
 * {@link ContainerLifecycleManagerDeviceRequestTest} is what runs by default.
 *
 * <p>Select a device explicitly with
 * {@code -Dgpu.integration.cdi-device=nvidia.com/gpu=GPU-<uuid>} against a daemon that
 * resolves Container Device Interface names, such as Podman. With no device named, the
 * test asks for every GPU using Docker's count form instead, which is the shape to run
 * against Docker. Note that Podman accepts the count form and then attaches no device
 * (containers/podman#22645), so that combination fails here by design.
 */
@QuarkusTest
class ContainerGpuDockerIntegrationTest {

    private static final Logger LOG = Logger.getLogger(ContainerGpuDockerIntegrationTest.class);
    private static final String ENABLED_PROPERTY = "gpu.integration.enabled";
    private static final String CDI_DEVICE_PROPERTY = "gpu.integration.cdi-device";
    private static final String IMAGE_PROPERTY = "gpu.integration.image";
    private static final String DEFAULT_IMAGE = "docker.io/nvidia/cuda:12.8.1-base-ubuntu24.04";

    @Inject
    DockerClient dockerClient;

    @Inject
    ContainerBuilder containerBuilder;

    @Inject
    ContainerLifecycleManager lifecycleManager;

    @BeforeEach
    void requireGpuEnabledDaemon() {
        Assumptions.assumeTrue(Boolean.getBoolean(ENABLED_PROPERTY),
                "GPU hardware tests are opt-in: run with -D" + ENABLED_PROPERTY + "=true on a host with a GPU");
        Assumptions.assumeTrue(isDockerAvailable(),
                "A container daemon must be available for GPU integration tests");
    }

    @Test
    void containerWithGpuRequestSeesTheDevice() {
        String image = System.getProperty(IMAGE_PROPERTY, DEFAULT_IMAGE);
        String cdiDevice = System.getProperty(CDI_DEVICE_PROPERTY);

        ContainerBuilder.Builder builder = containerBuilder.newContainer(image)
                .withName("floci-gpu-test-" + UUID.randomUUID())
                .withCmd(List.of("nvidia-smi", "-L"));
        if (cdiDevice != null && !cdiDevice.isBlank()) {
            builder.withCdiDevices(List.of(cdiDevice));
        } else {
            builder.withAllGpus();
        }

        String containerId = null;
        try {
            containerId = lifecycleManager.create(builder.build());
            dockerClient.startContainerCmd(containerId).exec();
            Integer status = dockerClient.waitContainerCmd(containerId)
                    .exec(new WaitContainerResultCallback())
                    .awaitStatusCode(120, TimeUnit.SECONDS);

            String output = logs(containerId);
            assertEquals(0, status, "nvidia-smi exited nonzero: " + output);
            // `nvidia-smi -L` prints one "GPU <n>: <name> (UUID: GPU-...)" line per visible
            // device, so this is the daemon confirming the request was honoured.
            assertTrue(output.contains("GPU"), "no GPU listed in the container: " + output);
            if (cdiDevice != null && !cdiDevice.isBlank()) {
                String uuid = cdiDevice.substring(cdiDevice.indexOf('=') + 1);
                assertTrue(output.contains(uuid),
                        "the container saw a device other than the one requested: " + output);
            }
            LOG.infov("GPU visible to container: {0}", output.trim());
        } finally {
            if (containerId != null) {
                dockerClient.removeContainerCmd(containerId).withForce(true).exec();
            }
        }
    }

    @Test
    void containerWithoutGpuRequestSeesNoDevice() {
        String image = System.getProperty(IMAGE_PROPERTY, DEFAULT_IMAGE);

        ContainerSpec spec = containerBuilder.newContainer(image)
                .withName("floci-gpu-test-cpu-" + UUID.randomUUID())
                .withCmd(List.of("nvidia-smi", "-L"))
                .build();

        String containerId = null;
        try {
            containerId = lifecycleManager.create(spec);
            // Two shapes of "no device", both acceptable: the start itself fails, because
            // nvidia-smi is injected by the runtime alongside the device and is absent from
            // the image without one; or the container starts and exits nonzero. What must
            // not happen is a clean exit, which would mean a container that asked for
            // nothing still reached a GPU because the daemon defaults to an accelerator
            // runtime.
            try {
                dockerClient.startContainerCmd(containerId).exec();
            } catch (RuntimeException expected) {
                LOG.infov("Container without a GPU request could not start, as expected: {0}",
                        expected.getMessage());
                return;
            }
            Integer status = dockerClient.waitContainerCmd(containerId)
                    .exec(new WaitContainerResultCallback())
                    .awaitStatusCode(120, TimeUnit.SECONDS);

            assertTrue(status != null && status != 0,
                    "a container that requested no GPU still reached one: " + logs(containerId));
        } finally {
            if (containerId != null) {
                dockerClient.removeContainerCmd(containerId).withForce(true).exec();
            }
        }
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
            LOG.warn("No container daemon available for the GPU integration test", e);
            return false;
        }
    }
}
