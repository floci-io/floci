package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class CreateClusterRequest {

    @JsonProperty("name")
    private String name;

    @JsonProperty("version")
    private String version;

    @JsonProperty("roleArn")
    private String roleArn;

    @JsonProperty("resourcesVpcConfig")
    private ResourcesVpcConfig resourcesVpcConfig;

    @JsonProperty("kubernetesNetworkConfig")
    private KubernetesNetworkConfig kubernetesNetworkConfig;

    @JsonProperty("encryptionConfig")
    private List<EncryptionConfig> encryptionConfig;

    @JsonProperty("logging")
    private Logging logging;

    @JsonProperty("tags")
    private Map<String, String> tags;

    @JsonProperty("clientRequestToken")
    private String clientRequestToken;

    private AccessConfig accessConfig;

    public AccessConfig getAccessConfig() { return accessConfig; }
    public void setAccessConfig(AccessConfig accessConfig) { this.accessConfig = accessConfig; }

    public CreateClusterRequest() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getRoleArn() { return roleArn; }
    public void setRoleArn(String roleArn) { this.roleArn = roleArn; }

    public ResourcesVpcConfig getResourcesVpcConfig() { return resourcesVpcConfig; }
    public void setResourcesVpcConfig(ResourcesVpcConfig resourcesVpcConfig) { this.resourcesVpcConfig = resourcesVpcConfig; }

    public KubernetesNetworkConfig getKubernetesNetworkConfig() { return kubernetesNetworkConfig; }
    public void setKubernetesNetworkConfig(KubernetesNetworkConfig kubernetesNetworkConfig) { this.kubernetesNetworkConfig = kubernetesNetworkConfig; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public List<EncryptionConfig> getEncryptionConfig() { return encryptionConfig; }
    public void setEncryptionConfig(List<EncryptionConfig> encryptionConfig) { this.encryptionConfig = encryptionConfig; }

    public Logging getLogging() { return logging; }
    public void setLogging(Logging logging) { this.logging = logging; }

    public String getClientRequestToken() { return clientRequestToken; }
    public void setClientRequestToken(String clientRequestToken) { this.clientRequestToken = clientRequestToken; }
}
