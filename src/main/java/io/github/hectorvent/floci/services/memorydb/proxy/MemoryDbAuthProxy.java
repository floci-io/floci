package io.github.hectorvent.floci.services.memorydb.proxy;

import io.github.hectorvent.floci.services.elasticache.proxy.RespReader;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * TCP auth proxy for a single MemoryDB cluster.
 * Intercepts the Redis AUTH command and delegates credential validation to an
 * {@link AuthValidator}, which resolves the supplied user against the cluster's ACL
 * (password or IAM). It then becomes a transparent byte relay to the backend Redis
 * container.
 *
 * <p>Whether authentication is required at all is decided up front from the cluster's
 * ACL: a cluster bound to {@code open-access} (or any ACL whose users require no
 * password) is created with {@code authRequired = false}, in which case the proxy is a
 * straight relay.
 *
 * <p>MemoryDB speaks the Redis wire protocol, so this reuses ElastiCache's
 * {@link RespReader}. A future refactor should lift the shared Redis-auth pieces into a
 * common package.
 */
public class MemoryDbAuthProxy {

    private static final Logger LOG = Logger.getLogger(MemoryDbAuthProxy.class);

    private static final byte[] OK_RESPONSE = "+OK\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NOAUTH_RESPONSE =
            "-NOAUTH Authentication required.\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INVALID_AUTH_RESPONSE =
            "-ERR invalid username-password pair or user is disabled.\r\n"
                    .getBytes(StandardCharsets.UTF_8);
    private static final byte[] WRONG_ARGS_RESPONSE =
            "-ERR wrong number of arguments for 'auth' command\r\n"
                    .getBytes(StandardCharsets.UTF_8);

    private final String clusterName;
    private final boolean authRequired;
    private final String backendHost;
    private final int backendPort;
    private final AuthValidator authValidator;

    private volatile boolean running;
    private ServerSocket serverSocket;

    public MemoryDbAuthProxy(String clusterName, boolean authRequired,
                             String backendHost, int backendPort,
                             AuthValidator authValidator) {
        this.clusterName = clusterName;
        this.authRequired = authRequired;
        this.backendHost = backendHost;
        this.backendPort = backendPort;
        this.authValidator = authValidator;
    }

    public void start(int proxyPort) throws IOException {
        serverSocket = new ServerSocket(proxyPort);
        running = true;
        Thread.ofVirtual().name("memorydb-proxy-accept-" + clusterName).start(this::acceptLoop);
        LOG.infov("MemoryDB proxy started for cluster {0} on port {1} → {2}:{3}",
                clusterName, String.valueOf(proxyPort), backendHost, String.valueOf(backendPort));
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            LOG.warnv("Error closing proxy server socket for cluster {0}: {1}", clusterName, e.getMessage());
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                Thread.ofVirtual().name("memorydb-proxy-conn-" + clusterName).start(() -> handleConnection(client));
            } catch (IOException e) {
                if (running) {
                    LOG.warnv("Accept error for cluster {0}: {1}", clusterName, e.getMessage());
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
            } else if (authRequired) {
                client.getOutputStream().write(NOAUTH_RESPONSE);
                client.getOutputStream().flush();
                closeQuietly(client);
            } else {
                Socket backend = new Socket(backendHost, backendPort);
                backend.setTcpNoDelay(true);
                resendCommand(cmd, backend.getOutputStream());
                bridge(client, backend);
            }
        } catch (Exception e) {
            LOG.debugv("Connection error for cluster {0}: {1}", clusterName, e.getMessage());
            closeQuietly(client);
        }
    }

    private void handleAuth(Socket client, String[] cmd) throws IOException {
        String username;
        String password;

        if (cmd.length == 2) {
            username = null;
            password = cmd[1];
        } else if (cmd.length == 3) {
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
        if (!authRequired) {
            return true;
        }
        return authValidator.authenticate(username, password);
    }

    private void bridge(Socket client, Socket backend) {
        Thread t1 = Thread.ofPlatform().daemon(true).name("memorydb-relay-c2b-" + clusterName)
                .start(() -> relay(client, backend));
        Thread t2 = Thread.ofPlatform().daemon(true).name("memorydb-relay-b2c-" + clusterName)
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
     * Callback interface for credential validation, provided by MemoryDbService.
     * Resolves the supplied {@code username}/{@code secret} against the cluster's ACL,
     * handling both password and IAM users. A {@code null} username corresponds to the
     * single-argument {@code AUTH <password>} form (the {@code default} user).
     */
    @FunctionalInterface
    public interface AuthValidator {
        boolean authenticate(String username, String secret);
    }
}
