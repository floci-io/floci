package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Ipam {

    private String ipamId;
    private String ipamArn;
    private String ownerId;
    private String region;
    private String description;
    private String publicDefaultScopeId;
    private String privateDefaultScopeId;
    private String state;
    private Boolean enablePrivateGua;
    private String meteredAccount;
    private String tier;
    private String clientToken;
    private List<String> operatingRegions = new ArrayList<>();
    private List<IpamScope> scopes = new ArrayList<>();
    private List<Tag> tags = new ArrayList<>();

    public Ipam() {}

    public String getIpamId() { return ipamId; }
    public void setIpamId(String ipamId) { this.ipamId = ipamId; }

    public String getIpamArn() { return ipamArn; }
    public void setIpamArn(String ipamArn) { this.ipamArn = ipamArn; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getPublicDefaultScopeId() { return publicDefaultScopeId; }
    public void setPublicDefaultScopeId(String publicDefaultScopeId) { this.publicDefaultScopeId = publicDefaultScopeId; }

    public String getPrivateDefaultScopeId() { return privateDefaultScopeId; }
    public void setPrivateDefaultScopeId(String privateDefaultScopeId) { this.privateDefaultScopeId = privateDefaultScopeId; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public Boolean getEnablePrivateGua() { return enablePrivateGua; }
    public void setEnablePrivateGua(Boolean enablePrivateGua) { this.enablePrivateGua = enablePrivateGua; }

    public String getMeteredAccount() { return meteredAccount; }
    public void setMeteredAccount(String meteredAccount) { this.meteredAccount = meteredAccount; }

    public String getTier() { return tier; }
    public void setTier(String tier) { this.tier = tier; }

    public String getClientToken() { return clientToken; }
    public void setClientToken(String clientToken) { this.clientToken = clientToken; }

    public List<String> getOperatingRegions() { return operatingRegions; }
    public void setOperatingRegions(List<String> operatingRegions) { this.operatingRegions = operatingRegions; }

    public List<IpamScope> getScopes() { return scopes; }
    public void setScopes(List<IpamScope> scopes) { this.scopes = scopes; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }
}
