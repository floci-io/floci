package io.github.hectorvent.floci.services.msk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class CreateClusterV2Request {

    @JsonProperty("clusterName")
    private String clusterName;

    @JsonProperty("tags")
    private Map<String, String> tags;

    @JsonProperty("provisioned")
    private ProvisionedRequest provisioned;

    @JsonProperty("serverless")
    private Serverless serverless;

    public CreateClusterV2Request() {}

    public String getClusterName() { return clusterName; }
    public void setClusterName(String clusterName) { this.clusterName = clusterName; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public ProvisionedRequest getProvisioned() { return provisioned; }
    public void setProvisioned(ProvisionedRequest provisioned) { this.provisioned = provisioned; }

    public Serverless getServerless() { return serverless; }
    public void setServerless(Serverless serverless) { this.serverless = serverless; }
}