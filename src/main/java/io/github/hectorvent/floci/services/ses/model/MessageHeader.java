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

    /**
     * Whether this header is safe to attach to an outgoing message: its name must be non-blank and
     * neither name nor value may contain CR/LF, otherwise it could inject additional headers. Used to
     * filter user-supplied headers consistently across storage, relay, and event publishing.
     */
    public boolean isSafe() {
        return name != null && !name.isBlank() && noCrlf(name) && value != null && noCrlf(value);
    }

    private static boolean noCrlf(String s) {
        return s.indexOf('\r') < 0 && s.indexOf('\n') < 0;
    }
}
