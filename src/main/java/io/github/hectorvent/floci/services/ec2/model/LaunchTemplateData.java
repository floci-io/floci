package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class LaunchTemplateData {

    private String imageId;
    private String instanceType;
    private String keyName;
    private String userData;
    private String encodedUserData;
    private String iamInstanceProfileArn;
    private List<String> securityGroupIds = new ArrayList<>();
    private List<Tag> instanceTags = new ArrayList<>();
    private MetadataOptions metadataOptions;
    private Boolean monitoringEnabled;

    // lex00/floci#119: LaunchTemplateData modeled only ~6 of AWS's ~30
    // documented RequestLaunchTemplateData/ResponseLaunchTemplateData fields,
    // so CreateLaunchTemplate/CreateLaunchTemplateVersion accepted and then
    // DROPPED the rest, and DescribeLaunchTemplateVersions could not echo
    // them back. Fields below close the gaps the issue named. Oracle:
    // botocore's ec2/2016-11-15/service-2.json RequestLaunchTemplateData /
    // ResponseLaunchTemplateData shapes.
    //
    // versionDescription isn't actually part of AWS's LaunchTemplateData
    // shape - it's CreateLaunchTemplate[Version]'s own top-level
    // VersionDescription, which floci models per-version by piggybacking it
    // on this class (the same class already serves as the per-version
    // record via LaunchTemplate.versions).
    private String versionDescription;
    private Boolean ebsOptimized;
    private List<BlockDeviceMapping> blockDeviceMappings = new ArrayList<>();
    private CapacityReservationSpecification capacityReservationSpecification;
    private CpuOptions cpuOptions;
    private InstanceMarketOptions instanceMarketOptions;
    private MaintenanceOptions maintenanceOptions;
    private List<NetworkInterfaceSpecification> networkInterfaces = new ArrayList<>();
    private Placement placement;
    private List<TagSpecification> tagSpecifications = new ArrayList<>();
    private InstanceRequirements instanceRequirements;

    public LaunchTemplateData() {}

    public LaunchTemplateData(LaunchTemplateData source) {
        this.imageId = source.imageId;
        this.instanceType = source.instanceType;
        this.keyName = source.keyName;
        this.userData = source.userData;
        this.encodedUserData = source.encodedUserData;
        this.iamInstanceProfileArn = source.iamInstanceProfileArn;
        this.securityGroupIds = new ArrayList<>(source.securityGroupIds);
        this.instanceTags = new ArrayList<>(source.instanceTags);
        this.metadataOptions = source.metadataOptions;
        this.monitoringEnabled = source.monitoringEnabled;
        this.versionDescription = source.versionDescription;
        this.ebsOptimized = source.ebsOptimized;
        this.blockDeviceMappings = new ArrayList<>(source.blockDeviceMappings);
        this.capacityReservationSpecification = source.capacityReservationSpecification;
        this.cpuOptions = source.cpuOptions;
        this.instanceMarketOptions = source.instanceMarketOptions;
        this.maintenanceOptions = source.maintenanceOptions;
        this.networkInterfaces = new ArrayList<>(source.networkInterfaces);
        this.placement = source.placement;
        this.tagSpecifications = new ArrayList<>(source.tagSpecifications);
        this.instanceRequirements = source.instanceRequirements;
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

    public String getIamInstanceProfileArn() { return iamInstanceProfileArn; }
    public void setIamInstanceProfileArn(String iamInstanceProfileArn) { this.iamInstanceProfileArn = iamInstanceProfileArn; }

    public List<String> getSecurityGroupIds() { return securityGroupIds; }
    public void setSecurityGroupIds(List<String> securityGroupIds) {
        this.securityGroupIds = securityGroupIds != null ? new ArrayList<>(securityGroupIds) : new ArrayList<>();
    }

    // lex00/floci#123: getSecurityGroupIds() above must stay the literal,
    // as-set top-level SecurityGroupIds list - DescribeLaunchTemplateVersions
    // echoes it back verbatim, and real AWS keeps it and
    // NetworkInterfaces[].Groups mutually exclusive (the terraform
    // provider's own docs describe vpc_security_group_ids and
    // network_interfaces.security_groups that way). RunInstances resolving
    // a launch template into an actual instance's security groups, though,
    // legitimately needs the UNION of both - a template that only ever set
    // groups per-network-interface still has to launch into those groups -
    // so that internal-only resolution gets its own method rather than
    // reusing (and re-polluting) the wire-facing field.
    public List<String> getEffectiveSecurityGroupIds() {
        java.util.LinkedHashSet<String> effective = new java.util.LinkedHashSet<>(securityGroupIds);
        for (NetworkInterfaceSpecification ni : networkInterfaces) {
            effective.addAll(ni.getGroups());
        }
        return new ArrayList<>(effective);
    }

    public List<Tag> getInstanceTags() { return instanceTags; }
    public void setInstanceTags(List<Tag> instanceTags) {
        this.instanceTags = instanceTags != null ? new ArrayList<>(instanceTags) : new ArrayList<>();
    }

    public MetadataOptions getMetadataOptions() { return metadataOptions; }
    public void setMetadataOptions(MetadataOptions metadataOptions) { this.metadataOptions = metadataOptions; }

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

    public CapacityReservationSpecification getCapacityReservationSpecification() { return capacityReservationSpecification; }
    public void setCapacityReservationSpecification(CapacityReservationSpecification v) { this.capacityReservationSpecification = v; }

    public CpuOptions getCpuOptions() { return cpuOptions; }
    public void setCpuOptions(CpuOptions cpuOptions) { this.cpuOptions = cpuOptions; }

    public InstanceMarketOptions getInstanceMarketOptions() { return instanceMarketOptions; }
    public void setInstanceMarketOptions(InstanceMarketOptions v) { this.instanceMarketOptions = v; }

    public MaintenanceOptions getMaintenanceOptions() { return maintenanceOptions; }
    public void setMaintenanceOptions(MaintenanceOptions maintenanceOptions) { this.maintenanceOptions = maintenanceOptions; }

    public List<NetworkInterfaceSpecification> getNetworkInterfaces() { return networkInterfaces; }
    public void setNetworkInterfaces(List<NetworkInterfaceSpecification> networkInterfaces) {
        this.networkInterfaces = networkInterfaces != null ? new ArrayList<>(networkInterfaces) : new ArrayList<>();
    }

    public Placement getPlacement() { return placement; }
    public void setPlacement(Placement placement) { this.placement = placement; }

    public List<TagSpecification> getTagSpecifications() { return tagSpecifications; }
    public void setTagSpecifications(List<TagSpecification> tagSpecifications) {
        this.tagSpecifications = tagSpecifications != null ? new ArrayList<>(tagSpecifications) : new ArrayList<>();
    }

    public InstanceRequirements getInstanceRequirements() { return instanceRequirements; }
    public void setInstanceRequirements(InstanceRequirements instanceRequirements) { this.instanceRequirements = instanceRequirements; }

    // aws_launch_template's metadata_options and monitoring blocks
    // (DescribeLaunchTemplateVersions.LaunchTemplateData.MetadataOptions /
    // .Monitoring in the real API) had no field on this model at all, so
    // @JsonIgnoreProperties(ignoreUnknown = true) silently dropped both on
    // create and DescribeLaunchTemplateVersions echoed back neither -
    // confirmed directly against this floci build with the AWS CLI, no
    // Terraform in the loop: CreateLaunchTemplate accepted both, and the
    // immediate DescribeLaunchTemplateVersions omitted them entirely.
    // Surfaced by live/e2e/corpus-autoscaling-complete: every module call
    // in that crossing's example sets metadata_options (the module's own
    // variable default is non-null), so a stateless replan proposed
    // `+ metadata_options {...}` and `+ monitoring {...}` on every launch
    // template, forever.
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MetadataOptions {
        private String httpEndpoint;
        private String httpProtocolIpv6;
        private Integer httpPutResponseHopLimit;
        private String httpTokens;
        private String instanceMetadataTags;

        public MetadataOptions() {}

        public String getHttpEndpoint() { return httpEndpoint; }
        public void setHttpEndpoint(String v) { this.httpEndpoint = v; }

        public String getHttpProtocolIpv6() { return httpProtocolIpv6; }
        public void setHttpProtocolIpv6(String v) { this.httpProtocolIpv6 = v; }

        public Integer getHttpPutResponseHopLimit() { return httpPutResponseHopLimit; }
        public void setHttpPutResponseHopLimit(Integer v) { this.httpPutResponseHopLimit = v; }

        public String getHttpTokens() { return httpTokens; }
        public void setHttpTokens(String v) { this.httpTokens = v; }

        public String getInstanceMetadataTags() { return instanceMetadataTags; }
        public void setInstanceMetadataTags(String v) { this.instanceMetadataTags = v; }

        public boolean isEmpty() {
            return httpEndpoint == null && httpProtocolIpv6 == null && httpPutResponseHopLimit == null
                    && httpTokens == null && instanceMetadataTags == null;
        }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CapacityReservationTarget {
        private String capacityReservationId;
        private String capacityReservationResourceGroupArn;

        public String getCapacityReservationId() { return capacityReservationId; }
        public void setCapacityReservationId(String v) { this.capacityReservationId = v; }

        public String getCapacityReservationResourceGroupArn() { return capacityReservationResourceGroupArn; }
        public void setCapacityReservationResourceGroupArn(String v) { this.capacityReservationResourceGroupArn = v; }

        public boolean isEmpty() { return capacityReservationId == null && capacityReservationResourceGroupArn == null; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CapacityReservationSpecification {
        private String capacityReservationPreference;
        private CapacityReservationTarget capacityReservationTarget;

        public String getCapacityReservationPreference() { return capacityReservationPreference; }
        public void setCapacityReservationPreference(String v) { this.capacityReservationPreference = v; }

        public CapacityReservationTarget getCapacityReservationTarget() { return capacityReservationTarget; }
        public void setCapacityReservationTarget(CapacityReservationTarget v) { this.capacityReservationTarget = v; }

        public boolean isEmpty() {
            return capacityReservationPreference == null
                    && (capacityReservationTarget == null || capacityReservationTarget.isEmpty());
        }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CpuOptions {
        private Integer coreCount;
        private Integer threadsPerCore;
        private String amdSevSnp;

        public Integer getCoreCount() { return coreCount; }
        public void setCoreCount(Integer v) { this.coreCount = v; }

        public Integer getThreadsPerCore() { return threadsPerCore; }
        public void setThreadsPerCore(Integer v) { this.threadsPerCore = v; }

        public String getAmdSevSnp() { return amdSevSnp; }
        public void setAmdSevSnp(String v) { this.amdSevSnp = v; }

        public boolean isEmpty() { return coreCount == null && threadsPerCore == null && amdSevSnp == null; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SpotOptions {
        private String maxPrice;
        private String spotInstanceType;
        private Integer blockDurationMinutes;
        private String validUntil;
        private String instanceInterruptionBehavior;

        public String getMaxPrice() { return maxPrice; }
        public void setMaxPrice(String v) { this.maxPrice = v; }

        public String getSpotInstanceType() { return spotInstanceType; }
        public void setSpotInstanceType(String v) { this.spotInstanceType = v; }

        public Integer getBlockDurationMinutes() { return blockDurationMinutes; }
        public void setBlockDurationMinutes(Integer v) { this.blockDurationMinutes = v; }

        public String getValidUntil() { return validUntil; }
        public void setValidUntil(String v) { this.validUntil = v; }

        public String getInstanceInterruptionBehavior() { return instanceInterruptionBehavior; }
        public void setInstanceInterruptionBehavior(String v) { this.instanceInterruptionBehavior = v; }

        public boolean isEmpty() {
            return maxPrice == null && spotInstanceType == null && blockDurationMinutes == null
                    && validUntil == null && instanceInterruptionBehavior == null;
        }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class InstanceMarketOptions {
        private String marketType;
        private SpotOptions spotOptions;

        public String getMarketType() { return marketType; }
        public void setMarketType(String v) { this.marketType = v; }

        public SpotOptions getSpotOptions() { return spotOptions; }
        public void setSpotOptions(SpotOptions v) { this.spotOptions = v; }

        public boolean isEmpty() { return marketType == null && (spotOptions == null || spotOptions.isEmpty()); }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MaintenanceOptions {
        private String autoRecovery;

        public String getAutoRecovery() { return autoRecovery; }
        public void setAutoRecovery(String v) { this.autoRecovery = v; }

        public boolean isEmpty() { return autoRecovery == null; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NetworkInterfaceSpecification {
        private Integer deviceIndex;
        private String subnetId;
        private List<String> groups = new ArrayList<>();
        private Boolean associatePublicIpAddress;
        private Boolean deleteOnTermination;
        private String description;
        private String privateIpAddress;
        private Integer networkCardIndex;
        private String interfaceType;
        private Integer ipv6AddressCount;
        private String networkInterfaceId;
        // lex00/floci#119's round-5 re-measure (choudoufu's own round-5 repin, ae2a613b25):
        // a network interface's ConnectionTrackingSpecification (TcpEstablishedTimeout/
        // UdpStreamTimeout/UdpTimeout) had no field at all, so
        // DescribeLaunchTemplateVersions could never echo it back. Oracle: botocore's
        // ec2/2016-11-15/service-2.json ConnectionTrackingSpecificationRequest/-Response shapes.
        private ConnectionTrackingSpecification connectionTrackingSpecification;

        public Integer getDeviceIndex() { return deviceIndex; }
        public void setDeviceIndex(Integer v) { this.deviceIndex = v; }

        public String getSubnetId() { return subnetId; }
        public void setSubnetId(String v) { this.subnetId = v; }

        public List<String> getGroups() { return groups; }
        public void setGroups(List<String> groups) { this.groups = groups != null ? new ArrayList<>(groups) : new ArrayList<>(); }

        public Boolean getAssociatePublicIpAddress() { return associatePublicIpAddress; }
        public void setAssociatePublicIpAddress(Boolean v) { this.associatePublicIpAddress = v; }

        public Boolean getDeleteOnTermination() { return deleteOnTermination; }
        public void setDeleteOnTermination(Boolean v) { this.deleteOnTermination = v; }

        public String getDescription() { return description; }
        public void setDescription(String v) { this.description = v; }

        public String getPrivateIpAddress() { return privateIpAddress; }
        public void setPrivateIpAddress(String v) { this.privateIpAddress = v; }

        public Integer getNetworkCardIndex() { return networkCardIndex; }
        public void setNetworkCardIndex(Integer v) { this.networkCardIndex = v; }

        public String getInterfaceType() { return interfaceType; }
        public void setInterfaceType(String v) { this.interfaceType = v; }

        public Integer getIpv6AddressCount() { return ipv6AddressCount; }
        public void setIpv6AddressCount(Integer v) { this.ipv6AddressCount = v; }

        public String getNetworkInterfaceId() { return networkInterfaceId; }
        public void setNetworkInterfaceId(String v) { this.networkInterfaceId = v; }

        public ConnectionTrackingSpecification getConnectionTrackingSpecification() { return connectionTrackingSpecification; }
        public void setConnectionTrackingSpecification(ConnectionTrackingSpecification v) { this.connectionTrackingSpecification = v; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ConnectionTrackingSpecification {
        private Integer tcpEstablishedTimeout;
        private Integer udpStreamTimeout;
        private Integer udpTimeout;

        public Integer getTcpEstablishedTimeout() { return tcpEstablishedTimeout; }
        public void setTcpEstablishedTimeout(Integer v) { this.tcpEstablishedTimeout = v; }

        public Integer getUdpStreamTimeout() { return udpStreamTimeout; }
        public void setUdpStreamTimeout(Integer v) { this.udpStreamTimeout = v; }

        public Integer getUdpTimeout() { return udpTimeout; }
        public void setUdpTimeout(Integer v) { this.udpTimeout = v; }

        public boolean isEmpty() {
            return tcpEstablishedTimeout == null && udpStreamTimeout == null && udpTimeout == null;
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
            this.tags = tags != null ? new ArrayList<>(tags) : new ArrayList<>();
        }

        public String getResourceType() { return resourceType; }
        public void setResourceType(String v) { this.resourceType = v; }

        public List<Tag> getTags() { return tags; }
        public void setTags(List<Tag> tags) { this.tags = tags != null ? new ArrayList<>(tags) : new ArrayList<>(); }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IntRange {
        private Integer min;
        private Integer max;

        public IntRange() {}
        public IntRange(Integer min, Integer max) { this.min = min; this.max = max; }

        public Integer getMin() { return min; }
        public void setMin(Integer v) { this.min = v; }

        public Integer getMax() { return max; }
        public void setMax(Integer v) { this.max = v; }

        public boolean isEmpty() { return min == null && max == null; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DoubleRange {
        private Double min;
        private Double max;

        public DoubleRange() {}
        public DoubleRange(Double min, Double max) { this.min = min; this.max = max; }

        public Double getMin() { return min; }
        public void setMin(Double v) { this.min = v; }

        public Double getMax() { return max; }
        public void setMax(Double v) { this.max = v; }

        public boolean isEmpty() { return min == null && max == null; }
    }

    // Covers the subset of InstanceRequirements(Request) that terraform's
    // aws_launch_template instance_requirements block commonly sets.
    // Deliberately NOT covered (real, but rarer/newer AWS fields, left as a
    // disclaimed gap the same way #119's own branch disclaimed
    // TargetTrackingConfiguration.CustomizedMetricSpecification):
    // BaselinePerformanceFactors, RequireEncryptionInTransit.
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class InstanceRequirements {
        private IntRange vCpuCount;
        private IntRange memoryMiB;
        private List<String> cpuManufacturers = new ArrayList<>();
        private DoubleRange memoryGiBPerVCpu;
        private List<String> excludedInstanceTypes = new ArrayList<>();
        private List<String> instanceGenerations = new ArrayList<>();
        private Integer spotMaxPricePercentageOverLowestPrice;
        private Integer onDemandMaxPricePercentageOverLowestPrice;
        private String bareMetal;
        private String burstablePerformance;
        private Boolean requireHibernateSupport;
        private IntRange networkInterfaceCount;
        private String localStorage;
        private List<String> localStorageTypes = new ArrayList<>();
        private DoubleRange totalLocalStorageGB;
        private IntRange baselineEbsBandwidthMbps;
        private List<String> acceleratorTypes = new ArrayList<>();
        private IntRange acceleratorCount;
        private List<String> acceleratorManufacturers = new ArrayList<>();
        private List<String> acceleratorNames = new ArrayList<>();
        private IntRange acceleratorTotalMemoryMiB;
        private DoubleRange networkBandwidthGbps;
        private List<String> allowedInstanceTypes = new ArrayList<>();
        private Integer maxSpotPriceAsPercentageOfOptimalOnDemandPrice;

        public IntRange getVCpuCount() { return vCpuCount; }
        public void setVCpuCount(IntRange v) { this.vCpuCount = v; }

        public IntRange getMemoryMiB() { return memoryMiB; }
        public void setMemoryMiB(IntRange v) { this.memoryMiB = v; }

        public List<String> getCpuManufacturers() { return cpuManufacturers; }
        public void setCpuManufacturers(List<String> v) { this.cpuManufacturers = v != null ? new ArrayList<>(v) : new ArrayList<>(); }

        public DoubleRange getMemoryGiBPerVCpu() { return memoryGiBPerVCpu; }
        public void setMemoryGiBPerVCpu(DoubleRange v) { this.memoryGiBPerVCpu = v; }

        public List<String> getExcludedInstanceTypes() { return excludedInstanceTypes; }
        public void setExcludedInstanceTypes(List<String> v) { this.excludedInstanceTypes = v != null ? new ArrayList<>(v) : new ArrayList<>(); }

        public List<String> getInstanceGenerations() { return instanceGenerations; }
        public void setInstanceGenerations(List<String> v) { this.instanceGenerations = v != null ? new ArrayList<>(v) : new ArrayList<>(); }

        public Integer getSpotMaxPricePercentageOverLowestPrice() { return spotMaxPricePercentageOverLowestPrice; }
        public void setSpotMaxPricePercentageOverLowestPrice(Integer v) { this.spotMaxPricePercentageOverLowestPrice = v; }

        public Integer getOnDemandMaxPricePercentageOverLowestPrice() { return onDemandMaxPricePercentageOverLowestPrice; }
        public void setOnDemandMaxPricePercentageOverLowestPrice(Integer v) { this.onDemandMaxPricePercentageOverLowestPrice = v; }

        public String getBareMetal() { return bareMetal; }
        public void setBareMetal(String v) { this.bareMetal = v; }

        public String getBurstablePerformance() { return burstablePerformance; }
        public void setBurstablePerformance(String v) { this.burstablePerformance = v; }

        public Boolean getRequireHibernateSupport() { return requireHibernateSupport; }
        public void setRequireHibernateSupport(Boolean v) { this.requireHibernateSupport = v; }

        public IntRange getNetworkInterfaceCount() { return networkInterfaceCount; }
        public void setNetworkInterfaceCount(IntRange v) { this.networkInterfaceCount = v; }

        public String getLocalStorage() { return localStorage; }
        public void setLocalStorage(String v) { this.localStorage = v; }

        public List<String> getLocalStorageTypes() { return localStorageTypes; }
        public void setLocalStorageTypes(List<String> v) { this.localStorageTypes = v != null ? new ArrayList<>(v) : new ArrayList<>(); }

        public DoubleRange getTotalLocalStorageGB() { return totalLocalStorageGB; }
        public void setTotalLocalStorageGB(DoubleRange v) { this.totalLocalStorageGB = v; }

        public IntRange getBaselineEbsBandwidthMbps() { return baselineEbsBandwidthMbps; }
        public void setBaselineEbsBandwidthMbps(IntRange v) { this.baselineEbsBandwidthMbps = v; }

        public List<String> getAcceleratorTypes() { return acceleratorTypes; }
        public void setAcceleratorTypes(List<String> v) { this.acceleratorTypes = v != null ? new ArrayList<>(v) : new ArrayList<>(); }

        public IntRange getAcceleratorCount() { return acceleratorCount; }
        public void setAcceleratorCount(IntRange v) { this.acceleratorCount = v; }

        public List<String> getAcceleratorManufacturers() { return acceleratorManufacturers; }
        public void setAcceleratorManufacturers(List<String> v) { this.acceleratorManufacturers = v != null ? new ArrayList<>(v) : new ArrayList<>(); }

        public List<String> getAcceleratorNames() { return acceleratorNames; }
        public void setAcceleratorNames(List<String> v) { this.acceleratorNames = v != null ? new ArrayList<>(v) : new ArrayList<>(); }

        public IntRange getAcceleratorTotalMemoryMiB() { return acceleratorTotalMemoryMiB; }
        public void setAcceleratorTotalMemoryMiB(IntRange v) { this.acceleratorTotalMemoryMiB = v; }

        public DoubleRange getNetworkBandwidthGbps() { return networkBandwidthGbps; }
        public void setNetworkBandwidthGbps(DoubleRange v) { this.networkBandwidthGbps = v; }

        public List<String> getAllowedInstanceTypes() { return allowedInstanceTypes; }
        public void setAllowedInstanceTypes(List<String> v) { this.allowedInstanceTypes = v != null ? new ArrayList<>(v) : new ArrayList<>(); }

        public Integer getMaxSpotPriceAsPercentageOfOptimalOnDemandPrice() { return maxSpotPriceAsPercentageOfOptimalOnDemandPrice; }
        public void setMaxSpotPriceAsPercentageOfOptimalOnDemandPrice(Integer v) { this.maxSpotPriceAsPercentageOfOptimalOnDemandPrice = v; }

        public boolean isEmpty() {
            return vCpuCount == null && memoryMiB == null && cpuManufacturers.isEmpty()
                    && memoryGiBPerVCpu == null && excludedInstanceTypes.isEmpty() && instanceGenerations.isEmpty()
                    && spotMaxPricePercentageOverLowestPrice == null && onDemandMaxPricePercentageOverLowestPrice == null
                    && bareMetal == null && burstablePerformance == null && requireHibernateSupport == null
                    && networkInterfaceCount == null && localStorage == null && localStorageTypes.isEmpty()
                    && totalLocalStorageGB == null && baselineEbsBandwidthMbps == null && acceleratorTypes.isEmpty()
                    && acceleratorCount == null && acceleratorManufacturers.isEmpty() && acceleratorNames.isEmpty()
                    && acceleratorTotalMemoryMiB == null && networkBandwidthGbps == null && allowedInstanceTypes.isEmpty()
                    && maxSpotPriceAsPercentageOfOptimalOnDemandPrice == null;
        }
    }
}
