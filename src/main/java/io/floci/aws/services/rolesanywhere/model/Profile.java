package io.floci.aws.services.rolesanywhere.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A profile, as {@code ProfileDetail} reports it: the roles a session may assume, the policies that intersect
 * its permissions, the session duration, the attribute mappings that turn certificate fields into session tags,
 * its alias and its tags. {@code tipConfiguration} is kept as the request gave it.
 */
@RegisterForReflection
public class Profile {

    private String profileId;
    private String profileArn;
    private String name;
    private Boolean requireInstanceProperties;
    private boolean enabled;
    private String createdBy;
    private String sessionPolicy;
    private List<String> roleArns = new ArrayList<>();
    private List<String> managedPolicyArns = new ArrayList<>();
    private Instant createdAt;
    private Instant updatedAt;
    private Integer durationSeconds;
    private Boolean acceptRoleSessionName;
    private List<AttributeMapping> attributeMappings = new ArrayList<>();
    private String aliasName;
    private String aliasArn;
    private JsonNode tipConfiguration;
    private String previousAliasName;
    private Map<String, String> tags = new LinkedHashMap<>();

    public String getProfileId() {
        return profileId;
    }

    public void setProfileId(String profileId) {
        this.profileId = profileId;
    }

    public String getProfileArn() {
        return profileArn;
    }

    public void setProfileArn(String profileArn) {
        this.profileArn = profileArn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Boolean getRequireInstanceProperties() {
        return requireInstanceProperties;
    }

    public void setRequireInstanceProperties(Boolean requireInstanceProperties) {
        this.requireInstanceProperties = requireInstanceProperties;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getSessionPolicy() {
        return sessionPolicy;
    }

    public void setSessionPolicy(String sessionPolicy) {
        this.sessionPolicy = sessionPolicy;
    }

    public List<String> getRoleArns() {
        return roleArns;
    }

    public void setRoleArns(List<String> roleArns) {
        this.roleArns = roleArns == null ? new ArrayList<>() : new ArrayList<>(roleArns);
    }

    public List<String> getManagedPolicyArns() {
        return managedPolicyArns;
    }

    public void setManagedPolicyArns(List<String> managedPolicyArns) {
        this.managedPolicyArns = managedPolicyArns == null ? new ArrayList<>() : new ArrayList<>(managedPolicyArns);
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

    public Integer getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(Integer durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public Boolean getAcceptRoleSessionName() {
        return acceptRoleSessionName;
    }

    public void setAcceptRoleSessionName(Boolean acceptRoleSessionName) {
        this.acceptRoleSessionName = acceptRoleSessionName;
    }

    public List<AttributeMapping> getAttributeMappings() {
        return attributeMappings;
    }

    public void setAttributeMappings(List<AttributeMapping> attributeMappings) {
        this.attributeMappings = attributeMappings == null ? new ArrayList<>() : new ArrayList<>(attributeMappings);
    }

    public String getAliasName() {
        return aliasName;
    }

    public void setAliasName(String aliasName) {
        this.aliasName = aliasName;
    }

    public String getAliasArn() {
        return aliasArn;
    }

    public void setAliasArn(String aliasArn) {
        this.aliasArn = aliasArn;
    }

    public JsonNode getTipConfiguration() {
        return tipConfiguration;
    }

    public void setTipConfiguration(JsonNode tipConfiguration) {
        this.tipConfiguration = tipConfiguration;
    }

    public String getPreviousAliasName() {
        return previousAliasName;
    }

    public void setPreviousAliasName(String previousAliasName) {
        this.previousAliasName = previousAliasName;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
