package io.github.hectorvent.floci.services.iam.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The account's outbound web identity federation state: whether the feature is on, and the issuer
 * URL minted for the account.
 *
 * <p>One of these exists per account once the feature has ever been enabled, and {@code enabled}
 * then toggles. The entry outliving a disable is deliberate: AWS describes the issuer as "a unique
 * issuer URL for your AWS account", and a relying party verifying tokens will have pinned it, so
 * re-enabling mints nothing new. AWS does not document which way that goes, so this is a choice
 * rather than an observed behaviour.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class OutboundWebIdentityFederation {

    private String issuerIdentifier;
    private boolean enabled;

    public OutboundWebIdentityFederation() {}

    public String getIssuerIdentifier() { return issuerIdentifier; }
    public void setIssuerIdentifier(String issuerIdentifier) {
        this.issuerIdentifier = issuerIdentifier;
    }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
