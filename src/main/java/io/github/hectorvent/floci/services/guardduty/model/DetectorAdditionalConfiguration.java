package io.github.hectorvent.floci.services.guardduty.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** Sub-feature configuration of a detector feature (e.g. EKS_ADDON_MANAGEMENT). */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DetectorAdditionalConfiguration {
    private String name;
    private String status;
    private Long updatedAt;

    public DetectorAdditionalConfiguration() {
    }

    public DetectorAdditionalConfiguration(String name, String status, Long updatedAt) {
        this.name = name;
        this.status = status;
        this.updatedAt = updatedAt;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
