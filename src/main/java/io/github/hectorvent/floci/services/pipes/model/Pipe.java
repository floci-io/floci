package io.github.hectorvent.floci.services.pipes.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Pipe {

    @JsonProperty("Name")
    private String name;

    @JsonProperty("Arn")
    private String arn;

    @JsonProperty("Source")
    private String source;

    @JsonProperty("Target")
    private String target;

    @JsonProperty("RoleArn")
    private String roleArn;

    @JsonProperty("Description")
    private String description;

    @JsonProperty("DesiredState")
    private DesiredState desiredState;

    @JsonProperty("CurrentState")
    private PipeState currentState;

    @JsonProperty("Enrichment")
    private String enrichment;

    @JsonProperty("SourceParameters")
    private JsonNode sourceParameters;

    @JsonProperty("TargetParameters")
    private JsonNode targetParameters;

    @JsonProperty("EnrichmentParameters")
    private JsonNode enrichmentParameters;

    @JsonProperty("Tags")
    private Map<String, String> tags;

    @JsonProperty("CreationTime")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant creationTime;

    @JsonProperty("LastModifiedTime")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant lastModifiedTime;

    @JsonProperty("StateReason")
    private String stateReason;

    public Pipe() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    public String getRoleArn() { return roleArn; }
    public void setRoleArn(String roleArn) { this.roleArn = roleArn; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public DesiredState getDesiredState() { return desiredState; }
    public void setDesiredState(DesiredState desiredState) { this.desiredState = desiredState; }

    public PipeState getCurrentState() { return currentState; }
    public void setCurrentState(PipeState currentState) { this.currentState = currentState; }

    public String getEnrichment() { return enrichment; }
    public void setEnrichment(String enrichment) { this.enrichment = enrichment; }

    public JsonNode getSourceParameters() { return sourceParameters; }
    public void setSourceParameters(JsonNode sourceParameters) { this.sourceParameters = sourceParameters; }

    public JsonNode getTargetParameters() { return targetParameters; }
    public void setTargetParameters(JsonNode targetParameters) { this.targetParameters = targetParameters; }

    public JsonNode getEnrichmentParameters() { return enrichmentParameters; }
    public void setEnrichmentParameters(JsonNode enrichmentParameters) { this.enrichmentParameters = enrichmentParameters; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public Instant getCreationTime() { return creationTime; }
    public void setCreationTime(Instant creationTime) { this.creationTime = creationTime; }

    public Instant getLastModifiedTime() { return lastModifiedTime; }
    public void setLastModifiedTime(Instant lastModifiedTime) { this.lastModifiedTime = lastModifiedTime; }

    public String getStateReason() { return stateReason; }
    public void setStateReason(String stateReason) { this.stateReason = stateReason; }
}
