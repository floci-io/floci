package io.github.hectorvent.floci.services.memorydb.proxy;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of all active MemoryDB auth proxies. One proxy instance per cluster.
 */
@ApplicationScoped
public class MemoryDbProxyManager {

    private static final Logger LOG = Logger.getLogger(MemoryDbProxyManager.class);

    private final ConcurrentHashMap<String, MemoryDbAuthProxy> proxies = new ConcurrentHashMap<>();

    public void startProxy(String clusterName, boolean authRequired, int proxyPort,
                           String backendHost, int backendPort,
                           MemoryDbAuthProxy.AuthValidator authValidator) {
        MemoryDbAuthProxy proxy = new MemoryDbAuthProxy(
                clusterName, authRequired, backendHost, backendPort, authValidator);
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
