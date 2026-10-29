package io.github.hectorvent.floci.services.rds.proxy;

import io.github.hectorvent.floci.core.common.ListenBacklog;
import io.github.hectorvent.floci.services.rds.container.RdsBackendGate;
import io.github.hectorvent.floci.services.rds.model.DatabaseEngine;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.Semaphore;

/**
 * TCP auth proxy for a single RDS DB instance or cluster.
 * Dispatches to the appropriate engine-specific protocol handler for the
 * auth intercept, then bridges client ↔ backend transparently.
 */
public class RdsAuthProxy {

    private static final Logger LOG = Logger.getLogger(RdsAuthProxy.class);

    private final int backendPort;
    private volatile boolean iamEnabled;
    private final EndpointKind endpointKind;

    /** Native database IAM and standard client-to-proxy IAM have different role requirements. */
    public enum EndpointKind { DATABASE, STANDARD_DB_PROXY }

    private final String instanceId;
    private final String backendHost;
    private final BackendEndpointResolver backendEndpointResolver;
    private final String backendRuntimeId;
    private final String masterUsername;
    private volatile String masterPassword;
    private final String dbName;
    private final DatabaseEngine engine;
    private final RdsSigV4Validator sigV4;
    private final RdsProxyBinding binding;
    private final MySqlProtocolHandler.IamUserChecker iamUserChecker;
    private final RdsProxyTlsCertificates tlsCertificates;
    private final MasterPasswordCheck passwordValidator;
    private final int handshakeTimeoutMillis;
    private final int backendConnectTimeoutMillis;
    private final Semaphore connectionPermits;
    private final RdsBackendGate backendGate;

    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    Duration acceptRetirementTimeout = Duration.ofSeconds(5);

    public RdsAuthProxy(String instanceId, String backendHost, int backendPort,
                        String backendRuntimeId,
                        DatabaseEngine engine, boolean iamEnabled,
                        String masterUsername, String masterPassword, String dbName,
                        RdsSigV4Validator sigV4, RdsProxyTlsCertificates tlsCertificates,
                        MasterPasswordCheck passwordValidator,
                        int handshakeTimeoutMillis, int backendConnectTimeoutMillis,
                        int maxConnections) {
        this(instanceId, backendHost, backendPort, backendRuntimeId, engine, iamEnabled,
                masterUsername, masterPassword, dbName, sigV4, tlsCertificates, passwordValidator,
                handshakeTimeoutMillis, backendConnectTimeoutMillis, maxConnections, null,
                username -> false, RdsBackendGate.OPEN);
    }

    public RdsAuthProxy(String instanceId, String backendHost, int backendPort,
                        String backendRuntimeId,
                        DatabaseEngine engine, boolean iamEnabled,
                        String masterUsername, String masterPassword, String dbName,
                        RdsSigV4Validator sigV4, RdsProxyTlsCertificates tlsCertificates,
                        MasterPasswordCheck passwordValidator,
                        int handshakeTimeoutMillis, int backendConnectTimeoutMillis,
                        int maxConnections, RdsProxyBinding binding, RdsBackendGate backendGate) {
        this(instanceId, backendHost, backendPort, backendRuntimeId, engine, iamEnabled,
                masterUsername, masterPassword, dbName, sigV4, tlsCertificates, passwordValidator,
                handshakeTimeoutMillis, backendConnectTimeoutMillis, maxConnections, binding,
                username -> binding != null, backendGate);
    }

    public RdsAuthProxy(String instanceId, String backendHost, int backendPort,
                        String backendRuntimeId,
                        DatabaseEngine engine, boolean iamEnabled,
                        String masterUsername, String masterPassword, String dbName,
                        RdsSigV4Validator sigV4, RdsProxyTlsCertificates tlsCertificates,
                        MasterPasswordCheck passwordValidator,
                        int handshakeTimeoutMillis, int backendConnectTimeoutMillis,
                        int maxConnections, RdsProxyBinding binding,
                        MySqlProtocolHandler.IamUserChecker iamUserChecker,
                        RdsBackendGate backendGate) {
        this(instanceId, backendHost, backendPort, backendRuntimeId, engine, iamEnabled,
                masterUsername, masterPassword, dbName, sigV4, tlsCertificates, passwordValidator,
                handshakeTimeoutMillis, backendConnectTimeoutMillis, maxConnections, binding,
                iamUserChecker, backendGate, EndpointKind.DATABASE);
    }

