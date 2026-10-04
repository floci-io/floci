package io.github.hectorvent.floci.services.iam.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * An IAM X.509 signing certificate, owned by one IAM user. Unlike a server certificate it has no
 * name, no path and no private key: AWS stores only the certificate body, and the generated
 * {@code certificateId} is what every later operation addresses it by.
 *
 * <p>{@code status} is {@code Active} on upload, which is what the model documents.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SigningCertificate {
    private String userName;
    private String certificateId;
    private String certificateBody;
    private String status = "Active";
    private Instant uploadDate = Instant.now();

    public SigningCertificate() {}

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }
    public String getCertificateId() { return certificateId; }
    public void setCertificateId(String certificateId) { this.certificateId = certificateId; }
    public String getCertificateBody() { return certificateBody; }
    public void setCertificateBody(String certificateBody) {
        this.certificateBody = certificateBody;
    }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getUploadDate() { return uploadDate; }
    public void setUploadDate(Instant uploadDate) { this.uploadDate = uploadDate; }
}
