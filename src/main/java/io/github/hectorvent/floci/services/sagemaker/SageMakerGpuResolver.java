package io.github.hectorvent.floci.services.sagemaker;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.EmulatorConfig.SageMakerServiceConfig.GpuRequestMode;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * Turns a SageMaker {@code InstanceType} into a device request on a container, or into a
 * clear refusal.
 *
 * <p>Currently used by training jobs. It is kept separate from the training runner so the
 * same rules can be reused if GPU-backed endpoint hosting is added later.
 *
 * <p>The design rule throughout: never answer a GPU request with a CPU container. A job
 * that asks for {@code ml.g5.xlarge}, trains on CPU and reports {@code Completed} leaves
 * behind an artifact that looks legitimate, which is worse than a failure. Every path that
 * cannot honour the request raises {@link UnsupportedResourceException} instead.
 *
 * <p>Only the accelerator is mapped. An instance type also implies vCPU and memory, and
 * Floci does not emulate those: the EC2 catalog is metadata for DescribeInstanceTypes and
 * never sizes a container, and RDS keeps DBInstanceClass without acting on it. Limits are
 * applied only where an AWS API hands over an explicit number, as Lambda's MemorySize and
 * ECS task memory do.
 */
@ApplicationScoped
public class SageMakerGpuResolver {

    private final EmulatorConfig config;
    private final SageMakerInstanceTypeCatalog catalog;

    @Inject
    public SageMakerGpuResolver(EmulatorConfig config, SageMakerInstanceTypeCatalog catalog) {
        this.config = config;
        this.catalog = catalog;
    }

    /**
     * Raised when the request names hardware this host cannot stand in for. Callers surface
     * the message as the AWS failure reason, so it has to say what is wrong and what would
     * fix it.
     */
    public static class UnsupportedResourceException extends RuntimeException {
        public UnsupportedResourceException(String message) {
            super(message);
        }
    }

    /**
     * Applies a device request to {@code builder} when the instance type calls for one.
     *
     * @param instanceType the requested {@code ml.*} type, as given in the AWS request
     * @param instanceCount instances requested; anything above one is refused, since a
     *                      single container is not distributed training
     * @throws UnsupportedResourceException when the request cannot be honoured
     */
    public void applyTo(ContainerBuilder.Builder builder, String instanceType, int instanceCount) {
        EmulatorConfig.SageMakerServiceConfig.GpuConfig gpu = config.services().sagemaker().gpu();
        if (!gpu.enabled()) {
            // Feature off: behave exactly as before, including for GPU instance types.
            // Turning it on is what opts a deployment into the stricter rules below.
            return;
        }

        Optional<Integer> catalogued = catalog.gpuCount(instanceType);
        if (catalogued.isEmpty()) {
            if (SageMakerInstanceTypeCatalog.isAcceleratorFamily(instanceType)) {
                throw new UnsupportedResourceException(
                        "Instance type " + instanceType + " is a GPU family but is not in Floci's "
                                + "SageMaker instance type catalog, so the number of accelerators it "
                                + "should receive is unknown. Add it to the catalog or choose a "
                                + "listed type.");
            }
            // An unlisted non-accelerator type is a CPU job, which needs nothing from us.
            return;
        }

        int requested = catalogued.get();
        if (requested == 0) {
            return;
        }
        if (instanceCount > 1) {
            throw new UnsupportedResourceException(
                    "Floci runs a training job as a single container, so InstanceCount " + instanceCount
                            + " with GPU instance type " + instanceType + " cannot be honoured. Use "
                            + "InstanceCount 1.");
        }

        List<String> devices = gpu.devices().orElse(List.of());
        GpuRequestMode mode = gpu.mode();
        if (mode != GpuRequestMode.COUNT && !devices.isEmpty() && devices.size() < requested) {
            throw new UnsupportedResourceException(
                    "Instance type " + instanceType + " needs " + requested + " GPUs but only "
                            + devices.size() + " device(s) are allowed by "
                            + "floci.services.sagemaker.gpu.devices.");
        }

        switch (mode) {
            case CDI -> {
                requireDevicesConfigured(devices, "cdi", instanceType);
                builder.withCdiDevices(devices.subList(0, requested));
            }
            case DEVICE_IDS -> {
                requireDevicesConfigured(devices, "device-ids", instanceType);
                builder.withGpuDeviceIds(devices.subList(0, requested));
            }
            // The daemon picks the devices, so no allowlist applies.
            case COUNT -> builder.withGpuCount(requested);
        }
    }

    /**
     * Both explicit-device modes need to be told which devices exist. Defaulting to "all
     * of them" would quietly hand Floci every GPU on a machine that is usually sharing
     * them with something else.
     */
    private static void requireDevicesConfigured(List<String> devices, String mode, String instanceType) {
        if (devices.isEmpty()) {
            throw new UnsupportedResourceException(
                    "GPU support is enabled in " + mode + " mode but "
                            + "floci.services.sagemaker.gpu.devices is empty, so there is no device to "
                            + "give " + instanceType + ". List the devices Floci may use, or switch to "
                            + "count mode to let the daemon choose.");
        }
    }
}
