package io.github.hectorvent.floci.services.apigateway.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
@RegisterForReflection
public class ThrottleSettings {

    private Integer burstLimit = 5000;
    private Double rateLimit = 10000.0;

    public Integer getBurstLimit() {
        return burstLimit;
    }

    public void setBurstLimit(Integer burstLimit) {
        this.burstLimit = burstLimit;
    }

    public Double getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(Double rateLimit) {
        this.rateLimit = rateLimit;
    }
}
