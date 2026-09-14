package io.github.hectorvent.floci.services.elasticache.proxy;

import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Shared TCP auth-proxy skeleton for the Redis (RESP) wire protocol. Intercepts the
 * {@code AUTH} command, validates credentials through the subclass, then becomes a
 * transparent byte relay to the backend container. Used by both the ElastiCache and
 * MemoryDB proxies, since MemoryDB's real AWS design is explicitly modeled on
 * ElastiCache: same Redis-compatible wire protocol, same IAM-auth mechanism.
 *
 * <p>Uses Java virtual threads to accept connections and run the AUTH handshake.
 */
public abstract class AbstractRedisAuthProxy {

    private static final byte[] OK_RESPONSE = "+OK\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NOAUTH_RESPONSE =
            "-NOAUTH Authentication required.\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INVALID_AUTH_RESPONSE =
            "-ERR invalid username-password pair or user is disabled.\r\n"
                    .getBytes(StandardCharsets.UTF_8);
    private static final byte[] WRONG_ARGS_RESPONSE =
            "-ERR wrong number of arguments for 'auth' command\r\n"
                    .getBytes(StandardCharsets.UTF_8);

    private final Logger log;
    private final String serviceName;
    private final String threadPrefix;
    private final String resourceId;
    private final String backendHost;
    private final int backendPort;

    private volatile boolean running;
    private ServerSocket serverSocket;

    protected AbstractRedisAuthProxy(Logger log, String serviceName, String threadPrefix,
                                      String resourceId, String backendHost, int backendPort) {
        this.log = log;
        this.serviceName = serviceName;
        this.threadPrefix = threadPrefix;
        this.resourceId = resourceId;
        this.backendHost = backendHost;
        this.backendPort = backendPort;
    }

    /** Whether the {@code AUTH} command is required before bridging to the backend. */
    protected abstract boolean authRequired();

    /** Validates the supplied credentials. Not called when {@link #authRequired()} is false. */
    protected abstract boolean authenticate(String username, String password);

    /** Closes a socket, tolerating errors. Implementations may add their own logging. */
    protected abstract void closeQuietly(Socket socket);

    public void start(int proxyPort) throws IOException {
        serverSocket = new ServerSocket(proxyPort);
        running = true;
        Thread.ofVirtual().name(threadPrefix + "-proxy-accept-" + resourceId).start(this::acceptLoop);
        log.infov("{0} proxy started for {1} on port {2} → {3}:{4}",
                serviceName, resourceId, String.valueOf(proxyPort), backendHost, String.valueOf(backendPort));
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            log.warnv("Error closing proxy server socket for {0}: {1}", resourceId, e.getMessage());
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                Thread.ofVirtual().name(threadPrefix + "-proxy-conn-" + resourceId)
                        .start(() -> handleConnection(client));
            } catch (IOException e) {
                if (running) {
                    log.warnv("Accept error for {0}: {1}", resourceId, e.getMessage());
                }
            }
        }
    }

    private void handleConnection(Socket client) {
        try {
            client.setTcpNoDelay(true);
            RespReader reader = new RespReader(client.getInputStream());
            String[] cmd = reader.readCommand();

            if (cmd.length == 0) {
                closeQuietly(client);
                return;
            }

            if (cmd[0].equalsIgnoreCase("AUTH")) {
                handleAuth(client, cmd);
            } else if (authRequired()) {
                client.getOutputStream().write(NOAUTH_RESPONSE);
                client.getOutputStream().flush();
                closeQuietly(client);
            } else {
                // No auth required and no AUTH command: bridge immediately.
                // First re-send the already-read command to the backend.
                Socket backend = new Socket(backendHost, backendPort);
                backend.setTcpNoDelay(true);
                resendCommand(cmd, backend.getOutputStream());
                bridge(client, backend);
            }
        } catch (Exception e) {
            log.debugv("Connection error for {0}: {1}", resourceId, e.getMessage());
            closeQuietly(client);
        }
    }

    private void handleAuth(Socket client, String[] cmd) throws IOException {
        String username;
        String password;

        if (cmd.length == 2) {
            // AUTH password
            username = null;
            password = cmd[1];
        } else if (cmd.length == 3) {
            // AUTH username password
            username = cmd[1];
            password = cmd[2];
        } else {
            client.getOutputStream().write(WRONG_ARGS_RESPONSE);
            client.getOutputStream().flush();
            closeQuietly(client);
            return;
        }

        boolean authenticated = !authRequired() || authenticate(username, password);
        if (!authenticated) {
            client.getOutputStream().write(INVALID_AUTH_RESPONSE);
            client.getOutputStream().flush();
            closeQuietly(client);
            return;
        }

        client.getOutputStream().write(OK_RESPONSE);
        client.getOutputStream().flush();

        Socket backend = new Socket(backendHost, backendPort);
        backend.setTcpNoDelay(true);
        bridge(client, backend);
    }

    /**
     * Relay I/O runs on platform daemon threads (not virtual threads). A parent virtual thread
     * from {@code handleConnection} blocks in {@code join} here; scheduling nested virtual-thread
     * relays under load can stall delivery of backend responses (e.g. PING/PONG) to the client.
     */
    private void bridge(Socket client, Socket backend) {
        Thread t1 = Thread.ofPlatform().daemon(true).name(threadPrefix + "-relay-c2b-" + resourceId)
                .start(() -> relay(client, backend));
        Thread t2 = Thread.ofPlatform().daemon(true).name(threadPrefix + "-relay-b2c-" + resourceId)
                .start(() -> relay(backend, client));
        try {
            t1.join();
            t2.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeQuietly(client);
            closeQuietly(backend);
        }
    }

    private static void relay(Socket from, Socket to) {
        byte[] buf = new byte[8192];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
            // Normal when either side closes the connection
        }
    }

    private static void resendCommand(String[] args, OutputStream out) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("*").append(args.length).append("\r\n");
        for (String arg : args) {
            byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
            sb.append("$").append(bytes.length).append("\r\n");
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            sb.setLength(0);
            out.write(bytes);
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        if (sb.length() > 0) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        out.flush();
    }
}
