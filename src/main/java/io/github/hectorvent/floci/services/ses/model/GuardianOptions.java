package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * VDM Guardian settings for a configuration set. Mirrors the AWS SES V2
 * {@code GuardianOptions} shape: whether optimized shared delivery is applied.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GuardianOptions {

    @JsonProperty("OptimizedSharedDelivery")
    private String optimizedSharedDelivery;

    public GuardianOptions() {}

    public String getOptimizedSharedDelivery() { return optimizedSharedDelivery; }
    public void setOptimizedSharedDelivery(String optimizedSharedDelivery) {
        this.optimizedSharedDelivery = optimizedSharedDelivery;
    }
}
