package io.floci.aws.core.common.docker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.LogConfig;
import com.github.dockerjava.api.model.StreamType;
import io.floci.aws.config.EmulatorConfig;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * The opt-in log-driver mode ({@code floci.docker.log-driver-mode}): long-lived service containers are
 * started with Docker's {@code gelf} log driver pointed at this listener, so the daemon pushes each log
 * line over TCP and the container holds no follow stream and needs no read on a timer. Docker opens the
 * connection itself, from wherever the daemon runs, so the mode needs a daemon that can reach Floci:
 * Docker Desktop (through {@code host.docker.internal}) or a daemon on Floci's own host, not
 * Docker-in-Docker or a remote daemon.
 *
 * <p>Each container gets a random tag, sent with every message as {@code _tag}, and a message whose tag
 * no container registered is dropped, so another container cannot write into a service's logs. A
 * message is one null-terminated GELF JSON document; its {@code level} tells standard error (3) from
 * standard output (6).
 */
@ApplicationScoped
public class GelfLogListener {

    private static final Logger LOG = Logger.getLogger(GelfLogListener.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_MESSAGE_BYTES = 1024 * 1024;
    private static final int STDERR_LEVEL = 3;
    private static final int MAX_PENDING_LINES = 1000;
    private static final Duration UNCLAIMED_EXPIRY = Duration.ofMinutes(10);

    /** Label carrying a container's tag, so its log can be claimed when it is attached. */
    static final String LOG_TOKEN_LABEL = "io.floci.log-token";

    /** Services whose containers run until deleted, the ones whose logs Floci reads rather than follows. */
    static final Set<String> LONG_LIVED_SERVICES = Set.of("rds", "docdb", "neptune", "elasticache", "memorydb",
            "msk", "redshift", "amazonmq", "kinesisanalytics", "ecr");

    private final DockerClient dockerClient;
    private final boolean enabled;
    private final String configuredHost;
    private final Map<String, BiConsumer<StreamType, String>> sinks = new ConcurrentHashMap<>();
    /** Lines of containers that started but are not attached yet, by the tag this listener issued them. */
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Object startLock = new Object();
    private ServerSocket server;
    private volatile String address;

    @Inject
    public GelfLogListener(DockerClient dockerClient, EmulatorConfig config) {
        this(dockerClient, config.docker().logDriverMode(), config.docker().logDriverHost().orElse(null));
    }

    GelfLogListener(DockerClient dockerClient, boolean enabled, String configuredHost) {
        this.dockerClient = dockerClient;
        this.enabled = enabled;
        this.configuredHost = configuredHost;
    }

    public boolean enabled() {
        return enabled;
    }

    /** A fresh tag for a container about to be created. */
    static String newToken() {
        return UUID.randomUUID().toString();
    }

    /**
     * The {@code gelf} log configuration that sends a container's log here under {@code token}. The daemon
     * sends from the moment the container starts, before its owner attaches, so the lines that arrive
     * first are held (up to {@value #MAX_PENDING_LINES}) until it does.
     */
    LogConfig logConfigFor(String token) {
        long now = System.nanoTime();
        pending.values().removeIf(waiting -> now - waiting.issuedAt() > UNCLAIMED_EXPIRY.toNanos());
        pending.put(token, new Pending(now, new ArrayDeque<>()));
        return new LogConfig(LogConfig.LoggingType.GELF, Map.of(
                "gelf-address", "tcp://" + ensureStarted(),
                "tag", token));
    }

    void register(String token, BiConsumer<StreamType, String> sink) {
        Pending waiting = pending.get(token);
        if (waiting == null) {
            sinks.put(token, sink);
            return;
        }
        synchronized (waiting) {
            for (String[] line : waiting.lines()) {
                sink.accept(StreamType.valueOf(line[0]), line[1]);
            }
            sinks.put(token, sink);
            pending.remove(token);
        }
    }

    void unregister(String token) {
        sinks.remove(token);
        pending.remove(token);
    }

    /** Starts listening on first use and returns the {@code host:port} the daemon dials. */
    String ensureStarted() {
        synchronized (startLock) {
            if (address != null) {
                return address;
            }
            String host = configuredHost != null ? configuredHost : daemonFacingHost();
            try {
                ServerSocket socket = new ServerSocket();
                socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
                server = socket;
            } catch (IOException e) {
                throw new IllegalStateException("Could not open the Docker log-driver listener", e);
            }
            address = host + ":" + server.getLocalPort();
            Thread.ofVirtual().name("floci-gelf-accept").start(this::acceptLoop);
            LOG.infov("Long-lived service containers send their logs to Floci over GELF at {0}", address);
            return address;
        }
    }

    // Docker Desktop's daemon runs in a VM and reaches the host through host.docker.internal; a daemon on
    // this host reaches its loopback directly.
    private String daemonFacingHost() {
        boolean desktop = DockerDaemon.operatingSystem(dockerClient).map(DockerDaemon::isDockerDesktop).orElse(false);
        return desktop ? "host.docker.internal" : "127.0.0.1";
    }

    private void acceptLoop() {
        ServerSocket socket = server;
        while (!socket.isClosed()) {
            try {
                Socket connection = socket.accept();
                Thread.ofVirtual().name("floci-gelf-connection").start(() -> read(connection));
            } catch (IOException e) {
                if (!socket.isClosed()) {
                    LOG.debugv("Docker log-driver listener could not accept a connection: {0}", e.getMessage());
                }
            }
        }
    }

    private void read(Socket connection) {
        try (connection; InputStream in = connection.getInputStream()) {
            readMessages(in);
        } catch (IOException e) {
            LOG.debugv("Docker log-driver connection ended: {0}", e.getMessage());
        }
    }

    /** Reads null-terminated GELF messages until the stream ends, handing each to its container's sink. */
    void readMessages(InputStream in) throws IOException {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        boolean oversized = false;
        int b;
        while ((b = in.read()) != -1) {
            if (b != 0) {
                if (message.size() < MAX_MESSAGE_BYTES) {
                    message.write(b);
                } else {
                    oversized = true;
                }
                continue;
            }
            if (oversized) {
                LOG.debugv("Dropped a GELF message over {0} bytes", MAX_MESSAGE_BYTES);
            } else if (message.size() > 0) {
                deliver(message.toByteArray());
            }
            message.reset();
            oversized = false;
        }
    }

    private void deliver(byte[] document) {
        JsonNode gelf;
        try {
            gelf = JSON.readTree(document);
        } catch (IOException e) {
            LOG.debugv("Dropped a GELF message that is not JSON: {0}", e.getMessage());
            return;
        }
        String token = gelf.path("_tag").asText("");
        StreamType stream = gelf.path("level").asInt(6) == STDERR_LEVEL ? StreamType.STDERR : StreamType.STDOUT;
        String line = gelf.path("short_message").asText("");
        BiConsumer<StreamType, String> sink = sinks.get(token);
        if (sink == null) {
            Pending waiting = pending.get(token);
            if (waiting == null) {
                return;
            }
            synchronized (waiting) {
                sink = sinks.get(token);
                if (sink == null) {
                    if (waiting.lines().size() < MAX_PENDING_LINES) {
                        waiting.lines().add(new String[] {stream.name(), line});
                    }
                    return;
                }
            }
        }
        sink.accept(stream, line);
    }

    private record Pending(long issuedAt, Deque<String[]> lines) {
    }

    void onShutdown(@Observes ShutdownEvent event) {
        close();
    }

    void close() {
        synchronized (startLock) {
            if (server != null) {
                try {
                    server.close();
                } catch (IOException e) {
                    LOG.debugv("Could not close the Docker log-driver listener: {0}", e.getMessage());
                }
            }
        }
    }
}
