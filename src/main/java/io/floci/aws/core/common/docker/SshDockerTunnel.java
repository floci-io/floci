package io.floci.aws.core.common.docker;

import io.floci.aws.config.EmulatorConfig;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Reaches a Docker daemon named by an {@code ssh://[user@]host[:port]} endpoint, as a Docker context created with
 * {@code docker context create --docker host=ssh://...} names it.
 *
 * <p>docker-java has no SSH transport, so Floci runs the system {@code ssh} client once, forwarding a loopback TCP
 * port to the daemon's socket on the remote host, and talks to the daemon over that port. The client reads the
 * user's own {@code ~/.ssh/config}, keys and agent, as the Docker CLI's own SSH connection does. A container port
 * Floci publishes is then bound on the remote host, so {@link #publishedPortHost()} names that host in place of
 * {@code localhost}.
 */
@ApplicationScoped
public class SshDockerTunnel {

    private static final Logger LOG = Logger.getLogger(SshDockerTunnel.class);

    /** The socket the Docker Engine listens on by default; the CLI's {@code docker system dial-stdio} reaches the same one. */
    static final String REMOTE_DOCKER_SOCKET = "/var/run/docker.sock";

    private static final Duration START_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration RESOLVE_TIMEOUT = Duration.ofSeconds(10);
    private static final String LOCAL_HOST = "localhost";

    private final Supplier<String> configuredHost;

    private Process tunnel;
    private String tunnelEndpoint;
    private String publishedPortHost;

    @Inject
    public SshDockerTunnel(EmulatorConfig config) {
        this(() -> DockerClientProducer.resolveDockerConnection(config.docker(), System::getenv).host());
    }

    SshDockerTunnel(Supplier<String> configuredHost) {
        this.configuredHost = configuredHost;
    }

    /** A tunnel for a daemon that is never reached over SSH. */
    public static SshDockerTunnel none() {
        return new SshDockerTunnel(() -> null);
    }

    static boolean isSshHost(String dockerHost) {
        return dockerHost != null && dockerHost.toLowerCase(Locale.ROOT).startsWith("ssh://");
    }

    /**
     * The endpoint the Docker client connects to for {@code dockerHost}: the same value, unless it is an
     * {@code ssh://} endpoint, which is replaced by the loopback end of a tunnel started on first use.
     */
    public synchronized String connectableHost(String dockerHost) {
        if (!isSshHost(dockerHost)) {
            return dockerHost;
        }
        if (tunnel == null || !tunnel.isAlive()) {
            tunnelEndpoint = start(URI.create(dockerHost));
        }
        return tunnelEndpoint;
    }

    /**
     * The host a natively running Floci reaches a container's published port on: {@code localhost}, or the remote
     * host's address when the daemon is reached over SSH.
     */
    public synchronized String publishedPortHost() {
        if (publishedPortHost == null) {
            String dockerHost = configuredHost.get();
            publishedPortHost = isSshHost(dockerHost) ? resolveHostName(URI.create(dockerHost)) : LOCAL_HOST;
        }
        return publishedPortHost;
    }

    @PreDestroy
    synchronized void close() {
        if (tunnel != null) {
            tunnel.destroy();
            tunnel = null;
        }
    }

    private String start(URI sshHost) {
        int localPort = freeLoopbackPort();
        List<String> command = tunnelCommand(sshHost, localPort);
        LOG.infov("Reaching Docker daemon {0} through an SSH tunnel on 127.0.0.1:{1,number,#}", sshHost, localPort);
        Process process;
        try {
            process = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException e) {
            throw new IllegalStateException("Could not run the ssh client to reach Docker daemon " + sshHost
                    + "; is OpenSSH on the PATH? " + e.getMessage(), e);
        }
        StringBuilder errors = new StringBuilder();
        Thread.ofVirtual().name("docker-ssh-tunnel-stderr").start(() -> drain(process, errors));
        awaitListening(process, sshHost, localPort, errors);
        process.onExit().thenRun(() -> LOG.warnv("SSH tunnel to Docker daemon {0} exited with status {1}: {2}",
                sshHost, process.exitValue(), errorsOf(errors)));
        tunnel = process;
        return "tcp://127.0.0.1:" + localPort;
    }

    private static void awaitListening(Process process, URI sshHost, int localPort, StringBuilder errors) {
        long deadline = System.nanoTime() + START_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException("SSH tunnel to Docker daemon " + sshHost + " exited with status "
                        + process.exitValue() + ": " + errorsOf(errors));
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), localPort), 200);
                return;
            } catch (IOException notYetListening) {
                LOG.tracev("SSH tunnel on port {0,number,#} not listening yet: {1}", localPort,
                        notYetListening.getMessage());
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroy();
                throw new IllegalStateException("Interrupted while starting the SSH tunnel to " + sshHost, e);
            }
        }
        process.destroy();
        throw new IllegalStateException("SSH tunnel to Docker daemon " + sshHost + " did not listen within "
                + START_TIMEOUT.toSeconds() + "s: " + errorsOf(errors));
    }

    private static void drain(Process process, StringBuilder errors) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (errors) {
                    errors.append(errors.isEmpty() ? "" : " | ").append(line);
                }
                LOG.debugv("ssh: {0}", line);
            }
        } catch (IOException e) {
            LOG.debugv("Stopped reading the SSH tunnel''s errors: {0}", e.getMessage());
        }
    }

    private static String errorsOf(StringBuilder errors) {
        synchronized (errors) {
            return errors.isEmpty() ? "no error output" : errors.toString();
        }
    }

    private static String resolveHostName(URI sshHost) {
        List<String> command = new ArrayList<>(List.of("ssh", "-G"));
        command.addAll(destination(sshHost));
        try {
            Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.waitFor(RESOLVE_TIMEOUT.toSeconds(), TimeUnit.SECONDS) && process.exitValue() == 0) {
                String hostName = hostNameFromConfig(output);
                if (hostName != null) {
                    return hostName;
                }
            }
        } catch (IOException e) {
            LOG.warnv("Could not resolve the address of SSH host {0}; using it as written: {1}",
                    hostOf(sshHost), e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return hostOf(sshHost);
    }

    /** The {@code hostname} of {@code ssh -G} output, where an alias in {@code ~/.ssh/config} names its real address. */
    static String hostNameFromConfig(String sshConfigOutput) {
        for (String line : sshConfigOutput.split("\\R")) {
            if (line.startsWith("hostname ")) {
                String value = line.substring("hostname ".length()).trim();
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    static List<String> tunnelCommand(URI sshHost, int localPort) {
        List<String> command = new ArrayList<>(List.of("ssh", "-N",
                "-o", "BatchMode=yes",
                "-o", "ExitOnForwardFailure=yes",
                "-o", "ServerAliveInterval=30",
                "-L", "127.0.0.1:" + localPort + ":" + REMOTE_DOCKER_SOCKET));
        command.addAll(destination(sshHost));
        return command;
    }

    /**
     * The {@code ssh} arguments naming the host of {@code ssh://[user@]host[:port]}. The authority is split by hand
     * rather than through {@link URI#getHost()}, which is null for an {@code ~/.ssh/config} alias that is not a
     * valid DNS name, such as one with an underscore.
     */
    static List<String> destination(URI sshHost) {
        String authority = sshHost.getAuthority();
        if (authority == null || authority.isBlank()) {
            throw new IllegalArgumentException("Docker host " + sshHost + " names no SSH host");
        }
        List<String> destination = new ArrayList<>();
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            destination.addAll(List.of("-l", authority.substring(0, at)));
            authority = authority.substring(at + 1);
        }
        int colon = authority.lastIndexOf(':');
        if (colon >= 0 && !authority.endsWith("]")) {
            destination.addAll(List.of("-p", authority.substring(colon + 1)));
            authority = authority.substring(0, colon);
        }
        destination.add(authority);
        return destination;
    }

    private static String hostOf(URI sshHost) {
        List<String> destination = destination(sshHost);
        return destination.get(destination.size() - 1);
    }

    private static int freeLoopbackPort() {
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not find a free loopback port for the SSH tunnel", e);
        }
    }
}
