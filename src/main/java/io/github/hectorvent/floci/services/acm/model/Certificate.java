package io.github.hectorvent.floci.services.acm.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mutable certificate entity for Jackson serialization/deserialization.
 * Thread-safety is managed by the storage layer (ConcurrentHashMap).
 *
 * @see <a href="https://docs.aws.amazon.com/acm/latest/APIReference/API_CertificateDetail.html">AWS ACM CertificateDetail</a>
 */
@RegisterForReflection
public class Certificate {
    private String arn;
    private String domainName;
    private List<String> subjectAlternativeNames;
    private CertificateStatus status;
    private CertificateType type;
    private ValidationMethod validationMethod;
    private Instant createdAt;
    private Instant issuedAt;
    private Instant importedAt;
    private Instant notBefore;
    private Instant notAfter;
    private String serial;
    private String subject;
    private String issuer;
    private KeyAlgorithm keyAlgorithm;
    private String signatureAlgorithm;
    private List<String> inUseBy;
    private Map<String, String> tags;
    private String certificateBody;
    private String privateKey;
    private String certificateChain;
    private CertificateOptions certOptions;
    private String certAuthorityArn;
    private List<DomainValidation> domainValidationOptions;
    private String idempotencyToken;

    public Certificate() {
        this.subjectAlternativeNames = new ArrayList<>();
        this.inUseBy = new ArrayList<>();
        this.tags = new HashMap<>();
        this.domainValidationOptions = new ArrayList<>();
    }

    // Getters and Setters
    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getDomainName() {
        return domainName;
    }

    public void setDomainName(String domainName) {
        this.domainName = domainName;
    }

    public List<String> getSubjectAlternativeNames() {
        return subjectAlternativeNames;
    }

    public void setSubjectAlternativeNames(List<String> subjectAlternativeNames) {
        this.subjectAlternativeNames = subjectAlternativeNames != null ? new ArrayList<>(subjectAlternativeNames) : new ArrayList<>();
    }

    public CertificateStatus getStatus() {
        return status;
    }

    public void setStatus(CertificateStatus status) {
        this.status = status;
    }

    public CertificateType getType() {
        return type;
    }

    public void setType(CertificateType type) {
        this.type = type;
    }

    public ValidationMethod getValidationMethod() {
        return validationMethod;
    }

    public void setValidationMethod(ValidationMethod validationMethod) {
        this.validationMethod = validationMethod;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public void setIssuedAt(Instant issuedAt) {
        this.issuedAt = issuedAt;
    }

    public Instant getImportedAt() {
        return importedAt;
    }

    public void setImportedAt(Instant importedAt) {
        this.importedAt = importedAt;
    }

    public Instant getNotBefore() {
        return notBefore;
    }

    public void setNotBefore(Instant notBefore) {
        this.notBefore = notBefore;
    }

    public Instant getNotAfter() {
        return notAfter;
    }

    public void setNotAfter(Instant notAfter) {
        this.notAfter = notAfter;
    }

    public String getSerial() {
        return serial;
    }

    public void setSerial(String serial) {
        this.serial = serial;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public KeyAlgorithm getKeyAlgorithm() {
        return keyAlgorithm;
    }

    public void setKeyAlgorithm(KeyAlgorithm keyAlgorithm) {
        this.keyAlgorithm = keyAlgorithm;
    }

    public String getSignatureAlgorithm() {
        return signatureAlgorithm;
    }

    public void setSignatureAlgorithm(String signatureAlgorithm) {
        this.signatureAlgorithm = signatureAlgorithm;
    }

    public List<String> getInUseBy() {
        return inUseBy;
    }

    public void setInUseBy(List<String> inUseBy) {
        this.inUseBy = inUseBy != null ? new ArrayList<>(inUseBy) : new ArrayList<>();
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? new HashMap<>(tags) : new HashMap<>();
    }

    public String getCertificateBody() {
        return certificateBody;
    }

    public void setCertificateBody(String certificateBody) {
        this.certificateBody = certificateBody;
    }

    public String getPrivateKey() {
        return privateKey;
    }

    public void setPrivateKey(String privateKey) {
        this.privateKey = privateKey;
    }

    public String getCertificateChain() {
        return certificateChain;
    }

    public void setCertificateChain(String certificateChain) {
        this.certificateChain = certificateChain;
    }

    public CertificateOptions getCertOptions() {
        return certOptions;
    }

    public void setCertOptions(CertificateOptions certOptions) {
        this.certOptions = certOptions;
    }

    public String getCertAuthorityArn() {
        return certAuthorityArn;
    }

    public void setCertAuthorityArn(String certAuthorityArn) {
        this.certAuthorityArn = certAuthorityArn;
    }

    public List<DomainValidation> getDomainValidationOptions() {
        return domainValidationOptions;
    }

    public void setDomainValidationOptions(List<DomainValidation> domainValidationOptions) {
        this.domainValidationOptions = domainValidationOptions != null ? new ArrayList<>(domainValidationOptions) : new ArrayList<>();
    }

    public String getIdempotencyToken() {
        return idempotencyToken;
    }

    public void setIdempotencyToken(String idempotencyToken) {
        this.idempotencyToken = idempotencyToken;
    }

    public boolean isExpired() {
        return notAfter != null && Instant.now().isAfter(notAfter);
    }

    public boolean canExport() {
        return type == CertificateType.PRIVATE ||
               type == CertificateType.IMPORTED ||
               (certOptions != null && "ENABLED".equals(certOptions.export()));
    }

    public String extractCertificateId() {
        if (arn == null) return null;
        int lastSlash = arn.lastIndexOf('/');
        return lastSlash >= 0 ? arn.substring(lastSlash + 1) : arn;
    }
}
