package io.github.hectorvent.floci.services.batch.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class BatchContainerOverrides {
    private List<String> command = new ArrayList<>();
    private List<BatchKeyValue> environment = new ArrayList<>();
    private List<BatchResourceRequirement> resourceRequirements = new ArrayList<>();

    public List<String> getCommand() {
        return command;
    }

    public void setCommand(List<String> command) {
        this.command = command != null ? command : new ArrayList<>();
    }

    public List<BatchKeyValue> getEnvironment() {
        return environment;
    }

    public void setEnvironment(List<BatchKeyValue> environment) {
        this.environment = environment != null ? environment : new ArrayList<>();
    }

    public List<BatchResourceRequirement> getResourceRequirements() {
        return resourceRequirements;
    }

    public void setResourceRequirements(List<BatchResourceRequirement> resourceRequirements) {
        this.resourceRequirements = resourceRequirements != null ? resourceRequirements : new ArrayList<>();
    }
}
