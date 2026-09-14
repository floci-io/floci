package io.github.hectorvent.floci.services.elasticache.proxy;

import io.github.hectorvent.floci.services.elasticache.model.AuthMode;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.Socket;

/**
 * TCP auth proxy for a single ElastiCache replication group.
 * Validates credentials (IAM or password) against the group's configured {@link AuthMode}.
 * See {@link AbstractRedisAuthProxy} for the shared AUTH-handling and relay logic.
 */
public class ElastiCacheAuthProxy extends AbstractRedisAuthProxy {

    private static final Logger LOG = Logger.getLogger(ElastiCacheAuthProxy.class);

    private final String groupId;
    private final AuthMode authMode;
    private final PasswordValidator passwordValidator;
    private final SigV4Validator sigV4Validator;

    public ElastiCacheAuthProxy(String groupId, AuthMode authMode,
                                String backendHost, int backendPort,
                                PasswordValidator passwordValidator,
                                SigV4Validator sigV4Validator) {
        super(LOG, "ElastiCache", "ec", groupId, backendHost, backendPort);
        this.groupId = groupId;
        this.authMode = authMode;
        this.passwordValidator = passwordValidator;
        this.sigV4Validator = sigV4Validator;
    }

    @Override
    protected boolean authRequired() {
        return authMode != AuthMode.NO_AUTH;
    }

    @Override
    protected boolean authenticate(String username, String password) {
        return switch (authMode) {
            case IAM -> sigV4Validator.validate(password, groupId, username);
            case PASSWORD -> passwordValidator.validatePassword(username, password);
            case NO_AUTH -> true; // unreachable: authRequired() is false for NO_AUTH
        };
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
     * Callback interface for password validation, provided by ElastiCacheService.
     */
    @FunctionalInterface
    public interface PasswordValidator {
        boolean validatePassword(String username, String password);
    }
}
