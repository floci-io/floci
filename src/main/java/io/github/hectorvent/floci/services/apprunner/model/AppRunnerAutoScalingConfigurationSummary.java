package io.github.hectorvent.floci.services.apprunner.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AppRunnerAutoScalingConfigurationSummary {

    @JsonProperty("AutoScalingConfigurationArn")
    private String autoScalingConfigurationArn;

    @JsonProperty("AutoScalingConfigurationName")
    private String autoScalingConfigurationName;

    @JsonProperty("AutoScalingConfigurationRevision")
    private Integer autoScalingConfigurationRevision;

    @JsonProperty("Status")
    private String status;

    @JsonProperty("CreatedAt")
    private Long createdAt;

    @JsonProperty("HasAssociatedService")
    private Boolean hasAssociatedService;

    @JsonProperty("IsDefault")
    private Boolean isDefault;

    public AppRunnerAutoScalingConfigurationSummary() {}

    public static AppRunnerAutoScalingConfigurationSummary of(AppRunnerAutoScalingConfiguration configuration) {
        AppRunnerAutoScalingConfigurationSummary summary = new AppRunnerAutoScalingConfigurationSummary();
        summary.setAutoScalingConfigurationArn(configuration.getAutoScalingConfigurationArn());
        summary.setAutoScalingConfigurationName(configuration.getAutoScalingConfigurationName());
        summary.setAutoScalingConfigurationRevision(configuration.getAutoScalingConfigurationRevision());
        summary.setStatus(configuration.getStatus());
        summary.setCreatedAt(configuration.getCreatedAt());
        summary.setHasAssociatedService(configuration.getHasAssociatedService());
        summary.setIsDefault(configuration.getIsDefault());
        return summary;
    }

    public String getAutoScalingConfigurationArn() { return autoScalingConfigurationArn; }
    public void setAutoScalingConfigurationArn(String autoScalingConfigurationArn) {
        this.autoScalingConfigurationArn = autoScalingConfigurationArn;
    }

    public String getAutoScalingConfigurationName() { return autoScalingConfigurationName; }
    public void setAutoScalingConfigurationName(String autoScalingConfigurationName) {
        this.autoScalingConfigurationName = autoScalingConfigurationName;
    }

    public Integer getAutoScalingConfigurationRevision() { return autoScalingConfigurationRevision; }
    public void setAutoScalingConfigurationRevision(Integer autoScalingConfigurationRevision) {
        this.autoScalingConfigurationRevision = autoScalingConfigurationRevision;
    }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }

    public Boolean getHasAssociatedService() { return hasAssociatedService; }
    public void setHasAssociatedService(Boolean hasAssociatedService) {
        this.hasAssociatedService = hasAssociatedService;
    }

    public Boolean getIsDefault() { return isDefault; }
    public void setIsDefault(Boolean isDefault) { this.isDefault = isDefault; }
}
