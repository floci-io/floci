package io.floci.aws.core.common.docker;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SshDockerTunnelTest {

    @Test
    void tunnelCommandForwardsALoopbackPortToTheRemoteDockerSocket() {
        List<String> command = SshDockerTunnel.tunnelCommand(URI.create("ssh://remote-docker"), 23750);

        assertThat(command, contains("ssh", "-N",
                "-o", "BatchMode=yes",
                "-o", "ExitOnForwardFailure=yes",
                "-o", "ServerAliveInterval=30",
                "-L", "127.0.0.1:23750:/var/run/docker.sock",
                "remote-docker"));
    }

    @Test
    void destinationPassesUserAndPortAsSshOptions() {
        assertThat(SshDockerTunnel.destination(URI.create("ssh://ubuntu@10.0.0.5:2222")),
                contains("-l", "ubuntu", "-p", "2222", "10.0.0.5"));
    }

    @Test
    void destinationKeepsAnAliasThatIsNotAValidHostName() {
        assertThat(SshDockerTunnel.destination(URI.create("ssh://docker_box")), contains("docker_box"));
    }

    @Test
    void destinationRejectsAnEndpointWithNoHost() {
        assertThrows(IllegalArgumentException.class, () -> SshDockerTunnel.destination(URI.create("ssh:///path")));
    }

    @Test
    void hostNameFromConfigReadsTheResolvedAddressOfAnAlias() {
        String output = "user ubuntu\nhostname 100.110.111.77\nport 22\n";

        assertThat(SshDockerTunnel.hostNameFromConfig(output), equalTo("100.110.111.77"));
    }

    @Test
    void hostNameFromConfigIsNullWithoutAHostNameLine() {
        assertThat(SshDockerTunnel.hostNameFromConfig("user ubuntu\r\nport 22\r\n"), is(nullValue()));
    }

    @Test
    void connectableHostLeavesANonSshEndpointAsItIs() {
        SshDockerTunnel tunnel = SshDockerTunnel.none();

        assertThat(tunnel.connectableHost("unix:///var/run/docker.sock"), equalTo("unix:///var/run/docker.sock"));
        assertThat(tunnel.connectableHost("tcp://10.0.0.5:2375"), equalTo("tcp://10.0.0.5:2375"));
    }

    @Test
    void publishedPortHostIsLocalhostForADaemonNotReachedOverSsh() {
        assertThat(new SshDockerTunnel(() -> "unix:///var/run/docker.sock").publishedPortHost(),
                equalTo("localhost"));
        assertThat(SshDockerTunnel.none().publishedPortHost(), equalTo("localhost"));
    }

    @Test
    void isSshHostMatchesTheSchemeInAnyCase() {
        assertThat(SshDockerTunnel.isSshHost("SSH://remote"), is(true));
        assertThat(SshDockerTunnel.isSshHost("tcp://ssh:2375"), is(false));
        assertThat(SshDockerTunnel.isSshHost(null), is(false));
    }
}
