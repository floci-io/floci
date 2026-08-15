package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A cluster-scoped ECS daemon ({@code CreateDaemon}): a cross-cutting agent deployed as
 * exactly one task per container instance of the given capacity providers. Holds a single
 * revision inline ({@code revisionArn} / {@code daemonTaskDefinitionArn} /
 * {@code revisionCreatedAt}) rather than a separate revision-history map, because
 * {@code UpdateDaemon} is not implemented here and a daemon this emulator creates therefore
 * only ever has the one revision it was created with.
 */
@RegisterForReflection
public class Daemon {

    private String daemonArn;
    private String daemonName;
    private String clusterArn;
    private String status; // ACTIVE or DELETE_IN_PROGRESS
    private List<String> capacityProviderArns;
    private String daemonTaskDefinitionArn;
    private String deploymentArn;
    private String revisionArn;
    private Instant revisionCreatedAt;
    private boolean enableEcsManagedTags;
    private boolean enableExecuteCommand;
    private String propagateTags; // DAEMON or NONE
    private Instant createdAt;
    private Instant updatedAt;
    private Map<String, String> tags = new HashMap<>();

    public String getDaemonArn() { return daemonArn; }
    public void setDaemonArn(String daemonArn) { this.daemonArn = daemonArn; }

    public String getDaemonName() { return daemonName; }
    public void setDaemonName(String daemonName) { this.daemonName = daemonName; }

    public String getClusterArn() { return clusterArn; }
    public void setClusterArn(String clusterArn) { this.clusterArn = clusterArn; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public List<String> getCapacityProviderArns() { return capacityProviderArns; }
    public void setCapacityProviderArns(List<String> capacityProviderArns) { this.capacityProviderArns = capacityProviderArns; }

    public String getDaemonTaskDefinitionArn() { return daemonTaskDefinitionArn; }
    public void setDaemonTaskDefinitionArn(String daemonTaskDefinitionArn) { this.daemonTaskDefinitionArn = daemonTaskDefinitionArn; }

    public String getDeploymentArn() { return deploymentArn; }
    public void setDeploymentArn(String deploymentArn) { this.deploymentArn = deploymentArn; }

    public String getRevisionArn() { return revisionArn; }
    public void setRevisionArn(String revisionArn) { this.revisionArn = revisionArn; }

    public Instant getRevisionCreatedAt() { return revisionCreatedAt; }
    public void setRevisionCreatedAt(Instant revisionCreatedAt) { this.revisionCreatedAt = revisionCreatedAt; }

    public boolean isEnableEcsManagedTags() { return enableEcsManagedTags; }
    public void setEnableEcsManagedTags(boolean enableEcsManagedTags) { this.enableEcsManagedTags = enableEcsManagedTags; }

    public boolean isEnableExecuteCommand() { return enableExecuteCommand; }
    public void setEnableExecuteCommand(boolean enableExecuteCommand) { this.enableExecuteCommand = enableExecuteCommand; }

    public String getPropagateTags() { return propagateTags; }
    public void setPropagateTags(String propagateTags) { this.propagateTags = propagateTags; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
