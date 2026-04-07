package io.github.hectorvent.floci.services.ecr.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Mutable ECR repository entity for Jackson serialization/deserialization.
 *
 * @see <a href="https://docs.aws.amazon.com/AmazonECR/latest/APIReference/API_Repository.html">AWS ECR Repository</a>
 */
@RegisterForReflection
public class Repository {
    private String repositoryArn;
    private String registryId;
    private String repositoryName;
    private String repositoryUri;
    private Instant createdAt;
    private String imageTagMutability = "MUTABLE";
    private boolean scanOnPush;
    private String encryptionType = "AES256";
    private String kmsKey;
    private String lifecyclePolicyText;
    private String repositoryPolicyText;
    private Map<String, String> tags = new HashMap<>();

    public Repository() {}

    public String getRepositoryArn() { return repositoryArn; }
    public void setRepositoryArn(String repositoryArn) { this.repositoryArn = repositoryArn; }

    public String getRegistryId() { return registryId; }
    public void setRegistryId(String registryId) { this.registryId = registryId; }

    public String getRepositoryName() { return repositoryName; }
    public void setRepositoryName(String repositoryName) { this.repositoryName = repositoryName; }

    public String getRepositoryUri() { return repositoryUri; }
    public void setRepositoryUri(String repositoryUri) { this.repositoryUri = repositoryUri; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getImageTagMutability() { return imageTagMutability; }
    public void setImageTagMutability(String imageTagMutability) { this.imageTagMutability = imageTagMutability; }

    public boolean isScanOnPush() { return scanOnPush; }
    public void setScanOnPush(boolean scanOnPush) { this.scanOnPush = scanOnPush; }

    public String getEncryptionType() { return encryptionType; }
    public void setEncryptionType(String encryptionType) { this.encryptionType = encryptionType; }

    public String getKmsKey() { return kmsKey; }
    public void setKmsKey(String kmsKey) { this.kmsKey = kmsKey; }

    public String getLifecyclePolicyText() { return lifecyclePolicyText; }
    public void setLifecyclePolicyText(String lifecyclePolicyText) { this.lifecyclePolicyText = lifecyclePolicyText; }

    public String getRepositoryPolicyText() { return repositoryPolicyText; }
    public void setRepositoryPolicyText(String repositoryPolicyText) { this.repositoryPolicyText = repositoryPolicyText; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags == null ? new HashMap<>() : tags; }
}
