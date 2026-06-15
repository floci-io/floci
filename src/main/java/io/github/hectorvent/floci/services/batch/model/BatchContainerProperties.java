package io.github.hectorvent.floci.services.batch.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class BatchContainerProperties {
    private String image;
    private List<String> command = new ArrayList<>();
    private List<BatchKeyValue> environment = new ArrayList<>();
    private List<BatchResourceRequirement> resourceRequirements = new ArrayList<>();
    private String jobRoleArn;
    private String executionRoleArn;
    private Map<String, Object> logConfiguration;
    private Map<String, Object> networkConfiguration;
    private Map<String, Object> ephemeralStorage;

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

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

    public String getJobRoleArn() {
        return jobRoleArn;
    }

    public void setJobRoleArn(String jobRoleArn) {
        this.jobRoleArn = jobRoleArn;
    }

    public String getExecutionRoleArn() {
        return executionRoleArn;
    }

    public void setExecutionRoleArn(String executionRoleArn) {
        this.executionRoleArn = executionRoleArn;
    }

    public Map<String, Object> getLogConfiguration() {
        return logConfiguration;
    }

    public void setLogConfiguration(Map<String, Object> logConfiguration) {
        this.logConfiguration = logConfiguration;
    }

    public Map<String, Object> getNetworkConfiguration() {
        return networkConfiguration;
    }

    public void setNetworkConfiguration(Map<String, Object> networkConfiguration) {
        this.networkConfiguration = networkConfiguration;
    }

    public Map<String, Object> getEphemeralStorage() {
        return ephemeralStorage;
    }

    public void setEphemeralStorage(Map<String, Object> ephemeralStorage) {
        this.ephemeralStorage = ephemeralStorage;
    }
}
