package io.github.hectorvent.floci.services.rds.proxy;

import io.github.hectorvent.floci.services.rds.model.DatabaseEngine;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
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
    private volatile String masterPassword;
    private final String dbName;
    private final DatabaseEngine engine;
    private final RdsSigV4Validator sigV4;
    private final RdsProxyTlsCertificates tlsCertificates;
    private final PasswordValidator passwordValidator;

    private volatile boolean running;
    private ServerSocket serverSocket;

    public RdsAuthProxy(String instanceId, String backendHost, int backendPort,
                        DatabaseEngine engine, boolean iamEnabled,
                        String masterUsername, String masterPassword, String dbName,
                        RdsSigV4Validator sigV4, RdsProxyTlsCertificates tlsCertificates,
                        PasswordValidator passwordValidator) {
        this.instanceId = instanceId;
        this.backendHost = backendHost;
        this.backendPort = backendPort;
        this.engine = engine;
        this.iamEnabled = iamEnabled;
        this.masterUsername = masterUsername;
        this.masterPassword = masterPassword;
        this.dbName = dbName;
        this.sigV4 = sigV4;
        this.tlsCertificates = tlsCertificates;
        this.passwordValidator = passwordValidator;
    }

    public void start(int proxyPort) throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(proxyPort));
        running = true;
        Thread.ofVirtual().name("rds-proxy-accept-" + instanceId).start(this::acceptLoop);
        LOG.infov("RDS proxy started for instance {0} on port {1} → {2}:{3}",
                instanceId, String.valueOf(proxyPort), backendHost, String.valueOf(backendPort));
    }

    /** Swap the master-password snapshot after a rotation; new connections authenticate against it. */
    public void updateMasterPassword(String newPassword) {
        this.masterPassword = newPassword;
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            LOG.warnv(e, "Error closing RDS proxy server socket for instance {0}", instanceId);
            throw new RuntimeException(
                    "Failed to stop RDS proxy for instance " + instanceId, e);
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
        Socket backend = null;
        try {
            client.setTcpNoDelay(true);
            backend = new Socket(backendHost, backendPort);
            backend.setTcpNoDelay(true);

            switch (engine) {
                case POSTGRES -> PostgresProtocolHandler.handleAuth(
                        client, backend, masterUsername, masterPassword, dbName,
                        iamEnabled, sigV4, tlsCertificates, passwordValidator::validate);
                case MYSQL, MARIADB -> MySqlProtocolHandler.handleAuth(
                        client, backend, masterUsername, masterPassword,
                        iamEnabled, sigV4, tlsCertificates, passwordValidator::validate);
            }
        } catch (Exception e) {
            LOG.debugv("RDS connection error for instance {0}: {1}", instanceId, e.getMessage());
        } finally {
            // A handler's success path bridges then closes both sockets; every other path
            // (early return on a bare probe, auth failure, thrown IOException) can leave the
            // backend DB connection open. Closing here is idempotent.
            closeQuietly(client);
            if (backend != null) {
                closeQuietly(backend);
            }
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException e) {
            LOG.debugv(e, "Error closing RDS proxy client socket");
        }
    }

    /**
     * Callback for password validation — implemented by RdsService.
     */
    @FunctionalInterface
    public interface PasswordValidator {
        boolean validate(String username, String password);
    }
}
