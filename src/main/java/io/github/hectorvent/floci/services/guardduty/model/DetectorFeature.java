package io.github.hectorvent.floci.services.guardduty.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * A detector feature (e.g. S3_DATA_EVENTS). List positions are preserved as submitted:
 * {@code additionalConfiguration} is a list block in the Terraform provider, so a reordered
 * read-back makes every subsequent plan propose a replacement.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DetectorFeature {
    private String name;
    private String status;
    private Long updatedAt;
    private List<DetectorAdditionalConfiguration> additionalConfiguration;

    public DetectorFeature() {
    }

    public DetectorFeature(
            String name,
            String status,
            Long updatedAt,
            List<DetectorAdditionalConfiguration> additionalConfiguration) {
        this.name = name;
        this.status = status;
        this.updatedAt = updatedAt;
        setAdditionalConfiguration(additionalConfiguration);
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

    public List<DetectorAdditionalConfiguration> getAdditionalConfiguration() {
        return additionalConfiguration;
    }

    public void setAdditionalConfiguration(List<DetectorAdditionalConfiguration> additionalConfiguration) {
        this.additionalConfiguration =
                additionalConfiguration == null ? null : List.copyOf(additionalConfiguration);
    }
}
