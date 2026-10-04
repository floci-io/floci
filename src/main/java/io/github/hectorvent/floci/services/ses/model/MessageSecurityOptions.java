package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Per-configuration-set message security settings. Mirrors the AWS SES V2
 * {@code MessageSecurityOptions} shape, whose only member is the {@code SigningScheme} union.
 * Floci stores and returns the setting but never signs a message.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessageSecurityOptions {

    @JsonProperty("SigningScheme")
    private SigningScheme signingScheme;

    public MessageSecurityOptions() {}

    public MessageSecurityOptions(SigningScheme signingScheme) {
        this.signingScheme = signingScheme;
    }

    public SigningScheme getSigningScheme() { return signingScheme; }
    public void setSigningScheme(SigningScheme signingScheme) { this.signingScheme = signingScheme; }
}
