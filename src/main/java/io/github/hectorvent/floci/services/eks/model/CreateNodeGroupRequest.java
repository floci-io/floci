package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

/**
 * {@code CreateNodegroup} request body. {@code clusterName} is absent because it travels in the
 * URI path. Recursive sub-structures (remoteAccess, taints, launchTemplate, updateConfig,
 * nodeRepairConfig, warmPoolConfig) round-trip as generic JSON, matching the convention
 * {@link Nodegroup} records: the management plane only echoes them back.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class CreateNodeGroupRequest {

    @JsonProperty("nodegroupName")
    private String nodegroupName;

    @JsonProperty("version")
    private String version;

    @JsonProperty("releaseVersion")
    private String releaseVersion;

    @JsonProperty("subnets")
    private List<String> subnets;

    @JsonProperty("nodeRole")
    private String nodeRole;

    @JsonProperty("amiType")
    private String amiType;

    @JsonProperty("capacityType")
    private String capacityType;

    @JsonProperty("diskSize")
    private Integer diskSize;

    @JsonProperty("instanceTypes")
    private List<String> instanceTypes;

    @JsonProperty("scalingConfig")
    private NodegroupScalingConfig scalingConfig;

    @JsonProperty("updateConfig")
    private Object updateConfig;

    @JsonProperty("remoteAccess")
    private Object remoteAccess;

    @JsonProperty("taints")
    private List<Object> taints;

    @JsonProperty("launchTemplate")
    private Object launchTemplate;

    @JsonProperty("nodeRepairConfig")
    private Object nodeRepairConfig;

    @JsonProperty("warmPoolConfig")
    private Object warmPoolConfig;

    @JsonProperty("labels")
    private Map<String, String> labels;

    @JsonProperty("tags")
    private Map<String, String> tags;

    @JsonProperty("clientRequestToken")
    private String clientRequestToken;

    public CreateNodeGroupRequest() {}

    public String getNodegroupName() { return nodegroupName; }
    public void setNodegroupName(String nodegroupName) { this.nodegroupName = nodegroupName; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getReleaseVersion() { return releaseVersion; }
    public void setReleaseVersion(String releaseVersion) { this.releaseVersion = releaseVersion; }

    public List<String> getSubnets() { return subnets; }
    public void setSubnets(List<String> subnets) { this.subnets = subnets; }

    public String getNodeRole() { return nodeRole; }
    public void setNodeRole(String nodeRole) { this.nodeRole = nodeRole; }

    public String getAmiType() { return amiType; }
    public void setAmiType(String amiType) { this.amiType = amiType; }

    public String getCapacityType() { return capacityType; }
    public void setCapacityType(String capacityType) { this.capacityType = capacityType; }

    public Integer getDiskSize() { return diskSize; }
    public void setDiskSize(Integer diskSize) { this.diskSize = diskSize; }

    public List<String> getInstanceTypes() { return instanceTypes; }
    public void setInstanceTypes(List<String> instanceTypes) { this.instanceTypes = instanceTypes; }

    public NodegroupScalingConfig getScalingConfig() { return scalingConfig; }
    public void setScalingConfig(NodegroupScalingConfig scalingConfig) { this.scalingConfig = scalingConfig; }

    public Object getUpdateConfig() { return updateConfig; }
    public void setUpdateConfig(Object updateConfig) { this.updateConfig = updateConfig; }

    public Object getRemoteAccess() { return remoteAccess; }
    public void setRemoteAccess(Object remoteAccess) { this.remoteAccess = remoteAccess; }

    public List<Object> getTaints() { return taints; }
    public void setTaints(List<Object> taints) { this.taints = taints; }

    public Object getLaunchTemplate() { return launchTemplate; }
    public void setLaunchTemplate(Object launchTemplate) { this.launchTemplate = launchTemplate; }

    public Object getNodeRepairConfig() { return nodeRepairConfig; }
    public void setNodeRepairConfig(Object nodeRepairConfig) { this.nodeRepairConfig = nodeRepairConfig; }

    public Object getWarmPoolConfig() { return warmPoolConfig; }
    public void setWarmPoolConfig(Object warmPoolConfig) { this.warmPoolConfig = warmPoolConfig; }

    public Map<String, String> getLabels() { return labels; }
    public void setLabels(Map<String, String> labels) { this.labels = labels; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public String getClientRequestToken() { return clientRequestToken; }
    public void setClientRequestToken(String clientRequestToken) { this.clientRequestToken = clientRequestToken; }
}
