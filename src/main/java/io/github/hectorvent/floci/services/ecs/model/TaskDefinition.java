package io.github.hectorvent.floci.services.ecs.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public class TaskDefinition {

    private String taskDefinitionArn;
    private String family;
    private int revision;
    private String status; // ACTIVE or INACTIVE
    private NetworkMode networkMode;
    private String cpu;
    private String memory;
    private String taskRoleArn;
    private String executionRoleArn;
    private List<ContainerDefinition> containerDefinitions;
    private List<Volume> volumes;
    private RuntimePlatform runtimePlatform;
    private List<String> requiresCompatibilities;
    private List<String> compatibilities;
    /** The container instance capabilities the definition needs, derived at registration. */
    private List<Attribute> requiresAttributes;
    private EphemeralStorage ephemeralStorage;
    private String pidMode;
    private String ipcMode;
    private Instant registeredAt;
    private String registeredBy;
    private Instant deregisteredAt;
    private Instant deleteRequestedAt;
    private Map<String, String> tags = new HashMap<>();
    /** Members Floci does not act on, kept verbatim so DescribeTaskDefinition round-trips. */
    private Map<String, Object> unparsed;

    public String getTaskDefinitionArn() { return taskDefinitionArn; }
    public void setTaskDefinitionArn(String taskDefinitionArn) { this.taskDefinitionArn = taskDefinitionArn; }

    public String getFamily() { return family; }
    public void setFamily(String family) { this.family = family; }

    public int getRevision() { return revision; }
    public void setRevision(int revision) { this.revision = revision; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public NetworkMode getNetworkMode() { return networkMode; }
    public void setNetworkMode(NetworkMode networkMode) { this.networkMode = networkMode; }

    public String getCpu() { return cpu; }
    public void setCpu(String cpu) { this.cpu = cpu; }

    public String getMemory() { return memory; }
    public void setMemory(String memory) { this.memory = memory; }

    public String getTaskRoleArn() { return taskRoleArn; }
    public void setTaskRoleArn(String taskRoleArn) { this.taskRoleArn = taskRoleArn; }

    public String getExecutionRoleArn() { return executionRoleArn; }
    public void setExecutionRoleArn(String executionRoleArn) { this.executionRoleArn = executionRoleArn; }

    public List<ContainerDefinition> getContainerDefinitions() { return containerDefinitions; }
    public void setContainerDefinitions(List<ContainerDefinition> containerDefinitions) {
        this.containerDefinitions = containerDefinitions;
    }

    public List<Volume> getVolumes() { return volumes; }
    public void setVolumes(List<Volume> volumes) { this.volumes = volumes; }

    public RuntimePlatform getRuntimePlatform() { return runtimePlatform; }
    public void setRuntimePlatform(RuntimePlatform runtimePlatform) { this.runtimePlatform = runtimePlatform; }

    public List<String> getRequiresCompatibilities() { return requiresCompatibilities; }
    public void setRequiresCompatibilities(List<String> requiresCompatibilities) { this.requiresCompatibilities = requiresCompatibilities; }

    public List<Attribute> getRequiresAttributes() { return requiresAttributes; }
    public void setRequiresAttributes(List<Attribute> requiresAttributes) { this.requiresAttributes = requiresAttributes; }

    public List<String> getCompatibilities() { return compatibilities; }
    public void setCompatibilities(List<String> compatibilities) { this.compatibilities = compatibilities; }

    public EphemeralStorage getEphemeralStorage() { return ephemeralStorage; }
    public void setEphemeralStorage(EphemeralStorage ephemeralStorage) { this.ephemeralStorage = ephemeralStorage; }

    public String getPidMode() { return pidMode; }
    public void setPidMode(String pidMode) { this.pidMode = pidMode; }

    public String getIpcMode() { return ipcMode; }
    public void setIpcMode(String ipcMode) { this.ipcMode = ipcMode; }

    public Instant getRegisteredAt() { return registeredAt; }
    public void setRegisteredAt(Instant registeredAt) { this.registeredAt = registeredAt; }

    public String getRegisteredBy() { return registeredBy; }
    public void setRegisteredBy(String registeredBy) { this.registeredBy = registeredBy; }

    public Instant getDeregisteredAt() { return deregisteredAt; }
    public void setDeregisteredAt(Instant deregisteredAt) { this.deregisteredAt = deregisteredAt; }

    /** When deletion was asked for, which is when the revision entered DELETE_IN_PROGRESS. */
    public Instant getDeleteRequestedAt() { return deleteRequestedAt; }
    public void setDeleteRequestedAt(Instant deleteRequestedAt) { this.deleteRequestedAt = deleteRequestedAt; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public Map<String, Object> getUnparsed() { return unparsed; }
    public void setUnparsed(Map<String, Object> unparsed) { this.unparsed = unparsed; }

    /** Whether the definition declares Fargate compatibility, which gates the Fargate-only rules. */
    @JsonIgnore
    public boolean isFargateCompatible() {
        return requiresCompatibilities != null && requiresCompatibilities.contains("FARGATE");
    }
}
