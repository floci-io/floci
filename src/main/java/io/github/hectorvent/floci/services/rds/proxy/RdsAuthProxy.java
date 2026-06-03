package io.github.hectorvent.floci.services.rds.proxy;

import io.github.hectorvent.floci.services.rds.model.DatabaseEngine;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * TCP auth proxy for a single RDS DB instance or cluster.
 * Dispatches to the appropriate engine-specific protocol handler for the
 * auth intercept, then bridges client ↔ backend transparently.
 */
public class RdsAuthProxy {

    private static final Logger LOG = Logger.getLogger(RdsAuthProxy.class);

    private final int backendPort;
    private final boolean iamEnabled;
    private final String instanceId;
    private final String backendHost;
    private final String masterUsername;
    private final String masterPassword;
    private final String dbName;
    private final DatabaseEngine engine;
    private final RdsSigV4Validator sigV4;
    private final PasswordValidator passwordValidator;

    private volatile boolean running;
    private ServerSocket serverSocket;

    public RdsAuthProxy(String instanceId, String backendHost, int backendPort,
                        DatabaseEngine engine, boolean iamEnabled,
                        String masterUsername, String masterPassword, String dbName,
                        RdsSigV4Validator sigV4, PasswordValidator passwordValidator) {
        this.instanceId = instanceId;
        this.backendHost = backendHost;
        this.backendPort = backendPort;
        this.engine = engine;
        this.iamEnabled = iamEnabled;
        this.masterUsername = masterUsername;
        this.masterPassword = masterPassword;
        this.dbName = dbName;
        this.sigV4 = sigV4;
        this.passwordValidator = passwordValidator;
    }

    public void start(int proxyPort) throws IOException {
        serverSocket = new ServerSocket(proxyPort);
        running = true;
        Thread.ofVirtual().name("rds-proxy-accept-" + instanceId).start(this::acceptLoop);
        LOG.infov("RDS proxy started for instance {0} on port {1} → {2}:{3}",
                instanceId, String.valueOf(proxyPort), backendHost, String.valueOf(backendPort));
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            LOG.warnv("Error closing RDS proxy server socket for instance {0}: {1}",
                    instanceId, e.getMessage());
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                Thread.ofVirtual().name("rds-proxy-conn-" + instanceId)
                        .start(() -> handleConnection(client));
            } catch (IOException e) {
                if (running) {
                    LOG.warnv("Accept error for RDS instance {0}: {1}", instanceId, e.getMessage());
                }
            }
        }
    }

    private void handleConnection(Socket client) {
        try {
            client.setTcpNoDelay(true);
            Socket backend = new Socket(backendHost, backendPort);
            backend.setTcpNoDelay(true);

            switch (engine) {
                case POSTGRES -> PostgresProtocolHandler.handleAuth(
                        client, backend, masterUsername, masterPassword, dbName,
                        iamEnabled, sigV4, passwordValidator::validate);
                case MYSQL, MARIADB -> MySqlProtocolHandler.handleAuth(
                        client, backend, masterUsername, masterPassword,
                        iamEnabled, sigV4, passwordValidator::validate);
            }
        } catch (Exception e) {
            LOG.debugv("RDS connection error for instance {0}: {1}", instanceId, e.getMessage());
            closeQuietly(client);
        }
    }

    private static void closeQuietly(Socket s) {
        try { s.close(); } catch (IOException ignored) {}
    }

    /**
     * Callback for password validation — implemented by RdsService.
     */
    @FunctionalInterface
    public interface PasswordValidator {
        boolean validate(String username, String password);
    }
}
