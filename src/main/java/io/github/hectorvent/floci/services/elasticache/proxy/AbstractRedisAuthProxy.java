package io.github.hectorvent.floci.services.elasticache.proxy;

import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Shared TCP auth-proxy skeleton for the Redis (RESP) wire protocol. Intercepts the
 * {@code AUTH} command or the {@code AUTH} option in {@code HELLO}, validates credentials
 * through the subclass, then becomes a transparent byte relay to the backend container.
 * Used by both the ElastiCache and MemoryDB proxies, since MemoryDB's real AWS design is
 * explicitly modeled on ElastiCache: same Redis-compatible wire protocol, same IAM-auth
 * mechanism.
 *
 * <p>Uses Java virtual threads to accept connections and run the AUTH handshake.
 */
public abstract class AbstractRedisAuthProxy {
    private static final long RELAY_JOIN_TIMEOUT_MILLIS = 1_000;

    private static final byte[] OK_RESPONSE = "+OK\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NOAUTH_RESPONSE =
            "-NOAUTH Authentication required.\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INVALID_AUTH_RESPONSE =
            "-ERR invalid username-password pair or user is disabled.\r\n"
                    .getBytes(StandardCharsets.UTF_8);
    private static final byte[] WRONGPASS_RESPONSE =
            "-WRONGPASS invalid username-password pair or user is disabled.\r\n"
                    .getBytes(StandardCharsets.UTF_8);
    private static final byte[] WRONG_ARGS_RESPONSE =
            "-ERR wrong number of arguments for 'auth' command\r\n"
                    .getBytes(StandardCharsets.UTF_8);
    private static final byte[] HELLO_NOAUTH_RESPONSE =
            ("-NOAUTH HELLO must be called with the client already authenticated, otherwise the "
                    + "HELLO <proto> AUTH <user> <pass> option can be used to authenticate the client "
                    + "and select the RESP protocol version at the same time\r\n")
                    .getBytes(StandardCharsets.UTF_8);
    private static final byte[] HELLO_PROTOCOL_ERROR_RESPONSE =
            "-ERR Protocol version is not an integer or out of range\r\n"
                    .getBytes(StandardCharsets.UTF_8);
    private static final byte[] HELLO_UNSUPPORTED_PROTOCOL_RESPONSE =
            "-NOPROTO unsupported protocol version\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INVALID_CLIENT_NAME_RESPONSE =
            "-ERR Client names cannot contain spaces, newlines or special characters.\r\n"
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
            while (true) {
                String[] cmd = reader.readCommand();

                if (cmd.length == 0) {
                    closeQuietly(client);
                    return;
                }

                if (cmd[0].equalsIgnoreCase("AUTH")) {
                    handleAuth(client, cmd);
                    return;
                }
                if (cmd[0].equalsIgnoreCase("HELLO")) {
                    if (authRequired()) {
                        if (handleHello(client, cmd)) {
                            return;
                        }
                        continue;
                    }
                    bridgeCommand(client, cmd);
                    return;
                }
                if (authRequired()) {
                    writeResponse(client, NOAUTH_RESPONSE);
                    closeQuietly(client);
                    return;
                }

                bridgeCommand(client, cmd);
                return;
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
            writeResponse(client, INVALID_AUTH_RESPONSE);
            closeQuietly(client);
            return;
        }

