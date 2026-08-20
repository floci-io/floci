package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

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

    public Map<String, String> getLabels() { return labels; }
    public void setLabels(Map<String, String> labels) { this.labels = labels; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public String getClientRequestToken() { return clientRequestToken; }
    public void setClientRequestToken(String clientRequestToken) { this.clientRequestToken = clientRequestToken; }
}
