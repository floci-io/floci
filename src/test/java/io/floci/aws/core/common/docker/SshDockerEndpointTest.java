package io.floci.aws.core.common.docker;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
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
    void runForOutputGivesUpOnACommandThatOutlastsTheTimeout() throws IOException {
        StalledProcess stalled = new StalledProcess();

        String output = SshDockerEndpoint.runForOutput(stalled, List.of("ssh", "-G", "remote"), Duration.ofSeconds(10));

        assertThat(output, is(nullValue()));
        assertThat(stalled.destroyed, is(true));
    }

    @Test
    void isSshHostMatchesTheSchemeInAnyCase() {
        assertThat(SshDockerEndpoint.isSshHost("SSH://remote"), is(true));
        assertThat(SshDockerEndpoint.isSshHost("tcp://ssh:2375"), is(false));
        assertThat(SshDockerEndpoint.isSshHost(null), is(false));
    }

    /** Holds its output open and never exits within the wait, as a stalled {@code ssh -G} would. */
    private static final class StalledProcess extends Process {

        private final PipedOutputStream stdoutWriter = new PipedOutputStream();
        private final PipedInputStream stdout;
        private volatile boolean destroyed;

        StalledProcess() throws IOException {
            this.stdout = new PipedInputStream(stdoutWriter);
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return false;
        }

        @Override
        public int exitValue() {
            throw new IllegalThreadStateException("process has not exited");
        }

        @Override
        public void destroy() {
            destroyForcibly();
        }

        @Override
        public Process destroyForcibly() {
            destroyed = true;
            try {
                stdoutWriter.close();
            } catch (IOException ignored) {
                // Closing the pipe releases the reader thread; a second close is harmless.
            }
            return this;
        }
    }
}
