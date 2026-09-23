package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public class ContainerInstance {

    private String containerInstanceArn;
    private String ec2InstanceId;
    private String status;
    private int runningTasksCount;
    private int pendingTasksCount;
    private String statusReason;
    private boolean agentConnected;
    private String agentUpdateStatus;
    private String capacityProviderName;
    private long version;
    private Instant registeredAt;
    /** The agent's own {@code versionInfo}: agentHash, agentVersion and dockerVersion. */
    private Map<String, Object> versionInfo;
    private List<Map<String, Object>> registeredResources;
    private List<Map<String, Object>> remainingResources;
    private List<Attribute> attributes = new ArrayList<>();
    private Map<String, String> tags = new HashMap<>();

    public String getContainerInstanceArn() { return containerInstanceArn; }
    public void setContainerInstanceArn(String containerInstanceArn) { this.containerInstanceArn = containerInstanceArn; }

    public String getEc2InstanceId() { return ec2InstanceId; }
    public void setEc2InstanceId(String ec2InstanceId) { this.ec2InstanceId = ec2InstanceId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getRunningTasksCount() { return runningTasksCount; }
    public void setRunningTasksCount(int runningTasksCount) { this.runningTasksCount = runningTasksCount; }

    public int getPendingTasksCount() { return pendingTasksCount; }
    public void setPendingTasksCount(int pendingTasksCount) { this.pendingTasksCount = pendingTasksCount; }

    public String getStatusReason() { return statusReason; }
    public void setStatusReason(String statusReason) { this.statusReason = statusReason; }

    public boolean isAgentConnected() { return agentConnected; }
    public void setAgentConnected(boolean agentConnected) { this.agentConnected = agentConnected; }

    public String getAgentUpdateStatus() { return agentUpdateStatus; }
    public void setAgentUpdateStatus(String agentUpdateStatus) { this.agentUpdateStatus = agentUpdateStatus; }

    public String getCapacityProviderName() { return capacityProviderName; }
    public void setCapacityProviderName(String capacityProviderName) { this.capacityProviderName = capacityProviderName; }

    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }

    public Instant getRegisteredAt() { return registeredAt; }
    public void setRegisteredAt(Instant registeredAt) { this.registeredAt = registeredAt; }

    public Map<String, Object> getVersionInfo() { return versionInfo; }
    public void setVersionInfo(Map<String, Object> versionInfo) { this.versionInfo = versionInfo; }

    public List<Map<String, Object>> getRegisteredResources() { return registeredResources; }
    public void setRegisteredResources(List<Map<String, Object>> resources) { this.registeredResources = resources; }

    public List<Map<String, Object>> getRemainingResources() { return remainingResources; }
    public void setRemainingResources(List<Map<String, Object>> resources) { this.remainingResources = resources; }

    public List<Attribute> getAttributes() { return attributes; }
    public void setAttributes(List<Attribute> attributes) { this.attributes = attributes; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
