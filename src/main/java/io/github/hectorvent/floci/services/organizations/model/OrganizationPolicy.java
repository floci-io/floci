package io.github.hectorvent.floci.services.organizations.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Domain model for an organization policy (SCP, tag policy, backup policy, and peers).
 *
 * <p>Attachments are held on the policy rather than on the target so that
 * {@code ListTargetsForPolicy} and {@code DetachPolicy} stay a single lookup; the reverse
 * direction ({@code ListPoliciesForTarget}) scans the org's policies.
 */
@RegisterForReflection
public class OrganizationPolicy {

    private String id;
    private String arn;
    private String name;
    private String description;
    private String type;
    private boolean awsManaged;
    private String content;
    private String organizationId;

    /** Root, OU and account ids this policy is attached to, in attachment order. */
    private Set<String> targets = new LinkedHashSet<>();

    private Map<String, String> tags = new LinkedHashMap<>();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public boolean isAwsManaged() {
        return awsManaged;
    }

    public void setAwsManaged(boolean awsManaged) {
        this.awsManaged = awsManaged;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(String organizationId) {
        this.organizationId = organizationId;
    }

    public Set<String> getTargets() {
        return targets;
    }

    public void setTargets(Set<String> targets) {
        this.targets = targets;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags;
    }
}
