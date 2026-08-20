package io.github.hectorvent.floci.services.guardduty.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * Organization auto-enablement for a detector feature. {@code additionalConfiguration}
 * order is preserved as submitted; see {@link DetectorFeature}.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OrganizationFeature {
    private String name;
    private String autoEnable;
    private List<OrganizationAdditionalConfiguration> additionalConfiguration;

    public OrganizationFeature() {
    }

    public OrganizationFeature(
            String name,
            String autoEnable,
            List<OrganizationAdditionalConfiguration> additionalConfiguration) {
        this.name = name;
        this.autoEnable = autoEnable;
        setAdditionalConfiguration(additionalConfiguration);
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

    public List<OrganizationAdditionalConfiguration> getAdditionalConfiguration() {
        return additionalConfiguration;
    }

    public void setAdditionalConfiguration(List<OrganizationAdditionalConfiguration> additionalConfiguration) {
        this.additionalConfiguration =
                additionalConfiguration == null ? null : List.copyOf(additionalConfiguration);
    }
}
