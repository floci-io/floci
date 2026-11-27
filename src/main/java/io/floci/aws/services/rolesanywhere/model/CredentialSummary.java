package io.floci.aws.services.rolesanywhere.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/** One X.509 credential a subject presented in a temporary credential request, as {@code CredentialSummary} reports it. */
@RegisterForReflection
public class CredentialSummary {

    private Instant seenAt;
    private String serialNumber;
    private String issuer;
    private boolean enabled;
    private String x509CertificateData;
    private boolean failed;

    public Instant getSeenAt() {
        return seenAt;
    }

    public void setSeenAt(Instant seenAt) {
        this.seenAt = seenAt;
    }

    public String getSerialNumber() {
        return serialNumber;
    }

    public void setSerialNumber(String serialNumber) {
        this.serialNumber = serialNumber;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getX509CertificateData() {
        return x509CertificateData;
    }

    public void setX509CertificateData(String x509CertificateData) {
        this.x509CertificateData = x509CertificateData;
    }

    public boolean isFailed() {
        return failed;
    }

    public void setFailed(boolean failed) {
        this.failed = failed;
    }
}
