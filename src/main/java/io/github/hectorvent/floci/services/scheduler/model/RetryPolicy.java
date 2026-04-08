package io.github.hectorvent.floci.services.scheduler.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class RetryPolicy {

    private Integer maximumEventAgeInSeconds;
    private Integer maximumRetryAttempts;

    public RetryPolicy() {}

    public RetryPolicy(Integer maximumEventAgeInSeconds, Integer maximumRetryAttempts) {
        this.maximumEventAgeInSeconds = maximumEventAgeInSeconds;
        this.maximumRetryAttempts = maximumRetryAttempts;
    }

    public Integer getMaximumEventAgeInSeconds() { return maximumEventAgeInSeconds; }
    public void setMaximumEventAgeInSeconds(Integer maximumEventAgeInSeconds) { this.maximumEventAgeInSeconds = maximumEventAgeInSeconds; }

    public Integer getMaximumRetryAttempts() { return maximumRetryAttempts; }
    public void setMaximumRetryAttempts(Integer maximumRetryAttempts) { this.maximumRetryAttempts = maximumRetryAttempts; }
}
