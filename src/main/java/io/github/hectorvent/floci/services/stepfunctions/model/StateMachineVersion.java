package io.github.hectorvent.floci.services.stepfunctions.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** A published version of a state machine ({@code <stateMachineArn>:<version>}). */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class StateMachineVersion {
    private String stateMachineVersionArn;
    private int version;
    private double creationDate;
    private String definition;
    private String roleArn;
    private String type;
    private String revisionId;
    private String description;
    private JsonNode loggingConfiguration;
    private JsonNode tracingConfiguration;
    private JsonNode encryptionConfiguration;

    public StateMachineVersion() {
    }

    public StateMachineVersion(String stateMachineVersionArn, int version, double creationDate) {
        this.stateMachineVersionArn = stateMachineVersionArn;
        this.version = version;
        this.creationDate = creationDate;
    }

    public String getStateMachineVersionArn() { return stateMachineVersionArn; }
    public void setStateMachineVersionArn(String stateMachineVersionArn) { this.stateMachineVersionArn = stateMachineVersionArn; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public double getCreationDate() { return creationDate; }
    public void setCreationDate(double creationDate) { this.creationDate = creationDate; }

    public String getDefinition() { return definition; }
    public void setDefinition(String definition) { this.definition = definition; }

    public String getRoleArn() { return roleArn; }
    public void setRoleArn(String roleArn) { this.roleArn = roleArn; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getRevisionId() { return revisionId; }
    public void setRevisionId(String revisionId) { this.revisionId = revisionId; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public JsonNode getLoggingConfiguration() { return loggingConfiguration; }
    public void setLoggingConfiguration(JsonNode loggingConfiguration) { this.loggingConfiguration = loggingConfiguration; }

    public JsonNode getTracingConfiguration() { return tracingConfiguration; }
    public void setTracingConfiguration(JsonNode tracingConfiguration) { this.tracingConfiguration = tracingConfiguration; }

    public JsonNode getEncryptionConfiguration() { return encryptionConfiguration; }
    public void setEncryptionConfiguration(JsonNode encryptionConfiguration) { this.encryptionConfiguration = encryptionConfiguration; }
}
