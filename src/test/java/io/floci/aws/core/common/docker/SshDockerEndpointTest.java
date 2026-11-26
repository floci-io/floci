package io.floci.aws.core.common.docker;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SshDockerEndpointTest {

    @Test
    void dialStdioCommandRunsTheDockerRelayOnTheRemoteHost() {
        assertThat(SshDockerEndpoint.dialStdioCommand(URI.create("ssh://remote-docker")), contains("ssh",
                "-o", "BatchMode=yes",
                "-o", "ConnectTimeout=30",
                "-o", "LogLevel=VERBOSE",
                "-o", "ServerAliveInterval=30",
                "remote-docker", "docker", "system", "dial-stdio"));
    }

    @Test
    void destinationPassesUserAndPortAsSshOptions() {
        assertThat(SshDockerEndpoint.destination(URI.create("ssh://ubuntu@10.0.0.5:2222")),
                contains("-l", "ubuntu", "-p", "2222", "10.0.0.5"));
    }

    @Test
    void destinationKeepsAnAliasThatIsNotAValidHostName() {
        assertThat(SshDockerEndpoint.destination(URI.create("ssh://docker_box")), contains("docker_box"));
    }

    @Test
    void destinationRejectsAnEndpointWithNoHost() {
        assertThrows(IllegalArgumentException.class, () -> SshDockerEndpoint.destination(URI.create("ssh:///path")));
    }

    @Test
    void hostNameFromConfigReadsTheResolvedAddressOfAnAlias() {
        assertThat(SshDockerEndpoint.hostNameFromConfig("user ubuntu\nhostname 100.110.111.77\nport 22\n"),
                equalTo("100.110.111.77"));
    }

    @Test
    void hostNameFromConfigIsNullWithoutAHostNameLine() {
        assertThat(SshDockerEndpoint.hostNameFromConfig("user ubuntu\r\nport 22\r\n"), is(nullValue()));
    }

    @Test
    void publishedPortHostIsTheAddressSshResolvesTheAliasTo() {
        AtomicInteger lookups = new AtomicInteger();
        SshDockerEndpoint endpoint = new SshDockerEndpoint(() -> "ssh://ubuntu@remote-docker", command -> {
            lookups.incrementAndGet();
            assertThat(command, contains("ssh", "-G", "-l", "ubuntu", "remote-docker"));
            return "user ubuntu\nhostname 100.110.111.77\n";
        });

        assertThat(endpoint.publishedPortHost(), equalTo("100.110.111.77"));
        assertThat(endpoint.publishedPortHost(), equalTo("100.110.111.77"));
        assertThat(lookups.get(), equalTo(1));
    }

    @Test
    void publishedPortHostFallsBackToTheHostAsWrittenWhenSshCannotResolveIt() {
        SshDockerEndpoint endpoint = new SshDockerEndpoint(() -> "ssh://remote-docker:2222", command -> null);

        assertThat(endpoint.publishedPortHost(), equalTo("remote-docker"));
    }

    @Test
    void publishedPortHostIsLocalhostForADaemonNotReachedOverSsh() {
        SshDockerEndpoint endpoint = new SshDockerEndpoint(() -> "unix:///var/run/docker.sock", command -> {
            throw new AssertionError("ssh must not run for a local daemon");
        });

        assertThat(endpoint.publishedPortHost(), equalTo("localhost"));
        assertThat(SshDockerEndpoint.none().publishedPortHost(), equalTo("localhost"));
    }

    @Test
    void runForOutputGivesUpOnACommandThatOutlastsTheTimeout() {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        long started = System.nanoTime();

        String output = SshDockerEndpoint.runForOutput(
                List.of(java, "-cp", SshStdioSocketTest.testClasses(SleepForever.class), SleepForever.class.getName()),
                Duration.ofSeconds(2));

        assertThat(output, is(nullValue()));
        assertThat(Duration.ofNanos(System.nanoTime() - started).toSeconds(), lessThan(10L));
    }

    @Test
    void isSshHostMatchesTheSchemeInAnyCase() {
        assertThat(SshDockerEndpoint.isSshHost("SSH://remote"), is(true));
        assertThat(SshDockerEndpoint.isSshHost("tcp://ssh:2375"), is(false));
        assertThat(SshDockerEndpoint.isSshHost(null), is(false));
    }

    /** Holds its output open without writing, as a stalled {@code ssh -G} would. */
    public static final class SleepForever {
        public static void main(String[] args) throws InterruptedException {
            Thread.sleep(Long.MAX_VALUE);
        }
    }
}
