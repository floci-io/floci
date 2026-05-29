package io.github.hectorvent.floci.services.elasticache.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
public class CacheCluster {

    private String cacheClusterId;
    private CacheClusterStatus cacheClusterStatus;
    private String engine;
    private String engineVersion;
    private Endpoint configurationEndpoint;
    private Instant cacheClusterCreateTime;

    // Transient — not persisted, restored on container restart
    private transient String containerId;
    private transient String containerHost;
    private transient int containerPort;

    public CacheCluster() {}

    public CacheCluster(String cacheClusterId, CacheClusterStatus cacheClusterStatus,
                        String engine, String engineVersion,
                        Endpoint configurationEndpoint, Instant cacheClusterCreateTime) {
        this.cacheClusterId = cacheClusterId;
        this.cacheClusterStatus = cacheClusterStatus;
        this.engine = engine;
        this.engineVersion = engineVersion;
        this.configurationEndpoint = configurationEndpoint;
        this.cacheClusterCreateTime = cacheClusterCreateTime;
    }

    public String getCacheClusterId() { return cacheClusterId; }
    public void setCacheClusterId(String cacheClusterId) { this.cacheClusterId = cacheClusterId; }

    public CacheClusterStatus getCacheClusterStatus() { return cacheClusterStatus; }
    public void setCacheClusterStatus(CacheClusterStatus cacheClusterStatus) { this.cacheClusterStatus = cacheClusterStatus; }

    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }

    public String getEngineVersion() { return engineVersion; }
    public void setEngineVersion(String engineVersion) { this.engineVersion = engineVersion; }

    public Endpoint getConfigurationEndpoint() { return configurationEndpoint; }
    public void setConfigurationEndpoint(Endpoint configurationEndpoint) { this.configurationEndpoint = configurationEndpoint; }

    public Instant getCacheClusterCreateTime() { return cacheClusterCreateTime; }
    public void setCacheClusterCreateTime(Instant cacheClusterCreateTime) { this.cacheClusterCreateTime = cacheClusterCreateTime; }

    public String getContainerId() { return containerId; }
    public void setContainerId(String containerId) { this.containerId = containerId; }

    public String getContainerHost() { return containerHost; }
    public void setContainerHost(String containerHost) { this.containerHost = containerHost; }

    public int getContainerPort() { return containerPort; }
    public void setContainerPort(int containerPort) { this.containerPort = containerPort; }
}
