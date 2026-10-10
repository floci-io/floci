package io.github.hectorvent.floci.services.elasticache.proxy;

import io.github.hectorvent.floci.services.elasticache.model.AuthMode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of all active ElastiCache auth proxies.
 * One proxy instance per replication group.
 */
@ApplicationScoped
public class ElastiCacheProxyManager {

    private static final Logger LOG = Logger.getLogger(ElastiCacheProxyManager.class);

    private final SigV4Validator sigV4Validator;
    private final ConcurrentHashMap<String, ElastiCacheAuthProxy> proxies = new ConcurrentHashMap<>();

    @Inject
    public ElastiCacheProxyManager(SigV4Validator sigV4Validator) {
        this.sigV4Validator = sigV4Validator;
    }

    public void startProxy(String groupId, AuthMode authMode, int proxyPort,
                           String backendHost, int backendPort,
                           ElastiCacheAuthProxy.PasswordValidator passwordValidator) {
        startProxy(groupId, authMode, proxyPort, backendHost, backendPort, passwordValidator, groupId);
    }

    public void startProxy(String groupId, AuthMode authMode, int proxyPort,
                           String backendHost, int backendPort,
                           ElastiCacheAuthProxy.PasswordValidator passwordValidator, String authIdentity) {
        ElastiCacheAuthProxy proxy = new ElastiCacheAuthProxy(
                authIdentity, authMode, backendHost, backendPort,
                passwordValidator, sigV4Validator);
        try {
            proxy.start(proxyPort);
            proxies.put(groupId, proxy);
        } catch (IOException e) {
            throw new RuntimeException("Failed to start proxy for group " + groupId
                    + " on port " + proxyPort, e);
        }
    }

    public void stopProxy(String groupId) {
        ElastiCacheAuthProxy proxy = proxies.remove(groupId);
        if (proxy != null) {
            proxy.stop();
            LOG.infov("Stopped proxy for group {0}", groupId);
        }
    }

    public void stopAll() {
        proxies.values().forEach(ElastiCacheAuthProxy::stop);
        proxies.clear();
        LOG.info("Stopped all ElastiCache proxies");
    }
}
