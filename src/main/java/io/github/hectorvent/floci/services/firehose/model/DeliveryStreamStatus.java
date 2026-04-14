package io.github.hectorvent.floci.services.firehose.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum DeliveryStreamStatus {
    @JsonProperty("CREATING")
    CREATING,
    @JsonProperty("DELETING")
    DELETING,
    @JsonProperty("ACTIVE")
    ACTIVE
}
