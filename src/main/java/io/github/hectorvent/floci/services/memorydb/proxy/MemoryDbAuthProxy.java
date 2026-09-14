package io.github.hectorvent.floci.services.memorydb.proxy;

import io.github.hectorvent.floci.services.elasticache.proxy.AbstractRedisAuthProxy;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.Socket;

/**
 * TCP auth proxy for a single MemoryDB cluster.
 * Delegates credential validation to an {@link AuthValidator}, which resolves the supplied
 * user against the cluster's ACL (password or IAM). See {@link AbstractRedisAuthProxy} for
 * the shared AUTH-handling and relay logic, reused here because MemoryDB speaks the same
 * Redis wire protocol as ElastiCache.
 *
 * <p>Whether authentication is required at all is decided up front from the cluster's
 * ACL: a cluster bound to {@code open-access} (or any ACL whose users require no
 * password) is created with {@code requireAuth = false}, in which case the proxy is a
 * straight relay.
 */
public class MemoryDbAuthProxy extends AbstractRedisAuthProxy {

    private static final Logger LOG = Logger.getLogger(MemoryDbAuthProxy.class);

    private final boolean requireAuth;
    private final AuthValidator authValidator;

    public MemoryDbAuthProxy(String clusterName, boolean authRequired,
                             String backendHost, int backendPort,
                             AuthValidator authValidator) {
        super(LOG, "MemoryDB", "memorydb", clusterName, backendHost, backendPort);
        this.requireAuth = authRequired;
        this.authValidator = authValidator;
    }

    @Override
    protected boolean authRequired() {
        return requireAuth;
    }

    @Override
    protected boolean authenticate(String username, String password) {
        return authValidator.authenticate(username, password);
    }

    @Override
    protected void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // Normal when the peer has already closed the connection
        }
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
