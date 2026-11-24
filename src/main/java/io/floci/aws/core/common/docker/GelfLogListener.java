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
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
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
    // The daemon holds one connection per container; this bounds what any other local process can open.
    private static final int MAX_CONNECTIONS = 1024;

    /** Label carrying a container's tag, so its log can be claimed when it is attached. */
    static final String LOG_TOKEN_LABEL = "io.floci.log-token";

    private final DockerClient dockerClient;
    private final boolean enabled;
    private final String configuredHost;
    private final int port;
    private final Map<String, BiConsumer<StreamType, String>> sinks = new ConcurrentHashMap<>();
    /** Lines of containers that started but are not attached yet, by the tag this listener issued them. */
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final int maxConnections;
    private final Semaphore connections;
    private final Object startLock = new Object();
    private ServerSocket server;
    private volatile String address;

    @Inject
    public GelfLogListener(DockerClient dockerClient, EmulatorConfig config) {
        this(dockerClient, config.docker().logDriverMode(), config.docker().logDriverHost().orElse(null),
                config.docker().logDriverPort(), MAX_CONNECTIONS);
    }

    /** A listener on an ephemeral port, for tests. */
    GelfLogListener(DockerClient dockerClient, boolean enabled, String configuredHost) {
        this(dockerClient, enabled, configuredHost, 0, MAX_CONNECTIONS);
    }

    GelfLogListener(DockerClient dockerClient, boolean enabled, String configuredHost, int port,
                    int maxConnections) {
        this.dockerClient = dockerClient;
        this.enabled = enabled;
        this.configuredHost = configuredHost;
        this.port = port;
        this.maxConnections = maxConnections;
        this.connections = new Semaphore(maxConnections);
    }

    /** A listener for the mode switched off, for the builder and streamer constructed outside CDI. */
    static GelfLogListener disabled() {
        return new GelfLogListener(null, false, null);
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
     * first are held (up to {@value #MAX_PENDING_LINES}) until it does. The json-file rotation the container
     * would have had ({@code max-size}, {@code max-file}) bounds the daemon's local copy of the log instead.
     */
    LogConfig logConfigFor(String token, Map<String, String> jsonFileOptions) {
        expireUnclaimed();
        pending.put(token, new Pending(System.nanoTime(), new ArrayDeque<>()));
        Map<String, String> options = new HashMap<>();
        options.put("gelf-address", "tcp://" + ensureStarted());
        options.put("tag", token);
        options.put("mode", "non-blocking");
        if (jsonFileOptions != null) {
            copyOption(jsonFileOptions, "max-size", options, "cache-max-size");
            copyOption(jsonFileOptions, "max-file", options, "cache-max-file");
        }
        return new LogConfig(LogConfig.LoggingType.GELF, options);
    }

    private static void copyOption(Map<String, String> from, String key, Map<String, String> to, String as) {
        String value = from.get(key);
        if (value != null && !value.isBlank()) {
            to.put(as, value);
        }
    }

    private void expireUnclaimed() {
        long now = System.nanoTime();
        pending.values().removeIf(waiting -> now - waiting.issuedAt() > UNCLAIMED_EXPIRY.toNanos());
    }

    /**
     * Whether a container created with {@code gelfAddress} reaches this listener, starting it if needed. A
     * container created before {@code log-driver-port} or {@code log-driver-host} changed dials elsewhere.
     */
    boolean listensAt(String gelfAddress) {
        if (gelfAddress == null || !gelfAddress.startsWith("tcp://")) {
            return false;
        }
        try {
            return gelfAddress.equals("tcp://" + ensureStarted());
        } catch (IllegalStateException e) {
            LOG.warnv("Docker log-driver listener is not up, so {0} is not reached: {1}", gelfAddress,
                    e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
            return false;
        }
    }

    boolean started() {
        return address != null;
    }

    void register(String token, BiConsumer<StreamType, String> sink) {
        expireUnclaimed();
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

    /** Removes {@code sink}, unless a later attach of the same container has replaced it. */
    void unregister(String token, BiConsumer<StreamType, String> sink) {
        if (sinks.remove(token, sink)) {
            pending.remove(token);
        }
    }

    /** Starts listening on first use and returns the {@code host:port} the daemon dials. */
    String ensureStarted() {
        synchronized (startLock) {
            if (address == null) {
                start();
            }
            return address;
        }
    }

    // Called holding startLock.
    private void start() {
        String host = configuredHost != null ? configuredHost : daemonFacingHost();
        try {
            ServerSocket socket = new ServerSocket();
            socket.bind(new InetSocketAddress(bindAddress(), port));
            server = socket;
        } catch (IOException e) {
            throw new IllegalStateException("Could not open the Docker log-driver listener on port " + port
                    + " (floci.docker.log-driver-port)", e);
        }
        address = host + ":" + server.getLocalPort();
        Thread.ofVirtual().name("floci-gelf-accept").start(this::acceptLoop);
        LOG.infov("Long-lived service containers send their logs to Floci over GELF at {0}", address);
    }

    /**
     * Loopback, unless {@code log-driver-host} names an address of this host (Floci's address on a Docker
     * network, say), which the listener then binds so the daemon's connection reaches it.
     */
    private InetAddress bindAddress() {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        if (configuredHost == null) {
            return loopback;
        }
        try {
            InetAddress configured = InetAddress.getByName(configuredHost);
            if (configured.isLoopbackAddress() || NetworkInterface.getByInetAddress(configured) != null) {
                return configured;
            }
        } catch (IOException e) {
            LOG.debugv("Could not resolve log-driver-host {0}: {1}", configuredHost, e.getMessage());
        }
        LOG.warnv("log-driver-host {0} is not an address of this host, so the log-driver listener stays on "
                + "loopback; the daemon reaches it only if {0} forwards to this host's loopback", configuredHost);
        return loopback;
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
                if (!connections.tryAcquire()) {
                    LOG.warnv("Docker log-driver listener refused a connection from {0}: {1} are already open",
                            connection.getRemoteSocketAddress(), maxConnections);
                    connection.close();
                    continue;
                }
                Thread.ofVirtual().name("floci-gelf-connection").start(() -> {
                    try {
                        read(connection);
                    } finally {
                        connections.release();
                    }
                });
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
                // register may have moved the pending lines to the sink between the two reads.
                sink = sinks.get(token);
                if (sink != null) {
                    sink.accept(stream, line);
                }
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
