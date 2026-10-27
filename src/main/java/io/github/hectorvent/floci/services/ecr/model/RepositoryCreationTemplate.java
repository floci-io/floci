package io.github.hectorvent.floci.services.ecr.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;

/**
 * A repository creation template: the settings ECR gives a repository it creates for a
 * replication, pull through cache or create-on-push rule whose name starts with the template's
 * prefix. Floci records templates; nothing in Floci creates repositories that would use them.
 */
@RegisterForReflection
public class RepositoryCreationTemplate {

    /** The encryption a created repository gets. */
    @RegisterForReflection
    public record EncryptionConfiguration(String encryptionType, String kmsKey) {}

    /** One tag a created repository gets. */
    @RegisterForReflection
    public record ResourceTag(String key, String value) {}

    /** One tag filter exempt from the template's tag mutability setting. */
    @RegisterForReflection
    public record ExclusionFilter(String filterType, String filter) {}

    private String prefix;
    private String description;
    private EncryptionConfiguration encryptionConfiguration;
    private List<ResourceTag> resourceTags;
    private String imageTagMutability;
    private List<ExclusionFilter> imageTagMutabilityExclusionFilters;
    private String repositoryPolicy;
    private String lifecyclePolicy;
    private List<String> appliedFor;
    private String customRoleArn;
    private Instant createdAt;
    private Instant updatedAt;

    public RepositoryCreationTemplate() {}

    public String getPrefix() { return prefix; }
    public void setPrefix(String prefix) { this.prefix = prefix; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public EncryptionConfiguration getEncryptionConfiguration() { return encryptionConfiguration; }
    public void setEncryptionConfiguration(EncryptionConfiguration encryptionConfiguration) {
        this.encryptionConfiguration = encryptionConfiguration;
    }

    public List<ResourceTag> getResourceTags() { return resourceTags; }
    public void setResourceTags(List<ResourceTag> resourceTags) { this.resourceTags = resourceTags; }

    public String getImageTagMutability() { return imageTagMutability; }
    public void setImageTagMutability(String imageTagMutability) { this.imageTagMutability = imageTagMutability; }

    public List<ExclusionFilter> getImageTagMutabilityExclusionFilters() { return imageTagMutabilityExclusionFilters; }
    public void setImageTagMutabilityExclusionFilters(List<ExclusionFilter> imageTagMutabilityExclusionFilters) {
        this.imageTagMutabilityExclusionFilters = imageTagMutabilityExclusionFilters;
    }

    public String getRepositoryPolicy() { return repositoryPolicy; }
    public void setRepositoryPolicy(String repositoryPolicy) { this.repositoryPolicy = repositoryPolicy; }

    public String getLifecyclePolicy() { return lifecyclePolicy; }
    public void setLifecyclePolicy(String lifecyclePolicy) { this.lifecyclePolicy = lifecyclePolicy; }

    public List<String> getAppliedFor() { return appliedFor; }
    public void setAppliedFor(List<String> appliedFor) { this.appliedFor = appliedFor; }

    public String getCustomRoleArn() { return customRoleArn; }
    public void setCustomRoleArn(String customRoleArn) { this.customRoleArn = customRoleArn; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
