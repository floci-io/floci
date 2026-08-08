package io.github.hectorvent.floci.services.elasticache.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@RegisterForReflection
public class ReplicationGroup {

    private String replicationGroupId;
    private String description;
    private ReplicationGroupStatus status;
    private AuthMode authMode;
    private Endpoint configurationEndpoint;
    private Instant createdAt;
    private int proxyPort;
    private String authToken; // stored plain-text for PASSWORD auth validation in the proxy
    private Set<String> associatedUserIds = new HashSet<>();
    private String arn;
    private String region;

    // Transient fields — not persisted, restored on container restart
    private transient String containerId;
    private transient String containerHost;
    private transient int containerPort;

    public ReplicationGroup() {}

    public ReplicationGroup(String replicationGroupId, String description,
                            ReplicationGroupStatus status, AuthMode authMode,
                            Endpoint configurationEndpoint, Instant createdAt, int proxyPort) {
        this.replicationGroupId = replicationGroupId;
        this.description = description;
        this.status = status;
        this.authMode = authMode;
        this.configurationEndpoint = configurationEndpoint;
        this.createdAt = createdAt;
        this.proxyPort = proxyPort;
    }

    public String getReplicationGroupId() { return replicationGroupId; }
    public void setReplicationGroupId(String replicationGroupId) { this.replicationGroupId = replicationGroupId; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public ReplicationGroupStatus getStatus() { return status; }
    public void setStatus(ReplicationGroupStatus status) { this.status = status; }

    public AuthMode getAuthMode() { return authMode; }
    public void setAuthMode(AuthMode authMode) { this.authMode = authMode; }

    public Endpoint getConfigurationEndpoint() { return configurationEndpoint; }
    public void setConfigurationEndpoint(Endpoint configurationEndpoint) { this.configurationEndpoint = configurationEndpoint; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public int getProxyPort() { return proxyPort; }
    public void setProxyPort(int proxyPort) { this.proxyPort = proxyPort; }

    public String getAuthToken() { return authToken; }
    public void setAuthToken(String authToken) { this.authToken = authToken; }

    public Set<String> getAssociatedUserIds() { return associatedUserIds; }
    public void setAssociatedUserIds(Set<String> associatedUserIds) {
        this.associatedUserIds = associatedUserIds != null ? associatedUserIds : new HashSet<>();
    }

    public String getContainerId() { return containerId; }
    public void setContainerId(String containerId) { this.containerId = containerId; }

    public String getContainerHost() { return containerHost; }
    public void setContainerHost(String containerHost) { this.containerHost = containerHost; }

    public int getContainerPort() { return containerPort; }
    public void setContainerPort(int containerPort) { this.containerPort = containerPort; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
}
