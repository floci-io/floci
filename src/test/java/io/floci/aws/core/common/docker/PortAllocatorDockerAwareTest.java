package io.floci.aws.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
 * {@link PortAllocator#publishedHostPorts} adds, and what this test
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
        // the published.contains(port) check in allocate() and this assertion
        // fails, allocating 19900 - the pre-fix bug, reproduced without
        // starting a second Docker container at all.
        int allocated = allocator.allocate(19900, 19999);

        assertNotEquals(19900, allocated, "must not allocate a port a container already publishes, even though this process's own socket check sees it as free");
        allocator.release(allocated);
    }

    private static final String FLOCI_ID = "0123456789ab" + "c".repeat(52);

    private static Container container(String id, String name, int... publicPorts) {
        ContainerPort[] ports = new ContainerPort[publicPorts.length];
        for (int i = 0; i < publicPorts.length; i++) {
            ports[i] = new ContainerPort().withPublicPort(publicPorts[i]);
        }
        Container container = mock(Container.class);
        when(container.getId()).thenReturn(id);
        when(container.getNames()).thenReturn(new String[] {"/" + name});
        when(container.getPorts()).thenReturn(ports);
        return container;
    }

    private static DockerClient dockerListing(Container... containers) {
        DockerClient dockerClient = mock(DockerClient.class);
        ListContainersCmd listCmd = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.withShowAll(anyBoolean())).thenReturn(listCmd);
        when(listCmd.exec()).thenReturn(List.of(containers));
        return dockerClient;
    }

    private static CurrentContainerNetworkResolver flociIn(String hostname) {
        CurrentContainerNetworkResolver resolver = mock(CurrentContainerNetworkResolver.class);
        when(resolver.resolveContainerId()).thenReturn(Optional.ofNullable(hostname));
        return resolver;
    }

    @Test
    void anInProcessListenerInAContainerCanUseARangeFlocisOwnContainerPublishes() {
        // Floci's compose file publishes 19900-19902 for its own MWAA web proxies. allocate() reads
        // those as taken and runs out; the listener binds inside Floci's container, where they are free.
        DockerClient dockerClient = dockerListing(container(FLOCI_ID, "floci", 19900, 19901, 19902));
        PortAllocator allocator = new PortAllocator(dockerClient, flociIn("0123456789ab"));

        assertThrows(RuntimeException.class, () -> allocator.allocate(19900, 19902));
        int port = allocator.allocateForInProcessListener(19900, 19902);

        assertEquals(19900, port);
        allocator.release(port);
    }

    @Test
    void anInProcessListenerInAContainerSkipsAPortAnotherContainerPublishes() {
        // Floci publishes only part of the range and another container a port in it: the host routes
        // that port to the other container, so a proxy listening on it in Floci's container is unreachable.
        DockerClient dockerClient = dockerListing(container(FLOCI_ID, "floci", 19901),
                container("fedcba987654" + "d".repeat(52), "other", 19900));
        PortAllocator allocator = new PortAllocator(dockerClient, flociIn("0123456789ab"));

        int port = allocator.allocateForInProcessListener(19900, 19901);

        assertEquals(19901, port);
        allocator.release(port);
    }

    @Test
    void flocisOwnContainerIsAlsoFoundByTheNameItsHostnameGives() {
        DockerClient dockerClient = dockerListing(container(FLOCI_ID, "floci", 19900));
        PortAllocator allocator = new PortAllocator(dockerClient, flociIn("floci"));

        int port = allocator.allocateForInProcessListener(19900, 19900);

        assertEquals(19900, port);
        allocator.release(port);
    }

    @Test
    void anInProcessListenerOutsideAContainerStillSkipsPublishedPorts() {
        DockerClient dockerClient = dockerListing(container(FLOCI_ID, "some-container", 19900));
        PortAllocator allocator = new PortAllocator(dockerClient, flociIn(null));

        int port = allocator.allocateForInProcessListener(19900, 19999);

        assertNotEquals(19900, port);
        allocator.release(port);
    }

    @Test
    void allocateStillWorksWithNoDockerClient() {
        // The no-arg constructor's own contract, unchanged: every pre-existing
        // caller (this class's sibling PortAllocatorTest, every other service
        // that built a PortAllocator directly before this fix existed) keeps
        // behaving exactly as it did, because publishedHostPorts is a
        // no-op with no DockerClient to ask.
        PortAllocator allocator = new PortAllocator();
        int port = allocator.allocate(19900, 19999);
        assertEquals(19900, port, "with no DockerClient, the first free port in range is still chosen");
        allocator.release(port);
    }

    @Test
    void allocateFetchesTheContainerListOncePerInvocation() {
        DockerClient dockerClient = mock(DockerClient.class);
        ListContainersCmd listCmd = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.withShowAll(anyBoolean())).thenReturn(listCmd);

        // Several leading candidates already published - the exact scenario the
        // Docker check exists for, and the one where a per-candidate round-trip
        // would multiply API calls inside the allocator's lock.
        Container sibling = mock(Container.class);
        when(sibling.getPorts()).thenReturn(new ContainerPort[]{
                new ContainerPort().withPublicPort(19900),
                new ContainerPort().withPublicPort(19901),
                new ContainerPort().withPublicPort(19902),
        });
        when(listCmd.exec()).thenReturn(List.of(sibling));

        PortAllocator allocator = new PortAllocator(dockerClient);
        int allocated = allocator.allocate(19900, 19999);

        assertNotEquals(19900, allocated);
        assertNotEquals(19901, allocated);
        assertNotEquals(19902, allocated);
        // The snapshot is only as fresh as the start of allocate() either way,
        // so one listContainersCmd round-trip must serve every candidate.
        verify(dockerClient, times(1)).listContainersCmd();
        allocator.release(allocated);
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
