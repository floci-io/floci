package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * VDM dashboard settings for a configuration set. Mirrors the AWS SES V2
 * {@code DashboardOptions} shape: whether engagement metrics are collected.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DashboardOptions {

    @JsonProperty("EngagementMetrics")
    private String engagementMetrics;

    public DashboardOptions() {}

    public String getEngagementMetrics() { return engagementMetrics; }
    public void setEngagementMetrics(String engagementMetrics) {
        this.engagementMetrics = engagementMetrics;
    }
}
