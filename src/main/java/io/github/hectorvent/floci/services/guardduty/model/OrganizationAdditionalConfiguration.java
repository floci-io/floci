package io.github.hectorvent.floci.services.guardduty.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** Organization auto-enablement for a sub-feature (e.g. EKS_ADDON_MANAGEMENT). */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OrganizationAdditionalConfiguration {
    private String name;
    private String autoEnable;

    public OrganizationAdditionalConfiguration() {
    }

    public OrganizationAdditionalConfiguration(String name, String autoEnable) {
        this.name = name;
        this.autoEnable = autoEnable;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAutoEnable() {
        return autoEnable;
    }

    public void setAutoEnable(String autoEnable) {
        this.autoEnable = autoEnable;
    }
}
