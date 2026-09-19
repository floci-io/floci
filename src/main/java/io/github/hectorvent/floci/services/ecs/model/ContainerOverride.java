package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * A per-container override supplied on a RunTask request
 * (overrides.containerOverrides[]). When present and matched by {@code name}
 * to a container definition, its {@code command} replaces the task-def command
 * and its {@code environment} is merged over the task-def environment.
 */
@RegisterForReflection
public class ContainerOverride {

    private String name;
    private List<String> command;
    private List<KeyValuePair> environment;
    private List<EnvironmentFile> environmentFiles;
    private Integer cpu;
    private Integer memory;
    private Integer memoryReservation;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public List<String> getCommand() { return command; }
    public void setCommand(List<String> command) { this.command = command; }

    public List<KeyValuePair> getEnvironment() { return environment; }
    public void setEnvironment(List<KeyValuePair> environment) { this.environment = environment; }

    public List<EnvironmentFile> getEnvironmentFiles() { return environmentFiles; }
    public void setEnvironmentFiles(List<EnvironmentFile> environmentFiles) {
        this.environmentFiles = environmentFiles;
    }

    public Integer getCpu() { return cpu; }
    public void setCpu(Integer cpu) { this.cpu = cpu; }

    public Integer getMemory() { return memory; }
    public void setMemory(Integer memory) { this.memory = memory; }

    public Integer getMemoryReservation() { return memoryReservation; }
    public void setMemoryReservation(Integer memoryReservation) { this.memoryReservation = memoryReservation; }
}
