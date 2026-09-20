package io.github.hectorvent.floci.services.sagemaker;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.EmulatorConfig.SageMakerServiceConfig.GpuRequestMode;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.CurrentContainerNetworkResolver;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import com.github.dockerjava.api.model.DeviceRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("SageMakerGpuResolver: instance type to device request")
class SageMakerGpuResolverTest {

    private static final String UUID_A = "nvidia.com/gpu=GPU-aaaaaaaa";
    private static final String UUID_B = "nvidia.com/gpu=GPU-bbbbbbbb";
    private static final String UUID_C = "nvidia.com/gpu=GPU-cccccccc";
    private static final String UUID_D = "nvidia.com/gpu=GPU-dddddddd";

    @Test
    void disabledByDefaultLeavesEvenGpuInstanceTypesAlone() {
        Fixture fixture = new Fixture(false, GpuRequestMode.CDI, List.of(UUID_A));

        ContainerSpec spec = fixture.resolve("ml.g5.xlarge", 1);

        // The guard on every existing deployment: opting out means nothing changes,
        // including for instance types that would otherwise ask for hardware.
        assertEquals(List.of(), spec.deviceRequests());
    }

    @Test
    void cpuInstanceTypeGetsNoDevice() {
        Fixture fixture = new Fixture(true, GpuRequestMode.CDI, List.of(UUID_A));

        assertEquals(List.of(), fixture.resolve("ml.m5.large", 1).deviceRequests());
    }

    @Test
    void unknownNonAcceleratorTypeIsTreatedAsCpu() {
        Fixture fixture = new Fixture(true, GpuRequestMode.CDI, List.of(UUID_A));

        // Not in the catalog and not a GPU family: a CPU job Floci simply does not
        // have metadata for, which is no reason to fail it.
        assertEquals(List.of(), fixture.resolve("ml.c7i.12xlarge", 1).deviceRequests());
    }

    @Test
    void gpuInstanceTypeRequestsItsCataloguedDeviceCount() {
        Fixture fixture = new Fixture(true, GpuRequestMode.CDI, List.of(UUID_A, UUID_B));

        ContainerSpec spec = fixture.resolve("ml.g5.xlarge", 1);

        DeviceRequest request = spec.deviceRequests().get(0);
        assertEquals("cdi", request.getDriver());
        assertEquals(List.of(UUID_A), request.getDeviceIds());
    }

    @Test
    void multiGpuTypeTakesAsManyDevicesAsTheCatalogSays() {
        Fixture fixture = new Fixture(true, GpuRequestMode.CDI,
                List.of(UUID_A, UUID_B, UUID_C, UUID_D));

        // ml.g5.12xlarge is four devices on AWS, so a host offering four gets all four
        // in one container. Counts come from the catalog rather than from matching
        // ml.g*, because they vary within a family.
        ContainerSpec spec = fixture.resolve("ml.g5.12xlarge", 1);

        assertEquals(List.of(UUID_A, UUID_B, UUID_C, UUID_D),
                spec.deviceRequests().get(0).getDeviceIds());
    }

    @Test
    void devicesAreTakenInOrderSoASmallerTypeDoesNotClaimEverything() {
        Fixture fixture = new Fixture(true, GpuRequestMode.CDI,
                List.of(UUID_A, UUID_B, UUID_C, UUID_D));

        // A one-GPU type on a four-GPU host takes one device, not the whole allowlist.
        assertEquals(List.of(UUID_A), fixture.resolve("ml.g5.xlarge", 1)
                .deviceRequests().get(0).getDeviceIds());
    }

    @Test
    void multiGpuTypeIsRefusedWhenTheHostAllowsFewerDevices() {
        Fixture fixture = new Fixture(true, GpuRequestMode.CDI, List.of(UUID_A, UUID_B));

        SageMakerGpuResolver.UnsupportedResourceException thrown = assertThrows(
                SageMakerGpuResolver.UnsupportedResourceException.class,
                () -> fixture.resolve("ml.g5.12xlarge", 1));
        assertTrue(thrown.getMessage().contains("needs 4 GPUs"), thrown.getMessage());
    }

