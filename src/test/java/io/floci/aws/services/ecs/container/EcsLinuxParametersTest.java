package io.floci.aws.services.ecs.container;

import com.github.dockerjava.api.model.Device;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.LinuxOptions;
import io.floci.aws.services.ecs.model.ContainerDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A container definition's {@code linuxParameters}, read where RegisterTaskDefinition keeps them,
 * become the Docker options ECS documents for each member.
 */
class EcsLinuxParametersTest {

    private static final long MIB = 1024L * 1024L;

    @Test
    void everyMemberBecomesTheDockerOptionEcsDocuments() {
        LinuxOptions options = apply(Map.of(
                "initProcessEnabled", true,
                "capabilities", Map.of("add", List.of("SYS_PTRACE"), "drop", List.of("NET_RAW", "MKNOD")),
                "devices", List.of(Map.of("hostPath", "/dev/fuse", "containerPath", "/dev/fuse0",
                        "permissions", List.of("write", "read"))),
                "sharedMemorySize", 256,
                "tmpfs", List.of(Map.of("containerPath", "/scratch", "size", 64,
                        "mountOptions", List.of("noexec", "nosuid"))),
                "maxSwap", 1024,
                "swappiness", 10), 512);

        assertEquals(List.of("SYS_PTRACE"), options.capAdd());
        assertEquals(List.of("NET_RAW", "MKNOD"), options.capDrop());
        assertEquals(List.of(new Device("rw", "/dev/fuse0", "/dev/fuse")), options.devices());
        assertEquals(256 * MIB, options.shmSizeBytes());
        assertEquals(Map.of("/scratch", "noexec,nosuid,size=64m"), options.tmpfs());
        assertEquals((512 + 1024) * MIB, options.memorySwapBytes(), "memory plus maxSwap");
        assertEquals(10L, options.memorySwappiness());
    }

    @Test
    void aDeviceWithoutPermissionsOrContainerPathGetsReadWriteMknodAtItsHostPath() {
        LinuxOptions options = apply(Map.of("devices", List.of(Map.of("hostPath", "/dev/null"))), null);

        assertEquals(List.of(new Device("rwm", "/dev/null", "/dev/null")), options.devices());
    }

    @Test
    void maxSwapZeroMeansNoSwap() {
        LinuxOptions options = apply(Map.of("maxSwap", 0, "swappiness", 0), 512);

        assertEquals(512 * MIB, options.memorySwapBytes(), "swap limit equal to the memory limit: no swap");
        assertEquals(0L, options.memorySwappiness());
    }

    @Test
    void swappinessWithoutMaxSwapIsIgnored() {
        LinuxOptions options = apply(Map.of("swappiness", 10), 512);

        assertNull(options.memorySwapBytes());
        assertNull(options.memorySwappiness());
    }

    @Test
    void maxSwapIsNotAppliedToAContainerWithNoMemoryLimitButItsSwappinessIs() {
        LinuxOptions options = apply(Map.of("maxSwap", 256, "swappiness", 10), null);

        assertNull(options.memorySwapBytes());
        assertEquals(10L, options.memorySwappiness());
    }

    @Test
    void initProcessDisabledOrAbsentLeavesTheDaemonDefault() {
        assertTrue(build(Map.of("initProcessEnabled", true), 512).init());
        assertFalse(build(Map.of("initProcessEnabled", false), 512).init());
        assertFalse(build(Map.of(), 512).init());
        assertEquals(LinuxOptions.NONE, apply(Map.of(), 512));
    }

    @Test
    void aContainerWithoutLinuxParametersGetsNone() {
        ContainerDefinition def = new ContainerDefinition();
        ContainerBuilder.Builder spec = new ContainerBuilder(null, null, null).newContainer("busybox:stable");

        EcsLinuxParameters.apply(spec, def, 512);

        assertEquals(LinuxOptions.NONE, spec.build().linuxOptions());
    }

    private static LinuxOptions apply(Map<String, Object> linuxParameters, Integer memoryLimitMb) {
        return build(linuxParameters, memoryLimitMb).linuxOptions();
    }

    private static ContainerSpec build(Map<String, Object> linuxParameters, Integer memoryLimitMb) {
        ContainerDefinition def = new ContainerDefinition();
        def.setUnparsed(Map.of("linuxParameters", linuxParameters));
        ContainerBuilder.Builder spec = new ContainerBuilder(null, null, null).newContainer("busybox:stable");
        EcsLinuxParameters.apply(spec, def, memoryLimitMb);
        return spec.build();
    }
}
