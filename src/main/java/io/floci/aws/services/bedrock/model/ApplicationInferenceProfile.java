package io.floci.aws.services.bedrock.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public class ApplicationInferenceProfile {

    private String inferenceProfileId;
    private String inferenceProfileArn;
    private String inferenceProfileName;
    private String description;
    private List<String> models;
    private Instant createdAt;
    private Instant updatedAt;
    private Map<String, String> tags;
    private String accountId;
    private String region;
    private String clientRequestToken;

    public String getInferenceProfileId() {
        return inferenceProfileId;
    }

    public void setInferenceProfileId(String inferenceProfileId) {
        this.inferenceProfileId = inferenceProfileId;
    }

    public String getInferenceProfileArn() {
        return inferenceProfileArn;
    }

    public void setInferenceProfileArn(String inferenceProfileArn) {
        this.inferenceProfileArn = inferenceProfileArn;
    }

    public String getInferenceProfileName() {
        return inferenceProfileName;
    }

    public void setInferenceProfileName(String inferenceProfileName) {
        this.inferenceProfileName = inferenceProfileName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<String> getModels() {
        return models;
    }

    public void setModels(List<String> models) {
        this.models = models;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getClientRequestToken() {
        return clientRequestToken;
    }

    public void setClientRequestToken(String clientRequestToken) {
        this.clientRequestToken = clientRequestToken;
    }
}
