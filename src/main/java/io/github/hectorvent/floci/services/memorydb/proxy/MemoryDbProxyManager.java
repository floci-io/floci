package io.github.hectorvent.floci.services.memorydb.proxy;

import io.github.hectorvent.floci.services.elasticache.proxy.SigV4Validator;
import io.github.hectorvent.floci.services.memorydb.model.AuthMode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of all active MemoryDB auth proxies. One proxy instance per cluster.
 */
@ApplicationScoped
public class MemoryDbProxyManager {

    private static final Logger LOG = Logger.getLogger(MemoryDbProxyManager.class);

    private final SigV4Validator sigV4Validator;
    private final ConcurrentHashMap<String, MemoryDbAuthProxy> proxies = new ConcurrentHashMap<>();

    @Inject
    public MemoryDbProxyManager(SigV4Validator sigV4Validator) {
        this.sigV4Validator = sigV4Validator;
    }

    public void startProxy(String clusterName, AuthMode authMode, int proxyPort,
                           String backendHost, int backendPort,
                           MemoryDbAuthProxy.PasswordValidator passwordValidator) {
        MemoryDbAuthProxy proxy = new MemoryDbAuthProxy(
                clusterName, authMode, backendHost, backendPort,
                passwordValidator, sigV4Validator);
        try {
            proxy.start(proxyPort);
            proxies.put(clusterName, proxy);
        } catch (IOException e) {
            throw new RuntimeException("Failed to start proxy for cluster " + clusterName
                    + " on port " + proxyPort, e);
        }
    }

    public void stopProxy(String clusterName) {
        MemoryDbAuthProxy proxy = proxies.remove(clusterName);
        if (proxy != null) {
            proxy.stop();
            LOG.infov("Stopped proxy for cluster {0}", clusterName);
        }
    }

    public void stopAll() {
        proxies.values().forEach(MemoryDbAuthProxy::stop);
        proxies.clear();
        LOG.info("Stopped all MemoryDB proxies");
    }
}
