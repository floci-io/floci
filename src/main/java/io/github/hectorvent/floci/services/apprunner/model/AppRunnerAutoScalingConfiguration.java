package io.github.hectorvent.floci.services.apprunner.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AppRunnerAutoScalingConfiguration {

    @JsonProperty("AutoScalingConfigurationArn")
    private String autoScalingConfigurationArn;

    @JsonProperty("AutoScalingConfigurationName")
    private String autoScalingConfigurationName;

    @JsonProperty("AutoScalingConfigurationRevision")
    private Integer autoScalingConfigurationRevision;

    @JsonProperty("Latest")
    private Boolean latest;

    @JsonProperty("Status")
    private String status;

    @JsonProperty("MaxConcurrency")
    private Integer maxConcurrency;

    @JsonProperty("MinSize")
    private Integer minSize;

    @JsonProperty("MaxSize")
    private Integer maxSize;

    @JsonProperty("CreatedAt")
    private Long createdAt;

    @JsonProperty("DeletedAt")
    private Long deletedAt;

    @JsonProperty("HasAssociatedService")
    private Boolean hasAssociatedService;

    @JsonProperty("IsDefault")
    private Boolean isDefault;

    public AppRunnerAutoScalingConfiguration() {}

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

    public Boolean getLatest() { return latest; }
    public void setLatest(Boolean latest) { this.latest = latest; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getMaxConcurrency() { return maxConcurrency; }
    public void setMaxConcurrency(Integer maxConcurrency) { this.maxConcurrency = maxConcurrency; }

    public Integer getMinSize() { return minSize; }
    public void setMinSize(Integer minSize) { this.minSize = minSize; }

    public Integer getMaxSize() { return maxSize; }
    public void setMaxSize(Integer maxSize) { this.maxSize = maxSize; }

    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }

    public Long getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Long deletedAt) { this.deletedAt = deletedAt; }

    public Boolean getHasAssociatedService() { return hasAssociatedService; }
    public void setHasAssociatedService(Boolean hasAssociatedService) {
        this.hasAssociatedService = hasAssociatedService;
    }

    public Boolean getIsDefault() { return isDefault; }
    public void setIsDefault(Boolean isDefault) { this.isDefault = isDefault; }
}
