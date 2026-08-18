package io.github.hectorvent.floci.services.apprunner.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AppRunnerObservabilityConfiguration {

    @JsonProperty("ObservabilityConfigurationArn")
    private String observabilityConfigurationArn;

    @JsonProperty("ObservabilityConfigurationName")
    private String observabilityConfigurationName;

    @JsonProperty("ObservabilityConfigurationRevision")
    private Integer observabilityConfigurationRevision;

    @JsonProperty("Latest")
    private Boolean latest;

    @JsonProperty("Status")
    private String status;

    @JsonProperty("TraceConfiguration")
    private JsonNode traceConfiguration;

    @JsonProperty("CreatedAt")
    private Long createdAt;

    @JsonProperty("DeletedAt")
    private Long deletedAt;

    public AppRunnerObservabilityConfiguration() {}

    public String getObservabilityConfigurationArn() { return observabilityConfigurationArn; }
    public void setObservabilityConfigurationArn(String observabilityConfigurationArn) {
        this.observabilityConfigurationArn = observabilityConfigurationArn;
    }

    public String getObservabilityConfigurationName() { return observabilityConfigurationName; }
    public void setObservabilityConfigurationName(String observabilityConfigurationName) {
        this.observabilityConfigurationName = observabilityConfigurationName;
    }

    public Integer getObservabilityConfigurationRevision() { return observabilityConfigurationRevision; }
    public void setObservabilityConfigurationRevision(Integer observabilityConfigurationRevision) {
        this.observabilityConfigurationRevision = observabilityConfigurationRevision;
    }

    public Boolean getLatest() { return latest; }
    public void setLatest(Boolean latest) { this.latest = latest; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public JsonNode getTraceConfiguration() { return traceConfiguration; }
    public void setTraceConfiguration(JsonNode traceConfiguration) { this.traceConfiguration = traceConfiguration; }

    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }

    public Long getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Long deletedAt) { this.deletedAt = deletedAt; }
}
