package io.github.hectorvent.floci.services.guardduty.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

/** A GuardDuty detector. Serialized with GuardDuty's lowerCamelCase member names. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Detector {
    private String id;
    private String status;
    private String findingPublishingFrequency;
    private String serviceRole;
    private String createdAt;
    private String updatedAt;
    private Map<String, String> tags;
    private List<DetectorFeature> features;
    private OrganizationConfiguration organizationConfiguration;

    public Detector() {
    }

    public Detector(
            String id,
            String status,
            String findingPublishingFrequency,
            String serviceRole,
            String createdAt,
            String updatedAt,
            Map<String, String> tags,
            List<DetectorFeature> features,
            OrganizationConfiguration organizationConfiguration) {
        this.id = id;
        this.status = status;
        this.findingPublishingFrequency = findingPublishingFrequency;
        this.serviceRole = serviceRole;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        setTags(tags);
        setFeatures(features);
        this.organizationConfiguration = organizationConfiguration;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getFindingPublishingFrequency() {
        return findingPublishingFrequency;
    }

    public void setFindingPublishingFrequency(String findingPublishingFrequency) {
        this.findingPublishingFrequency = findingPublishingFrequency;
    }

    public String getServiceRole() {
        return serviceRole;
    }

    public void setServiceRole(String serviceRole) {
        this.serviceRole = serviceRole;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? null : Map.copyOf(tags);
    }

    public List<DetectorFeature> getFeatures() {
        return features;
    }

    public void setFeatures(List<DetectorFeature> features) {
        this.features = features == null ? null : List.copyOf(features);
    }

    public OrganizationConfiguration getOrganizationConfiguration() {
        return organizationConfiguration;
    }

    public void setOrganizationConfiguration(OrganizationConfiguration organizationConfiguration) {
        this.organizationConfiguration = organizationConfiguration;
    }
}
