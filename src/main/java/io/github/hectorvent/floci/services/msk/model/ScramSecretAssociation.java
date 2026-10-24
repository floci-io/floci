package io.github.hectorvent.floci.services.msk.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class ScramSecretAssociation {

    private String username;
    private String appliedVersionId;
    private String appliedContainerId;
    private boolean credentialsRevoked;
    private boolean disassociating;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getAppliedVersionId() {
        return appliedVersionId;
    }

    public void setAppliedVersionId(String appliedVersionId) {
        this.appliedVersionId = appliedVersionId;
    }

    public String getAppliedContainerId() {
        return appliedContainerId;
    }

    public void setAppliedContainerId(String appliedContainerId) {
        this.appliedContainerId = appliedContainerId;
    }

    public boolean isCredentialsRevoked() {
        return credentialsRevoked;
    }

    public void setCredentialsRevoked(boolean credentialsRevoked) {
        this.credentialsRevoked = credentialsRevoked;
    }

    public boolean isDisassociating() {
        return disassociating;
    }

    public void setDisassociating(boolean disassociating) {
        this.disassociating = disassociating;
    }
}
