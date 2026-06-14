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

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public List<String> getCommand() { return command; }
    public void setCommand(List<String> command) { this.command = command; }

    public List<KeyValuePair> getEnvironment() { return environment; }
    public void setEnvironment(List<KeyValuePair> environment) { this.environment = environment; }
}
