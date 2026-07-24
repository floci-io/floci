package io.github.hectorvent.floci.services.stepfunctions.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class StateMachine {
    private String stateMachineArn;
    private String name;
    private String definition;
    private String roleArn;
    private String type = "STANDARD";
    private String status = "ACTIVE";
    private double creationDate;
    private Double updateDate;
    private String revisionId;
    private JsonNode loggingConfiguration;
    private JsonNode tracingConfiguration;
    private JsonNode encryptionConfiguration;
    private String description;
    private Map<String, String> tags = new HashMap<>();
    private int versionCounter = 0;
    private List<StateMachineVersion> versions = new ArrayList<>();

    public StateMachine() {
        this.creationDate = System.currentTimeMillis() / 1000.0;
    }

    public String getStateMachineArn() { return stateMachineArn; }
    public void setStateMachineArn(String stateMachineArn) { this.stateMachineArn = stateMachineArn; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDefinition() { return definition; }
    public void setDefinition(String definition) { this.definition = definition; }

    public String getRoleArn() { return roleArn; }
    public void setRoleArn(String roleArn) { this.roleArn = roleArn; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public double getCreationDate() { return creationDate; }
    public void setCreationDate(double creationDate) { this.creationDate = creationDate; }

    public Double getUpdateDate() { return updateDate; }
    public void setUpdateDate(Double updateDate) { this.updateDate = updateDate; }

    public String getRevisionId() { return revisionId; }
    public void setRevisionId(String revisionId) { this.revisionId = revisionId; }

    public JsonNode getLoggingConfiguration() { return loggingConfiguration; }
    public void setLoggingConfiguration(JsonNode loggingConfiguration) { this.loggingConfiguration = loggingConfiguration; }

    public JsonNode getTracingConfiguration() { return tracingConfiguration; }
    public void setTracingConfiguration(JsonNode tracingConfiguration) { this.tracingConfiguration = tracingConfiguration; }

    public JsonNode getEncryptionConfiguration() { return encryptionConfiguration; }
    public void setEncryptionConfiguration(JsonNode encryptionConfiguration) { this.encryptionConfiguration = encryptionConfiguration; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public int getVersionCounter() { return versionCounter; }
    public void setVersionCounter(int versionCounter) { this.versionCounter = versionCounter; }

    public List<StateMachineVersion> getVersions() { return versions; }
    public void setVersions(List<StateMachineVersion> versions) { this.versions = versions; }
}
