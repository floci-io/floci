package io.github.hectorvent.floci.services.amazonmq.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum BrokerState {
    @JsonProperty("CREATION_IN_PROGRESS")
    CREATION_IN_PROGRESS,
    @JsonProperty("CREATION_FAILED")
    CREATION_FAILED,
    @JsonProperty("DELETION_IN_PROGRESS")
    DELETION_IN_PROGRESS,
    @JsonProperty("RUNNING")
    RUNNING,
    @JsonProperty("REBOOT_IN_PROGRESS")
    REBOOT_IN_PROGRESS,
    @JsonProperty("CRITICAL_ACTION_REQUIRED")
    CRITICAL_ACTION_REQUIRED,
    @JsonProperty("REPLICA")
    REPLICA
}
