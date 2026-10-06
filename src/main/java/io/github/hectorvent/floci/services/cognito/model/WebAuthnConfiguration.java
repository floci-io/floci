package io.github.hectorvent.floci.services.cognito.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** A user pool's passkey settings, from {@code SetUserPoolMfaConfig}'s {@code WebAuthnConfiguration}. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class WebAuthnConfiguration {

    private String relyingPartyId;
    private String userVerification;
    private String factorConfiguration;

    public String getRelyingPartyId() { return relyingPartyId; }
    public void setRelyingPartyId(String relyingPartyId) { this.relyingPartyId = relyingPartyId; }

    public String getUserVerification() { return userVerification; }
    public void setUserVerification(String userVerification) { this.userVerification = userVerification; }

    public String getFactorConfiguration() { return factorConfiguration; }
    public void setFactorConfiguration(String factorConfiguration) { this.factorConfiguration = factorConfiguration; }
}
