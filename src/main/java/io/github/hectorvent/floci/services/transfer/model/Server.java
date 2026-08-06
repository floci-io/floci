package io.github.hectorvent.floci.services.transfer.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public class Server {

    private String serverId;
    private String arn;
    private String state;
    private List<String> protocols;
    private String endpointType;
    private Map<String, Object> endpointDetails;
    private String identityProviderType;
    private Map<String, String> identityProviderDetails;
    private String loggingRole;
    private String securityPolicyName;
    private String hostKeyFingerprint;
    private Map<String, String> tags;
    private Instant creationTime;

    public Server() {}

    public String getServerId() { return serverId; }
    public void setServerId(String serverId) { this.serverId = serverId; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public List<String> getProtocols() { return protocols; }
    public void setProtocols(List<String> protocols) { this.protocols = protocols; }

    public String getEndpointType() { return endpointType; }
    public void setEndpointType(String endpointType) { this.endpointType = endpointType; }

    public Map<String, Object> getEndpointDetails() { return endpointDetails; }
    public void setEndpointDetails(Map<String, Object> endpointDetails) { this.endpointDetails = endpointDetails; }

    public String getIdentityProviderType() { return identityProviderType; }
    public void setIdentityProviderType(String identityProviderType) { this.identityProviderType = identityProviderType; }

    public Map<String, String> getIdentityProviderDetails() { return identityProviderDetails; }
    public void setIdentityProviderDetails(Map<String, String> identityProviderDetails) { this.identityProviderDetails = identityProviderDetails; }

    public String getLoggingRole() { return loggingRole; }
    public void setLoggingRole(String loggingRole) { this.loggingRole = loggingRole; }

    public String getSecurityPolicyName() { return securityPolicyName; }
    public void setSecurityPolicyName(String securityPolicyName) { this.securityPolicyName = securityPolicyName; }

    public String getHostKeyFingerprint() { return hostKeyFingerprint; }
    public void setHostKeyFingerprint(String hostKeyFingerprint) { this.hostKeyFingerprint = hostKeyFingerprint; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public Instant getCreationTime() { return creationTime; }
    public void setCreationTime(Instant creationTime) { this.creationTime = creationTime; }
}
