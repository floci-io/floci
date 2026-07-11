package io.github.hectorvent.floci.services.transfer.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
public class SshPublicKey {

    private String sshPublicKeyId;
    private String sshPublicKeyBody;
    private Instant dateImported;

    public SshPublicKey() {}

    public SshPublicKey(String sshPublicKeyId, String sshPublicKeyBody, Instant dateImported) {
        this.sshPublicKeyId = sshPublicKeyId;
        this.sshPublicKeyBody = sshPublicKeyBody;
        this.dateImported = dateImported;
    }

    public String getSshPublicKeyId() { return sshPublicKeyId; }
    public void setSshPublicKeyId(String sshPublicKeyId) { this.sshPublicKeyId = sshPublicKeyId; }

    public String getSshPublicKeyBody() { return sshPublicKeyBody; }
    public void setSshPublicKeyBody(String sshPublicKeyBody) { this.sshPublicKeyBody = sshPublicKeyBody; }

    public Instant getDateImported() { return dateImported; }
    public void setDateImported(Instant dateImported) { this.dateImported = dateImported; }
}
