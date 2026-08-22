package io.github.hectorvent.floci.services.efs.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum ThroughputMode {
    @JsonProperty("bursting")
    BURSTING,
    
    @JsonProperty("provisioned")
    PROVISIONED,
    
    @JsonProperty("elastic")
    ELASTIC
}