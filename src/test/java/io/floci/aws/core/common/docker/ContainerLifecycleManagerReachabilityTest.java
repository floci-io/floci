package io.floci.aws.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.PingCmd;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContainerLifecycleManagerReachabilityTest {

    @Test
    void aDaemonThatAnswersThePingIsReachable() {
        DockerClient docker = mock(DockerClient.class);
        when(docker.pingCmd()).thenReturn(mock(PingCmd.class));

        DockerClient streaming = mock(DockerClient.class);
        ContainerLifecycleManager manager = new ContainerLifecycleManager(docker, streaming, null, null, null);

        assertTrue(manager.isDockerReachable());
    }

    // Catches: a missing daemon surfacing as an exception from the probe, which callers use to tell a
    // missing daemon apart from a container that failed for its own reasons.
    @Test
    void aPingThatFailsReportsTheDaemonUnreachableInsteadOfThrowing() {
        DockerClient docker = mock(DockerClient.class);
        PingCmd ping = mock(PingCmd.class);
        when(ping.exec()).thenThrow(new RuntimeException("java.net.SocketException: No such file or directory"));
        when(docker.pingCmd()).thenReturn(ping);

        DockerClient streaming = mock(DockerClient.class);
        ContainerLifecycleManager manager = new ContainerLifecycleManager(docker, streaming, null, null, null);

        assertFalse(manager.isDockerReachable());
    }
}
