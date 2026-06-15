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
    private int defaultCooldown = 300;
    private List<String> availabilityZones = new ArrayList<>();
    private List<String> subnetIds = new ArrayList<>();
    private List<String> loadBalancerNames = new ArrayList<>();
    private List<String> targetGroupARNs = new ArrayList<>();
    private String healthCheckType = "EC2";
    private int healthCheckGracePeriod = 0;
    private List<AsgInstance> instances = new ArrayList<>();
    private List<String> terminationPolicies = new ArrayList<>();
    private Instant createdTime;
    private String region;
    private Map<String, String> tags = new ConcurrentHashMap<>();
    private String status;  // null = active, "Delete in progress" = deleting

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

    public Instant getCreatedTime() { return createdTime; }
    public void setCreatedTime(Instant v) { this.createdTime = v; }

    public String getRegion() { return region; }
    public void setRegion(String v) { this.region = v; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> v) { this.tags = v; }

    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
}
