package io.github.hectorvent.floci.services.iam.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An IAM server certificate: a PEM certificate, its private key and an optional chain, stored under
 * a name unique within the account.
 *
 * <p>{@code expiration} is read off the certificate's {@code notAfter} at upload rather than stored
 * independently, so it cannot drift from the certificate it describes.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ServerCertificate {
    private String serverCertificateName;
    private String serverCertificateId;
    private String path = "/";
    private String arn;
    /** ARNs the certificate carried before a rename or move, which a referrer may still hold. */
    private List<String> formerArns = new ArrayList<>();
    private String certificateBody;
    /** PEM private key. Never echoed back: no AWS reader returns it once uploaded. */
    private String privateKey;
    /** Optional, and absent rather than empty when the upload omitted it. */
    private String certificateChain;
    private Instant uploadDate = Instant.now();
    /** The certificate's own {@code notAfter}. */
    private Instant expiration;
    private Map<String, String> tags = new ConcurrentHashMap<>();

    public ServerCertificate() {}

    public String getServerCertificateName() { return serverCertificateName; }
    public void setServerCertificateName(String serverCertificateName) {
        this.serverCertificateName = serverCertificateName;
    }
    public String getServerCertificateId() { return serverCertificateId; }
    public void setServerCertificateId(String serverCertificateId) {
        this.serverCertificateId = serverCertificateId;
    }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }
    public List<String> getFormerArns() { return formerArns; }
    public void setFormerArns(List<String> formerArns) {
        this.formerArns = formerArns == null ? new ArrayList<>() : new ArrayList<>(formerArns);
    }
    public String getCertificateBody() { return certificateBody; }
    public void setCertificateBody(String certificateBody) { this.certificateBody = certificateBody; }
    public String getPrivateKey() { return privateKey; }
    public void setPrivateKey(String privateKey) { this.privateKey = privateKey; }
    public String getCertificateChain() { return certificateChain; }
    public void setCertificateChain(String certificateChain) { this.certificateChain = certificateChain; }
    public Instant getUploadDate() { return uploadDate; }
    public void setUploadDate(Instant uploadDate) { this.uploadDate = uploadDate; }
    public Instant getExpiration() { return expiration; }
    public void setExpiration(Instant expiration) { this.expiration = expiration; }
    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new ConcurrentHashMap<>() : new ConcurrentHashMap<>(tags);
    }
}
