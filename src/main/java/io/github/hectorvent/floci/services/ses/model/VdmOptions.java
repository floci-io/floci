package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Per-configuration-set Virtual Deliverability Manager override. Mirrors the
 * AWS SES V2 {@code VdmOptions} shape: nested {@code DashboardOptions} and
 * {@code GuardianOptions}.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VdmOptions {

    @JsonProperty("DashboardOptions")
    private DashboardOptions dashboardOptions;

    @JsonProperty("GuardianOptions")
    private GuardianOptions guardianOptions;

    public VdmOptions() {}

    public DashboardOptions getDashboardOptions() { return dashboardOptions; }
    public void setDashboardOptions(DashboardOptions dashboardOptions) {
        this.dashboardOptions = dashboardOptions;
    }

    public GuardianOptions getGuardianOptions() { return guardianOptions; }
    public void setGuardianOptions(GuardianOptions guardianOptions) {
        this.guardianOptions = guardianOptions;
    }
}
