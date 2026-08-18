package io.github.hectorvent.floci.services.apprunner.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * An App Runner service. The nested configuration blocks are held as {@link JsonNode} so the
 * caller's source, instance, health check, network and observability configuration round-trip
 * exactly as sent, including the runtime environment maps App Runner never interprets.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AppRunnerServiceModel {

    @JsonProperty("ServiceName")
    private String serviceName;

    @JsonProperty("ServiceId")
    private String serviceId;

    @JsonProperty("ServiceArn")
    private String serviceArn;

    @JsonProperty("ServiceUrl")
    private String serviceUrl;

    @JsonProperty("CreatedAt")
    private Long createdAt;

    @JsonProperty("UpdatedAt")
    private Long updatedAt;

    @JsonProperty("DeletedAt")
    private Long deletedAt;

    @JsonProperty("Status")
    private String status;

    @JsonProperty("SourceConfiguration")
    private JsonNode sourceConfiguration;

    @JsonProperty("InstanceConfiguration")
    private JsonNode instanceConfiguration;

    @JsonProperty("EncryptionConfiguration")
    private JsonNode encryptionConfiguration;

    @JsonProperty("HealthCheckConfiguration")
    private JsonNode healthCheckConfiguration;

    @JsonProperty("AutoScalingConfigurationSummary")
    private AppRunnerAutoScalingConfigurationSummary autoScalingConfigurationSummary;

    @JsonProperty("NetworkConfiguration")
    private JsonNode networkConfiguration;

    @JsonProperty("ObservabilityConfiguration")
    private JsonNode observabilityConfiguration;

    public AppRunnerServiceModel() {}

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    public String getServiceId() { return serviceId; }
    public void setServiceId(String serviceId) { this.serviceId = serviceId; }

    public String getServiceArn() { return serviceArn; }
    public void setServiceArn(String serviceArn) { this.serviceArn = serviceArn; }

    public String getServiceUrl() { return serviceUrl; }
    public void setServiceUrl(String serviceUrl) { this.serviceUrl = serviceUrl; }

    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }

    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }

    public Long getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Long deletedAt) { this.deletedAt = deletedAt; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public JsonNode getSourceConfiguration() { return sourceConfiguration; }
    public void setSourceConfiguration(JsonNode sourceConfiguration) { this.sourceConfiguration = sourceConfiguration; }

    public JsonNode getInstanceConfiguration() { return instanceConfiguration; }
    public void setInstanceConfiguration(JsonNode instanceConfiguration) {
        this.instanceConfiguration = instanceConfiguration;
    }

    public JsonNode getEncryptionConfiguration() { return encryptionConfiguration; }
    public void setEncryptionConfiguration(JsonNode encryptionConfiguration) {
        this.encryptionConfiguration = encryptionConfiguration;
    }

    public JsonNode getHealthCheckConfiguration() { return healthCheckConfiguration; }
    public void setHealthCheckConfiguration(JsonNode healthCheckConfiguration) {
        this.healthCheckConfiguration = healthCheckConfiguration;
    }

    public AppRunnerAutoScalingConfigurationSummary getAutoScalingConfigurationSummary() {
        return autoScalingConfigurationSummary;
    }
    public void setAutoScalingConfigurationSummary(AppRunnerAutoScalingConfigurationSummary summary) {
        this.autoScalingConfigurationSummary = summary;
    }

    public JsonNode getNetworkConfiguration() { return networkConfiguration; }
    public void setNetworkConfiguration(JsonNode networkConfiguration) {
        this.networkConfiguration = networkConfiguration;
    }

    public JsonNode getObservabilityConfiguration() { return observabilityConfiguration; }
    public void setObservabilityConfiguration(JsonNode observabilityConfiguration) {
        this.observabilityConfiguration = observabilityConfiguration;
    }
}
