package io.github.hectorvent.floci.services.ecr.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/** Mutable ECR pull through cache rule for Jackson persistence. */
@RegisterForReflection
public class PullThroughCacheRule {
    private String registryId;
    private String ecrRepositoryPrefix;
    private String upstreamRegistryUrl;
    private String upstreamRegistry;
    private String credentialArn;
    private String customRoleArn;
    private String upstreamRepositoryPrefix;
    private Instant createdAt;
    private Instant updatedAt;

    public PullThroughCacheRule() {}

    public String getRegistryId() { return registryId; }
    public void setRegistryId(String registryId) { this.registryId = registryId; }

    public String getEcrRepositoryPrefix() { return ecrRepositoryPrefix; }
    public void setEcrRepositoryPrefix(String ecrRepositoryPrefix) {
        this.ecrRepositoryPrefix = ecrRepositoryPrefix;
    }

    public String getUpstreamRegistryUrl() { return upstreamRegistryUrl; }
    public void setUpstreamRegistryUrl(String upstreamRegistryUrl) {
        this.upstreamRegistryUrl = upstreamRegistryUrl;
    }

    public String getUpstreamRegistry() { return upstreamRegistry; }
    public void setUpstreamRegistry(String upstreamRegistry) { this.upstreamRegistry = upstreamRegistry; }

    public String getCredentialArn() { return credentialArn; }
    public void setCredentialArn(String credentialArn) { this.credentialArn = credentialArn; }

    public String getCustomRoleArn() { return customRoleArn; }
    public void setCustomRoleArn(String customRoleArn) { this.customRoleArn = customRoleArn; }

    public String getUpstreamRepositoryPrefix() { return upstreamRepositoryPrefix; }
    public void setUpstreamRepositoryPrefix(String upstreamRepositoryPrefix) {
        this.upstreamRepositoryPrefix = upstreamRepositoryPrefix;
    }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
