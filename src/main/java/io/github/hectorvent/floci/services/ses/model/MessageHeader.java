package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Represents one entry of the SES V2 {@code Content.Simple.Headers} /
 * {@code Content.Template.Headers} list — a user-supplied additional header to attach to
 * an outgoing message.
 */
@RegisterForReflection
public record MessageHeader(
        @JsonProperty("Name") String name,
        @JsonProperty("Value") String value) {
}
