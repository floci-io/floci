package io.floci.aws.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Device;
import com.github.dockerjava.api.model.HostConfig;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.services.lambda.launcher.ImageCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A spec's Linux options reach Docker's HostConfig as {@code docker run} would set them, a
 * container that asked for none gets the daemon's defaults, and a container behind a security
 * group keeps NET_ADMIN and NET_RAW dropped whatever it asks to add.
 */
@ExtendWith(MockitoExtension.class)
class ContainerLifecycleManagerLinuxOptionsTest {

    private static final long MIB = 1024L * 1024L;

    @Mock
    DockerClient dockerClient;

    @Mock
    ImageCacheService imageCacheService;

    @Mock
    ContainerDetector containerDetector;


    @Mock
    EmulatorConfig config;

    @Mock
    EmulatorConfig.DockerConfig dockerConfig;

    @Mock
    EmulatorConfig.TlsConfig tlsConfig;

    @BeforeEach
    void setUp() {
        lenient().when(config.docker()).thenReturn(dockerConfig);
        lenient().when(config.tls()).thenReturn(tlsConfig);
        lenient().when(dockerConfig.resourceNamespace()).thenReturn(Optional.empty());
        lenient().when(imageCacheService.ensureImageExists(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void linuxOptionsReachTheHostConfig() {
        CreateContainerCmd createCmd = stubCreateContainer();
        ContainerSpec spec = builder()
                .withMemoryMb(512)
                .withInit()
                .withCapAdd("SYS_PTRACE")
                .withCapDrop("NET_RAW")
                .withDevice("/dev/fuse", "/dev/fuse", "rw")
                .withShmSizeBytes(256 * MIB)
                .withTmpfs("/scratch", "noexec,size=64m")
                .withMemorySwapBytes(1536 * MIB)
                .withMemorySwappiness(10)
                .build();

        manager().create(spec);

        HostConfig hostConfig = capturedHostConfig(createCmd);
        assertTrue(hostConfig.getInit());
        assertArrayEquals(new Capability[] {Capability.SYS_PTRACE}, hostConfig.getCapAdd());
        assertArrayEquals(new Capability[] {Capability.NET_RAW}, hostConfig.getCapDrop());
        assertArrayEquals(new Device[] {new Device("rw", "/dev/fuse", "/dev/fuse")}, hostConfig.getDevices());
        assertEquals(256 * MIB, hostConfig.getShmSize());
        assertEquals(Map.of("/scratch", "noexec,size=64m"), hostConfig.getTmpFs());
        assertEquals(512 * MIB, hostConfig.getMemory());
        assertEquals(1536 * MIB, hostConfig.getMemorySwap());
        assertEquals(10L, hostConfig.getMemorySwappiness());
    }

    @Test
    void aContainerThatAskedForNoneGetsTheDaemonDefaults() {
        CreateContainerCmd createCmd = stubCreateContainer();

        manager().create(new ContainerSpec("busybox:stable"));

        HostConfig hostConfig = capturedHostConfig(createCmd);
        assertNull(hostConfig.getInit());
        assertNull(hostConfig.getCapAdd());
        assertNull(hostConfig.getCapDrop());
        assertNull(hostConfig.getDevices());
        assertNull(hostConfig.getShmSize());
        assertNull(hostConfig.getTmpFs());
        assertNull(hostConfig.getMemorySwap());
        assertNull(hostConfig.getMemorySwappiness());
    }

    @Test
    void capabilityNamesAreReadWithOrWithoutThePrefixAndInAnyCase() {
        CreateContainerCmd createCmd = stubCreateContainer();

        manager().create(builder().withCapAdd("cap_sys_ptrace").withCapDrop("Mknod").build());

        HostConfig hostConfig = capturedHostConfig(createCmd);
        assertArrayEquals(new Capability[] {Capability.SYS_PTRACE}, hostConfig.getCapAdd());
        assertArrayEquals(new Capability[] {Capability.MKNOD}, hostConfig.getCapDrop());
    }

    @Test
    void anUnknownCapabilityFailsTheCreate() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> manager().create(builder().withCapAdd("NOT_A_CAPABILITY").build()));

        assertEquals("Unknown Linux capability: NOT_A_CAPABILITY", e.getMessage());
    }

    @Test
    void aSecurityGroupWorkloadCannotAddBackWhatItsFirewallNeedsDropped() {
        CreateContainerCmd createCmd = stubCreateContainer();
        ContainerSpec spec = builder()
                .withLabels(Map.of(ContainerStorageHelper.SECURITY_GROUP_WORKLOAD_LABEL, "true"))
                .withCapAdd("NET_ADMIN")
                .withCapAdd("SYS_PTRACE")
                .withCapAdd("NET_RAW")
                .withCapDrop("MKNOD")
                .build();

        manager().create(spec);

        HostConfig hostConfig = capturedHostConfig(createCmd);
        assertArrayEquals(new Capability[] {Capability.SYS_PTRACE}, hostConfig.getCapAdd());
        assertArrayEquals(new Capability[] {Capability.NET_ADMIN, Capability.NET_RAW, Capability.MKNOD},
                hostConfig.getCapDrop());
    }

    /**
     * ALL among the adds stays an add: Docker grants every capability but the ones dropped when
     * ALL is added (measured: docker run --cap-add ALL --cap-drop NET_ADMIN --cap-drop NET_RAW
     * reports CapEff 000001ffffffcfff, bits 12 and 13 clear), so the firewall's drops still hold.
     */
    @Test
    void aSecurityGroupWorkloadAskingForAllStillHasItsFirewallCapabilitiesDropped() {
        CreateContainerCmd createCmd = stubCreateContainer();

        manager().create(builder()
                .withLabels(Map.of(ContainerStorageHelper.SECURITY_GROUP_WORKLOAD_LABEL, "true"))
                .withCapAdd("ALL")
                .build());

        HostConfig hostConfig = capturedHostConfig(createCmd);
        assertArrayEquals(new Capability[] {Capability.ALL}, hostConfig.getCapAdd());
        assertArrayEquals(new Capability[] {Capability.NET_ADMIN, Capability.NET_RAW}, hostConfig.getCapDrop());
    }

    /** KILL is one of the capabilities ECS accepts, and docker-java has it. */
    @Test
    void killIsAddedAndDroppedLikeAnyOtherCapability() {
        CreateContainerCmd createCmd = stubCreateContainer();

        manager().create(builder().withCapAdd("KILL").withCapDrop("CAP_KILL").build());

        HostConfig hostConfig = capturedHostConfig(createCmd);
        assertArrayEquals(new Capability[] {Capability.KILL}, hostConfig.getCapAdd());
        assertArrayEquals(new Capability[] {Capability.KILL}, hostConfig.getCapDrop());
    }

    @Test
    void aSecurityGroupWorkloadThatAskedForNothingKeepsItsDrops() {
        CreateContainerCmd createCmd = stubCreateContainer();

        manager().create(builder()
                .withLabels(Map.of(ContainerStorageHelper.SECURITY_GROUP_WORKLOAD_LABEL, "true"))
                .build());

        HostConfig hostConfig = capturedHostConfig(createCmd);
        assertNull(hostConfig.getCapAdd());
        assertArrayEquals(new Capability[] {Capability.NET_ADMIN, Capability.NET_RAW}, hostConfig.getCapDrop());
    }

    private static ContainerBuilder.Builder builder() {
        return new ContainerBuilder(null, null, null).newContainer("busybox:stable");
    }

    private ContainerLifecycleManager manager() {
        return new ContainerLifecycleManager(
                dockerClient, imageCacheService, containerDetector, config);
    }

    private CreateContainerCmd stubCreateContainer() {
        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(dockerClient.createContainerCmd("busybox:stable")).thenReturn(createCmd);
        CreateContainerResponse response = mock(CreateContainerResponse.class);
        when(response.getId()).thenReturn("container-id");
        when(createCmd.exec()).thenReturn(response);
        return createCmd;
    }

    private static HostConfig capturedHostConfig(CreateContainerCmd createCmd) {
        ArgumentCaptor<HostConfig> hostConfig = ArgumentCaptor.forClass(HostConfig.class);
        verify(createCmd).withHostConfig(hostConfig.capture());
        return hostConfig.getValue();
    }
}
