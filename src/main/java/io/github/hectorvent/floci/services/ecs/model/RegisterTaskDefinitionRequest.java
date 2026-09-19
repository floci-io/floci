package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

/**
 * A parsed {@code RegisterTaskDefinition} request.
 *
 * <p>Everything the request carried reaches the service in one object, so validation that spans
 * several members (the Fargate rules, which read {@code requiresCompatibilities},
 * {@code networkMode}, the task size and the container definitions together) happens in one place
 * rather than being split between the handler and the service.
 */
@RegisterForReflection
public class RegisterTaskDefinitionRequest {

    private String family;
    private List<ContainerDefinition> containerDefinitions;
    private NetworkMode networkMode;
    private String cpu;
    private String memory;
    private String taskRoleArn;
    private String executionRoleArn;
    private List<String> requiresCompatibilities;
    private List<Volume> volumes;
    private RuntimePlatform runtimePlatform;
    private EphemeralStorage ephemeralStorage;
    private String pidMode;
    private String ipcMode;
    private Map<String, String> tags;
    /** Members Floci does not act on, such as proxyConfiguration and placementConstraints. */
    private Map<String, Object> unparsed;

    public String getFamily() { return family; }
    public void setFamily(String family) { this.family = family; }

    public List<ContainerDefinition> getContainerDefinitions() { return containerDefinitions; }
    public void setContainerDefinitions(List<ContainerDefinition> containerDefinitions) {
        this.containerDefinitions = containerDefinitions;
    }

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

    public List<String> getRequiresCompatibilities() { return requiresCompatibilities; }
    public void setRequiresCompatibilities(List<String> requiresCompatibilities) {
        this.requiresCompatibilities = requiresCompatibilities;
    }

    public List<Volume> getVolumes() { return volumes; }
    public void setVolumes(List<Volume> volumes) { this.volumes = volumes; }

    public RuntimePlatform getRuntimePlatform() { return runtimePlatform; }
    public void setRuntimePlatform(RuntimePlatform runtimePlatform) { this.runtimePlatform = runtimePlatform; }

    public EphemeralStorage getEphemeralStorage() { return ephemeralStorage; }
    public void setEphemeralStorage(EphemeralStorage ephemeralStorage) {
        this.ephemeralStorage = ephemeralStorage;
    }

    public String getPidMode() { return pidMode; }
    public void setPidMode(String pidMode) { this.pidMode = pidMode; }

    public String getIpcMode() { return ipcMode; }
    public void setIpcMode(String ipcMode) { this.ipcMode = ipcMode; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public Map<String, Object> getUnparsed() { return unparsed; }
    public void setUnparsed(Map<String, Object> unparsed) { this.unparsed = unparsed; }

    public boolean isFargate() {
        return requiresCompatibilities != null && requiresCompatibilities.contains("FARGATE");
    }
}
