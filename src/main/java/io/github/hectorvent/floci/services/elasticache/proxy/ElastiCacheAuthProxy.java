package io.github.hectorvent.floci.services.elasticache.proxy;

import io.github.hectorvent.floci.services.elasticache.model.AuthMode;
import io.github.hectorvent.floci.services.elasticache.model.TransitEncryption;
import org.jboss.logging.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.Socket;
import java.util.function.Supplier;

/**
 * TCP auth proxy for a single ElastiCache replication group.
 * Validates credentials (IAM or password) against the group's configured {@link AuthMode}.
 * See {@link AbstractRedisAuthProxy} for the shared AUTH-handling and relay logic.
 */
public class ElastiCacheAuthProxy extends AbstractRedisAuthProxy {

    private static final Logger LOG = Logger.getLogger(ElastiCacheAuthProxy.class);
    /** The content type of a TLS handshake record, the first byte of every ClientHello. */
    private static final int TLS_HANDSHAKE_RECORD = 0x16;

    private final String groupId;
    private final AuthMode authMode;
    private final PasswordValidator passwordValidator;
    private final SigV4Validator sigV4Validator;
    private final Supplier<SSLContext> tlsContext;
    private final ElastiCacheBackendAcl backendAcl = new ElastiCacheBackendAcl();

    public ElastiCacheAuthProxy(String groupId, AuthMode authMode,
                                String backendHost, int backendPort,
                                PasswordValidator passwordValidator,
                                SigV4Validator sigV4Validator) {
        this(groupId, authMode, backendHost, backendPort, passwordValidator, sigV4Validator, null);
    }

    public ElastiCacheAuthProxy(String groupId, AuthMode authMode,
                                String backendHost, int backendPort,
                                PasswordValidator passwordValidator, SigV4Validator sigV4Validator,
                                Supplier<SSLContext> tlsContext) {
        super(LOG, "ElastiCache", "ec", groupId, backendHost, backendPort);
        this.groupId = groupId;
        this.authMode = authMode;
        this.passwordValidator = passwordValidator;
        this.sigV4Validator = sigV4Validator;
        this.tlsContext = tlsContext;
    }

    @Override
    protected ClientTransport clientTransport(Socket client) throws IOException {
        TransitEncryption encryption = tlsContext == null ? TransitEncryption.NONE : passwordValidator.transitEncryption();
        return switch (encryption) {
            case NONE -> ClientTransport.unencrypted(client);
            case REQUIRED -> ClientTransport.encrypted(handshake(tlsContext.get().getSocketFactory()
                    .createSocket(client, client.getInetAddress().getHostAddress(), client.getPort(), true), client));
            case PREFERRED -> eitherTransport(client);
        };
    }

    /** Both kinds are taken: a client that does not open with a TLS handshake is speaking RESP in the clear. */
    private ClientTransport eitherTransport(Socket client) throws IOException {
        int first = client.getInputStream().read();
        if (first == -1) {
            throw new EOFException("client closed the connection before sending anything");
        }
        InputStream consumed = new ByteArrayInputStream(new byte[]{(byte) first});
        if (first != TLS_HANDSHAKE_RECORD) {
            return new ClientTransport(client, new SequenceInputStream(consumed, client.getInputStream()), false);
        }
        return ClientTransport.encrypted(handshake(tlsContext.get().getSocketFactory()
                .createSocket(client, consumed, true), client));
    }

    private static SSLSocket handshake(Socket layered, Socket client) throws IOException {
        SSLSocket encrypted = (SSLSocket) layered;
        encrypted.setUseClientMode(false);
        encrypted.setSoTimeout(client.getSoTimeout());
        encrypted.startHandshake();
        return encrypted;
    }

