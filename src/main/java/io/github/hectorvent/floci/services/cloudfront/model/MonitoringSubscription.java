package io.github.hectorvent.floci.services.cloudfront.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class MonitoringSubscription {

    private String distributionId;
    private String realtimeMetricsSubscriptionStatus;

    public MonitoringSubscription() {}

    public String getDistributionId() { return distributionId; }
    public void setDistributionId(String distributionId) { this.distributionId = distributionId; }

    public String getRealtimeMetricsSubscriptionStatus() { return realtimeMetricsSubscriptionStatus; }
    public void setRealtimeMetricsSubscriptionStatus(String realtimeMetricsSubscriptionStatus) {
        this.realtimeMetricsSubscriptionStatus = realtimeMetricsSubscriptionStatus;
    }
}
