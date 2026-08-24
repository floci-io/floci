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
        // lex00/floci#112: real per-override field (LaunchTemplateOverrides.WeightedCapacity in
        // botocore's autoscaling service model) that terraform-aws-autoscaling's
        // module.mixed_instance sets; previously dropped on create and never echoed back.
        private String weightedCapacity;
        // lex00/floci#112's round-5 re-measure: an attribute-based override
        // (LaunchTemplateOverrides.InstanceRequirements) - the alternative to naming an explicit
        // InstanceType, used by terraform-aws-autoscaling's own module.instance_requirements
        // example - had no field at all, and the parser's own loop used InstanceType's presence
        // as its sole "is there another override" signal, so an override that set ONLY
        // InstanceRequirements (mutually exclusive with InstanceType by AWS's own design) was
        // never even reached, dropping the whole Overrides list, not just this one field. Oracle:
        // botocore's autoscaling/2011-01-01/service-2.json LaunchTemplateOverrides/
        // InstanceRequirements shapes; scoped to the sub-fields terraform-aws-autoscaling's own
        // example actually sets rather than every field AWS documents.
        private InstanceRequirements instanceRequirements;

        public LaunchTemplateOverride() {}

        public String getInstanceType() { return instanceType; }
        public void setInstanceType(String v) { this.instanceType = v; }

        public String getWeightedCapacity() { return weightedCapacity; }
        public void setWeightedCapacity(String v) { this.weightedCapacity = v; }

        public InstanceRequirements getInstanceRequirements() { return instanceRequirements; }
        public void setInstanceRequirements(InstanceRequirements v) { this.instanceRequirements = v; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class InstanceRequirements {
        private IntRange vCpuCount;
        private IntRange memoryMiB;
        private List<String> cpuManufacturers = new ArrayList<>();
        private DoubleRange memoryGiBPerVCpu;
        private List<String> excludedInstanceTypes = new ArrayList<>();
        private List<String> instanceGenerations = new ArrayList<>();
        private List<String> localStorageTypes = new ArrayList<>();
        private Integer maxSpotPriceAsPercentageOfOptimalOnDemandPrice;
        private String bareMetal;
        private String burstablePerformance;
        private List<String> allowedInstanceTypes = new ArrayList<>();

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

        public List<String> getLocalStorageTypes() { return localStorageTypes; }
        public void setLocalStorageTypes(List<String> v) { this.localStorageTypes = v != null ? new ArrayList<>(v) : new ArrayList<>(); }

        public Integer getMaxSpotPriceAsPercentageOfOptimalOnDemandPrice() { return maxSpotPriceAsPercentageOfOptimalOnDemandPrice; }
        public void setMaxSpotPriceAsPercentageOfOptimalOnDemandPrice(Integer v) { this.maxSpotPriceAsPercentageOfOptimalOnDemandPrice = v; }

        public String getBareMetal() { return bareMetal; }
        public void setBareMetal(String v) { this.bareMetal = v; }

        public String getBurstablePerformance() { return burstablePerformance; }
        public void setBurstablePerformance(String v) { this.burstablePerformance = v; }

        public List<String> getAllowedInstanceTypes() { return allowedInstanceTypes; }
        public void setAllowedInstanceTypes(List<String> v) { this.allowedInstanceTypes = v != null ? new ArrayList<>(v) : new ArrayList<>(); }

        public boolean isEmpty() {
            return vCpuCount == null && memoryMiB == null && cpuManufacturers.isEmpty()
                    && memoryGiBPerVCpu == null && excludedInstanceTypes.isEmpty()
                    && instanceGenerations.isEmpty() && localStorageTypes.isEmpty()
                    && maxSpotPriceAsPercentageOfOptimalOnDemandPrice == null && bareMetal == null
                    && burstablePerformance == null && allowedInstanceTypes.isEmpty();
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
