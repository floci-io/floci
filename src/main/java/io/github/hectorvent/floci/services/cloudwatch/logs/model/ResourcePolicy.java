package io.github.hectorvent.floci.services.cloudwatch.logs.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResourcePolicy {

    private String policyName;
    private String policyDocument;
    private long lastUpdatedTime;

    public ResourcePolicy() {}

    public String getPolicyName() { return policyName; }
    public void setPolicyName(String policyName) { this.policyName = policyName; }

    public String getPolicyDocument() { return policyDocument; }
    public void setPolicyDocument(String policyDocument) { this.policyDocument = policyDocument; }

    public long getLastUpdatedTime() { return lastUpdatedTime; }
    public void setLastUpdatedTime(long lastUpdatedTime) { this.lastUpdatedTime = lastUpdatedTime; }
}
