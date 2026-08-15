package io.github.hectorvent.floci.services.autoscaling.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class LaunchConfiguration {

    private String launchConfigurationName;
    private String launchConfigurationArn;
    private String imageId;
    private String instanceType;
    private String keyName;
    private List<String> securityGroups = new ArrayList<>();
    private String userData;
    private String iamInstanceProfile;
    // Nullable on purpose: AWS treats an absent flag as "fall back to the
    // subnet's MapPublicIpOnLaunch" and an explicit false as an override.
    private Boolean associatePublicIpAddress;
    private Instant createdTime;
    private String region;

    public LaunchConfiguration() {}

    public String getLaunchConfigurationName() { return launchConfigurationName; }
    public void setLaunchConfigurationName(String v) { this.launchConfigurationName = v; }

    public String getLaunchConfigurationArn() { return launchConfigurationArn; }
    public void setLaunchConfigurationArn(String v) { this.launchConfigurationArn = v; }

    public String getImageId() { return imageId; }
    public void setImageId(String v) { this.imageId = v; }

    public String getInstanceType() { return instanceType; }
    public void setInstanceType(String v) { this.instanceType = v; }

    public String getKeyName() { return keyName; }
    public void setKeyName(String v) { this.keyName = v; }

    public List<String> getSecurityGroups() { return securityGroups; }
    public void setSecurityGroups(List<String> v) { this.securityGroups = v; }

    public String getUserData() { return userData; }
    public void setUserData(String v) { this.userData = v; }

    public String getIamInstanceProfile() { return iamInstanceProfile; }
    public void setIamInstanceProfile(String v) { this.iamInstanceProfile = v; }

    public Boolean getAssociatePublicIpAddress() { return associatePublicIpAddress; }
    public void setAssociatePublicIpAddress(Boolean v) { this.associatePublicIpAddress = v; }

    public Instant getCreatedTime() { return createdTime; }
    public void setCreatedTime(Instant v) { this.createdTime = v; }

    public String getRegion() { return region; }
    public void setRegion(String v) { this.region = v; }
}