    public RdsAuthProxy(String instanceId, String backendHost, int backendPort,
                        String backendRuntimeId,
                        DatabaseEngine engine, boolean iamEnabled,
                        String masterUsername, String masterPassword, String dbName,
                        RdsSigV4Validator sigV4, RdsProxyTlsCertificates tlsCertificates,
                        MasterPasswordCheck passwordValidator,
                        int handshakeTimeoutMillis, int backendConnectTimeoutMillis,
                        int maxConnections, RdsProxyBinding binding,
                        MySqlProtocolHandler.IamUserChecker iamUserChecker,
                        RdsBackendGate backendGate, EndpointKind endpointKind) {
        this(instanceId, backendHost, backendPort,
                () -> new BackendEndpoint(backendHost, backendPort), backendRuntimeId,
                engine, iamEnabled, masterUsername, masterPassword, dbName, sigV4,
                tlsCertificates, passwordValidator, handshakeTimeoutMillis,
                backendConnectTimeoutMillis, maxConnections, binding, iamUserChecker,
                backendGate, endpointKind);
    }

    public RdsAuthProxy(String instanceId, String backendHost, int backendPort,
                        BackendEndpointResolver backendEndpointResolver, String backendRuntimeId,
                        DatabaseEngine engine, boolean iamEnabled,
                        String masterUsername, String masterPassword, String dbName,
                        RdsSigV4Validator sigV4, RdsProxyTlsCertificates tlsCertificates,
                        MasterPasswordCheck passwordValidator,
                        int handshakeTimeoutMillis, int backendConnectTimeoutMillis,
                        int maxConnections, RdsProxyBinding binding,
                        MySqlProtocolHandler.IamUserChecker iamUserChecker,
                        RdsBackendGate backendGate, EndpointKind endpointKind) {
        if (backendRuntimeId == null || backendRuntimeId.isBlank()) {
            throw new IllegalArgumentException("RDS backend runtime ID is required");
        }
        this.instanceId = instanceId;
        this.backendHost = backendHost;
        this.backendPort = backendPort;
        this.backendEndpointResolver = backendEndpointResolver;
        this.backendRuntimeId = backendRuntimeId;
        this.engine = engine;
        this.iamEnabled = iamEnabled;
        this.endpointKind = endpointKind;
        this.masterUsername = masterUsername;
        this.masterPassword = masterPassword;
        this.dbName = dbName;
        this.sigV4 = sigV4;
        this.binding = binding;
        this.iamUserChecker = iamUserChecker;
        this.tlsCertificates = tlsCertificates;
        this.passwordValidator = passwordValidator;
        this.handshakeTimeoutMillis = handshakeTimeoutMillis;
        this.backendConnectTimeoutMillis = backendConnectTimeoutMillis;
        this.connectionPermits = new Semaphore(Math.max(1, maxConnections));
        this.backendGate = backendGate;
    }

    public void start(int proxyPort) throws IOException {
        serverSocket = listenSocket(proxyPort, ListenBacklog.ACCEPT);
        running = true;
        acceptThread = Thread.ofVirtual().name("rds-proxy-accept-" + instanceId).unstarted(this::acceptLoop);
        acceptThread.start();
        LOG.infov("RDS proxy started for instance {0} on port {1} → {2}:{3}",
                instanceId, String.valueOf(proxyPort), backendHost, String.valueOf(backendPort));
    }

    /**
     * Binds the listening socket. A seam so a test can pin the accept backlog: a bound
     * {@code ServerSocket} does not report the backlog it was created with.
     */
    ServerSocket listenSocket(int proxyPort, int backlog) throws IOException {
        return ListenBacklog.acceptSocket(proxyPort, backlog);
    }

    /** Swap the master-password snapshot after a rotation; new connections authenticate against it. */
    public void updateMasterPassword(String newPassword) {
        this.masterPassword = newPassword;
    }

    /** Apply an IAM-auth setting change to new connections without restarting the listener. */
    public void updateIamEnabled(boolean enabled) {
        this.iamEnabled = enabled;
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
        // A socket closed during accept can retain its native descriptor until accept exits.
        // Retire that loop before a reboot binds its replacement to the same port.
        Thread accepting = acceptThread;
        if (accepting != null && accepting != Thread.currentThread() && accepting.isAlive()) {
            try {
                if (!accepting.join(acceptRetirementTimeout)) {
                    throw new IllegalStateException("RDS proxy accept loop did not stop for instance " + instanceId);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while stopping RDS proxy for instance " + instanceId, e);
            }
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                if (!running) {
                    closeQuietly(client);
                    break;
                }
                if (!connectionPermits.tryAcquire()) {
                    LOG.warnv("Refusing RDS connection for instance {0}: connection limit reached",
                            instanceId);
                    closeQuietly(client);
                    continue;
                }
                Thread.ofVirtual().name("rds-proxy-conn-" + instanceId)
                        .start(() -> {
                            try {
                                handleConnection(client);
                            } finally {
                                connectionPermits.release();
                            }
                        });
            } catch (IOException e) {
                if (running) {
                    LOG.warnv("Accept error for RDS instance {0}: {1}", instanceId, e.getMessage());
                }
            }
        }
    }

