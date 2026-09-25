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
                    return sigV4Validator.validate(password, groupId, "default");
                }
                return false;
            }

            AuthMode userMode = passwordValidator.memberAuthMode(effectiveUser);
            if (userMode == null && username != null) {
                userMode = passwordValidator.memberAuthMode(username);
            }
            if (userMode != null) {
                return switch (userMode) {
                    case IAM -> sigV4Validator.validate(password, groupId, effectiveUser);
                    case PASSWORD, NO_AUTH -> passwordValidator.validatePassword(effectiveUser, password);
                };
            }
            return false;
        }

        return switch (currentAuthMode()) {
            case IAM -> sigV4Validator.validate(password, groupId, effectiveUser);
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
    }
}
