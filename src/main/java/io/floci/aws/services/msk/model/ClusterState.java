package io.floci.aws.services.msk.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum ClusterState {
    @JsonProperty("ACTIVE")
    ACTIVE,
    @JsonProperty("CREATING")
    CREATING,
    @JsonProperty("DELETING")
    DELETING,
    @JsonProperty("FAILED")
    FAILED,
    @JsonProperty("HEALING")
    HEALING,
    @JsonProperty("MAINTENANCE")
    MAINTENANCE,
    @JsonProperty("REBOOTING_BROKER")
    REBOOTING_BROKER,
    @JsonProperty("UPDATING")
    UPDATING
}
