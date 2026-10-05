package io.github.hectorvent.floci.services.iam.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * An SSH public key owned by one IAM user, which AWS accepts for authenticating that user to a
 * CodeCommit repository.
 *
 * <p>The body is kept as it was uploaded, in whichever of the two accepted encodings that was, and
 * {@code GetSSHPublicKey} converts it to the encoding the request asks for. Keeping the original
 * means a caller that uploaded PEM gets back byte-identical PEM rather than a re-encoding of it.
 *
 * <p>{@code fingerprint} is the MD5 of the OpenSSH blob, which is what AWS reports here. It is not
 * the digest EC2 reports for the same key, which is taken over the DER.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SshPublicKey {
    private String userName;
    private String sshPublicKeyId;
    private String fingerprint;
    /** As uploaded: either an {@code ssh-rsa} line or a PEM SubjectPublicKeyInfo. */
    private String sshPublicKeyBody;
    private String status = "Active";
    private Instant uploadDate = Instant.now();

    public SshPublicKey() {}

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }
    public String getSshPublicKeyId() { return sshPublicKeyId; }
    public void setSshPublicKeyId(String sshPublicKeyId) { this.sshPublicKeyId = sshPublicKeyId; }
    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }
    public String getSshPublicKeyBody() { return sshPublicKeyBody; }
    public void setSshPublicKeyBody(String sshPublicKeyBody) {
        this.sshPublicKeyBody = sshPublicKeyBody;
    }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getUploadDate() { return uploadDate; }
    public void setUploadDate(Instant uploadDate) { this.uploadDate = uploadDate; }
}