    @Test
    void deviceIdsModeUsesTheDaemonIdForm() {
        Fixture fixture = new Fixture(true, GpuRequestMode.DEVICE_IDS, List.of("0", "1"));

        DeviceRequest request = fixture.resolve("ml.g5.xlarge", 1).deviceRequests().get(0);

        assertEquals(List.of("0"), request.getDeviceIds());
        assertEquals(List.of(List.of("gpu")), request.getCapabilities());
    }

    @Test
    void countModeLetsTheDaemonChooseAndIgnoresTheAllowlist() {
        Fixture fixture = new Fixture(true, GpuRequestMode.COUNT, List.of());

        DeviceRequest request = fixture.resolve("ml.g5.xlarge", 1).deviceRequests().get(0);

        assertEquals(1, request.getCount());
    }

    @Test
    void acceleratorTypeMissingFromTheCatalogIsRefused() {
        Fixture fixture = new Fixture(true, GpuRequestMode.CDI, List.of(UUID_A));

        // The case this whole class exists to prevent: the caller asked for a GPU, so
        // running on CPU and reporting Completed would hand back an artifact that looks
        // legitimate.
        SageMakerGpuResolver.UnsupportedResourceException thrown = assertThrows(
                SageMakerGpuResolver.UnsupportedResourceException.class,
                () -> fixture.resolve("ml.p5.48xlarge", 1));
        assertTrue(thrown.getMessage().contains("not in Floci's"), thrown.getMessage());
    }

    @Test
    void multipleInstancesAreRefusedRatherThanPretended() {
        Fixture fixture = new Fixture(true, GpuRequestMode.CDI, List.of(UUID_A, UUID_B));

        SageMakerGpuResolver.UnsupportedResourceException thrown = assertThrows(
                SageMakerGpuResolver.UnsupportedResourceException.class,
                () -> fixture.resolve("ml.g5.xlarge", 2));
        assertTrue(thrown.getMessage().contains("InstanceCount 2"), thrown.getMessage());
    }

    @Test
    void explicitModeWithNoDevicesConfiguredIsRefused() {
        Fixture fixture = new Fixture(true, GpuRequestMode.CDI, List.of());

        SageMakerGpuResolver.UnsupportedResourceException thrown = assertThrows(
                SageMakerGpuResolver.UnsupportedResourceException.class,
                () -> fixture.resolve("ml.g5.xlarge", 1));
        assertTrue(thrown.getMessage().contains("devices is empty"), thrown.getMessage());
    }

    /** Wires a real ContainerBuilder so the assertions see the request actually built. */
    private static final class Fixture {
        private final SageMakerGpuResolver resolver;
        private final ContainerBuilder containerBuilder;

        private Fixture(boolean enabled, GpuRequestMode mode, List<String> devices) {
            EmulatorConfig config = mock(EmulatorConfig.class);
            EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
            EmulatorConfig.SageMakerServiceConfig sagemaker =
                    mock(EmulatorConfig.SageMakerServiceConfig.class);
            EmulatorConfig.SageMakerServiceConfig.GpuConfig gpu =
                    mock(EmulatorConfig.SageMakerServiceConfig.GpuConfig.class);
            EmulatorConfig.DockerConfig docker = mock(EmulatorConfig.DockerConfig.class);

            when(config.services()).thenReturn(services);
            when(services.sagemaker()).thenReturn(sagemaker);
            when(sagemaker.gpu()).thenReturn(gpu);
            when(gpu.enabled()).thenReturn(enabled);
            when(gpu.mode()).thenReturn(mode);
            when(gpu.devices()).thenReturn(Optional.of(devices));
            when(config.docker()).thenReturn(docker);
            when(docker.imageRegistryBase()).thenReturn(Optional.empty());

            this.containerBuilder = new ContainerBuilder(config, mock(DockerHostResolver.class),
                    mock(EmbeddedDnsServer.class), mock(CurrentContainerNetworkResolver.class));
            this.resolver = new SageMakerGpuResolver(config, new SageMakerInstanceTypeCatalog());
        }

        private ContainerSpec resolve(String instanceType, int instanceCount) {
            ContainerBuilder.Builder builder = containerBuilder.newContainer("alpine");
            resolver.applyTo(builder, instanceType, instanceCount);
            return builder.build();
        }
    }
}
