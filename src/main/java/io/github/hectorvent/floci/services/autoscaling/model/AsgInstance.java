package io.github.hectorvent.floci.services.autoscaling.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class AsgInstance {

    private String instanceId;
    private String availabilityZone;
    private String lifecycleState;   // Pending | InService | Terminating | Terminated | Detached
    private String healthStatus;     // Healthy | Unhealthy
    private String launchConfigurationName;
    private String launchTemplateId;
    private String launchTemplateName;
    private String launchTemplateVersion;
    private String instanceType;
    private boolean protectedFromScaleIn;

    public AsgInstance() {}

    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String v) { this.instanceId = v; }

    public String getAvailabilityZone() { return availabilityZone; }
    public void setAvailabilityZone(String v) { this.availabilityZone = v; }

    public String getLifecycleState() { return lifecycleState; }
    public void setLifecycleState(String v) { this.lifecycleState = v; }

    public String getHealthStatus() { return healthStatus; }
    public void setHealthStatus(String v) { this.healthStatus = v; }

    public String getLaunchConfigurationName() { return launchConfigurationName; }
    public void setLaunchConfigurationName(String v) { this.launchConfigurationName = v; }

    public String getLaunchTemplateId() { return launchTemplateId; }
    public void setLaunchTemplateId(String v) { this.launchTemplateId = v; }

    public String getLaunchTemplateName() { return launchTemplateName; }
    public void setLaunchTemplateName(String v) { this.launchTemplateName = v; }

    public String getLaunchTemplateVersion() { return launchTemplateVersion; }
    public void setLaunchTemplateVersion(String v) { this.launchTemplateVersion = v; }

    public String getInstanceType() { return instanceType; }
    public void setInstanceType(String v) { this.instanceType = v; }

    public boolean isProtectedFromScaleIn() { return protectedFromScaleIn; }
    public void setProtectedFromScaleIn(boolean v) { this.protectedFromScaleIn = v; }
}
