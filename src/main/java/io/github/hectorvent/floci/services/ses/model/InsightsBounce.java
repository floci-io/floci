package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The {@code Bounce} member of a {@code GetMessageInsights} event's {@code Details}.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InsightsBounce(
        @JsonProperty("BounceType") String bounceType,
        @JsonProperty("BounceSubType") String bounceSubType,
        @JsonProperty("DiagnosticCode") String diagnosticCode) {
}
