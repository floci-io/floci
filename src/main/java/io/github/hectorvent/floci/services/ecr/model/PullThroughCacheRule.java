package io.github.hectorvent.floci.services.ecr.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** An ECR pull through cache rule, as CreatePullThroughCacheRule returns it. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PullThroughCacheRule {

    private String ecrRepositoryPrefix;
    private String upstreamRegistryUrl;
    private String upstreamRegistry;
    private String registryId;
    private String credentialArn;
    private String customRoleArn;
    private String upstreamRepositoryPrefix;
    private Long createdAt;

    public String getEcrRepositoryPrefix() { return ecrRepositoryPrefix; }
    public void setEcrRepositoryPrefix(String ecrRepositoryPrefix) { this.ecrRepositoryPrefix = ecrRepositoryPrefix; }
    public String getUpstreamRegistryUrl() { return upstreamRegistryUrl; }
    public void setUpstreamRegistryUrl(String upstreamRegistryUrl) { this.upstreamRegistryUrl = upstreamRegistryUrl; }
    public String getUpstreamRegistry() { return upstreamRegistry; }
    public void setUpstreamRegistry(String upstreamRegistry) { this.upstreamRegistry = upstreamRegistry; }
    public String getRegistryId() { return registryId; }
    public void setRegistryId(String registryId) { this.registryId = registryId; }
    public String getCredentialArn() { return credentialArn; }
    public void setCredentialArn(String credentialArn) { this.credentialArn = credentialArn; }
    public String getCustomRoleArn() { return customRoleArn; }
    public void setCustomRoleArn(String customRoleArn) { this.customRoleArn = customRoleArn; }
    public String getUpstreamRepositoryPrefix() { return upstreamRepositoryPrefix; }
    public void setUpstreamRepositoryPrefix(String upstreamRepositoryPrefix) {
        this.upstreamRepositoryPrefix = upstreamRepositoryPrefix;
    }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
}
