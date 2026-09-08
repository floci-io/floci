package io.github.hectorvent.floci.services.controltower.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

@RegisterForReflection
public class EnabledControl {
    private String arn;
    private String controlIdentifier;
    private String targetIdentifier;
    private String status;
    private String driftStatus;
    private String lastOperationIdentifier;
    private JsonNode parameters;
    private Map<String, String> tags;

    public EnabledControl() {
    }

    public EnabledControl(String arn, String controlIdentifier, String targetIdentifier,
                          String status, String driftStatus, String lastOperationIdentifier,
                          JsonNode parameters, Map<String, String> tags) {
        this.arn = arn;
        this.controlIdentifier = controlIdentifier;
        this.targetIdentifier = targetIdentifier;
        this.status = status;
        this.driftStatus = driftStatus;
        this.lastOperationIdentifier = lastOperationIdentifier;
        this.parameters = parameters;
        this.tags = tags;
    }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }
    public String getControlIdentifier() { return controlIdentifier; }
    public void setControlIdentifier(String controlIdentifier) { this.controlIdentifier = controlIdentifier; }
    public String getTargetIdentifier() { return targetIdentifier; }
    public void setTargetIdentifier(String targetIdentifier) { this.targetIdentifier = targetIdentifier; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getDriftStatus() { return driftStatus; }
    public void setDriftStatus(String driftStatus) { this.driftStatus = driftStatus; }
    public String getLastOperationIdentifier() { return lastOperationIdentifier; }
    public void setLastOperationIdentifier(String lastOperationIdentifier) { this.lastOperationIdentifier = lastOperationIdentifier; }
    public JsonNode getParameters() { return parameters; }
    public void setParameters(JsonNode parameters) { this.parameters = parameters; }
    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
