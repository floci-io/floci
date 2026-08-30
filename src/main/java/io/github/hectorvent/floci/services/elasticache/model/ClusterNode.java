package io.github.hectorvent.floci.services.elasticache.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One node of a cluster-mode-enabled replication group: a member cache cluster
 * backed by its own Valkey container and auth-proxy port.
 */
@RegisterForReflection
public class ClusterNode {

    private String memberClusterId;
    private String nodeGroupId;
    private boolean primary;
    private int proxyPort;
    private String slots;

    // Transient fields — not persisted, restored on container restart
    private transient String containerId;
    private transient String containerHost;
    private transient int containerPort;

    public ClusterNode() {}

    public ClusterNode(String memberClusterId, String nodeGroupId, boolean primary,
                       int proxyPort, String slots) {
        this.memberClusterId = memberClusterId;
        this.nodeGroupId = nodeGroupId;
        this.primary = primary;
        this.proxyPort = proxyPort;
        this.slots = slots;
    }

    public String getMemberClusterId() { return memberClusterId; }
    public void setMemberClusterId(String memberClusterId) { this.memberClusterId = memberClusterId; }

    public String getNodeGroupId() { return nodeGroupId; }
    public void setNodeGroupId(String nodeGroupId) { this.nodeGroupId = nodeGroupId; }

    public boolean isPrimary() { return primary; }
    public void setPrimary(boolean primary) { this.primary = primary; }

    public int getProxyPort() { return proxyPort; }
    public void setProxyPort(int proxyPort) { this.proxyPort = proxyPort; }

    public String getSlots() { return slots; }
    public void setSlots(String slots) { this.slots = slots; }

    public String getContainerId() { return containerId; }
    public void setContainerId(String containerId) { this.containerId = containerId; }

    public String getContainerHost() { return containerHost; }
    public void setContainerHost(String containerHost) { this.containerHost = containerHost; }

    public int getContainerPort() { return containerPort; }
    public void setContainerPort(int containerPort) { this.containerPort = containerPort; }
}
