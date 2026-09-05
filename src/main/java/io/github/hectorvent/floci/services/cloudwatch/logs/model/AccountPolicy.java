package io.github.hectorvent.floci.services.cloudwatch.logs.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class AccountPolicy {
    private String accountId;
    private String policyName;
    private String policyDocument;
    private String policyType;
    private String selectionCriteria;
    private String scope;
    private long lastUpdatedTime;

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public String getPolicyName() { return policyName; }
    public void setPolicyName(String policyName) { this.policyName = policyName; }
    public String getPolicyDocument() { return policyDocument; }
    public void setPolicyDocument(String policyDocument) { this.policyDocument = policyDocument; }
    public String getPolicyType() { return policyType; }
    public void setPolicyType(String policyType) { this.policyType = policyType; }
    public String getSelectionCriteria() { return selectionCriteria; }
    public void setSelectionCriteria(String selectionCriteria) { this.selectionCriteria = selectionCriteria; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public long getLastUpdatedTime() { return lastUpdatedTime; }
    public void setLastUpdatedTime(long lastUpdatedTime) { this.lastUpdatedTime = lastUpdatedTime; }
}
