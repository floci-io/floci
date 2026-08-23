package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/**
 * The subset of EC2 {@code RequestLaunchTemplateData} / {@code ResponseLaunchTemplateData} that
 * Floci round-trips. Members the service model declares but this class omits are listed in
 * {@code docs/services/ec2.md}; they are accepted and ignored rather than rejected.
 *
 * <p>A version persisted before this class existed in its current shape stored the instance
 * profile as a bare {@code iamInstanceProfileArn} string and instance tags as a flat
 * {@code instanceTags} list, rather than the {@link IamInstanceProfile} / {@link TagSpecification}
 * shapes below. Both old keys are mapped onto the new fields at load time (see the legacy setters
 * near the bottom of this class), so ignoreUnknown never has to silently drop them.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class LaunchTemplateData {

    private String imageId;
    private String instanceType;
    private String keyName;
    private String userData;
    private String encodedUserData;
    private String kernelId;
    private String ramDiskId;
    private String instanceInitiatedShutdownBehavior;
    private Boolean ebsOptimized;
    private Boolean disableApiTermination;
    private Boolean disableApiStop;
    private IamInstanceProfile iamInstanceProfile;
    private MetadataOptions metadataOptions;
    private Monitoring monitoring;
    private Placement placement;
    private CpuOptions cpuOptions;
    private CreditSpecification creditSpecification;
    private EnclaveOptions enclaveOptions;
    private HibernationOptions hibernationOptions;
    private MaintenanceOptions maintenanceOptions;
    private PrivateDnsNameOptions privateDnsNameOptions;
    private CapacityReservationSpecification capacityReservationSpecification;
    private List<String> securityGroupIds = new ArrayList<>();
    private List<BlockDeviceMapping> blockDeviceMappings = new ArrayList<>();
    private List<NetworkInterface> networkInterfaces = new ArrayList<>();
    private List<TagSpecification> tagSpecifications = new ArrayList<>();

    public LaunchTemplateData() {}

    public LaunchTemplateData(LaunchTemplateData source) {
        this.imageId = source.imageId;
        this.instanceType = source.instanceType;
        this.keyName = source.keyName;
        this.userData = source.userData;
        this.encodedUserData = source.encodedUserData;
        this.kernelId = source.kernelId;
        this.ramDiskId = source.ramDiskId;
        this.instanceInitiatedShutdownBehavior = source.instanceInitiatedShutdownBehavior;
        this.ebsOptimized = source.ebsOptimized;
        this.disableApiTermination = source.disableApiTermination;
        this.disableApiStop = source.disableApiStop;
        this.iamInstanceProfile = source.iamInstanceProfile;
        this.metadataOptions = source.metadataOptions;
        this.monitoring = source.monitoring;
        this.placement = source.placement;
        this.cpuOptions = source.cpuOptions;
        this.creditSpecification = source.creditSpecification;
        this.enclaveOptions = source.enclaveOptions;
        this.hibernationOptions = source.hibernationOptions;
        this.maintenanceOptions = source.maintenanceOptions;
        this.privateDnsNameOptions = source.privateDnsNameOptions;
        this.capacityReservationSpecification = source.capacityReservationSpecification;
        this.securityGroupIds = new ArrayList<>(source.securityGroupIds);
        this.blockDeviceMappings = new ArrayList<>(source.blockDeviceMappings);
        this.networkInterfaces = new ArrayList<>(source.networkInterfaces);
        this.tagSpecifications = new ArrayList<>(source.tagSpecifications);
    }

    /**
     * Members present on {@code override} replace those inherited from this instance, which is how
     * {@code CreateLaunchTemplateVersion} layers a new version onto its source version.
     */
    public LaunchTemplateData mergedWith(LaunchTemplateData override) {
        LaunchTemplateData merged = new LaunchTemplateData(this);
        if (isPresent(override.imageId)) {
            merged.imageId = override.imageId;
        }
        if (isPresent(override.instanceType)) {
            merged.instanceType = override.instanceType;
        }
        if (isPresent(override.keyName)) {
            merged.keyName = override.keyName;
        }
        if (isPresent(override.userData) || isPresent(override.encodedUserData)) {
            merged.userData = override.userData;
            merged.encodedUserData = override.encodedUserData;
        }
        if (isPresent(override.kernelId)) {
            merged.kernelId = override.kernelId;
        }
        if (isPresent(override.ramDiskId)) {
            merged.ramDiskId = override.ramDiskId;
        }
        if (isPresent(override.instanceInitiatedShutdownBehavior)) {
            merged.instanceInitiatedShutdownBehavior = override.instanceInitiatedShutdownBehavior;
        }
        if (override.ebsOptimized != null) {
            merged.ebsOptimized = override.ebsOptimized;
        }
        if (override.disableApiTermination != null) {
            merged.disableApiTermination = override.disableApiTermination;
        }
        if (override.disableApiStop != null) {
            merged.disableApiStop = override.disableApiStop;
        }
        if (override.iamInstanceProfile != null) {
            merged.iamInstanceProfile = override.iamInstanceProfile;
        }
        if (override.metadataOptions != null) {
            merged.metadataOptions = override.metadataOptions;
        }
        if (override.monitoring != null) {
            merged.monitoring = override.monitoring;
        }
        if (override.placement != null) {
            merged.placement = override.placement;
        }
        if (override.cpuOptions != null) {
            merged.cpuOptions = override.cpuOptions;
        }
        if (override.creditSpecification != null) {
            merged.creditSpecification = override.creditSpecification;
        }
        if (override.enclaveOptions != null) {
            merged.enclaveOptions = override.enclaveOptions;
        }
        if (override.hibernationOptions != null) {
            merged.hibernationOptions = override.hibernationOptions;
        }
        if (override.maintenanceOptions != null) {
            merged.maintenanceOptions = override.maintenanceOptions;
        }
        if (override.privateDnsNameOptions != null) {
            merged.privateDnsNameOptions = override.privateDnsNameOptions;
        }
        if (override.capacityReservationSpecification != null) {
            merged.capacityReservationSpecification = override.capacityReservationSpecification;
        }
        if (!override.securityGroupIds.isEmpty()) {
            merged.securityGroupIds = new ArrayList<>(override.securityGroupIds);
        }
        if (!override.blockDeviceMappings.isEmpty()) {
            merged.blockDeviceMappings = new ArrayList<>(override.blockDeviceMappings);
        }
        if (!override.networkInterfaces.isEmpty()) {
            merged.networkInterfaces = new ArrayList<>(override.networkInterfaces);
        }
        if (!override.tagSpecifications.isEmpty()) {
            merged.tagSpecifications = new ArrayList<>(override.tagSpecifications);
        }
        return merged;
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

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

    public String getKernelId() { return kernelId; }
    public void setKernelId(String kernelId) { this.kernelId = kernelId; }

    public String getRamDiskId() { return ramDiskId; }
    public void setRamDiskId(String ramDiskId) { this.ramDiskId = ramDiskId; }

    public String getInstanceInitiatedShutdownBehavior() { return instanceInitiatedShutdownBehavior; }
    public void setInstanceInitiatedShutdownBehavior(String instanceInitiatedShutdownBehavior) {
        this.instanceInitiatedShutdownBehavior = instanceInitiatedShutdownBehavior;
    }

    public Boolean getEbsOptimized() { return ebsOptimized; }
    public void setEbsOptimized(Boolean ebsOptimized) { this.ebsOptimized = ebsOptimized; }

    public Boolean getDisableApiTermination() { return disableApiTermination; }
    public void setDisableApiTermination(Boolean disableApiTermination) { this.disableApiTermination = disableApiTermination; }

    public Boolean getDisableApiStop() { return disableApiStop; }
    public void setDisableApiStop(Boolean disableApiStop) { this.disableApiStop = disableApiStop; }

    public IamInstanceProfile getIamInstanceProfile() { return iamInstanceProfile; }
    public void setIamInstanceProfile(IamInstanceProfile iamInstanceProfile) { this.iamInstanceProfile = iamInstanceProfile; }

    public MetadataOptions getMetadataOptions() { return metadataOptions; }
    public void setMetadataOptions(MetadataOptions metadataOptions) { this.metadataOptions = metadataOptions; }

    public Monitoring getMonitoring() { return monitoring; }
    public void setMonitoring(Monitoring monitoring) { this.monitoring = monitoring; }

    public Placement getPlacement() { return placement; }
    public void setPlacement(Placement placement) { this.placement = placement; }

    public CpuOptions getCpuOptions() { return cpuOptions; }
    public void setCpuOptions(CpuOptions cpuOptions) { this.cpuOptions = cpuOptions; }

    public CreditSpecification getCreditSpecification() { return creditSpecification; }
    public void setCreditSpecification(CreditSpecification creditSpecification) { this.creditSpecification = creditSpecification; }

    public EnclaveOptions getEnclaveOptions() { return enclaveOptions; }
    public void setEnclaveOptions(EnclaveOptions enclaveOptions) { this.enclaveOptions = enclaveOptions; }

    public HibernationOptions getHibernationOptions() { return hibernationOptions; }
    public void setHibernationOptions(HibernationOptions hibernationOptions) { this.hibernationOptions = hibernationOptions; }

    public MaintenanceOptions getMaintenanceOptions() { return maintenanceOptions; }
    public void setMaintenanceOptions(MaintenanceOptions maintenanceOptions) { this.maintenanceOptions = maintenanceOptions; }

    public PrivateDnsNameOptions getPrivateDnsNameOptions() { return privateDnsNameOptions; }
    public void setPrivateDnsNameOptions(PrivateDnsNameOptions privateDnsNameOptions) {
        this.privateDnsNameOptions = privateDnsNameOptions;
    }

    public CapacityReservationSpecification getCapacityReservationSpecification() {
        return capacityReservationSpecification;
    }
    public void setCapacityReservationSpecification(CapacityReservationSpecification capacityReservationSpecification) {
        this.capacityReservationSpecification = capacityReservationSpecification;
    }

    public List<String> getSecurityGroupIds() { return securityGroupIds; }
    public void setSecurityGroupIds(List<String> securityGroupIds) {
        this.securityGroupIds = securityGroupIds != null ? new ArrayList<>(securityGroupIds) : new ArrayList<>();
    }

    public List<BlockDeviceMapping> getBlockDeviceMappings() { return blockDeviceMappings; }
    public void setBlockDeviceMappings(List<BlockDeviceMapping> blockDeviceMappings) {
        this.blockDeviceMappings = blockDeviceMappings != null ? new ArrayList<>(blockDeviceMappings) : new ArrayList<>();
    }

    public List<NetworkInterface> getNetworkInterfaces() { return networkInterfaces; }
    public void setNetworkInterfaces(List<NetworkInterface> networkInterfaces) {
        this.networkInterfaces = networkInterfaces != null ? new ArrayList<>(networkInterfaces) : new ArrayList<>();
    }

    public List<TagSpecification> getTagSpecifications() { return tagSpecifications; }
    public void setTagSpecifications(List<TagSpecification> tagSpecifications) {
        this.tagSpecifications = tagSpecifications != null ? new ArrayList<>(tagSpecifications) : new ArrayList<>();
    }

    /** The tags a launch from this template applies to the instance it creates. */
    @JsonIgnore
    public List<Tag> getInstanceTags() {
        return tagsForResource("instance");
    }

    // ── Pre-unified-data schema migration ────────────────────────────────────────────────
    //
    // A version persisted before this PR stored the instance profile and instance tags under
    // these two keys directly on this class, rather than nested inside iamInstanceProfile /
    // tagSpecifications. Without the setters below, ignoreUnknown silently drops both keys and
    // an upgraded Floci launches from that version with no IAM profile and no instance tags.
    // Deserialize-only: there is no getter for either key, so a file saved today never writes
    // this shape back out. Guarded so a current-schema record — which never carries these keys —
    // can't have a stray legacy key fight the real field, whichever order they load in.

    @JsonSetter("iamInstanceProfileArn")
    public void setLegacyIamInstanceProfileArn(String arn) {
        if (arn != null && !arn.isBlank() && iamInstanceProfile == null) {
            iamInstanceProfile = new IamInstanceProfile(arn, null);
        }
    }

    @JsonSetter("instanceTags")
    public void setLegacyInstanceTags(List<Tag> tags) {
        if (tags != null && !tags.isEmpty() && tagSpecifications.isEmpty()) {
            tagSpecifications.add(new TagSpecification("instance", tags));
        }
    }

    @JsonIgnore
    public List<Tag> tagsForResource(String resourceType) {
        List<Tag> result = new ArrayList<>();
        for (TagSpecification spec : tagSpecifications) {
            if (resourceType.equals(spec.getResourceType())) {
                result.addAll(spec.getTags());
            }
        }
        return result;
    }

    /**
     * The security groups a launch from this template applies. On AWS, top-level
     * {@code SecurityGroupIds} and {@code NetworkInterfaces[].Groups} are mutually exclusive, so at
     * most one of the two is populated. Resolving them here — rather than folding the interface
     * groups into the stored top-level list — is what keeps a NetworkInterfaces block readable back
     * as a NetworkInterfaces block.
     */
    @JsonIgnore
    public List<String> effectiveSecurityGroupIds() {
        if (!securityGroupIds.isEmpty()) {
            return List.copyOf(securityGroupIds);
        }
        List<String> fromInterfaces = new ArrayList<>();
        for (NetworkInterface networkInterface : networkInterfaces) {
            for (String group : networkInterface.getGroups()) {
                if (!fromInterfaces.contains(group)) {
                    fromInterfaces.add(group);
                }
            }
        }
        return fromInterfaces;
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IamInstanceProfile {
        private String arn;
        private String name;

        public IamInstanceProfile() {}

        public IamInstanceProfile(String arn, String name) {
            this.arn = arn;
            this.name = name;
        }

        public String getArn() { return arn; }
        public void setArn(String arn) { this.arn = arn; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BlockDeviceMapping {
        private String deviceName;
        private String virtualName;
        private String noDevice;
        private Ebs ebs;

        public String getDeviceName() { return deviceName; }
        public void setDeviceName(String deviceName) { this.deviceName = deviceName; }

        public String getVirtualName() { return virtualName; }
        public void setVirtualName(String virtualName) { this.virtualName = virtualName; }

        public String getNoDevice() { return noDevice; }
        public void setNoDevice(String noDevice) { this.noDevice = noDevice; }

        public Ebs getEbs() { return ebs; }
        public void setEbs(Ebs ebs) { this.ebs = ebs; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Ebs {
        private Boolean encrypted;
        private Boolean deleteOnTermination;
        private Integer iops;
        private String kmsKeyId;
        private String snapshotId;
        private Integer volumeSize;
        private String volumeType;
        private Integer throughput;

        public Boolean getEncrypted() { return encrypted; }
        public void setEncrypted(Boolean encrypted) { this.encrypted = encrypted; }

        public Boolean getDeleteOnTermination() { return deleteOnTermination; }
        public void setDeleteOnTermination(Boolean deleteOnTermination) { this.deleteOnTermination = deleteOnTermination; }

        public Integer getIops() { return iops; }
        public void setIops(Integer iops) { this.iops = iops; }

        public String getKmsKeyId() { return kmsKeyId; }
        public void setKmsKeyId(String kmsKeyId) { this.kmsKeyId = kmsKeyId; }

        public String getSnapshotId() { return snapshotId; }
        public void setSnapshotId(String snapshotId) { this.snapshotId = snapshotId; }

        public Integer getVolumeSize() { return volumeSize; }
        public void setVolumeSize(Integer volumeSize) { this.volumeSize = volumeSize; }

        public String getVolumeType() { return volumeType; }
        public void setVolumeType(String volumeType) { this.volumeType = volumeType; }

        public Integer getThroughput() { return throughput; }
        public void setThroughput(Integer throughput) { this.throughput = throughput; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MetadataOptions {
        private String state;
        private String httpTokens;
        private Integer httpPutResponseHopLimit;
        private String httpEndpoint;
        private String httpProtocolIpv6;
        private String instanceMetadataTags;

        public String getState() { return state; }
        public void setState(String state) { this.state = state; }

        public String getHttpTokens() { return httpTokens; }
        public void setHttpTokens(String httpTokens) { this.httpTokens = httpTokens; }

        public Integer getHttpPutResponseHopLimit() { return httpPutResponseHopLimit; }
        public void setHttpPutResponseHopLimit(Integer httpPutResponseHopLimit) {
            this.httpPutResponseHopLimit = httpPutResponseHopLimit;
        }

        public String getHttpEndpoint() { return httpEndpoint; }
        public void setHttpEndpoint(String httpEndpoint) { this.httpEndpoint = httpEndpoint; }

        public String getHttpProtocolIpv6() { return httpProtocolIpv6; }
        public void setHttpProtocolIpv6(String httpProtocolIpv6) { this.httpProtocolIpv6 = httpProtocolIpv6; }

        public String getInstanceMetadataTags() { return instanceMetadataTags; }
        public void setInstanceMetadataTags(String instanceMetadataTags) { this.instanceMetadataTags = instanceMetadataTags; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NetworkInterface {
        private Boolean associatePublicIpAddress;
        private Boolean associateCarrierIpAddress;
        private Boolean deleteOnTermination;
        private String description;
        private Integer deviceIndex;
        private String interfaceType;
        private Integer ipv6AddressCount;
        private String networkInterfaceId;
        private String privateIpAddress;
        private Integer secondaryPrivateIpAddressCount;
        private String subnetId;
        private Integer networkCardIndex;
        private List<String> groups = new ArrayList<>();

        public Boolean getAssociatePublicIpAddress() { return associatePublicIpAddress; }
        public void setAssociatePublicIpAddress(Boolean associatePublicIpAddress) {
            this.associatePublicIpAddress = associatePublicIpAddress;
        }

        public Boolean getAssociateCarrierIpAddress() { return associateCarrierIpAddress; }
        public void setAssociateCarrierIpAddress(Boolean associateCarrierIpAddress) {
            this.associateCarrierIpAddress = associateCarrierIpAddress;
        }

        public Boolean getDeleteOnTermination() { return deleteOnTermination; }
        public void setDeleteOnTermination(Boolean deleteOnTermination) { this.deleteOnTermination = deleteOnTermination; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public Integer getDeviceIndex() { return deviceIndex; }
        public void setDeviceIndex(Integer deviceIndex) { this.deviceIndex = deviceIndex; }

        public String getInterfaceType() { return interfaceType; }
        public void setInterfaceType(String interfaceType) { this.interfaceType = interfaceType; }

        public Integer getIpv6AddressCount() { return ipv6AddressCount; }
        public void setIpv6AddressCount(Integer ipv6AddressCount) { this.ipv6AddressCount = ipv6AddressCount; }

        public String getNetworkInterfaceId() { return networkInterfaceId; }
        public void setNetworkInterfaceId(String networkInterfaceId) { this.networkInterfaceId = networkInterfaceId; }

        public String getPrivateIpAddress() { return privateIpAddress; }
        public void setPrivateIpAddress(String privateIpAddress) { this.privateIpAddress = privateIpAddress; }

        public Integer getSecondaryPrivateIpAddressCount() { return secondaryPrivateIpAddressCount; }
        public void setSecondaryPrivateIpAddressCount(Integer secondaryPrivateIpAddressCount) {
            this.secondaryPrivateIpAddressCount = secondaryPrivateIpAddressCount;
        }

        public String getSubnetId() { return subnetId; }
        public void setSubnetId(String subnetId) { this.subnetId = subnetId; }

        public Integer getNetworkCardIndex() { return networkCardIndex; }
        public void setNetworkCardIndex(Integer networkCardIndex) { this.networkCardIndex = networkCardIndex; }

        public List<String> getGroups() { return groups; }
        public void setGroups(List<String> groups) {
            this.groups = groups != null ? new ArrayList<>(groups) : new ArrayList<>();
        }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TagSpecification {
        private String resourceType;
        private List<Tag> tags = new ArrayList<>();

        public TagSpecification() {}

        public TagSpecification(String resourceType, List<Tag> tags) {
            this.resourceType = resourceType;
            setTags(tags);
        }

        public String getResourceType() { return resourceType; }
        public void setResourceType(String resourceType) { this.resourceType = resourceType; }

        public List<Tag> getTags() { return tags; }
        public void setTags(List<Tag> tags) {
            this.tags = tags != null ? new ArrayList<>(tags) : new ArrayList<>();
        }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Monitoring {
        private Boolean enabled;

        public Boolean getEnabled() { return enabled; }
        public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EnclaveOptions {
        private Boolean enabled;

        public Boolean getEnabled() { return enabled; }
        public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HibernationOptions {
        private Boolean configured;

        public Boolean getConfigured() { return configured; }
        public void setConfigured(Boolean configured) { this.configured = configured; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MaintenanceOptions {
        private String autoRecovery;

        public String getAutoRecovery() { return autoRecovery; }
        public void setAutoRecovery(String autoRecovery) { this.autoRecovery = autoRecovery; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CreditSpecification {
        private String cpuCredits;

        public String getCpuCredits() { return cpuCredits; }
        public void setCpuCredits(String cpuCredits) { this.cpuCredits = cpuCredits; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CpuOptions {
        private Integer coreCount;
        private Integer threadsPerCore;
        private String amdSevSnp;

        public Integer getCoreCount() { return coreCount; }
        public void setCoreCount(Integer coreCount) { this.coreCount = coreCount; }

        public Integer getThreadsPerCore() { return threadsPerCore; }
        public void setThreadsPerCore(Integer threadsPerCore) { this.threadsPerCore = threadsPerCore; }

        public String getAmdSevSnp() { return amdSevSnp; }
        public void setAmdSevSnp(String amdSevSnp) { this.amdSevSnp = amdSevSnp; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Placement {
        private String availabilityZone;
        private String availabilityZoneId;
        private String affinity;
        private String groupName;
        private String groupId;
        private String hostId;
        private String tenancy;
        private String spreadDomain;
        private String hostResourceGroupArn;
        private Integer partitionNumber;

        public String getAvailabilityZone() { return availabilityZone; }
        public void setAvailabilityZone(String availabilityZone) { this.availabilityZone = availabilityZone; }

        public String getAvailabilityZoneId() { return availabilityZoneId; }
        public void setAvailabilityZoneId(String availabilityZoneId) { this.availabilityZoneId = availabilityZoneId; }

        public String getAffinity() { return affinity; }
        public void setAffinity(String affinity) { this.affinity = affinity; }

        public String getGroupName() { return groupName; }
        public void setGroupName(String groupName) { this.groupName = groupName; }

        public String getGroupId() { return groupId; }
        public void setGroupId(String groupId) { this.groupId = groupId; }

        public String getHostId() { return hostId; }
        public void setHostId(String hostId) { this.hostId = hostId; }

        public String getTenancy() { return tenancy; }
        public void setTenancy(String tenancy) { this.tenancy = tenancy; }

        public String getSpreadDomain() { return spreadDomain; }
        public void setSpreadDomain(String spreadDomain) { this.spreadDomain = spreadDomain; }

        public String getHostResourceGroupArn() { return hostResourceGroupArn; }
        public void setHostResourceGroupArn(String hostResourceGroupArn) { this.hostResourceGroupArn = hostResourceGroupArn; }

        public Integer getPartitionNumber() { return partitionNumber; }
        public void setPartitionNumber(Integer partitionNumber) { this.partitionNumber = partitionNumber; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PrivateDnsNameOptions {
        private String hostnameType;
        private Boolean enableResourceNameDnsARecord;
        private Boolean enableResourceNameDnsAAAARecord;

        public String getHostnameType() { return hostnameType; }
        public void setHostnameType(String hostnameType) { this.hostnameType = hostnameType; }

        public Boolean getEnableResourceNameDnsARecord() { return enableResourceNameDnsARecord; }
        public void setEnableResourceNameDnsARecord(Boolean enableResourceNameDnsARecord) {
            this.enableResourceNameDnsARecord = enableResourceNameDnsARecord;
        }

        public Boolean getEnableResourceNameDnsAAAARecord() { return enableResourceNameDnsAAAARecord; }
        public void setEnableResourceNameDnsAAAARecord(Boolean enableResourceNameDnsAAAARecord) {
            this.enableResourceNameDnsAAAARecord = enableResourceNameDnsAAAARecord;
        }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CapacityReservationSpecification {
        private String capacityReservationPreference;
        private CapacityReservationTarget capacityReservationTarget;

        public String getCapacityReservationPreference() { return capacityReservationPreference; }
        public void setCapacityReservationPreference(String capacityReservationPreference) {
            this.capacityReservationPreference = capacityReservationPreference;
        }

        public CapacityReservationTarget getCapacityReservationTarget() { return capacityReservationTarget; }
        public void setCapacityReservationTarget(CapacityReservationTarget capacityReservationTarget) {
            this.capacityReservationTarget = capacityReservationTarget;
        }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CapacityReservationTarget {
        private String capacityReservationId;
        private String capacityReservationResourceGroupArn;

        public String getCapacityReservationId() { return capacityReservationId; }
        public void setCapacityReservationId(String capacityReservationId) {
            this.capacityReservationId = capacityReservationId;
        }

        public String getCapacityReservationResourceGroupArn() { return capacityReservationResourceGroupArn; }
        public void setCapacityReservationResourceGroupArn(String capacityReservationResourceGroupArn) {
            this.capacityReservationResourceGroupArn = capacityReservationResourceGroupArn;
        }
    }
}
