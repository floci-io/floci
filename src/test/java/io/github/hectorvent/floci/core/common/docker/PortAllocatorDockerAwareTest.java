package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerPort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves lex00/floci#139's fix: two independently-started floci instances,
 * each in its own Docker container and so each with its own network
 * namespace, that both try to allocate a host port for a sibling container
 * (an EKS real-mode cluster's k3s API server is the shape that found this -
 * EksClusterManager always requests the same base range) must not both
 * settle on the SAME port just because each one's own in-namespace
 * ServerSocket probe sees nothing bound.
 *
 * A single-process allocator cannot reproduce a second floci INSTANCE
 * choosing the same port; what it CAN reproduce, and what actually causes
 * the bug, is the one thing {@link PortAllocator#isPortFree} structurally
 * cannot see: a port already published by SOME OTHER container on the same
 * Docker host, discoverable only through the Docker API - not through this
 * process's own socket namespace. That is exactly what
 * {@link PortAllocator#isPortBoundByAnyContainer} adds, and what this test
 * exercises directly, with a mocked {@link DockerClient} standing in for a
 * sibling container's own port binding.
 */
class PortAllocatorDockerAwareTest {

    @Test
    void allocateSkipsAPortAlreadyPublishedByAnotherContainer() {
        DockerClient dockerClient = mock(DockerClient.class);
        ListContainersCmd listCmd = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.withShowAll(anyBoolean())).thenReturn(listCmd);

        ContainerPort boundPort = new ContainerPort().withPublicPort(19900);
        Container sibling = mock(Container.class);
        when(sibling.getPorts()).thenReturn(new ContainerPort[]{boundPort});
        when(listCmd.exec()).thenReturn(List.of(sibling));

        PortAllocator allocator = new PortAllocator(dockerClient);

        // 19900 is free from this process's own ServerSocket point of view -
        // nothing here is bound to it - but the mocked DockerClient reports it
        // as already published by a sibling container, exactly the shape two
        // independently-started k3s API server containers both requesting the
        // EKS manager's base port produce on a real host. BREAK: comment out
        // the isPortBoundByAnyContainer call in allocate() and this assertion
        // fails, allocating 19900 - the pre-fix bug, reproduced without
        // starting a second Docker container at all.
        int allocated = allocator.allocate(19900, 19999);

        assertNotEquals(19900, allocated, "must not allocate a port a container already publishes, even though this process's own socket check sees it as free");
        allocator.release(allocated);
    }

    @Test
    void allocateStillWorksWithNoDockerClient() {
        // The no-arg constructor's own contract, unchanged: every pre-existing
        // caller (this class's sibling PortAllocatorTest, every other service
        // that built a PortAllocator directly before this fix existed) keeps
        // behaving exactly as it did, because isPortBoundByAnyContainer is a
        // no-op with no DockerClient to ask.
        PortAllocator allocator = new PortAllocator();
        int port = allocator.allocate(19900, 19999);
        assertEquals(19900, port, "with no DockerClient, the first free port in range is still chosen");
        allocator.release(port);
    }

    @Test
    void dockerApiFailureDoesNotBlockAllocation() {
        DockerClient dockerClient = mock(DockerClient.class);
        when(dockerClient.listContainersCmd()).thenThrow(new RuntimeException("docker daemon unreachable"));

        PortAllocator allocator = new PortAllocator(dockerClient);
        // Fail-permissive, matching isPortFree's own posture for a probe that
        // could not be completed: a Docker API error must not make every
        // allocation in the estate throw.
        int port = allocator.allocate(19900, 19999);
        assertEquals(19900, port);
        allocator.release(port);
    }
}
