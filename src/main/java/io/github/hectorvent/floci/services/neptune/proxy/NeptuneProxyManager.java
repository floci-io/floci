package io.github.hectorvent.floci.services.neptune.proxy;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of all active Neptune Gremlin proxies. One proxy per DB cluster.
 */
@ApplicationScoped
public class NeptuneProxyManager {

    private static final Logger LOG = Logger.getLogger(NeptuneProxyManager.class);

    private final ConcurrentHashMap<String, NeptuneGremlinProxy> proxies = new ConcurrentHashMap<>();

    public void startProxy(String clusterId, int proxyPort, String backendHost, int backendPort) {
        NeptuneGremlinProxy proxy = new NeptuneGremlinProxy(clusterId, backendHost, backendPort);
        try {
            proxy.start(proxyPort);
            proxies.put(clusterId, proxy);
        } catch (IOException e) {
            throw new RuntimeException("Failed to start Gremlin proxy for cluster " + clusterId
                    + " on port " + proxyPort, e);
        }
    }

    public void stopProxy(String clusterId) {
        NeptuneGremlinProxy proxy = proxies.remove(clusterId);
        if (proxy != null) {
            proxy.stop();
            LOG.infov("Stopped Gremlin proxy for cluster {0}", clusterId);
        }
    }

    public void stopAll() {
        proxies.values().forEach(NeptuneGremlinProxy::stop);
        proxies.clear();
        LOG.info("Stopped all Neptune Gremlin proxies");
    }
}
