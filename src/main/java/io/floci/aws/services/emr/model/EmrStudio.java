package io.floci.aws.services.emr.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An EMR Studio, as DescribeStudio reports it, with the Region it is in and its users' and groups' session mappings,
 * oldest first.
 */
@RegisterForReflection
public class EmrStudio {
    private String studioId;
    private String studioArn;
    private String name;
    private String description;
    private String authMode;
    private String vpcId;
    private List<String> subnetIds = new ArrayList<>();
    private String serviceRole;
    private String userRole;
    private String workspaceSecurityGroupId;
    private String engineSecurityGroupId;
    private String url;
    private Instant creationTime;
    private String defaultS3Location;
    private String idpAuthUrl;
    private String idpRelayStateParameterName;
    private Map<String, String> tags = new LinkedHashMap<>();
    private String idcInstanceArn;
    private Boolean trustedIdentityPropagationEnabled;
    private String idcUserAssignment;
    private String encryptionKeyArn;
    private String region;
    // Its place among the account's Studios, 1 for the first, which orders ListStudios.
    private long sequence;
    private List<EmrStudioSessionMapping> sessionMappings = new ArrayList<>();

    public EmrStudio() {}

    public String getStudioId() { return studioId; }
    public void setStudioId(String studioId) { this.studioId = studioId; }
    public String getStudioArn() { return studioArn; }
    public void setStudioArn(String studioArn) { this.studioArn = studioArn; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getAuthMode() { return authMode; }
    public void setAuthMode(String authMode) { this.authMode = authMode; }
    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }
    public List<String> getSubnetIds() { return subnetIds; }
    public void setSubnetIds(List<String> subnetIds) { this.subnetIds = subnetIds; }
    public String getServiceRole() { return serviceRole; }
    public void setServiceRole(String serviceRole) { this.serviceRole = serviceRole; }
    public String getUserRole() { return userRole; }
    public void setUserRole(String userRole) { this.userRole = userRole; }
    public String getWorkspaceSecurityGroupId() { return workspaceSecurityGroupId; }
    public void setWorkspaceSecurityGroupId(String workspaceSecurityGroupId) {
        this.workspaceSecurityGroupId = workspaceSecurityGroupId;
    }
    public String getEngineSecurityGroupId() { return engineSecurityGroupId; }
    public void setEngineSecurityGroupId(String engineSecurityGroupId) {
        this.engineSecurityGroupId = engineSecurityGroupId;
    }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public Instant getCreationTime() { return creationTime; }
    public void setCreationTime(Instant creationTime) { this.creationTime = creationTime; }
    public String getDefaultS3Location() { return defaultS3Location; }
    public void setDefaultS3Location(String defaultS3Location) { this.defaultS3Location = defaultS3Location; }
    public String getIdpAuthUrl() { return idpAuthUrl; }
    public void setIdpAuthUrl(String idpAuthUrl) { this.idpAuthUrl = idpAuthUrl; }
    public String getIdpRelayStateParameterName() { return idpRelayStateParameterName; }
    public void setIdpRelayStateParameterName(String idpRelayStateParameterName) {
        this.idpRelayStateParameterName = idpRelayStateParameterName;
    }
    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
    public String getIdcInstanceArn() { return idcInstanceArn; }
    public void setIdcInstanceArn(String idcInstanceArn) { this.idcInstanceArn = idcInstanceArn; }
    public Boolean getTrustedIdentityPropagationEnabled() { return trustedIdentityPropagationEnabled; }
    public void setTrustedIdentityPropagationEnabled(Boolean trustedIdentityPropagationEnabled) {
        this.trustedIdentityPropagationEnabled = trustedIdentityPropagationEnabled;
    }
    public String getIdcUserAssignment() { return idcUserAssignment; }
    public void setIdcUserAssignment(String idcUserAssignment) { this.idcUserAssignment = idcUserAssignment; }
    public String getEncryptionKeyArn() { return encryptionKeyArn; }
    public void setEncryptionKeyArn(String encryptionKeyArn) { this.encryptionKeyArn = encryptionKeyArn; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public long getSequence() { return sequence; }
    public void setSequence(long sequence) { this.sequence = sequence; }
    public List<EmrStudioSessionMapping> getSessionMappings() { return sessionMappings; }
    public void setSessionMappings(List<EmrStudioSessionMapping> sessionMappings) {
        this.sessionMappings = sessionMappings;
    }
}
