package io.github.hectorvent.floci.services.mwaa.proxy;

import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of all active {@link MwaaWebProxy} instances. One proxy per MWAA environment,
 * started/stopped alongside the environment's containers. Mirrors {@code NeptuneProxyManager}.
 */
@ApplicationScoped
public class MwaaProxyManager {

    private static final Logger LOG = Logger.getLogger(MwaaProxyManager.class);

    private final Vertx vertx;
    private final ConcurrentHashMap<String, MwaaWebProxy> proxies = new ConcurrentHashMap<>();

    @Inject
    public MwaaProxyManager(Vertx vertx) {
        this.vertx = vertx;
    }

    public void startProxy(String environmentName, int proxyPort, String backendHost, int backendPort,
                           MwaaWebProxy.CliTokenValidator tokenValidator, MwaaWebProxy.CliExecutor cliExecutor) {
        MwaaWebProxy proxy = new MwaaWebProxy(environmentName, vertx, backendHost, backendPort,
                tokenValidator, cliExecutor);
        try {
            proxy.start(proxyPort);
            proxies.put(environmentName, proxy);
        } catch (Exception e) {
            throw new RuntimeException("Failed to start MWAA web proxy for environment " + environmentName
                    + " on port " + proxyPort, e);
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
