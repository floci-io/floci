package io.github.hectorvent.floci.services.securityhub.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SecurityHubState {
    private String adminAccountId;
    private boolean enabled;
    private String aggregatorArn;
    private String regionLinkingMode;
    private JsonNode regions;
    private String organizationConfigurationType = "LOCAL";
    private String organizationConfigurationStatus = "ENABLED";
    private int organizationConfigurationPendingPollsRemaining;
    private Map<String, JsonNode> policies = new LinkedHashMap<>();
    private Map<String, SecurityHubAssociation> associations = new LinkedHashMap<>();

    public String getAdminAccountId() { return adminAccountId; }
    public void setAdminAccountId(String adminAccountId) { this.adminAccountId = adminAccountId; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getAggregatorArn() { return aggregatorArn; }
    public void setAggregatorArn(String aggregatorArn) { this.aggregatorArn = aggregatorArn; }
    public String getRegionLinkingMode() { return regionLinkingMode; }
    public void setRegionLinkingMode(String regionLinkingMode) { this.regionLinkingMode = regionLinkingMode; }
    public JsonNode getRegions() { return regions; }
    public void setRegions(JsonNode regions) { this.regions = regions; }
    public String getOrganizationConfigurationType() { return organizationConfigurationType; }
    public void setOrganizationConfigurationType(String value) { organizationConfigurationType = value; }
    public String getOrganizationConfigurationStatus() { return organizationConfigurationStatus; }
    public void setOrganizationConfigurationStatus(String value) { organizationConfigurationStatus = value; }
    public int getOrganizationConfigurationPendingPollsRemaining() { return organizationConfigurationPendingPollsRemaining; }
    public void setOrganizationConfigurationPendingPollsRemaining(int value) { organizationConfigurationPendingPollsRemaining = value; }
    public Map<String, JsonNode> getPolicies() { return policies; }
    public void setPolicies(Map<String, JsonNode> policies) { this.policies = policies; }
    public Map<String, SecurityHubAssociation> getAssociations() { return associations; }
    public void setAssociations(Map<String, SecurityHubAssociation> associations) { this.associations = associations; }
}
