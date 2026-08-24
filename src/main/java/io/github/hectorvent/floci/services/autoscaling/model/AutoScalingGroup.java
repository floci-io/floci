package io.github.hectorvent.floci.services.autoscaling.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class AutoScalingGroup {

    private String autoScalingGroupName;
    private String autoScalingGroupArn;
    private String launchConfigurationName;
    private String launchTemplateId;
    private String launchTemplateName;
    private String launchTemplateVersion;
    private MixedInstancesPolicy mixedInstancesPolicy;
    private int minSize;
    private int maxSize;
    private int desiredCapacity;
    // lex00/floci#112's round-5 re-measure: CreateAutoScalingGroup's own DesiredCapacityType
    // ("units" | "vcpu" | "memory-mib" - how DesiredCapacity/MinSize/MaxSize are measured when
    // a mixed instances policy weights instance types) had no field at all, so
    // DescribeAutoScalingGroups always echoed it back as absent regardless of what was
    // requested. Same class of gap as #112's own named mixed_instances_policy override fields,
    // not separately itemized in #112's own text but the identical root cause. Oracle:
    // botocore's autoscaling/2011-01-01/service-2.json AutoScalingGroup/CreateAutoScalingGroup
    // DesiredCapacityType shape.
    private String desiredCapacityType;
    private int defaultCooldown = 300;
    private List<String> availabilityZones = new ArrayList<>();
    private List<String> subnetIds = new ArrayList<>();
    private List<String> loadBalancerNames = new ArrayList<>();
    private List<String> targetGroupARNs = new ArrayList<>();
    private String healthCheckType = "EC2";
    private int healthCheckGracePeriod = 0;
    private List<AsgInstance> instances = new ArrayList<>();
    private List<String> terminationPolicies = new ArrayList<>();
    private List<String> suspendedProcesses = new ArrayList<>();
    // AttachTrafficSources (the modern elbv2/vpc-lattice ASG-to-load-balancer wiring API,
    // which aws_autoscaling_traffic_source_attachment uses instead of the older
    // AttachLoadBalancerTargetGroups) - identifier -> type ("elbv2", "elb", "vpc-lattice").
    private Map<String, String> trafficSourceTypeByIdentifier = new ConcurrentHashMap<>();
    private Instant createdTime;
    private String region;
    private Map<String, String> tags = new ConcurrentHashMap<>();
    private Map<String, Boolean> tagPropagateAtLaunch = new ConcurrentHashMap<>();
    private String status;  // null = active, "Delete in progress" = deleting

    // lex00/floci#112: DescribeAutoScalingGroups dropped most of the resource's own optional
    // fields - a stateless replan (like choudoufu's corpus-autoscaling-complete crossing) has
    // nowhere to source these from and proposes a permanent in-place-update diff for every one a
    // module call actually sets. Oracle: botocore's autoscaling/2011-01-01/service-2.json
    // AutoScalingGroup shape.
    private Integer defaultInstanceWarmup;
    private Boolean capacityRebalance;
    private List<String> enabledMetrics = new ArrayList<>();
    private Integer maxInstanceLifetime;
    private String serviceLinkedRoleArn;
    private InstanceMaintenancePolicy instanceMaintenancePolicy;
    private AvailabilityZoneDistribution availabilityZoneDistribution;
    private CapacityReservationSpecification capacityReservationSpecification;

    public AutoScalingGroup() {}

    public String getAutoScalingGroupName() { return autoScalingGroupName; }
    public void setAutoScalingGroupName(String v) { this.autoScalingGroupName = v; }

    public String getAutoScalingGroupArn() { return autoScalingGroupArn; }
    public void setAutoScalingGroupArn(String v) { this.autoScalingGroupArn = v; }

    public String getLaunchConfigurationName() { return launchConfigurationName; }
    public void setLaunchConfigurationName(String v) { this.launchConfigurationName = v; }

    public String getLaunchTemplateId() { return launchTemplateId; }
    public void setLaunchTemplateId(String v) { this.launchTemplateId = v; }

    public String getLaunchTemplateName() { return launchTemplateName; }
    public void setLaunchTemplateName(String v) { this.launchTemplateName = v; }

    public String getLaunchTemplateVersion() { return launchTemplateVersion; }
    public void setLaunchTemplateVersion(String v) { this.launchTemplateVersion = v; }

    public MixedInstancesPolicy getMixedInstancesPolicy() { return mixedInstancesPolicy; }
    public void setMixedInstancesPolicy(MixedInstancesPolicy v) { this.mixedInstancesPolicy = v; }

    public int getMinSize() { return minSize; }
    public void setMinSize(int v) { this.minSize = v; }

    public int getMaxSize() { return maxSize; }
    public void setMaxSize(int v) { this.maxSize = v; }

    public int getDesiredCapacity() { return desiredCapacity; }
    public void setDesiredCapacity(int v) { this.desiredCapacity = v; }

    public String getDesiredCapacityType() { return desiredCapacityType; }
    public void setDesiredCapacityType(String v) { this.desiredCapacityType = v; }

    public int getDefaultCooldown() { return defaultCooldown; }
    public void setDefaultCooldown(int v) { this.defaultCooldown = v; }

    public List<String> getAvailabilityZones() { return availabilityZones; }
    public void setAvailabilityZones(List<String> v) { this.availabilityZones = v; }

    public List<String> getSubnetIds() { return subnetIds; }
    public void setSubnetIds(List<String> v) { this.subnetIds = v; }

    public List<String> getLoadBalancerNames() { return loadBalancerNames; }
    public void setLoadBalancerNames(List<String> v) { this.loadBalancerNames = v; }

    public List<String> getTargetGroupARNs() { return targetGroupARNs; }
    public void setTargetGroupARNs(List<String> v) { this.targetGroupARNs = v; }

    public String getHealthCheckType() { return healthCheckType; }
    public void setHealthCheckType(String v) { this.healthCheckType = v; }

    public int getHealthCheckGracePeriod() { return healthCheckGracePeriod; }
    public void setHealthCheckGracePeriod(int v) { this.healthCheckGracePeriod = v; }

    public List<AsgInstance> getInstances() { return instances; }
    public void setInstances(List<AsgInstance> v) { this.instances = v; }

    public List<String> getTerminationPolicies() { return terminationPolicies; }
    public void setTerminationPolicies(List<String> v) { this.terminationPolicies = v; }

    public List<String> getSuspendedProcesses() { return suspendedProcesses; }
    public void setSuspendedProcesses(List<String> v) { this.suspendedProcesses = v; }

    public Map<String, String> getTrafficSourceTypeByIdentifier() { return trafficSourceTypeByIdentifier; }
    public void setTrafficSourceTypeByIdentifier(Map<String, String> v) { this.trafficSourceTypeByIdentifier = v; }

    public Instant getCreatedTime() { return createdTime; }
    public void setCreatedTime(Instant v) { this.createdTime = v; }

    public String getRegion() { return region; }
    public void setRegion(String v) { this.region = v; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> v) { this.tags = v; }

    public Map<String, Boolean> getTagPropagateAtLaunch() { return tagPropagateAtLaunch; }
    public void setTagPropagateAtLaunch(Map<String, Boolean> v) { this.tagPropagateAtLaunch = v; }

    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }

    public Integer getDefaultInstanceWarmup() { return defaultInstanceWarmup; }
    public void setDefaultInstanceWarmup(Integer v) { this.defaultInstanceWarmup = v; }

    public Boolean getCapacityRebalance() { return capacityRebalance; }
    public void setCapacityRebalance(Boolean v) { this.capacityRebalance = v; }

    public List<String> getEnabledMetrics() { return enabledMetrics; }
    public void setEnabledMetrics(List<String> v) { this.enabledMetrics = v != null ? v : new ArrayList<>(); }

    public Integer getMaxInstanceLifetime() { return maxInstanceLifetime; }
    public void setMaxInstanceLifetime(Integer v) { this.maxInstanceLifetime = v; }

    public String getServiceLinkedRoleArn() { return serviceLinkedRoleArn; }
    public void setServiceLinkedRoleArn(String v) { this.serviceLinkedRoleArn = v; }

    public InstanceMaintenancePolicy getInstanceMaintenancePolicy() { return instanceMaintenancePolicy; }
    public void setInstanceMaintenancePolicy(InstanceMaintenancePolicy v) { this.instanceMaintenancePolicy = v; }

    public AvailabilityZoneDistribution getAvailabilityZoneDistribution() { return availabilityZoneDistribution; }
    public void setAvailabilityZoneDistribution(AvailabilityZoneDistribution v) { this.availabilityZoneDistribution = v; }

    public CapacityReservationSpecification getCapacityReservationSpecification() { return capacityReservationSpecification; }
    public void setCapacityReservationSpecification(CapacityReservationSpecification v) { this.capacityReservationSpecification = v; }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class InstanceMaintenancePolicy {
        private Integer minHealthyPercentage;
        private Integer maxHealthyPercentage;

        public Integer getMinHealthyPercentage() { return minHealthyPercentage; }
        public void setMinHealthyPercentage(Integer v) { this.minHealthyPercentage = v; }

        public Integer getMaxHealthyPercentage() { return maxHealthyPercentage; }
        public void setMaxHealthyPercentage(Integer v) { this.maxHealthyPercentage = v; }

        public boolean isEmpty() { return minHealthyPercentage == null && maxHealthyPercentage == null; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AvailabilityZoneDistribution {
        private String capacityDistributionStrategy;

        public String getCapacityDistributionStrategy() { return capacityDistributionStrategy; }
        public void setCapacityDistributionStrategy(String v) { this.capacityDistributionStrategy = v; }

        public boolean isEmpty() { return capacityDistributionStrategy == null; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CapacityReservationSpecification {
        private String capacityReservationPreference;

        public String getCapacityReservationPreference() { return capacityReservationPreference; }
        public void setCapacityReservationPreference(String v) { this.capacityReservationPreference = v; }

        public boolean isEmpty() { return capacityReservationPreference == null; }
    }
}
