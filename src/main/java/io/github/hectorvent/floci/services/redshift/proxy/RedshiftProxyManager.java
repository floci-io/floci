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
    private final java.util.Set<String> failedCleanups = ConcurrentHashMap.newKeySet();

    @Inject
    public RedshiftProxyManager(RdsSigV4Validator sigV4Validator, RdsProxyTlsCertificates tlsCertificates) {
        this.sigV4Validator = sigV4Validator;
        this.tlsCertificates = tlsCertificates;
    }

    public synchronized void startProxy(String relayKey, int proxyPort,
                                        String backendHost, int backendPort, String advertisedHost,
                                        String masterUsername, String masterPassword, String dbName,
                                        RdsAuthProxy.PasswordValidator passwordValidator) {
        failedCleanups.remove(relayKey);
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
        if (failedCleanups.contains(relayKey)) {
            throw new RuntimeException("Proxy listener cleanup previously failed for " + relayKey);
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
        failedCleanups.clear();
        LOG.info("Stopped all Redshift proxies");
    }

    void onShutdown(@Observes ShutdownEvent event) {
        stopAll();
    }

    private void cleanupFailedStart(String relayKey, RedshiftAuthProxy proxy, RuntimeException failure) {
        try {
            proxy.stop();
        } catch (RuntimeException cleanupFailure) {
            failedCleanups.add(relayKey);
            failure.addSuppressed(cleanupFailure);
        }
    }
}
