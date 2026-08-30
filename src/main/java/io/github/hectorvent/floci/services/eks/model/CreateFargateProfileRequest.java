package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class CreateFargateProfileRequest {

    @JsonProperty("fargateProfileName")
    private String fargateProfileName;

    @JsonProperty("podExecutionRoleArn")
    private String podExecutionRoleArn;

    @JsonProperty("subnets")
    private List<String> subnets;

    @JsonProperty("selectors")
    private List<FargateProfile.Selector> selectors;

    @JsonProperty("clientRequestToken")
    private String clientRequestToken;

    @JsonProperty("tags")
    private Map<String, String> tags;

    public CreateFargateProfileRequest() {}

    public String getFargateProfileName() { return fargateProfileName; }
    public void setFargateProfileName(String fargateProfileName) { this.fargateProfileName = fargateProfileName; }

    public String getPodExecutionRoleArn() { return podExecutionRoleArn; }
    public void setPodExecutionRoleArn(String podExecutionRoleArn) { this.podExecutionRoleArn = podExecutionRoleArn; }

    public List<String> getSubnets() { return subnets; }
    public void setSubnets(List<String> subnets) { this.subnets = subnets; }

    public List<FargateProfile.Selector> getSelectors() { return selectors; }
    public void setSelectors(List<FargateProfile.Selector> selectors) { this.selectors = selectors; }

    public String getClientRequestToken() { return clientRequestToken; }
    public void setClientRequestToken(String clientRequestToken) { this.clientRequestToken = clientRequestToken; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
