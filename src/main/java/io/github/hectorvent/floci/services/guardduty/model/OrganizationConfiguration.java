package io.github.hectorvent.floci.services.guardduty.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/** Organization-wide GuardDuty configuration attached to a detector. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OrganizationConfiguration {
    private Boolean autoEnable;
    private String autoEnableOrganizationMembers;
    private List<OrganizationFeature> features;

    public OrganizationConfiguration() {
    }

    public OrganizationConfiguration(
            Boolean autoEnable, String autoEnableOrganizationMembers, List<OrganizationFeature> features) {
        this.autoEnable = autoEnable;
        this.autoEnableOrganizationMembers = autoEnableOrganizationMembers;
        setFeatures(features);
    }

    public Boolean getAutoEnable() {
        return autoEnable;
    }

    public void setAutoEnable(Boolean autoEnable) {
        this.autoEnable = autoEnable;
    }

    public String getAutoEnableOrganizationMembers() {
        return autoEnableOrganizationMembers;
    }

    public void setAutoEnableOrganizationMembers(String autoEnableOrganizationMembers) {
        this.autoEnableOrganizationMembers = autoEnableOrganizationMembers;
    }

    public List<OrganizationFeature> getFeatures() {
        return features;
    }

    public void setFeatures(List<OrganizationFeature> features) {
        this.features = features == null ? null : List.copyOf(features);
    }
}
