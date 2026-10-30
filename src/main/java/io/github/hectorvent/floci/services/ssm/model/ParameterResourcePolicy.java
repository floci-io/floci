package io.github.hectorvent.floci.services.ssm.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The resource-based policy PutResourcePolicy attaches to a parameter. A later update or delete
 * must present the current {@code policyHash}.
 */
@RegisterForReflection
public record ParameterResourcePolicy(@JsonProperty("PolicyId") String policyId,
                                      @JsonProperty("PolicyHash") String policyHash,
                                      @JsonProperty("Policy") String policy) {
}
