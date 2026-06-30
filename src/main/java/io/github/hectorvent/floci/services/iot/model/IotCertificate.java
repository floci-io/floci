package io.github.hectorvent.floci.services.iot.model;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

public class IotCertificate {
    private String certificateId;
    private String certificateArn;
    private String certificatePem;
    private String publicKey;
    private String privateKey;
    private String status;
    private Instant creationDate;
    private Map<String, String> tags = new TreeMap<>();

    public String getCertificateId() { return certificateId; }
    public void setCertificateId(String certificateId) { this.certificateId = certificateId; }
    public String getCertificateArn() { return certificateArn; }
    public void setCertificateArn(String certificateArn) { this.certificateArn = certificateArn; }
    public String getCertificatePem() { return certificatePem; }
    public void setCertificatePem(String certificatePem) { this.certificatePem = certificatePem; }
    public String getPublicKey() { return publicKey; }
    public void setPublicKey(String publicKey) { this.publicKey = publicKey; }
    public String getPrivateKey() { return privateKey; }
    public void setPrivateKey(String privateKey) { this.privateKey = privateKey; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreationDate() { return creationDate; }
    public void setCreationDate(Instant creationDate) { this.creationDate = creationDate; }
    public Map<String, String> getTags() { return tags == null ? Map.of() : tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags == null ? new TreeMap<>() : new TreeMap<>(tags); }
}
