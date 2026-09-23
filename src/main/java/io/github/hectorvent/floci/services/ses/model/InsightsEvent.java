package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * One event in a {@code GetMessageInsights} recipient timeline. {@code type} is an SES
 * {@code EventType} name such as {@code SEND} or {@code DELIVERY}.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InsightsEvent(
        @JsonProperty("Timestamp") Instant timestamp,
        @JsonProperty("Type") String type,
        @JsonProperty("Details") InsightsEventDetails details) {

    public static InsightsEvent of(Instant timestamp, String type) {
        return new InsightsEvent(timestamp, type, null);
    }
}
