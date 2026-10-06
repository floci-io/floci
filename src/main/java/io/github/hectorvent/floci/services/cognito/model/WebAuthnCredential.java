package io.github.hectorvent.floci.services.cognito.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/**
 * A passkey registered with {@code CompleteWebAuthnRegistration}. {@code attestedCredentialData}
 * holds the authenticator's credential id and public key in their WebAuthn binary form, base64url
 * encoded, which is what an assertion is verified against.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class WebAuthnCredential {

    private String credentialId;
    private String attestedCredentialData;
    private long signCount;
    private boolean userVerified;
    private Boolean backupEligible;
    private Boolean backupState;
    private List<String> transports = new ArrayList<>();
    private String authenticatorAttachment;
    private String friendlyCredentialName;
    private String relyingPartyId;
    private long createdAtMillis;

    public String getCredentialId() { return credentialId; }
    public void setCredentialId(String credentialId) { this.credentialId = credentialId; }

    public String getAttestedCredentialData() { return attestedCredentialData; }
    public void setAttestedCredentialData(String attestedCredentialData) {
        this.attestedCredentialData = attestedCredentialData;
    }

    public long getSignCount() { return signCount; }
    public void setSignCount(long signCount) { this.signCount = signCount; }

    public boolean isUserVerified() { return userVerified; }
    public void setUserVerified(boolean userVerified) { this.userVerified = userVerified; }

    public Boolean getBackupEligible() { return backupEligible; }
    public void setBackupEligible(Boolean backupEligible) { this.backupEligible = backupEligible; }

    public Boolean getBackupState() { return backupState; }
    public void setBackupState(Boolean backupState) { this.backupState = backupState; }

    public List<String> getTransports() { return transports; }
    public void setTransports(List<String> transports) {
        this.transports = transports == null ? new ArrayList<>() : new ArrayList<>(transports);
    }

    public String getAuthenticatorAttachment() { return authenticatorAttachment; }
    public void setAuthenticatorAttachment(String authenticatorAttachment) {
        this.authenticatorAttachment = authenticatorAttachment;
    }

    public String getFriendlyCredentialName() { return friendlyCredentialName; }
    public void setFriendlyCredentialName(String friendlyCredentialName) {
        this.friendlyCredentialName = friendlyCredentialName;
    }

    public String getRelyingPartyId() { return relyingPartyId; }
    public void setRelyingPartyId(String relyingPartyId) { this.relyingPartyId = relyingPartyId; }

    public long getCreatedAtMillis() { return createdAtMillis; }
    public void setCreatedAtMillis(long createdAtMillis) { this.createdAtMillis = createdAtMillis; }
}
