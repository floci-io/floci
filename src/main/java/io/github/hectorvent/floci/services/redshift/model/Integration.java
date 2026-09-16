package io.github.hectorvent.floci.services.redshift.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A zero-ETL integration between a source (for example a DynamoDB table) and a Redshift target.
 *
 * <p>Field set captured from a live integration in us-west-2. {@code status} is lower case there
 * ({@code active}), and {@code errors} is present but empty on a healthy integration.
 */
@RegisterForReflection
public class Integration {
    private String integrationArn;
    private String integrationName;
    private String sourceArn;
    private String targetArn;
    private String description;
    private String status;
    private String kmsKeyId;
    private String createTime;
    private Map<String, String> tags = new LinkedHashMap<>();
    private Map<String, String> additionalEncryptionContext = new LinkedHashMap<>();
    private List<String> errors = new ArrayList<>();

    public Integration() {}

    public String getIntegrationArn() { return integrationArn; }
    public void setIntegrationArn(String integrationArn) { this.integrationArn = integrationArn; }
    public String getIntegrationName() { return integrationName; }
    public void setIntegrationName(String integrationName) { this.integrationName = integrationName; }
    public String getSourceArn() { return sourceArn; }
    public void setSourceArn(String sourceArn) { this.sourceArn = sourceArn; }
    public String getTargetArn() { return targetArn; }
    public void setTargetArn(String targetArn) { this.targetArn = targetArn; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getKmsKeyId() { return kmsKeyId; }
    public void setKmsKeyId(String kmsKeyId) { this.kmsKeyId = kmsKeyId; }
    public String getCreateTime() { return createTime; }
    public void setCreateTime(String createTime) { this.createTime = createTime; }
    public Map<String, String> getTags() { return tags == null ? null : new LinkedHashMap<>(tags); }
    public void setTags(Map<String, String> tags) { this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags); }
    public Map<String, String> getAdditionalEncryptionContext() {
        return additionalEncryptionContext == null ? null : new LinkedHashMap<>(additionalEncryptionContext);
    }
    public void setAdditionalEncryptionContext(Map<String, String> context) {
        this.additionalEncryptionContext = context == null ? new LinkedHashMap<>() : new LinkedHashMap<>(context);
    }
    public List<String> getErrors() { return errors == null ? null : new ArrayList<>(errors); }
    public void setErrors(List<String> errors) { this.errors = errors == null ? new ArrayList<>() : new ArrayList<>(errors); }
}
