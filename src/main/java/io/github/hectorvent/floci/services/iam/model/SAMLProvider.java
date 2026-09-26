package io.github.hectorvent.floci.services.iam.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SAMLProvider {
    private String arn;
    private String entityId;
    private String certificate;
    private Instant createDate = Instant.now();
    private Map<String, String> tags = new ConcurrentHashMap<>();

    public SAMLProvider() {}

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }
    public String getEntityId() { return entityId; }
    public void setEntityId(String entityId) { this.entityId = entityId; }
    public String getCertificate() { return certificate; }
    public void setCertificate(String certificate) { this.certificate = certificate; }
    public Instant getCreateDate() { return createDate; }
    public void setCreateDate(Instant createDate) { this.createDate = createDate; }
    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new ConcurrentHashMap<>() : new ConcurrentHashMap<>(tags);
    }
}
