package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class LaunchTemplate {

    private String launchTemplateId;
    private String launchTemplateName;
    private String defaultVersionNumber = "1";
    private String latestVersionNumber = "1";
    private Instant createTime;
    private String createdBy;
    private String region;
    private String imageId;
    private String instanceType;
    private String keyName;
    private String userData;
    private String encodedUserData;
    private String iamInstanceProfileArn;
    private List<String> securityGroupIds = new ArrayList<>();
    private List<Tag> tags = new ArrayList<>();
    private List<Tag> instanceTags = new ArrayList<>();
    private LaunchTemplateData.MetadataOptions metadataOptions;
    private Boolean monitoringEnabled;
    // lex00/floci#119: mirrors of LaunchTemplateData's own widened field set
    // (see that class for the oracle/context). Kept in sync with the
    // default version's data the same way imageId/instanceType/etc already
    // were, via dataFrom/applyData in Ec2Service.
    private String versionDescription;
    private Boolean ebsOptimized;
    private List<BlockDeviceMapping> blockDeviceMappings = new ArrayList<>();
    private LaunchTemplateData.CapacityReservationSpecification capacityReservationSpecification;
    private LaunchTemplateData.CpuOptions cpuOptions;
    private LaunchTemplateData.InstanceMarketOptions instanceMarketOptions;
    private LaunchTemplateData.MaintenanceOptions maintenanceOptions;
    private List<LaunchTemplateData.NetworkInterfaceSpecification> networkInterfaces = new ArrayList<>();
    private Placement placement;
    private List<LaunchTemplateData.TagSpecification> tagSpecifications = new ArrayList<>();
    private LaunchTemplateData.InstanceRequirements instanceRequirements;
    private Map<String, LaunchTemplateData> versions = new LinkedHashMap<>();

    public LaunchTemplate() {}

    public String getLaunchTemplateId() { return launchTemplateId; }
    public void setLaunchTemplateId(String launchTemplateId) { this.launchTemplateId = launchTemplateId; }

    public String getLaunchTemplateName() { return launchTemplateName; }
    public void setLaunchTemplateName(String launchTemplateName) { this.launchTemplateName = launchTemplateName; }

    public String getDefaultVersionNumber() { return defaultVersionNumber; }
    public void setDefaultVersionNumber(String defaultVersionNumber) { this.defaultVersionNumber = defaultVersionNumber; }

    public String getLatestVersionNumber() { return latestVersionNumber; }
    public void setLatestVersionNumber(String latestVersionNumber) { this.latestVersionNumber = latestVersionNumber; }

    public Instant getCreateTime() { return createTime; }
    public void setCreateTime(Instant createTime) { this.createTime = createTime; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getImageId() { return imageId; }
    public void setImageId(String imageId) { this.imageId = imageId; }

    public String getInstanceType() { return instanceType; }
    public void setInstanceType(String instanceType) { this.instanceType = instanceType; }

    public String getKeyName() { return keyName; }
    public void setKeyName(String keyName) { this.keyName = keyName; }

    public String getUserData() { return userData; }
    public void setUserData(String userData) { this.userData = userData; }

    public String getEncodedUserData() { return encodedUserData; }
    public void setEncodedUserData(String encodedUserData) { this.encodedUserData = encodedUserData; }

    public String getIamInstanceProfileArn() { return iamInstanceProfileArn; }
    public void setIamInstanceProfileArn(String iamInstanceProfileArn) { this.iamInstanceProfileArn = iamInstanceProfileArn; }

    public List<String> getSecurityGroupIds() { return securityGroupIds; }
    public void setSecurityGroupIds(List<String> securityGroupIds) { this.securityGroupIds = securityGroupIds; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }

    public List<Tag> getInstanceTags() { return instanceTags; }
    public void setInstanceTags(List<Tag> instanceTags) {
        this.instanceTags = instanceTags != null ? new ArrayList<>(instanceTags) : new ArrayList<>();
    }

    public LaunchTemplateData.MetadataOptions getMetadataOptions() { return metadataOptions; }
    public void setMetadataOptions(LaunchTemplateData.MetadataOptions metadataOptions) { this.metadataOptions = metadataOptions; }

    public Boolean getMonitoringEnabled() { return monitoringEnabled; }
    public void setMonitoringEnabled(Boolean monitoringEnabled) { this.monitoringEnabled = monitoringEnabled; }

    public String getVersionDescription() { return versionDescription; }
    public void setVersionDescription(String versionDescription) { this.versionDescription = versionDescription; }

    public Boolean getEbsOptimized() { return ebsOptimized; }
    public void setEbsOptimized(Boolean ebsOptimized) { this.ebsOptimized = ebsOptimized; }

    public List<BlockDeviceMapping> getBlockDeviceMappings() { return blockDeviceMappings; }
    public void setBlockDeviceMappings(List<BlockDeviceMapping> blockDeviceMappings) {
        this.blockDeviceMappings = blockDeviceMappings != null ? new ArrayList<>(blockDeviceMappings) : new ArrayList<>();
    }

    public LaunchTemplateData.CapacityReservationSpecification getCapacityReservationSpecification() { return capacityReservationSpecification; }
    public void setCapacityReservationSpecification(LaunchTemplateData.CapacityReservationSpecification v) { this.capacityReservationSpecification = v; }

    public LaunchTemplateData.CpuOptions getCpuOptions() { return cpuOptions; }
    public void setCpuOptions(LaunchTemplateData.CpuOptions cpuOptions) { this.cpuOptions = cpuOptions; }

    public LaunchTemplateData.InstanceMarketOptions getInstanceMarketOptions() { return instanceMarketOptions; }
    public void setInstanceMarketOptions(LaunchTemplateData.InstanceMarketOptions v) { this.instanceMarketOptions = v; }

    public LaunchTemplateData.MaintenanceOptions getMaintenanceOptions() { return maintenanceOptions; }
    public void setMaintenanceOptions(LaunchTemplateData.MaintenanceOptions maintenanceOptions) { this.maintenanceOptions = maintenanceOptions; }

    public List<LaunchTemplateData.NetworkInterfaceSpecification> getNetworkInterfaces() { return networkInterfaces; }
    public void setNetworkInterfaces(List<LaunchTemplateData.NetworkInterfaceSpecification> networkInterfaces) {
        this.networkInterfaces = networkInterfaces != null ? new ArrayList<>(networkInterfaces) : new ArrayList<>();
    }

    public Placement getPlacement() { return placement; }
    public void setPlacement(Placement placement) { this.placement = placement; }

    public List<LaunchTemplateData.TagSpecification> getTagSpecifications() { return tagSpecifications; }
    public void setTagSpecifications(List<LaunchTemplateData.TagSpecification> tagSpecifications) {
        this.tagSpecifications = tagSpecifications != null ? new ArrayList<>(tagSpecifications) : new ArrayList<>();
    }

    public LaunchTemplateData.InstanceRequirements getInstanceRequirements() { return instanceRequirements; }
    public void setInstanceRequirements(LaunchTemplateData.InstanceRequirements instanceRequirements) { this.instanceRequirements = instanceRequirements; }

    public Map<String, LaunchTemplateData> getVersions() { return versions; }
    public void setVersions(Map<String, LaunchTemplateData> versions) {
        this.versions = versions != null ? new LinkedHashMap<>(versions) : new LinkedHashMap<>();
    }
}
