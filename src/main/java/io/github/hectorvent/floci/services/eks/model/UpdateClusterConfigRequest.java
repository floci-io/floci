package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;
import java.util.TreeMap;

/** The parts of UpdateClusterConfig Floci applies; every other member is kept to be refused by name. */
@RegisterForReflection
public class UpdateClusterConfigRequest {
    @JsonProperty("resourcesVpcConfig")
    private ResourcesVpcConfig resourcesVpcConfig;

    @JsonProperty("logging")
    private Logging logging;

    @JsonProperty("upgradePolicy")
    private UpgradePolicy upgradePolicy;

    @JsonProperty("clientRequestToken")
    private String clientRequestToken;

    private final Map<String, Object> unsupported = new TreeMap<>();

    public ResourcesVpcConfig getResourcesVpcConfig() { return resourcesVpcConfig; }
    public void setResourcesVpcConfig(ResourcesVpcConfig resourcesVpcConfig) { this.resourcesVpcConfig = resourcesVpcConfig; }
    public Logging getLogging() { return logging; }
    public void setLogging(Logging logging) { this.logging = logging; }
    public UpgradePolicy getUpgradePolicy() { return upgradePolicy; }
    public void setUpgradePolicy(UpgradePolicy upgradePolicy) { this.upgradePolicy = upgradePolicy; }
    public String getClientRequestToken() { return clientRequestToken; }
    public void setClientRequestToken(String clientRequestToken) { this.clientRequestToken = clientRequestToken; }

    @JsonAnySetter
    public void setUnsupported(String name, Object value) {
        if (value != null) {
            unsupported.put(name, value);
        }
    }

    public Map<String, Object> getUnsupported() { return unsupported; }
}
