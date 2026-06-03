package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

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
    private Map<String, String> tags = new HashMap<>();

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

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
