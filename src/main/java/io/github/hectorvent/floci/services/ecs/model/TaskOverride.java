package io.github.hectorvent.floci.services.ecs.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * The {@code overrides} of a RunTask or StartTask request, echoed back on the task.
 *
 * <p>The task-level members replace the task definition's own {@code cpu}, {@code memory},
 * roles and {@code ephemeralStorage} for this task only; {@code containerOverrides} carries the
 * per-container half.
 */
@RegisterForReflection
public class TaskOverride {

    private List<ContainerOverride> containerOverrides;
    private String cpu;
    private String memory;
    private String taskRoleArn;
    private String executionRoleArn;
    private EphemeralStorage ephemeralStorage;

    public List<ContainerOverride> getContainerOverrides() { return containerOverrides; }
    public void setContainerOverrides(List<ContainerOverride> containerOverrides) {
        this.containerOverrides = containerOverrides;
    }

    public String getCpu() { return cpu; }
    public void setCpu(String cpu) { this.cpu = cpu; }

    public String getMemory() { return memory; }
    public void setMemory(String memory) { this.memory = memory; }

    public String getTaskRoleArn() { return taskRoleArn; }
    public void setTaskRoleArn(String taskRoleArn) { this.taskRoleArn = taskRoleArn; }

    public String getExecutionRoleArn() { return executionRoleArn; }
    public void setExecutionRoleArn(String executionRoleArn) { this.executionRoleArn = executionRoleArn; }

    public EphemeralStorage getEphemeralStorage() { return ephemeralStorage; }
    public void setEphemeralStorage(EphemeralStorage ephemeralStorage) {
        this.ephemeralStorage = ephemeralStorage;
    }

    @JsonIgnore
    public boolean isEmpty() {
        return (containerOverrides == null || containerOverrides.isEmpty())
                && cpu == null && memory == null && taskRoleArn == null
                && executionRoleArn == null && ephemeralStorage == null;
    }
}
