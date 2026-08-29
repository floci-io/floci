package io.github.hectorvent.floci.services.msk.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum ConfigurationState {
    @JsonProperty("ACTIVE")
    ACTIVE,
    @JsonProperty("DELETING")
    DELETING,
    @JsonProperty("DELETE_FAILED")
    DELETE_FAILED
}
