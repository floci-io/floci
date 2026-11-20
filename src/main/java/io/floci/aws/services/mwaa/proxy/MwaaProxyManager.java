package io.floci.aws.services.mwaa.proxy;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.tls.ReloadingKeyManager;
import io.quarkus.tls.CertificateUpdatedEvent;
import io.quarkus.tls.TlsConfiguration;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.quarkus.tls.runtime.config.TlsConfig;
import io.vertx.core.Vertx;
import io.vertx.core.net.KeyCertOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of all active {@link MwaaWebProxy} instances. One proxy per MWAA environment,
 * started/stopped alongside the environment's containers. Mirrors {@code NeptuneProxyManager}.
 *
 * <p>With {@code floci.tls.enabled}, {@code WebserverUrl} and {@code WebServerHostname} name an
 * {@code https} endpoint, so every proxy serves TLS with Floci's own server certificate, the way
 * the IoT MQTT TLS listener does, and follows that certificate when it is reissued.
 */
@ApplicationScoped
public class MwaaProxyManager {

    private static final Logger LOG = Logger.getLogger(MwaaProxyManager.class);

    private final Vertx vertx;
    private final EmulatorConfig config;
    private final TlsConfigurationRegistry tlsRegistry;
    private final ConcurrentHashMap<String, MwaaWebProxy> proxies = new ConcurrentHashMap<>();
    private ReloadingKeyManager keyManager;

    @Inject
    public MwaaProxyManager(Vertx vertx, EmulatorConfig config, TlsConfigurationRegistry tlsRegistry) {
        this.vertx = vertx;
        this.config = config;
        this.tlsRegistry = tlsRegistry;
    }

    public void startProxy(String environmentName, int proxyPort, String backendHost, int backendPort,
                           MwaaWebProxy.TokenValidator cliTokenValidator, MwaaWebProxy.CliExecutor cliExecutor,
                           MwaaWebProxy.TokenValidator webTokenValidator, MwaaWebProxy.AirflowAccount airflowAccount) {
        MwaaWebProxy proxy = new MwaaWebProxy(environmentName, vertx, backendHost, backendPort,
                cliTokenValidator, cliExecutor, webTokenValidator, airflowAccount);
        try {
            proxy.start(proxyPort, serverKeyCertOptions());
            proxies.put(environmentName, proxy);
        } catch (Exception e) {
            throw new RuntimeException("Failed to start MWAA web proxy for environment " + environmentName
                    + " on port " + proxyPort, e);
        }
    }

    /** Floci's server certificate when TLS is on, {@code null} for a plain HTTP proxy. */
    synchronized KeyCertOptions serverKeyCertOptions() {
        if (!config.tls().enabled()) {
            return null;
        }
        if (keyManager == null) {
            TlsConfiguration tls = tlsRegistry.getDefault().orElseThrow(() -> new IllegalStateException(
                    "floci.tls.enabled is true but no default TLS configuration is registered"));
            keyManager = new ReloadingKeyManager(ReloadingKeyManager.keyManagerOf(vertx, tls.getKeyStoreOptions()));
        }
        return KeyCertOptions.wrap(keyManager);
    }

    /**
     * Follows a reissued server certificate (a hostname learned by {@code TlsCertificateManager.ensureHost},
     * or a reload): the next handshake on every proxy presents it. A key store that cannot be loaded, which
     * {@link ReloadingKeyManager#keyManagerOf} reports as an {@link IllegalStateException}, keeps the previous
     * one; any other failure propagates.
     */
    synchronized void onCertificateUpdated(@Observes CertificateUpdatedEvent event) {
        if (keyManager == null || !TlsConfig.DEFAULT_NAME.equalsIgnoreCase(event.name())) {
            return;
        }
        try {
            keyManager.reload(ReloadingKeyManager.keyManagerOf(vertx, event.tlsConfiguration().getKeyStoreOptions()));
        } catch (IllegalStateException e) {
            LOG.warnv(e, "MWAA web proxies keep their previous server certificate: {0}", e.getMessage());
        }
    }

    public void stopProxy(String environmentName) {
        MwaaWebProxy proxy = proxies.remove(environmentName);
        if (proxy != null) {
            proxy.stop();
            LOG.infov("Stopped MWAA web proxy for environment {0}", environmentName);
        }
    }

    public void stopAll() {
        proxies.values().forEach(MwaaWebProxy::stop);
        proxies.clear();
        LOG.info("Stopped all MWAA web proxies");
    }
}