        writeResponse(client, OK_RESPONSE);
        bridgeCommand(client, null);
    }

    private boolean handleHello(Socket client, String[] cmd) throws IOException {
        if (cmd.length > 1) {
            long protocolVersion;
            try {
                protocolVersion = Long.parseLong(cmd[1]);
            } catch (NumberFormatException e) {
                writeResponse(client, HELLO_PROTOCOL_ERROR_RESPONSE);
                return false;
            }
            if (protocolVersion < 2 || protocolVersion > 3) {
                writeResponse(client, HELLO_UNSUPPORTED_PROTOCOL_RESPONSE);
                return false;
            }
        }

        List<String> forwarded = new ArrayList<>();
        forwarded.add(cmd[0]);
        if (cmd.length > 1) {
            forwarded.add(cmd[1]);
        }

        String username = null;
        String password = null;
        String clientName = null;
        boolean authProvided = false;
        boolean clientNameProvided = false;
        int optionIndex = 2;
        while (optionIndex < cmd.length) {
            String option = cmd[optionIndex];
            if (option.equalsIgnoreCase("AUTH") && optionIndex + 2 < cmd.length) {
                username = cmd[optionIndex + 1];
                password = cmd[optionIndex + 2];
                authProvided = true;
                optionIndex += 3;
            } else if (option.equalsIgnoreCase("SETNAME") && optionIndex + 1 < cmd.length) {
                clientName = cmd[optionIndex + 1];
                if (!isValidClientName(clientName)) {
                    writeResponse(client, INVALID_CLIENT_NAME_RESPONSE);
                    return false;
                }
                clientNameProvided = true;
                optionIndex += 2;
            } else {
                writeHelloSyntaxError(client, option);
                return false;
            }
        }

        if (clientNameProvided) {
            forwarded.add("SETNAME");
            forwarded.add(clientName);
        }

        if (authRequired() && !authProvided) {
            writeResponse(client, HELLO_NOAUTH_RESPONSE);
            return false;
        }
        if (authRequired() && !authenticate(username, password)) {
            writeResponse(client, WRONGPASS_RESPONSE);
            return false;
        }

        bridgeCommand(client, forwarded.toArray(String[]::new));
        return true;
    }

    private static void writeHelloSyntaxError(Socket client, String option) throws IOException {
        String sanitizedOption = option.replace('\r', ' ').replace('\n', ' ');
        byte[] response = ("-ERR Syntax error in HELLO option '" + sanitizedOption + "'\r\n")
                .getBytes(StandardCharsets.UTF_8);
        writeResponse(client, response);
    }

    private static boolean isValidClientName(String clientName) {
        for (int index = 0; index < clientName.length(); index++) {
            char character = clientName.charAt(index);
            if (character <= ' ' || character > '~') {
                return false;
            }
        }
        return true;
    }

    private void bridgeCommand(Socket client, String[] command) throws IOException {
        Socket backend = new Socket(backendHost, backendPort);
        backend.setTcpNoDelay(true);
        if (command != null) {
            resendCommand(command, backend.getOutputStream());
        }
        bridge(client, backend);
    }

    private static void writeResponse(Socket client, byte[] response) throws IOException {
        client.getOutputStream().write(response);
        client.getOutputStream().flush();
    }

    /**
     * Relay I/O runs on platform daemon threads (not virtual threads). A parent virtual thread
     * from {@code handleConnection} blocks in {@code join} here; scheduling nested virtual-thread
     * relays under load can stall delivery of backend responses (e.g. PING/PONG) to the client.
     */
    private void bridge(Socket client, Socket backend) {
        CountDownLatch firstRelayDone = new CountDownLatch(1);
        Thread t1 = Thread.ofPlatform().daemon(true).name(threadPrefix + "-relay-c2b-" + resourceId)
                .start(() -> {
                    try {
                        relay(client, backend);
                    } finally {
                        firstRelayDone.countDown();
                    }
                });
        Thread t2 = Thread.ofPlatform().daemon(true).name(threadPrefix + "-relay-b2c-" + resourceId)
                .start(() -> {
                    try {
                        relay(backend, client);
                    } finally {
                        firstRelayDone.countDown();
                    }
                });
        try {
            firstRelayDone.await();
            t1.join(RELAY_JOIN_TIMEOUT_MILLIS);
            t2.join(RELAY_JOIN_TIMEOUT_MILLIS);
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
        } finally {
            shutdownOutput(to);
        }
    }

    private static void shutdownOutput(Socket socket) {
        try {
            socket.shutdownOutput();
        } catch (IOException ignored) {
            // The bridge closes both sockets after both relay directions finish.
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
