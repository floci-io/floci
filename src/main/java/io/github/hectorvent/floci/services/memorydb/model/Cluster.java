package io.github.hectorvent.floci.services.memorydb.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Cluster {

    private String name;
    private String description;
    private ClusterStatus status;
    private String nodeType;
    private int numberOfShards;
    private String engine;
    private String engineVersion;
    private String aclName;
    private boolean tlsEnabled;
    private Endpoint clusterEndpoint;
    private String arn;
    private Instant createdAt;
    private int proxyPort;
    private Map<String, String> tags = new LinkedHashMap<>();

    // Transient fields — not persisted, restored on container restart
    private transient String containerId;
    private transient String containerHost;
    private transient int containerPort;

    public Cluster() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public ClusterStatus getStatus() { return status; }
    public void setStatus(ClusterStatus status) { this.status = status; }

    public String getNodeType() { return nodeType; }
    public void setNodeType(String nodeType) { this.nodeType = nodeType; }

    public int getNumberOfShards() { return numberOfShards; }
    public void setNumberOfShards(int numberOfShards) { this.numberOfShards = numberOfShards; }

    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }

    public String getEngineVersion() { return engineVersion; }
    public void setEngineVersion(String engineVersion) { this.engineVersion = engineVersion; }

    public String getAclName() { return aclName; }
    public void setAclName(String aclName) { this.aclName = aclName; }

    public boolean isTlsEnabled() { return tlsEnabled; }
    public void setTlsEnabled(boolean tlsEnabled) { this.tlsEnabled = tlsEnabled; }

    public Endpoint getClusterEndpoint() { return clusterEndpoint; }
    public void setClusterEndpoint(Endpoint clusterEndpoint) { this.clusterEndpoint = clusterEndpoint; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public int getProxyPort() { return proxyPort; }
    public void setProxyPort(int proxyPort) { this.proxyPort = proxyPort; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }

    public String getContainerId() { return containerId; }
    public void setContainerId(String containerId) { this.containerId = containerId; }

    public String getContainerHost() { return containerHost; }
    public void setContainerHost(String containerHost) { this.containerHost = containerHost; }

    public int getContainerPort() { return containerPort; }
    public void setContainerPort(int containerPort) { this.containerPort = containerPort; }
}
