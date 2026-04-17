package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record AwsErrorResponse(@JsonProperty("__type") String type, @JsonProperty("message") String message) {
}