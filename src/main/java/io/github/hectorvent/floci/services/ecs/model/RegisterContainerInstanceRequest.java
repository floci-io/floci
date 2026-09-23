package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

/** A parsed {@code RegisterContainerInstance} request, which only the ECS agent sends. */
@RegisterForReflection
public class RegisterContainerInstanceRequest {

    private String cluster;
    /** Set when the agent is re-registering an instance it already has an ARN for. */
    private String containerInstanceArn;
    private String instanceIdentityDocument;
    private List<Attribute> attributes;
    private List<Map<String, Object>> totalResources;
    private Map<String, Object> versionInfo;
    private List<Map<String, Object>> platformDevices;
    private Map<String, String> tags;

    public String getCluster() { return cluster; }
    public void setCluster(String cluster) { this.cluster = cluster; }

    public String getContainerInstanceArn() { return containerInstanceArn; }
    public void setContainerInstanceArn(String containerInstanceArn) {
        this.containerInstanceArn = containerInstanceArn;
    }

    public String getInstanceIdentityDocument() { return instanceIdentityDocument; }
    public void setInstanceIdentityDocument(String doc) { this.instanceIdentityDocument = doc; }

    public List<Attribute> getAttributes() { return attributes; }
    public void setAttributes(List<Attribute> attributes) { this.attributes = attributes; }

    public List<Map<String, Object>> getTotalResources() { return totalResources; }
    public void setTotalResources(List<Map<String, Object>> totalResources) {
        this.totalResources = totalResources;
    }

    public Map<String, Object> getVersionInfo() { return versionInfo; }
    public void setVersionInfo(Map<String, Object> versionInfo) { this.versionInfo = versionInfo; }

    public List<Map<String, Object>> getPlatformDevices() { return platformDevices; }
    public void setPlatformDevices(List<Map<String, Object>> platformDevices) {
        this.platformDevices = platformDevices;
    }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
