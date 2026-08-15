package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A registered daemon task definition ({@code RegisterDaemonTaskDefinition}). A template for
 * the containers that make up an ECS daemon, distinct from a regular {@link TaskDefinition}
 * (no network mode, no per-launch-type compatibility list).
 */
@RegisterForReflection
public class DaemonTaskDefinition {

    private String daemonTaskDefinitionArn;
    private String family;
    private int revision;
    private String status; // ACTIVE or DELETED
    private String cpu;
    private String memory;
    private String executionRoleArn;
    private String taskRoleArn;
    private String ipcMode; // none or shared
    private String pidMode; // none or shared
    private List<DaemonContainerDefinition> containerDefinitions;
    private List<Volume> volumes;
    private Instant registeredAt;
    private Map<String, String> tags = new HashMap<>();

    public String getDaemonTaskDefinitionArn() { return daemonTaskDefinitionArn; }
    public void setDaemonTaskDefinitionArn(String daemonTaskDefinitionArn) { this.daemonTaskDefinitionArn = daemonTaskDefinitionArn; }

    public String getFamily() { return family; }
    public void setFamily(String family) { this.family = family; }

    public int getRevision() { return revision; }
    public void setRevision(int revision) { this.revision = revision; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCpu() { return cpu; }
    public void setCpu(String cpu) { this.cpu = cpu; }

    public String getMemory() { return memory; }
    public void setMemory(String memory) { this.memory = memory; }

    public String getExecutionRoleArn() { return executionRoleArn; }
    public void setExecutionRoleArn(String executionRoleArn) { this.executionRoleArn = executionRoleArn; }

    public String getTaskRoleArn() { return taskRoleArn; }
    public void setTaskRoleArn(String taskRoleArn) { this.taskRoleArn = taskRoleArn; }

    public String getIpcMode() { return ipcMode; }
    public void setIpcMode(String ipcMode) { this.ipcMode = ipcMode; }

    public String getPidMode() { return pidMode; }
    public void setPidMode(String pidMode) { this.pidMode = pidMode; }

    public List<DaemonContainerDefinition> getContainerDefinitions() { return containerDefinitions; }
    public void setContainerDefinitions(List<DaemonContainerDefinition> containerDefinitions) {
        this.containerDefinitions = containerDefinitions;
    }

    public List<Volume> getVolumes() { return volumes; }
    public void setVolumes(List<Volume> volumes) { this.volumes = volumes; }

    public Instant getRegisteredAt() { return registeredAt; }
    public void setRegisteredAt(Instant registeredAt) { this.registeredAt = registeredAt; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
