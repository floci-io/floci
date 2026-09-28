package io.github.hectorvent.floci.services.dlm.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
public class LifecyclePolicy {
    private String policyId;
    private String description;
    private String state;
    private String executionRoleArn;
    private String dateCreated;
    private String dateModified;
    private JsonNode policyDetails;
    private Map<String, String> tags = new LinkedHashMap<>();
    private String policyArn;
    private String defaultPolicyType;
    private Integer createInterval;
    private Integer retainInterval;
    private Boolean copyTags;
    private Boolean extendDeletion;
    private JsonNode crossRegionCopyTargets;
    private JsonNode exclusions;

    public LifecyclePolicy() {
    }

    public LifecyclePolicy(LifecyclePolicy source) {
        this.policyId = source.policyId;
        this.description = source.description;
        this.state = source.state;
        this.executionRoleArn = source.executionRoleArn;
        this.dateCreated = source.dateCreated;
        this.dateModified = source.dateModified;
        this.policyDetails = source.policyDetails == null ? null : source.policyDetails.deepCopy();
        this.tags = source.tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source.tags);
        this.policyArn = source.policyArn;
        this.defaultPolicyType = source.defaultPolicyType;
        this.createInterval = source.createInterval;
        this.retainInterval = source.retainInterval;
        this.copyTags = source.copyTags;
        this.extendDeletion = source.extendDeletion;
        this.crossRegionCopyTargets = source.crossRegionCopyTargets == null
                ? null : source.crossRegionCopyTargets.deepCopy();
        this.exclusions = source.exclusions == null ? null : source.exclusions.deepCopy();
    }

    public String getPolicyId() {
        return policyId;
    }

    public void setPolicyId(String policyId) {
        this.policyId = policyId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getExecutionRoleArn() {
        return executionRoleArn;
    }

    public void setExecutionRoleArn(String executionRoleArn) {
        this.executionRoleArn = executionRoleArn;
    }

    public String getDateCreated() {
        return dateCreated;
    }

    public void setDateCreated(String dateCreated) {
        this.dateCreated = dateCreated;
    }

    public String getDateModified() {
        return dateModified;
    }

    public void setDateModified(String dateModified) {
        this.dateModified = dateModified;
    }

    public JsonNode getPolicyDetails() {
        return policyDetails;
    }

    public void setPolicyDetails(JsonNode policyDetails) {
        this.policyDetails = policyDetails;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags;
    }

    public String getPolicyArn() {
        return policyArn;
    }

    public void setPolicyArn(String policyArn) {
        this.policyArn = policyArn;
    }

    public String getDefaultPolicyType() {
        return defaultPolicyType;
    }

    public void setDefaultPolicyType(String defaultPolicyType) {
        this.defaultPolicyType = defaultPolicyType;
    }

    public Integer getCreateInterval() {
        return createInterval;
    }

    public void setCreateInterval(Integer createInterval) {
        this.createInterval = createInterval;
    }

    public Integer getRetainInterval() {
        return retainInterval;
    }

    public void setRetainInterval(Integer retainInterval) {
        this.retainInterval = retainInterval;
    }

    public Boolean getCopyTags() {
        return copyTags;
    }

    public void setCopyTags(Boolean copyTags) {
        this.copyTags = copyTags;
    }

    public Boolean getExtendDeletion() {
        return extendDeletion;
    }

    public void setExtendDeletion(Boolean extendDeletion) {
        this.extendDeletion = extendDeletion;
    }

    public JsonNode getCrossRegionCopyTargets() {
        return crossRegionCopyTargets;
    }

    public void setCrossRegionCopyTargets(JsonNode crossRegionCopyTargets) {
        this.crossRegionCopyTargets = crossRegionCopyTargets;
    }

    public JsonNode getExclusions() {
        return exclusions;
    }

    public void setExclusions(JsonNode exclusions) {
        this.exclusions = exclusions;
    }
}