    private void handleConnection(Socket client) {
        Socket backend = null;
        PostgresProtocolHandler.AuthenticatedSession session = null;
        RdsBackendGate.Lease lease = null;
        try {
            // An auto-paused Aurora backend resumes before the client is served: the client is
            // held, not refused, and the backend stays awake for as long as the connection lasts.
            lease = backendGate.enterRuntime(backendRuntimeId);
            client.setTcpNoDelay(true);

            // RDS only proxy-validates the master user; a non-master user passes through so the
            // backend enforces its own credentials.
            PasswordValidator authAdapter = (user, pass) -> {
                if (!masterUsername.equals(user)) {
                    return PasswordValidator.AuthResult.PASSTHROUGH;
                }
                return passwordValidator.validate(user, pass)
                        ? PasswordValidator.AuthResult.MASTER_EQUIVALENT
                        : PasswordValidator.AuthResult.REJECT;
            };

            PostgresProtocolHandler.BackendConnector connector = () -> {
                BackendEndpoint endpoint = backendEndpointResolver.resolve();
                Socket backendSocket = new Socket();
                backendSocket.connect(new InetSocketAddress(endpoint.host(), endpoint.port()),
                        backendConnectTimeoutMillis);
                backendSocket.setTcpNoDelay(true);
                return backendSocket;
            };

            switch (engine) {
                case POSTGRES -> {
                    // forwardStartupParameters = true: an RDS for PostgreSQL endpoint is the
                    // server itself, so the client's options and run-time parameters apply.
                    session = PostgresProtocolHandler.authenticate(
                                    client, connector, masterUsername, masterPassword, dbName,
                                    iamEnabled, sigV4, binding, tlsCertificates, authAdapter,
                                    handshakeTimeoutMillis, true, endpointKind == EndpointKind.DATABASE);
                    if (session != null) {
                        PostgresProtocolHandler.bridge(session);
                    }
                }
                case MYSQL, MARIADB -> {
                    backend = connector.connect();
                    MySqlProtocolHandler.handleAuth(
                            client, backend, connector, masterUsername, masterPassword,
                            iamEnabled, sigV4, tlsCertificates, authAdapter,
                            handshakeTimeoutMillis,
                            iamUserChecker, binding);
                }
                case SQLSERVER -> {
                    backend = connector.connect();
                    TcpStreamBridge.relay(client, backend);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.debugv("RDS connection for instance {0} interrupted while its backend resumed", instanceId);
        } catch (Exception e) {
            LOG.debugv("RDS connection error for instance {0}: {1}", instanceId, e.getMessage());
        } finally {
            // A handler's success path bridges then closes both sockets; every other path
            // (early return on a bare probe, auth failure, thrown IOException) can leave the
            // backend DB connection open. Closing here is idempotent, and also covers a
            // RuntimeException thrown between authenticate() returning a session and bridge()
            // finishing its own cleanup, which would otherwise leak session.backend().
            closeQuietly(client);
            closeQuietly(backend);
            if (session != null) {
                closeQuietly(session.backend());
            }
            if (lease != null) {
                lease.close();
            }
        }
    }

    private static void closeQuietly(Socket s) {
        if (s == null) {
            return;
        }
        try {
            s.close();
        } catch (IOException e) {
            LOG.debugv(e, "Error closing RDS proxy client socket");
        }
    }

    /** Backend address resolved immediately before each outbound connection attempt. */
    public record BackendEndpoint(String host, int port) {}

    /** Resolves the current backend address for a new outbound connection. */
    @FunctionalInterface
    public interface BackendEndpointResolver {
        BackendEndpoint resolve() throws IOException;
    }

    /**
     * Callback for master-password validation, implemented by RdsService. Returns true when the
     * supplied master credentials are current. Non-master users are never asked here: the backend
     * database is the authority for their passwords.
     */
    @FunctionalInterface
    public interface MasterPasswordCheck {
        boolean validate(String username, String password);
    }
}
