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

        public LaunchTemplateOverride() {}

        public String getInstanceType() { return instanceType; }
        public void setInstanceType(String v) { this.instanceType = v; }

        public String getWeightedCapacity() { return weightedCapacity; }
        public void setWeightedCapacity(String v) { this.weightedCapacity = v; }
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
