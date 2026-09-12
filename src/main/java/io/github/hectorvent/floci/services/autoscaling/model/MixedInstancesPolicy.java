package io.github.hectorvent.floci.services.autoscaling.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class MixedInstancesPolicy {
    private LaunchTemplate launchTemplate;
    private InstancesDistribution instancesDistribution;

    public MixedInstancesPolicy() {}

    public LaunchTemplate getLaunchTemplate() { return launchTemplate; }
    public void setLaunchTemplate(LaunchTemplate v) { this.launchTemplate = v; }

    public InstancesDistribution getInstancesDistribution() { return instancesDistribution; }
    public void setInstancesDistribution(InstancesDistribution v) { this.instancesDistribution = v; }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LaunchTemplate {
        private LaunchTemplateSpecification launchTemplateSpecification;
        private List<LaunchTemplateOverride> overrides = new ArrayList<>();

        public LaunchTemplate() {}

        public LaunchTemplateSpecification getLaunchTemplateSpecification() { return launchTemplateSpecification; }
        public void setLaunchTemplateSpecification(LaunchTemplateSpecification v) { this.launchTemplateSpecification = v; }

        public List<LaunchTemplateOverride> getOverrides() { return overrides; }
        public void setOverrides(List<LaunchTemplateOverride> v) { this.overrides = v; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LaunchTemplateSpecification {
        private String launchTemplateId;
        private String launchTemplateName;
        private String version;

        public LaunchTemplateSpecification() {}

        public String getLaunchTemplateId() { return launchTemplateId; }
        public void setLaunchTemplateId(String v) { this.launchTemplateId = v; }

        public String getLaunchTemplateName() { return launchTemplateName; }
        public void setLaunchTemplateName(String v) { this.launchTemplateName = v; }

        public String getVersion() { return version; }
        public void setVersion(String v) { this.version = v; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LaunchTemplateOverride {
        private String instanceType;
        private InstanceRequirements instanceRequirements;

        public LaunchTemplateOverride() {}

        public String getInstanceType() { return instanceType; }
        public void setInstanceType(String v) { this.instanceType = v; }

        public InstanceRequirements getInstanceRequirements() { return instanceRequirements; }
        public void setInstanceRequirements(InstanceRequirements v) { this.instanceRequirements = v; }
    }

    /**
     * Attribute-based instance type selection for one launch template override, the alternative
     * to naming an explicit {@code InstanceType}. AWS rejects a request that sets both.
     *
     * <p>Covers every member of botocore's {@code InstanceRequirements} shape except
     * {@code BaselinePerformanceFactors}.
     */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class InstanceRequirements {
        private IntRange vCpuCount;
        private IntRange memoryMiB;
        private IntRange networkInterfaceCount;
        private IntRange acceleratorCount;
        private IntRange acceleratorTotalMemoryMiB;
        private IntRange baselineEbsBandwidthMbps;
        private DoubleRange memoryGiBPerVCpu;
        private DoubleRange totalLocalStorageGB;
        private DoubleRange networkBandwidthGbps;
        private List<String> cpuManufacturers = new ArrayList<>();
        private List<String> excludedInstanceTypes = new ArrayList<>();
        private List<String> instanceGenerations = new ArrayList<>();
        private List<String> localStorageTypes = new ArrayList<>();
        private List<String> acceleratorTypes = new ArrayList<>();
        private List<String> acceleratorManufacturers = new ArrayList<>();
        private List<String> acceleratorNames = new ArrayList<>();
        private List<String> allowedInstanceTypes = new ArrayList<>();
        private Integer spotMaxPricePercentageOverLowestPrice;
        private Integer maxSpotPriceAsPercentageOfOptimalOnDemandPrice;
        private Integer onDemandMaxPricePercentageOverLowestPrice;
        private String bareMetal;
        private String burstablePerformance;
        private String localStorage;
        private Boolean requireHibernateSupport;

        public InstanceRequirements() {}

        public IntRange getVCpuCount() { return vCpuCount; }
        public void setVCpuCount(IntRange v) { this.vCpuCount = v; }

        public IntRange getMemoryMiB() { return memoryMiB; }
        public void setMemoryMiB(IntRange v) { this.memoryMiB = v; }

        public IntRange getNetworkInterfaceCount() { return networkInterfaceCount; }
        public void setNetworkInterfaceCount(IntRange v) { this.networkInterfaceCount = v; }

        public IntRange getAcceleratorCount() { return acceleratorCount; }
        public void setAcceleratorCount(IntRange v) { this.acceleratorCount = v; }

        public IntRange getAcceleratorTotalMemoryMiB() { return acceleratorTotalMemoryMiB; }
        public void setAcceleratorTotalMemoryMiB(IntRange v) { this.acceleratorTotalMemoryMiB = v; }

        public IntRange getBaselineEbsBandwidthMbps() { return baselineEbsBandwidthMbps; }
        public void setBaselineEbsBandwidthMbps(IntRange v) { this.baselineEbsBandwidthMbps = v; }

        public DoubleRange getMemoryGiBPerVCpu() { return memoryGiBPerVCpu; }
        public void setMemoryGiBPerVCpu(DoubleRange v) { this.memoryGiBPerVCpu = v; }

        public DoubleRange getTotalLocalStorageGB() { return totalLocalStorageGB; }
        public void setTotalLocalStorageGB(DoubleRange v) { this.totalLocalStorageGB = v; }

        public DoubleRange getNetworkBandwidthGbps() { return networkBandwidthGbps; }
        public void setNetworkBandwidthGbps(DoubleRange v) { this.networkBandwidthGbps = v; }

        public List<String> getCpuManufacturers() { return cpuManufacturers; }
        public void setCpuManufacturers(List<String> v) { this.cpuManufacturers = v != null ? new ArrayList<>(v) : new ArrayList<>(); }

        public List<String> getExcludedInstanceTypes() { return excludedInstanceTypes; }
        public void setExcludedInstanceTypes(List<String> v) { this.excludedInstanceTypes = v != null ? new ArrayList<>(v) : new ArrayList<>(); }

        public List<String> getInstanceGenerations() { return instanceGenerations; }
        public void setInstanceGenerations(List<String> v) { this.instanceGenerations = v != null ? new ArrayList<>(v) : new ArrayList<>(); }

        public List<String> getLocalStorageTypes() { return localStorageTypes; }
        public void setLocalStorageTypes(List<String> v) { this.localStorageTypes = v != null ? new ArrayList<>(v) : new ArrayList<>(); }

        public List<String> getAcceleratorTypes() { return acceleratorTypes; }
        public void setAcceleratorTypes(List<String> v) { this.acceleratorTypes = v != null ? new ArrayList<>(v) : new ArrayList<>(); }

        public List<String> getAcceleratorManufacturers() { return acceleratorManufacturers; }
        public void setAcceleratorManufacturers(List<String> v) { this.acceleratorManufacturers = v != null ? new ArrayList<>(v) : new ArrayList<>(); }

        public List<String> getAcceleratorNames() { return acceleratorNames; }
        public void setAcceleratorNames(List<String> v) { this.acceleratorNames = v != null ? new ArrayList<>(v) : new ArrayList<>(); }

        public List<String> getAllowedInstanceTypes() { return allowedInstanceTypes; }
        public void setAllowedInstanceTypes(List<String> v) { this.allowedInstanceTypes = v != null ? new ArrayList<>(v) : new ArrayList<>(); }

        public Integer getSpotMaxPricePercentageOverLowestPrice() { return spotMaxPricePercentageOverLowestPrice; }
        public void setSpotMaxPricePercentageOverLowestPrice(Integer v) { this.spotMaxPricePercentageOverLowestPrice = v; }

        public Integer getMaxSpotPriceAsPercentageOfOptimalOnDemandPrice() { return maxSpotPriceAsPercentageOfOptimalOnDemandPrice; }
        public void setMaxSpotPriceAsPercentageOfOptimalOnDemandPrice(Integer v) { this.maxSpotPriceAsPercentageOfOptimalOnDemandPrice = v; }

        public Integer getOnDemandMaxPricePercentageOverLowestPrice() { return onDemandMaxPricePercentageOverLowestPrice; }
        public void setOnDemandMaxPricePercentageOverLowestPrice(Integer v) { this.onDemandMaxPricePercentageOverLowestPrice = v; }

        public String getBareMetal() { return bareMetal; }
        public void setBareMetal(String v) { this.bareMetal = v; }

        public String getBurstablePerformance() { return burstablePerformance; }
        public void setBurstablePerformance(String v) { this.burstablePerformance = v; }

        public String getLocalStorage() { return localStorage; }
        public void setLocalStorage(String v) { this.localStorage = v; }

        public Boolean getRequireHibernateSupport() { return requireHibernateSupport; }
        public void setRequireHibernateSupport(Boolean v) { this.requireHibernateSupport = v; }

        public boolean isEmpty() {
            return vCpuCount == null && memoryMiB == null && networkInterfaceCount == null
                    && acceleratorCount == null && acceleratorTotalMemoryMiB == null
                    && baselineEbsBandwidthMbps == null && memoryGiBPerVCpu == null
                    && totalLocalStorageGB == null && networkBandwidthGbps == null
                    && cpuManufacturers.isEmpty() && excludedInstanceTypes.isEmpty()
                    && instanceGenerations.isEmpty() && localStorageTypes.isEmpty()
                    && acceleratorTypes.isEmpty() && acceleratorManufacturers.isEmpty()
                    && acceleratorNames.isEmpty() && allowedInstanceTypes.isEmpty()
                    && spotMaxPricePercentageOverLowestPrice == null
                    && maxSpotPriceAsPercentageOfOptimalOnDemandPrice == null
                    && onDemandMaxPricePercentageOverLowestPrice == null && bareMetal == null
                    && burstablePerformance == null && localStorage == null
                    && requireHibernateSupport == null;
        }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IntRange {
        private Integer min;
        private Integer max;

        public IntRange() {}

        public Integer getMin() { return min; }
        public void setMin(Integer v) { this.min = v; }

        public Integer getMax() { return max; }
        public void setMax(Integer v) { this.max = v; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DoubleRange {
        private Double min;
        private Double max;

        public DoubleRange() {}

        public Double getMin() { return min; }
        public void setMin(Double v) { this.min = v; }

        public Double getMax() { return max; }
        public void setMax(Double v) { this.max = v; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class InstancesDistribution {
        private Integer onDemandBaseCapacity;
        private Integer onDemandPercentageAboveBaseCapacity;
        private String spotAllocationStrategy;

        public InstancesDistribution() {}

        public Integer getOnDemandBaseCapacity() { return onDemandBaseCapacity; }
        public void setOnDemandBaseCapacity(Integer v) { this.onDemandBaseCapacity = v; }

        public Integer getOnDemandPercentageAboveBaseCapacity() { return onDemandPercentageAboveBaseCapacity; }
        public void setOnDemandPercentageAboveBaseCapacity(Integer v) { this.onDemandPercentageAboveBaseCapacity = v; }

        public String getSpotAllocationStrategy() { return spotAllocationStrategy; }
        public void setSpotAllocationStrategy(String v) { this.spotAllocationStrategy = v; }
    }
}
