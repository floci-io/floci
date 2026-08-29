package io.github.hectorvent.floci.services.organizations.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Domain model for an AWS Organization.
 *
 * <p>The organization is the anchor for every other Organizations resource: accounts, OUs and
 * policies all carry its id, which is what lets a member account resolve the organization it
 * belongs to without knowing the management account up front.
 */
@RegisterForReflection
public class Organization {

    private String id;
    private String arn;
    private String featureSet;
    private String masterAccountId;
    private String masterAccountArn;
    private String masterAccountEmail;
    private Instant createdTimestamp;
    private Root root = new Root();

    /** Service principal to the timestamp trusted access was enabled, in insertion order. */
    private Map<String, Instant> enabledServicePrincipals = new LinkedHashMap<>();

    private String resourcePolicyId;
    private String resourcePolicyArn;
    private String resourcePolicyContent;
    private Map<String, String> resourcePolicyTags = new LinkedHashMap<>();

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

    public String getFeatureSet() {
        return featureSet;
    }

    public void setFeatureSet(String featureSet) {
        this.featureSet = featureSet;
    }

    public String getMasterAccountId() {
        return masterAccountId;
    }

    public void setMasterAccountId(String masterAccountId) {
        this.masterAccountId = masterAccountId;
    }

    public String getMasterAccountArn() {
        return masterAccountArn;
    }

    public void setMasterAccountArn(String masterAccountArn) {
        this.masterAccountArn = masterAccountArn;
    }

    public String getMasterAccountEmail() {
        return masterAccountEmail;
    }

    public void setMasterAccountEmail(String masterAccountEmail) {
        this.masterAccountEmail = masterAccountEmail;
    }

    public Instant getCreatedTimestamp() {
        return createdTimestamp;
    }

    public void setCreatedTimestamp(Instant createdTimestamp) {
        this.createdTimestamp = createdTimestamp;
    }

    public Root getRoot() {
        return root;
    }

    public void setRoot(Root root) {
        this.root = root;
    }

    public Map<String, Instant> getEnabledServicePrincipals() {
        return enabledServicePrincipals;
    }

    public void setEnabledServicePrincipals(Map<String, Instant> enabledServicePrincipals) {
        this.enabledServicePrincipals = enabledServicePrincipals;
    }

    public String getResourcePolicyId() {
        return resourcePolicyId;
    }

    public void setResourcePolicyId(String resourcePolicyId) {
        this.resourcePolicyId = resourcePolicyId;
    }

    public String getResourcePolicyArn() {
        return resourcePolicyArn;
    }

    public void setResourcePolicyArn(String resourcePolicyArn) {
        this.resourcePolicyArn = resourcePolicyArn;
    }

    public String getResourcePolicyContent() {
        return resourcePolicyContent;
    }

    public void setResourcePolicyContent(String resourcePolicyContent) {
        this.resourcePolicyContent = resourcePolicyContent;
    }

    public Map<String, String> getResourcePolicyTags() {
        return resourcePolicyTags;
    }

    public void setResourcePolicyTags(Map<String, String> resourcePolicyTags) {
        this.resourcePolicyTags = resourcePolicyTags;
    }
}