    @Override
    protected void initializeBackend(Socket backend, String username) throws IOException {
        boolean members = passwordValidator.hasMembers();
        if (!members && !backendAcl.initialized()) {
            return;
        }
        // Disabling default without configuring replication credentials breaks cluster nodes.
        // Until that path is implemented, fail closed rather than ignore the user's ACL.
        if (passwordValidator.clusterMode()) {
            throw new IOException("Cluster-mode user ACL transport is not supported yet");
        }
        String name = username == null || username.isEmpty() ? "default" : username;
        String access = members ? passwordValidator.accessString(name) : "on ~* &* +@all";
        if (access == null) {
            throw new IOException("Cache user is no longer associated with this resource");
        }
        backendAcl.authenticate(backend, name, access);
    }

    @Override
    protected boolean authRequired() {
        if (passwordValidator.hasMembers()) {
            AuthMode defaultMode = passwordValidator.memberAuthMode("default");
            if (defaultMode != null) {
                return defaultMode != AuthMode.NO_AUTH;
            }
            return true;
        }
        return currentAuthMode() != AuthMode.NO_AUTH;
    }

    @Override
    protected boolean authenticate(String username, String password) {
        String effectiveUser = (username == null || username.isEmpty()) ? "default" : username;
        if (passwordValidator.hasMembers()) {
            if ("default".equals(effectiveUser)) {
                if (passwordValidator.validatePassword(username, password)) {
                    return true;
                }
                AuthMode defaultMode = passwordValidator.memberAuthMode("default");
                if (defaultMode == null) {
                    defaultMode = passwordValidator.memberAuthMode(username);
                }
                if (defaultMode == AuthMode.IAM) {
                    return sigV4Validator.validate(password, groupId, "default", passwordValidator.ownerAccountId());
                }
                return false;
            }

            AuthMode userMode = passwordValidator.memberAuthMode(effectiveUser);
            if (userMode == null && username != null) {
                userMode = passwordValidator.memberAuthMode(username);
            }
            if (userMode != null) {
                return switch (userMode) {
                    case IAM -> sigV4Validator.validate(password, groupId, effectiveUser,
                            passwordValidator.ownerAccountId());
                    case PASSWORD, NO_AUTH -> passwordValidator.validatePassword(effectiveUser, password);
                };
            }
            return false;
        }

        return switch (currentAuthMode()) {
            case IAM -> sigV4Validator.validate(password, groupId, effectiveUser, passwordValidator.ownerAccountId());
            case PASSWORD -> passwordValidator.validatePassword(username, password);
            case NO_AUTH -> true;
        };
    }

    // read per connection: removing a cache's last user group changes it while the proxy runs
    private AuthMode currentAuthMode() {
        AuthMode current = passwordValidator.authMode();
        return current != null ? current : authMode;
    }

    @Override
    protected void closeQuietly(Socket s) {
        try {
            if (!s.isClosed()) {
                try {
                    s.shutdownOutput();
                } catch (IOException e) {
                    LOG.debugv(e, "Error shutting down socket output for group {0}", groupId);
                }
                s.close();
            }
        } catch (IOException e) {
            LOG.debugv(e, "Error closing socket for group {0}", groupId);
        }
    }

    /**
     * Callback interface for credential checks, provided by ElastiCacheService.
     */
    @FunctionalInterface
    public interface PasswordValidator {
        boolean validatePassword(String username, String password);

        /** Read per connection: a modify can move the cache from preferred to required while the proxy runs. */
        default TransitEncryption transitEncryption() {
            return TransitEncryption.NONE;
        }

        default String accessString(String username) {
            return null;
        }

        default boolean clusterMode() {
            return false;
        }

        default boolean hasMembers() {
            return false;
        }

        default AuthMode memberAuthMode(String username) {
            return null;
        }

        /** The cache's auth mode now, or null to keep the one the proxy started with. */
        default AuthMode authMode() {
            return null;
        }

        /**
         * The account that owns the cache, whose long-term access keys an IAM token may be signed
         * with, or null to look keys up in the default account.
         */
        default String ownerAccountId() {
            return null;
        }
    }
}
