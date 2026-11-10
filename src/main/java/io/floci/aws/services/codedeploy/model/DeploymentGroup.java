package io.floci.aws.services.codedeploy.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeploymentGroup {
    public DeploymentGroup() {}

    private String applicationName;
    private String deploymentGroupId;
    private String deploymentGroupName;
    private String deploymentConfigName;
    private String serviceRoleArn;
    private List<Map<String, String>> ec2TagFilters;
    private List<Map<String, String>> onPremisesInstanceTagFilters;
    private List<Map<String, Object>> autoScalingGroups;
    private Map<String, Object> deploymentStyle;
    private Map<String, Object> blueGreenDeploymentConfiguration;
    private Map<String, Object> loadBalancerInfo;
    private Map<String, Object> ec2TagSet;
    private Map<String, Object> onPremisesTagSet;
    private Map<String, Object> alarmConfiguration;
    private Map<String, Object> autoRollbackConfiguration;
    private List<Map<String, Object>> triggerConfigurations;
    private List<Map<String, Object>> ecsServices;
    private String computePlatform;
    private String outdatedInstancesStrategy;
    private Boolean terminationHookEnabled;

    public String getApplicationName() { return applicationName; }
    public void setApplicationName(String applicationName) { this.applicationName = applicationName; }

    public String getDeploymentGroupId() { return deploymentGroupId; }
    public void setDeploymentGroupId(String deploymentGroupId) { this.deploymentGroupId = deploymentGroupId; }

    public String getDeploymentGroupName() { return deploymentGroupName; }
    public void setDeploymentGroupName(String deploymentGroupName) { this.deploymentGroupName = deploymentGroupName; }

    public String getDeploymentConfigName() { return deploymentConfigName; }
    public void setDeploymentConfigName(String deploymentConfigName) { this.deploymentConfigName = deploymentConfigName; }

    public String getServiceRoleArn() { return serviceRoleArn; }
    public void setServiceRoleArn(String serviceRoleArn) { this.serviceRoleArn = serviceRoleArn; }

    public List<Map<String, String>> getEc2TagFilters() { return ec2TagFilters; }
    public void setEc2TagFilters(List<Map<String, String>> ec2TagFilters) { this.ec2TagFilters = ec2TagFilters; }

    public List<Map<String, String>> getOnPremisesInstanceTagFilters() { return onPremisesInstanceTagFilters; }
    public void setOnPremisesInstanceTagFilters(List<Map<String, String>> onPremisesInstanceTagFilters) { this.onPremisesInstanceTagFilters = onPremisesInstanceTagFilters; }

    public List<Map<String, Object>> getAutoScalingGroups() { return autoScalingGroups; }
    public void setAutoScalingGroups(List<Map<String, Object>> autoScalingGroups) { this.autoScalingGroups = autoScalingGroups; }

    public Map<String, Object> getDeploymentStyle() { return deploymentStyle; }
    public void setDeploymentStyle(Map<String, Object> deploymentStyle) { this.deploymentStyle = deploymentStyle; }

    public Map<String, Object> getBlueGreenDeploymentConfiguration() { return blueGreenDeploymentConfiguration; }
    public void setBlueGreenDeploymentConfiguration(Map<String, Object> blueGreenDeploymentConfiguration) { this.blueGreenDeploymentConfiguration = blueGreenDeploymentConfiguration; }

    public Map<String, Object> getLoadBalancerInfo() { return loadBalancerInfo; }
    public void setLoadBalancerInfo(Map<String, Object> loadBalancerInfo) { this.loadBalancerInfo = loadBalancerInfo; }

    public Map<String, Object> getEc2TagSet() { return ec2TagSet; }
    public void setEc2TagSet(Map<String, Object> ec2TagSet) { this.ec2TagSet = ec2TagSet; }

    public Map<String, Object> getOnPremisesTagSet() { return onPremisesTagSet; }
    public void setOnPremisesTagSet(Map<String, Object> onPremisesTagSet) { this.onPremisesTagSet = onPremisesTagSet; }

    public Map<String, Object> getAlarmConfiguration() { return alarmConfiguration; }
    public void setAlarmConfiguration(Map<String, Object> alarmConfiguration) { this.alarmConfiguration = alarmConfiguration; }

    public Map<String, Object> getAutoRollbackConfiguration() { return autoRollbackConfiguration; }
    public void setAutoRollbackConfiguration(Map<String, Object> autoRollbackConfiguration) { this.autoRollbackConfiguration = autoRollbackConfiguration; }

    public List<Map<String, Object>> getTriggerConfigurations() { return triggerConfigurations; }
    public void setTriggerConfigurations(List<Map<String, Object>> triggerConfigurations) { this.triggerConfigurations = triggerConfigurations; }

    public List<Map<String, Object>> getEcsServices() { return ecsServices; }
    public void setEcsServices(List<Map<String, Object>> ecsServices) { this.ecsServices = ecsServices; }

    public String getComputePlatform() { return computePlatform; }
    public void setComputePlatform(String computePlatform) { this.computePlatform = computePlatform; }

    public String getOutdatedInstancesStrategy() { return outdatedInstancesStrategy; }
    public void setOutdatedInstancesStrategy(String outdatedInstancesStrategy) { this.outdatedInstancesStrategy = outdatedInstancesStrategy; }

    public Boolean getTerminationHookEnabled() { return terminationHookEnabled; }
    public void setTerminationHookEnabled(Boolean terminationHookEnabled) { this.terminationHookEnabled = terminationHookEnabled; }
}
