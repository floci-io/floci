package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Cluster {

    @JsonProperty("name")
    private String name;

    @JsonProperty("arn")
    private String arn;

    @JsonProperty("createdAt")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant createdAt;

    @JsonProperty("version")
    private String version;

    @JsonProperty("endpoint")
    private String endpoint;

    @JsonProperty("roleArn")
    private String roleArn;

    @JsonProperty("resourcesVpcConfig")
    private ResourcesVpcConfig resourcesVpcConfig;

    @JsonProperty("kubernetesNetworkConfig")
    private KubernetesNetworkConfig kubernetesNetworkConfig;

    @JsonProperty("status")
    private ClusterStatus status;

    @JsonProperty("certificateAuthority")
    private CertificateAuthority certificateAuthority;

    @JsonProperty("platformVersion")
    private String platformVersion;

    @JsonProperty("tags")
    private Map<String, String> tags;

    @JsonIgnore
    private String containerId;

    public Cluster() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getRoleArn() { return roleArn; }
    public void setRoleArn(String roleArn) { this.roleArn = roleArn; }

    public ResourcesVpcConfig getResourcesVpcConfig() { return resourcesVpcConfig; }
    public void setResourcesVpcConfig(ResourcesVpcConfig resourcesVpcConfig) { this.resourcesVpcConfig = resourcesVpcConfig; }

    public KubernetesNetworkConfig getKubernetesNetworkConfig() { return kubernetesNetworkConfig; }
    public void setKubernetesNetworkConfig(KubernetesNetworkConfig kubernetesNetworkConfig) { this.kubernetesNetworkConfig = kubernetesNetworkConfig; }

    public ClusterStatus getStatus() { return status; }
    public void setStatus(ClusterStatus status) { this.status = status; }

    public CertificateAuthority getCertificateAuthority() { return certificateAuthority; }
    public void setCertificateAuthority(CertificateAuthority certificateAuthority) { this.certificateAuthority = certificateAuthority; }

    public String getPlatformVersion() { return platformVersion; }
    public void setPlatformVersion(String platformVersion) { this.platformVersion = platformVersion; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public String getContainerId() { return containerId; }
    public void setContainerId(String containerId) { this.containerId = containerId; }
}
