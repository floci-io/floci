package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * A container definition for an ECS daemon task. Distinct from {@link ContainerDefinition}
 * because the real AWS {@code DaemonContainerDefinition} shape has no {@code portMappings}
 * or {@code secrets} members — daemon tasks are cross-cutting agents, not traffic-serving
 * workloads. Models the same minimal, faithful subset of fields that {@link ContainerDefinition}
 * covers for regular task definitions.
 */
@RegisterForReflection
public class DaemonContainerDefinition {

    private String name;
    private String image;
    private Integer cpu;
    private Integer memory;
    private Integer memoryReservation;
    private boolean essential = true;
    private List<String> command;
    private List<String> entryPoint;
    private List<KeyValuePair> environment;
    private List<MountPoint> mountPoints;
    private String user;
    private String workingDirectory;
    private Boolean privileged;
    private Boolean readonlyRootFilesystem;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getImage() { return image; }
    public void setImage(String image) { this.image = image; }

    public Integer getCpu() { return cpu; }
    public void setCpu(Integer cpu) { this.cpu = cpu; }

    public Integer getMemory() { return memory; }
    public void setMemory(Integer memory) { this.memory = memory; }

    public Integer getMemoryReservation() { return memoryReservation; }
    public void setMemoryReservation(Integer memoryReservation) { this.memoryReservation = memoryReservation; }

    public boolean isEssential() { return essential; }
    public void setEssential(boolean essential) { this.essential = essential; }

    public List<String> getCommand() { return command; }
    public void setCommand(List<String> command) { this.command = command; }

    public List<String> getEntryPoint() { return entryPoint; }
    public void setEntryPoint(List<String> entryPoint) { this.entryPoint = entryPoint; }

    public List<KeyValuePair> getEnvironment() { return environment; }
    public void setEnvironment(List<KeyValuePair> environment) { this.environment = environment; }

    public List<MountPoint> getMountPoints() { return mountPoints; }
    public void setMountPoints(List<MountPoint> mountPoints) { this.mountPoints = mountPoints; }

    public String getUser() { return user; }
    public void setUser(String user) { this.user = user; }

    public String getWorkingDirectory() { return workingDirectory; }
    public void setWorkingDirectory(String workingDirectory) { this.workingDirectory = workingDirectory; }

    public Boolean getPrivileged() { return privileged; }
    public void setPrivileged(Boolean privileged) { this.privileged = privileged; }

    public Boolean getReadonlyRootFilesystem() { return readonlyRootFilesystem; }
    public void setReadonlyRootFilesystem(Boolean readonlyRootFilesystem) { this.readonlyRootFilesystem = readonlyRootFilesystem; }
}
