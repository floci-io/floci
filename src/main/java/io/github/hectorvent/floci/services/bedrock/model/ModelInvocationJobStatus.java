package io.github.hectorvent.floci.services.bedrock.model;

import com.fasterxml.jackson.annotation.JsonValue;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum ModelInvocationJobStatus {
    SUBMITTED("Submitted"),
    VALIDATING("Validating"),
    SCHEDULED("Scheduled"),
    IN_PROGRESS("InProgress"),
    COMPLETED("Completed"),
    PARTIALLY_COMPLETED("PartiallyCompleted"),
    FAILED("Failed"),
    STOPPING("Stopping"),
    STOPPED("Stopped"),
    EXPIRED("Expired");

    private final String value;

    ModelInvocationJobStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public static ModelInvocationJobStatus fromValue(String text) {
        for (ModelInvocationJobStatus b : ModelInvocationJobStatus.values()) {
            if (b.value.equalsIgnoreCase(text) || b.name().equalsIgnoreCase(text)) {
                return b;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return value;
    }
}
