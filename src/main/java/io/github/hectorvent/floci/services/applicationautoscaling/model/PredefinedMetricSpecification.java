package io.github.hectorvent.floci.services.applicationautoscaling.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A predefined metric for a target tracking scaling policy.
 *
 * <p>{@code resourceLabel} is required for load-balancer metrics such as
 * {@code ALBRequestCountPerTarget} and must round-trip exactly, otherwise Terraform
 * reports perpetual drift.</p>
 *
 * @see <a href="https://docs.aws.amazon.com/autoscaling/application/APIReference/API_PredefinedMetricSpecification.html">PredefinedMetricSpecification</a>
 */
@RegisterForReflection
public class PredefinedMetricSpecification {

    private String predefinedMetricType;
    private String resourceLabel;

    public String getPredefinedMetricType() { return predefinedMetricType; }
    public void setPredefinedMetricType(String v) { this.predefinedMetricType = v; }

    public String getResourceLabel() { return resourceLabel; }
    public void setResourceLabel(String v) { this.resourceLabel = v; }
}
