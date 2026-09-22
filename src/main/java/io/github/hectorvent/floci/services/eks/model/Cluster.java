package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;
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

    @JsonProperty("logging")
    private Logging logging;

    @JsonProperty("encryptionConfig")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<EncryptionConfig> encryptionConfig;

    @JsonProperty("status")
    private ClusterStatus status;

    @JsonProperty("certificateAuthority")
    private CertificateAuthority certificateAuthority;

    @JsonProperty("identity")
    private ClusterIdentity identity;

    @JsonProperty("platformVersion")
    private String platformVersion;

    @JsonProperty("tags")
    private Map<String, String> tags;

    /**
     * Caller-supplied containerd registry host configuration (certs.d/hosts.toml), persisted so a
     * recreated container (Floci restart with no surviving container) re-applies it. Never part of
     * the AWS DescribeCluster/CreateCluster response shape: EksController#toClusterResponse strips
     * it before returning a cluster over the wire. Read back via
     * {@code _floci/eks/clusters/{name}/registry-hosts}.
     */
    @JsonProperty("registryHosts")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<RegistryHostConfig> registryHosts;

    @JsonIgnore
    private String containerId;

    @JsonIgnore
    private String accountId;

    @JsonIgnore
    private String internalEndpoint;

    @JsonIgnore
    private int hostPort;

    @JsonIgnore
    private String podCidr;

    /**
     * Internal flag indicating whether the Kubernetes version was explicitly requested by
     * the caller (true) or defaulted (false). Persisted to storage so that clusters explicitly
     * pinned to the default version (e.g. 1.29) resolve to their pinned image across restarts
     * rather than falling back to the unversioned default image. Omitted from AWS API responses
     * by toClusterResponse in EksController.
     */
    @JsonProperty("explicitVersion")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private boolean explicitVersion;

    /**
     * Resolved Docker container/volume name for this cluster's k3s resources. In-memory only
     * (never part of the AWS response shape): assigned when the container is started, or
     * re-resolved deterministically from surviving Docker resources on restore.
     */
    @JsonIgnore
    private String dockerName;

    private AccessConfig accessConfig;

    public AccessConfig getAccessConfig() { return accessConfig; }
    public void setAccessConfig(AccessConfig accessConfig) { this.accessConfig = accessConfig; }

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

    public ClusterIdentity getIdentity() { return identity; }
    public void setIdentity(ClusterIdentity identity) { this.identity = identity; }

    public String getPlatformVersion() { return platformVersion; }
    public void setPlatformVersion(String platformVersion) { this.platformVersion = platformVersion; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public List<RegistryHostConfig> getRegistryHosts() { return registryHosts; }
    public void setRegistryHosts(List<RegistryHostConfig> registryHosts) { this.registryHosts = registryHosts; }

    public String getContainerId() { return containerId; }
    public void setContainerId(String containerId) { this.containerId = containerId; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getInternalEndpoint() { return internalEndpoint; }
    public void setInternalEndpoint(String internalEndpoint) { this.internalEndpoint = internalEndpoint; }

    public int getHostPort() { return hostPort; }
    public void setHostPort(int hostPort) { this.hostPort = hostPort; }

    public Logging getLogging() { return logging; }
    public void setLogging(Logging logging) { this.logging = logging; }

    public List<EncryptionConfig> getEncryptionConfig() { return encryptionConfig; }
    public void setEncryptionConfig(List<EncryptionConfig> encryptionConfig) { this.encryptionConfig = encryptionConfig; }

    public String getDockerName() { return dockerName; }
    public void setDockerName(String dockerName) { this.dockerName = dockerName; }

    public String getPodCidr() { return podCidr; }
    public void setPodCidr(String podCidr) { this.podCidr = podCidr; }

    public boolean isExplicitVersion() { return explicitVersion; }
    public void setExplicitVersion(boolean explicitVersion) { this.explicitVersion = explicitVersion; }

    public Cluster copy() {
        Cluster c = new Cluster();
        c.name = this.name;
        c.arn = this.arn;
        c.createdAt = this.createdAt;
        c.version = this.version;
        c.endpoint = this.endpoint;
        c.roleArn = this.roleArn;
        c.resourcesVpcConfig = this.resourcesVpcConfig;
        c.kubernetesNetworkConfig = this.kubernetesNetworkConfig;
        c.logging = this.logging;
        c.encryptionConfig = this.encryptionConfig;
        c.status = this.status;
        c.certificateAuthority = this.certificateAuthority;
        c.identity = this.identity;
        c.platformVersion = this.platformVersion;
        c.tags = this.tags;
        c.registryHosts = this.registryHosts;
        c.containerId = this.containerId;
        c.accountId = this.accountId;
        c.internalEndpoint = this.internalEndpoint;
        c.hostPort = this.hostPort;
        c.podCidr = this.podCidr;
        c.explicitVersion = this.explicitVersion;
        c.dockerName = this.dockerName;
        c.accessConfig = this.accessConfig;
        return c;
    }
}
