package io.floci.aws.services.ses.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Locale;
import java.util.Set;

/**
 * Represents one entry of the SES V2 {@code Content.Simple.Headers} /
 * {@code Content.Template.Headers} list — a user-supplied additional header to attach to
 * an outgoing message.
 */
@RegisterForReflection
public record MessageHeader(
        @JsonProperty("Name") String name,
        @JsonProperty("Value") String value) {

    // SES sets these itself and refuses them as an additional header of a v2 SendEmail, whatever
    // the case of the name. Sender, DKIM-Signature, Received and the List-Unsubscribe pair are
    // accepted (probed on Content.Simple.Headers).
    private static final Set<String> UNSUPPORTED_NAMES = Set.of(
            "from", "to", "cc", "bcc", "subject", "date", "message-id", "reply-to", "return-path",
            "mime-version", "content-type");

    /** Whether SES accepts {@code name} as the name of an additional header of a simple message. */
    public static boolean isSupportedName(String name) {
        return name == null || !UNSUPPORTED_NAMES.contains(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Whether this header is safe to attach to an outgoing message: its name must be non-blank and
     * neither name nor value may contain CR/LF, otherwise it could inject additional headers. Used to
     * filter user-supplied headers consistently across storage, relay, and event publishing.
     */
    public boolean isSafe() {
        return name != null && !name.isBlank() && noCrlf(name) && value != null && noCrlf(value);
    }

    /** True when {@code s} has no CR or LF, so it cannot start another MIME header line. */
    public static boolean noCrlf(String s) {
        return s.indexOf('\r') < 0 && s.indexOf('\n') < 0;
    }
}
