package io.github.hectorvent.floci.services.redshiftserverless;

import io.github.hectorvent.floci.services.rds.proxy.PasswordValidator;
import io.github.hectorvent.floci.services.redshift.RedshiftCredentialBroker;
import io.github.hectorvent.floci.services.redshift.TempCredential;
import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerHandle;
import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerManager;
import io.github.hectorvent.floci.services.redshift.model.Endpoint;
import io.github.hectorvent.floci.services.redshift.proxy.RedshiftProxyManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Owns the PostgreSQL container and the auth proxy behind a workgroup endpoint.
 *
 * <p>The container, proxy and credential managers are keyed by an {@code (account, identifier)}
 * string and know nothing about provisioned clusters, so a workgroup reuses them under the
 * identifier {@code serverless_<region>_<workgroup>}. The underscore cannot appear in a cluster
 * identifier, so the two never collide and the workgroup stays out of {@code DescribeClusters}.
 */
@ApplicationScoped
public class RedshiftServerlessRuntime {

    private static final Logger LOG = Logger.getLogger(RedshiftServerlessRuntime.class);
    private static final String PASSWORD_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int GENERATED_PASSWORD_LENGTH = 32;

    /** The namespace admin pair as it stands now; read on every login so a password change applies at once. */
    public record AdminCredentials(String username, String password) {
    }

    /** Where the workgroup's PostgreSQL container listens. */
    public record Backend(String host, int port) {
    }

    private final RedshiftContainerManager containerManager;
    private final RedshiftProxyManager proxyManager;
    private final RedshiftCredentialBroker credentialBroker;
    private final SecureRandom random = new SecureRandom();

    @Inject
    public RedshiftServerlessRuntime(RedshiftContainerManager containerManager, RedshiftProxyManager proxyManager,
                                     RedshiftCredentialBroker credentialBroker) {
        this.containerManager = containerManager;
        this.proxyManager = proxyManager;
        this.credentialBroker = credentialBroker;
    }

    public static String backendId(String region, String workgroupName) {
        return "serverless_" + region + "_" + workgroupName;
    }

    /**
     * Starts (or, when {@code adopt} is set, re-attaches to) the container and fronts it with the
     * auth proxy on {@code endpoint}. Anything started before a failure is torn down again, so a
     * failed call leaves neither a container nor a bound port behind.
     */
    public Backend start(String accountId, String region, String workgroupName, String masterUsername,
                         String masterPassword, String dbName, Endpoint endpoint, boolean adopt,
                         Supplier<Optional<AdminCredentials>> admin) {
        String backendId = backendId(region, workgroupName);
        try {
            RedshiftContainerHandle handle = adopt
                    ? containerManager.adoptOrStart(accountId, backendId, masterUsername, masterPassword, dbName)
                    : containerManager.start(accountId, backendId, masterUsername, masterPassword, dbName);
            proxyManager.startProxy(relayKey(accountId, backendId), endpoint.getPort(),
                    handle.getHost(), handle.getPort(), endpoint.getAddress(),
                    masterUsername, masterPassword, dbName,
                    validator(accountId, backendId, admin), List.of());
            return new Backend(handle.getHost(), handle.getPort());
        } catch (RuntimeException e) {
            rollback(accountId, backendId);
            throw e;
        }
    }

    /**
     * Stops the proxy first and lets a failure propagate: if the listener cannot be closed the
     * port must stay reserved and the workgroup must stay on record, so the caller can retry.
     */
    public void stop(String accountId, String region, String workgroupName) {
        String backendId = backendId(region, workgroupName);
        proxyManager.stopProxy(relayKey(accountId, backendId));
        containerManager.stop(accountId, backendId);
        credentialBroker.revokeCluster(accountId, backendId);
    }

