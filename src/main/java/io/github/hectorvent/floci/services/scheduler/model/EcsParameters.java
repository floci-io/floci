package io.github.hectorvent.floci.services.scheduler.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class EcsParameters {

    private String taskDefinitionArn;
    private String launchType;
    private Integer taskCount;
    private String platformVersion;
    private NetworkConfiguration networkConfiguration;

    public String getTaskDefinitionArn() { return taskDefinitionArn; }
    public void setTaskDefinitionArn(String taskDefinitionArn) { this.taskDefinitionArn = taskDefinitionArn; }

    public String getLaunchType() { return launchType; }
    public void setLaunchType(String launchType) { this.launchType = launchType; }

    public Integer getTaskCount() { return taskCount; }
    public void setTaskCount(Integer taskCount) { this.taskCount = taskCount; }

    public String getPlatformVersion() { return platformVersion; }
    public void setPlatformVersion(String platformVersion) { this.platformVersion = platformVersion; }

    public NetworkConfiguration getNetworkConfiguration() { return networkConfiguration; }
    public void setNetworkConfiguration(NetworkConfiguration networkConfiguration) { this.networkConfiguration = networkConfiguration; }
}
