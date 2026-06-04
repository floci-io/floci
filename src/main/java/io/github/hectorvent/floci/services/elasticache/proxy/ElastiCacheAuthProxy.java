package io.github.hectorvent.floci.services.elasticache.proxy;

import io.github.hectorvent.floci.services.elasticache.model.AuthMode;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * TCP auth proxy for a single ElastiCache replication group.
 * Intercepts the Redis AUTH command, validates credentials (IAM or password),
 * then becomes a transparent byte relay to the backend Valkey container.
 *
 * <p>Uses Java virtual threads for non-blocking I/O handling.
 */
public class ElastiCacheAuthProxy {

    private static final Logger LOG = Logger.getLogger(ElastiCacheAuthProxy.class);

    private static final byte[] OK_RESPONSE = "+OK\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NOAUTH_RESPONSE =
            "-NOAUTH Authentication required.\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INVALID_AUTH_RESPONSE =
            "-ERR invalid username-password pair or user is disabled.\r\n"
                    .getBytes(StandardCharsets.UTF_8);
    private static final byte[] WRONG_ARGS_RESPONSE =
            "-ERR wrong number of arguments for 'auth' command\r\n"
                    .getBytes(StandardCharsets.UTF_8);

    private final String groupId;
    private final AuthMode authMode;
    private final String backendHost;
    private final int backendPort;
    private final PasswordValidator passwordValidator;
    private final SigV4Validator sigV4Validator;

    private volatile boolean running;
    private ServerSocket serverSocket;

    public ElastiCacheAuthProxy(String groupId, AuthMode authMode,
                                String backendHost, int backendPort,
                                PasswordValidator passwordValidator,
                                SigV4Validator sigV4Validator) {
        this.groupId = groupId;
        this.authMode = authMode;
        this.backendHost = backendHost;
        this.backendPort = backendPort;
        this.passwordValidator = passwordValidator;
        this.sigV4Validator = sigV4Validator;
    }

    public void start(int proxyPort) throws IOException {
        serverSocket = new ServerSocket(proxyPort);
        running = true;
        Thread.ofVirtual().name("ec-proxy-accept-" + groupId).start(this::acceptLoop);
        LOG.infov("ElastiCache proxy started for group {0} on port {1} → {2}:{3}",
                groupId, String.valueOf(proxyPort), backendHost, String.valueOf(backendPort));
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            LOG.warnv("Error closing proxy server socket for group {0}: {1}", groupId, e.getMessage());
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                Thread.ofVirtual().name("ec-proxy-conn-" + groupId).start(() -> handleConnection(client));
            } catch (IOException e) {
                if (running) {
                    LOG.warnv("Accept error for group {0}: {1}", groupId, e.getMessage());
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
            } else if (authMode != AuthMode.NO_AUTH) {
                client.getOutputStream().write(NOAUTH_RESPONSE);
                client.getOutputStream().flush();
                closeQuietly(client);
            } else {
                // No auth required and no AUTH command — bridge immediately
                // First re-send the already-read command to the backend
                Socket backend = new Socket(backendHost, backendPort);
                backend.setTcpNoDelay(true);
                resendCommand(cmd, backend.getOutputStream());
                bridge(client, backend);
            }
        } catch (Exception e) {
            LOG.debugv("Connection error for group {0}: {1}", groupId, e.getMessage());
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

        boolean authenticated = validate(username, password);
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

    private boolean validate(String username, String password) {
        return switch (authMode) {
            case IAM -> sigV4Validator.validate(password, groupId, username);
            case PASSWORD -> passwordValidator.validatePassword(username, password);
            case NO_AUTH -> true;
        };
    }

    /**
     * Relay I/O runs on platform daemon threads (not virtual threads). A parent virtual thread
     * from {@code handleConnection} blocks in {@code join} here; scheduling nested virtual-thread
     * relays under load can stall delivery of backend responses (e.g. PING/PONG) to the client.
     */
    private void bridge(Socket client, Socket backend) {
        Thread t1 = Thread.ofPlatform().daemon(true).name("ec-relay-c2b-" + groupId)
                .start(() -> relay(client, backend));
        Thread t2 = Thread.ofPlatform().daemon(true).name("ec-relay-b2c-" + groupId)
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

    private static void closeQuietly(Socket s) {
        try { s.close(); } catch (IOException ignored) {}
    }

    /**
     * Callback interface for password validation, provided by ElastiCacheService.
     */
    @FunctionalInterface
    public interface PasswordValidator {
        boolean validatePassword(String username, String password);
    }
}
