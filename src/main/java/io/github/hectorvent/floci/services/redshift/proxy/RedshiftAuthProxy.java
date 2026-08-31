package io.github.hectorvent.floci.services.redshift.proxy;

import io.github.hectorvent.floci.services.rds.proxy.PostgresProtocolHandler;
import io.github.hectorvent.floci.services.rds.proxy.RdsAuthProxy;
import io.github.hectorvent.floci.services.rds.proxy.RdsProxyTlsCertificates;
import io.github.hectorvent.floci.services.rds.proxy.RdsSigV4Validator;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * TCP auth proxy for a single Redshift cluster's backing PostgreSQL container.
 * Redshift speaks the PostgreSQL wire protocol, so the RDS PostgreSQL protocol
 * handler is reused verbatim for the auth intercept; client and backend are then
 * bridged transparently. IAM auth is never enabled for Redshift, so the SigV4
 * validator is carried only to satisfy the shared handler signature.
 */
public class RedshiftAuthProxy {

    private static final Logger LOG = Logger.getLogger(RedshiftAuthProxy.class);

    private final String clusterKey;
    private final String backendHost;
    private final int backendPort;
    private final String masterUsername;
    private volatile String masterPassword;
    private final String dbName;
    private final RdsSigV4Validator sigV4;
    private final RdsProxyTlsCertificates tlsCertificates;
    private final RdsAuthProxy.PasswordValidator passwordValidator;

    private volatile boolean running;
    private ServerSocket serverSocket;

    public RedshiftAuthProxy(String clusterKey, String backendHost, int backendPort,
                             String masterUsername, String masterPassword, String dbName,
                             RdsSigV4Validator sigV4, RdsProxyTlsCertificates tlsCertificates,
                             RdsAuthProxy.PasswordValidator passwordValidator) {
        this.clusterKey = clusterKey;
        this.backendHost = backendHost;
        this.backendPort = backendPort;
        this.masterUsername = masterUsername;
        this.masterPassword = masterPassword;
        this.dbName = dbName;
        this.sigV4 = sigV4;
        this.tlsCertificates = tlsCertificates;
        this.passwordValidator = passwordValidator;
    }

    public void start(int proxyPort) throws IOException {
        serverSocket = bindListener(proxyPort);
        running = true;
        Thread.ofVirtual().name("redshift-proxy-accept-" + clusterKey).start(this::acceptLoop);
        LOG.infov("Redshift proxy started for cluster {0} on port {1} -> {2}:{3}",
                clusterKey, String.valueOf(proxyPort), backendHost, String.valueOf(backendPort));
    }

    /**
     * Bind the listener, retrying briefly on {@link BindException}. A reboot keeps the
     * cluster's proxy port fixed so the advertised endpoint is stable, which means the
     * old listener is closed and the same port rebound milliseconds later; under load
     * the kernel may not have released it yet and {@code SO_REUSEADDR} does not help
     * while the previous accept loop is still tearing down. ~1s of retry absorbs that
     * window; any other {@link IOException} propagates immediately.
     */
    private ServerSocket bindListener(int proxyPort) throws IOException {
        BindException lastFailure = null;
        for (int attempt = 0; attempt < 40; attempt++) {
            ServerSocket socket = new ServerSocket();
            try {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(proxyPort));
                return socket;
            } catch (BindException e) {
                closeQuietly(socket);
                lastFailure = e;
            } catch (IOException e) {
                closeQuietly(socket);
                throw e;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while binding Redshift proxy port " + proxyPort, e);
            }
        }
        throw lastFailure;
    }

    private static void closeQuietly(ServerSocket s) {
        try {
            s.close();
        } catch (IOException e) {
            LOG.debugv(e, "Error closing a discarded Redshift proxy listener socket");
        }
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
            LOG.warnv(e, "Error closing Redshift proxy server socket for cluster {0}", clusterKey);
            throw new RuntimeException("Failed to stop Redshift proxy for cluster " + clusterKey, e);
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                Thread.ofVirtual().name("redshift-proxy-conn-" + clusterKey)
                        .start(() -> handleConnection(client));
            } catch (IOException e) {
                if (running) {
                    LOG.warnv("Accept error for Redshift cluster {0}: {1}", clusterKey, e.getMessage());
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
            // iamEnabled = false: the SigV4 branch inside handleAuth is never taken.
            PostgresProtocolHandler.handleAuth(
                    client, backend, masterUsername, masterPassword, dbName,
                    false, sigV4, tlsCertificates, passwordValidator::validate);
        } catch (Exception e) {
            LOG.debugv("Redshift connection error for cluster {0}: {1}", clusterKey, e.getMessage());
        } finally {
            // handleAuth's success path bridges then closes both sockets; every other path
            // (early return on a bare probe, auth failure, thrown IOException) can leave the
            // backend connection to Postgres open. Closing here is idempotent.
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
            LOG.debugv(e, "Error closing Redshift proxy client socket");
        }
    }
}