    /**
     * Applies a new master password to the PostgreSQL role and to the proxy's backend leg. When the
     * proxy step fails the role is put back to {@code oldPassword}, so the backend never requires a
     * password the stored namespace does not hold.
     */
    public void changeMasterPassword(String accountId, String region, String workgroupName,
                                     String masterUsername, String dbName, String oldPassword,
                                     String newPassword) {
        String backendId = backendId(region, workgroupName);
        containerManager.alterUserPassword(accountId, backendId, masterUsername, newPassword, dbName);
        try {
            proxyManager.updateMasterPassword(relayKey(accountId, backendId), newPassword);
        } catch (RuntimeException e) {
            if (oldPassword != null) {
                try {
                    containerManager.alterUserPassword(accountId, backendId, masterUsername, oldPassword, dbName);
                } catch (RuntimeException restoreFailure) {
                    e.addSuppressed(restoreFailure);
                }
            }
            throw e;
        }
    }

    /**
     * Puts the role and the proxy back to {@code password} after a rotation the caller could not
     * store. The proxy is only reset once the role is, so a failed role restore never leaves the two
     * disagreeing; the failure propagates instead.
     */
    public void restoreMasterPassword(String accountId, String region, String workgroupName,
                                      String masterUsername, String dbName, String password) {
        String backendId = backendId(region, workgroupName);
        containerManager.alterUserPassword(accountId, backendId, masterUsername, password, dbName);
        proxyManager.updateMasterPassword(relayKey(accountId, backendId), password);
    }

    public TempCredential issueCredential(String accountId, String region, String workgroupName,
                                          String dbUser, int durationSeconds) {
        return credentialBroker.issue(accountId, backendId(region, workgroupName), dbUser, List.of(),
                durationSeconds);
    }

    public String generatePassword() {
        StringBuilder password = new StringBuilder(GENERATED_PASSWORD_LENGTH);
        for (int i = 0; i < GENERATED_PASSWORD_LENGTH; i++) {
            password.append(PASSWORD_ALPHABET.charAt(random.nextInt(PASSWORD_ALPHABET.length())));
        }
        return password.toString();
    }

    private void rollback(String accountId, String backendId) {
        try {
            proxyManager.stopProxy(relayKey(accountId, backendId));
        } catch (RuntimeException e) {
            LOG.warnv(e, "Could not stop the proxy while rolling back workgroup backend {0}", backendId);
        }
        try {
            containerManager.stop(accountId, backendId);
        } catch (RuntimeException e) {
            LOG.warnv(e, "Could not remove the container while rolling back workgroup backend {0}", backendId);
        }
        credentialBroker.revokeCluster(accountId, backendId);
    }

    /**
     * The namespace admin pair and any live {@code GetCredentials} credential run the backend leg as
     * the master role, a known broker user with a stale password is refused, and everyone else is
     * passed through for PostgreSQL to judge. A namespace that no longer exists vouches for nothing:
     * falling through to the broker would classify the admin name as unknown and let the wire proxy
     * open the backend as master.
     */
    private PasswordValidator validator(String accountId, String backendId,
                                        Supplier<Optional<AdminCredentials>> admin) {
        return (user, password) -> {
            Optional<AdminCredentials> current = admin.get();
            if (current.isEmpty()) {
                return PasswordValidator.AuthResult.REJECT;
            }
            if (user.equals(current.get().username())) {
                return password.equals(current.get().password())
                        ? PasswordValidator.AuthResult.MASTER_EQUIVALENT
                        : PasswordValidator.AuthResult.REJECT;
            }
            return switch (credentialBroker.classify(accountId, backendId, user, password)) {
                case MASTER_EQUIVALENT -> PasswordValidator.AuthResult.MASTER_EQUIVALENT;
                case REJECT -> PasswordValidator.AuthResult.REJECT;
                case PASSTHROUGH -> PasswordValidator.AuthResult.PASSTHROUGH;
            };
        };
    }

    private static String relayKey(String accountId, String backendId) {
        return accountId + ":" + backendId;
    }
}
