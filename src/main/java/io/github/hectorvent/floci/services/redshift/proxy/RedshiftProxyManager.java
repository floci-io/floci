package io.github.hectorvent.floci.services.redshift.proxy;

import io.github.hectorvent.floci.services.rds.proxy.RdsAuthProxy;
import io.github.hectorvent.floci.services.rds.proxy.RdsProxyTlsCertificates;
import io.github.hectorvent.floci.services.rds.proxy.RdsSigV4Validator;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of all active Redshift auth proxies. One proxy per cluster, keyed by
 * the relay key "{accountId}:{clusterId}". Mirrors RdsProxyManager; Redshift is
 * always PostgreSQL and never IAM-enabled, so those parameters are dropped.
 */
@ApplicationScoped
public class RedshiftProxyManager {

    private static final Logger LOG = Logger.getLogger(RedshiftProxyManager.class);

    private final RdsSigV4Validator sigV4Validator;
    private final RdsProxyTlsCertificates tlsCertificates;
    private final ConcurrentHashMap<String, RedshiftAuthProxy> proxies = new ConcurrentHashMap<>();
    /**
     * Proxies whose listener could not be closed during a failed start or replace. The
     * reference is kept (not discarded) so a later {@link #stopProxy}/{@link #stopAll}
     * can retry the close; until one succeeds, {@code stopProxy} keeps throwing so the
     * caller leaves the port reserved rather than handing it out to another cluster.
     */
    private final ConcurrentHashMap<String, RedshiftAuthProxy> unclosableProxies = new ConcurrentHashMap<>();

    @Inject
    public RedshiftProxyManager(RdsSigV4Validator sigV4Validator, RdsProxyTlsCertificates tlsCertificates) {
        this.sigV4Validator = sigV4Validator;
        this.tlsCertificates = tlsCertificates;
    }

    public synchronized void startProxy(String relayKey, int proxyPort,
                                        String backendHost, int backendPort, String advertisedHost,
                                        String masterUsername, String masterPassword, String dbName,
                                        RdsAuthProxy.PasswordValidator passwordValidator) {
        // A prior unclosable entry for this key is left in place: its listener may still
        // be bound, and only a successful stop (never a fresh start) may drop it.
        // Make sure the self-signed proxy certificate covers the host clients will connect to,
        // so sslmode=prefer/require handshakes succeed.
        tlsCertificates.ensureHost(advertisedHost);
        RedshiftAuthProxy proxy = new RedshiftAuthProxy(
                relayKey, backendHost, backendPort, masterUsername, masterPassword, dbName,
                sigV4Validator, tlsCertificates, passwordValidator);
        try {
            proxy.start(proxyPort);
        } catch (IOException | RuntimeException e) {
            RuntimeException failure = new RuntimeException(
                    "Failed to start Redshift proxy for cluster " + relayKey + " on port " + proxyPort, e);
            cleanupFailedStart(relayKey, proxy, failure);
            throw failure;
        }
        RedshiftAuthProxy previous = proxies.put(relayKey, proxy);
        if (previous != null) {
            try {
                previous.stop();
            } catch (RuntimeException e) {
                proxies.put(relayKey, previous);
                RuntimeException failure = new RuntimeException(
                        "Failed to replace Redshift proxy for cluster " + relayKey, e);
                cleanupFailedStart(relayKey, proxy, failure);
                throw failure;
            }
        }
    }

    public synchronized void updateMasterPassword(String relayKey, String newPassword) {
        RedshiftAuthProxy proxy = proxies.get(relayKey);
        if (proxy != null) {
            proxy.updateMasterPassword(newPassword);
            LOG.infov("Updated Redshift proxy master password for cluster {0}", relayKey);
        }
    }

    public synchronized void stopProxy(String relayKey) {
        // Retry any listener a previous failed start/replace could not close. If it still
        // cannot be closed this throws, and the entry stays for the next retry.
        RedshiftAuthProxy unclosable = unclosableProxies.get(relayKey);
        if (unclosable != null) {
            unclosable.stop();
            unclosableProxies.remove(relayKey);
            LOG.infov("Recovered previously unclosable Redshift proxy for cluster {0}", relayKey);
        }
        RedshiftAuthProxy proxy = proxies.get(relayKey);
        if (proxy != null) {
            proxy.stop();
            proxies.remove(relayKey);
            LOG.infov("Stopped Redshift proxy for cluster {0}", relayKey);
        }
    }

    public synchronized void stopAll() {
        proxies.forEach((relayKey, proxy) -> {
            try {
                proxy.stop();
                proxies.remove(relayKey, proxy);
            } catch (RuntimeException e) {
                LOG.warnv(e, "Failed to stop Redshift proxy for cluster {0} during shutdown", relayKey);
            }
        });
        unclosableProxies.forEach((relayKey, proxy) -> {
            try {
                proxy.stop();
                unclosableProxies.remove(relayKey, proxy);
            } catch (RuntimeException e) {
                LOG.warnv(e, "Failed to close leaked Redshift proxy for cluster {0} during shutdown", relayKey);
            }
        });
        LOG.info("Stopped all Redshift proxies");
    }

    void onShutdown(@Observes ShutdownEvent event) {
        stopAll();
    }

    private void cleanupFailedStart(String relayKey, RedshiftAuthProxy proxy, RuntimeException failure) {
        try {
            proxy.stop();
        } catch (RuntimeException cleanupFailure) {
            // Keep the listener reachable so stopProxy/stopAll can retry closing it later.
            unclosableProxies.put(relayKey, proxy);
            failure.addSuppressed(cleanupFailure);
        }
    }
}
