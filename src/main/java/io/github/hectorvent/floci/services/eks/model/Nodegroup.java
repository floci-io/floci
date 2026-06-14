package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * EKS managed node group. Doubles as the {@code CreateNodegroup} request body (the input
 * members are a subset) and the wire response. Recursive sub-structures
 * (remoteAccess, taints, launchTemplate, updateConfig, nodeRepairConfig, resources, health)
 * round-trip as generic JSON so we don't over-model what the management plane just echoes.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Nodegroup {

    @JsonProperty("nodegroupName")
    private String nodegroupName;

    @JsonProperty("nodegroupArn")
    private String nodegroupArn;

    @JsonProperty("clusterName")
    private String clusterName;

    @JsonProperty("version")
    private String version;

    @JsonProperty("releaseVersion")
    private String releaseVersion;

    @JsonProperty("createdAt")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant createdAt;

    @JsonProperty("modifiedAt")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant modifiedAt;

    @JsonProperty("status")
    private NodegroupStatus status;

    @JsonProperty("capacityType")
    private String capacityType;

    @JsonProperty("scalingConfig")
    private NodegroupScalingConfig scalingConfig;

    @JsonProperty("instanceTypes")
    private List<String> instanceTypes;

    @JsonProperty("subnets")
    private List<String> subnets;

    @JsonProperty("remoteAccess")
    private Object remoteAccess;

    @JsonProperty("amiType")
    private String amiType;

    @JsonProperty("nodeRole")
    private String nodeRole;

    @JsonProperty("labels")
    private Map<String, String> labels;

    @JsonProperty("taints")
    private List<Object> taints;

    @JsonProperty("resources")
    private Object resources;

    @JsonProperty("diskSize")
    private Integer diskSize;

    @JsonProperty("health")
    private Object health;

    @JsonProperty("updateConfig")
    private Object updateConfig;

    @JsonProperty("nodeRepairConfig")
    private Object nodeRepairConfig;

    @JsonProperty("launchTemplate")
    private Object launchTemplate;

    @JsonProperty("tags")
    private Map<String, String> tags;

    @JsonProperty("clientRequestToken")
    @JsonIgnore
    private String clientRequestToken;

    @JsonIgnore
    private String accountId;

    public Nodegroup() {}

    public String getNodegroupName() { return nodegroupName; }
    public void setNodegroupName(String nodegroupName) { this.nodegroupName = nodegroupName; }

    public String getNodegroupArn() { return nodegroupArn; }
    public void setNodegroupArn(String nodegroupArn) { this.nodegroupArn = nodegroupArn; }

    public String getClusterName() { return clusterName; }
    public void setClusterName(String clusterName) { this.clusterName = clusterName; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getReleaseVersion() { return releaseVersion; }
    public void setReleaseVersion(String releaseVersion) { this.releaseVersion = releaseVersion; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getModifiedAt() { return modifiedAt; }
    public void setModifiedAt(Instant modifiedAt) { this.modifiedAt = modifiedAt; }

    public NodegroupStatus getStatus() { return status; }
    public void setStatus(NodegroupStatus status) { this.status = status; }

    public String getCapacityType() { return capacityType; }
    public void setCapacityType(String capacityType) { this.capacityType = capacityType; }

    public NodegroupScalingConfig getScalingConfig() { return scalingConfig; }
    public void setScalingConfig(NodegroupScalingConfig scalingConfig) { this.scalingConfig = scalingConfig; }

    public List<String> getInstanceTypes() { return instanceTypes; }
    public void setInstanceTypes(List<String> instanceTypes) { this.instanceTypes = instanceTypes; }

    public List<String> getSubnets() { return subnets; }
    public void setSubnets(List<String> subnets) { this.subnets = subnets; }

    public Object getRemoteAccess() { return remoteAccess; }
    public void setRemoteAccess(Object remoteAccess) { this.remoteAccess = remoteAccess; }

    public String getAmiType() { return amiType; }
    public void setAmiType(String amiType) { this.amiType = amiType; }

    public String getNodeRole() { return nodeRole; }
    public void setNodeRole(String nodeRole) { this.nodeRole = nodeRole; }

    public Map<String, String> getLabels() { return labels; }
    public void setLabels(Map<String, String> labels) { this.labels = labels; }

    public List<Object> getTaints() { return taints; }
    public void setTaints(List<Object> taints) { this.taints = taints; }

    public Object getResources() { return resources; }
    public void setResources(Object resources) { this.resources = resources; }

    public Integer getDiskSize() { return diskSize; }
    public void setDiskSize(Integer diskSize) { this.diskSize = diskSize; }

    public Object getHealth() { return health; }
    public void setHealth(Object health) { this.health = health; }

    public Object getUpdateConfig() { return updateConfig; }
    public void setUpdateConfig(Object updateConfig) { this.updateConfig = updateConfig; }

    public Object getNodeRepairConfig() { return nodeRepairConfig; }
    public void setNodeRepairConfig(Object nodeRepairConfig) { this.nodeRepairConfig = nodeRepairConfig; }

    public Object getLaunchTemplate() { return launchTemplate; }
    public void setLaunchTemplate(Object launchTemplate) { this.launchTemplate = launchTemplate; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public String getClientRequestToken() { return clientRequestToken; }
    public void setClientRequestToken(String clientRequestToken) { this.clientRequestToken = clientRequestToken; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
}
