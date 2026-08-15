package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Account-level Virtual Deliverability Manager (VDM) settings, returned by GetAccount and set by
 * PutAccountVdmAttributes. Each flag maps to the AWS {@code FeatureStatus} enum (ENABLED / DISABLED).
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountVdmAttributes(boolean vdmEnabled,
                                   boolean engagementMetrics,
                                   boolean optimizedSharedDelivery) {
}
