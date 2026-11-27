package io.floci.aws.core.common.docker;

import io.floci.aws.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A Docker daemon named by an {@code ssh://[user@]host[:port]} endpoint, as a Docker context created with
 * {@code docker context create --docker host=ssh://...} names it.
 *
 * <p>docker-java has no SSH transport. As the Docker CLI does, each connection runs the system {@code ssh} client
 * with {@code docker system dial-stdio} on the remote host ({@link #dialStdioCommand(URI)}, {@link SshStdioSocket}),
 * so the user's own {@code ~/.ssh/config}, keys and agent apply, and the remote host's own Docker settings choose the
 * daemon socket. A container port Floci publishes is then bound on the remote host, so {@link #publishedPortHost()}
 * names that host in place of {@code localhost}.
 */
@ApplicationScoped
public class SshDockerEndpoint {

    private static final Logger LOG = Logger.getLogger(SshDockerEndpoint.class);

    static final Duration RESOLVE_TIMEOUT = Duration.ofSeconds(10);
    private static final String LOCAL_HOST = "localhost";

    private final Supplier<String> configuredHost;
    private final Function<List<String>, String> commandOutput;

    private volatile String publishedPortHost;

    @Inject
    public SshDockerEndpoint(EmulatorConfig config) {
        this(() -> DockerClientProducer.resolveDockerConnection(config.docker(), System::getenv).host(),
                command -> runForOutput(command, RESOLVE_TIMEOUT));
    }

    SshDockerEndpoint(Supplier<String> configuredHost, Function<List<String>, String> commandOutput) {
        this.configuredHost = configuredHost;
        this.commandOutput = commandOutput;
    }

    /** An endpoint for a daemon that is never reached over SSH. */
    public static SshDockerEndpoint none() {
        return new SshDockerEndpoint(() -> null, command -> null);
    }

    static boolean isSshHost(String dockerHost) {
        return dockerHost != null && dockerHost.toLowerCase(Locale.ROOT).startsWith("ssh://");
    }

    /**
     * The host a natively running Floci reaches a container's published port on: {@code localhost}, or the remote
     * host's address when the daemon is reached over SSH. Resolved once; two first callers may both resolve it.
     */
    public String publishedPortHost() {
        String resolved = publishedPortHost;
        if (resolved == null) {
            String dockerHost = configuredHost.get();
            resolved = isSshHost(dockerHost) ? resolveHostName(URI.create(dockerHost)) : LOCAL_HOST;
            publishedPortHost = resolved;
        }
        return resolved;
    }

    private String resolveHostName(URI sshHost) {
        List<String> command = new ArrayList<>(List.of("ssh", "-G"));
        command.addAll(destination(sshHost));
        String output = commandOutput.apply(command);
        String hostName = output == null ? null : hostNameFromConfig(output);
        if (hostName == null) {
            LOG.warnv("Could not resolve the address of SSH host {0} with ssh -G; using it as written",
                    hostOf(sshHost));
            return hostOf(sshHost);
        }
        return hostName;
    }

    /**
     * The standard output of {@code command}, or null when it fails or outlasts {@code timeout}. The output is read
     * beside the bounded wait, so a process that never closes its output cannot hold the caller past the timeout.
     */
    static String runForOutput(List<String> command, Duration timeout) {
        Process process;
        try {
            process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException e) {
            LOG.warnv("Could not run {0}: {1}", command, e.getMessage());
            return null;
        }
        return runForOutput(process, command, timeout);
    }

    static String runForOutput(Process process, List<String> command, Duration timeout) {
        CompletableFuture<String> output = new CompletableFuture<>();
        Thread.ofVirtual().name("docker-ssh-resolve").start(() -> {
            try (InputStream stdout = process.getInputStream()) {
                output.complete(new String(stdout.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                output.completeExceptionally(e);
            }
        });
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                LOG.warnv("{0} did not finish within {1}s", command, timeout.toSeconds());
                return null;
            }
            if (process.exitValue() != 0) {
                return null;
            }
            return output.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | TimeoutException e) {
            LOG.warnv("Could not read the output of {0}: {1}", command, e.getMessage());
            return null;
        } finally {
            process.destroyForcibly();
        }
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

    /** The command whose standard input and output carry one connection to the daemon of {@code sshHost}. */
    static List<String> dialStdioCommand(URI sshHost) {
        List<String> command = new ArrayList<>(List.of("ssh",
                "-o", "BatchMode=yes",
                "-o", "ConnectTimeout=" + SshStdioSocket.DEFAULT_CONNECT_TIMEOUT_MILLIS / 1000,
                "-o", "LogLevel=VERBOSE",
                "-o", "ServerAliveInterval=30"));
        command.addAll(destination(sshHost));
        command.addAll(List.of("docker", "system", "dial-stdio"));
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
}
