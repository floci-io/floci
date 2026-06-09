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
    private List<String> securityGroupIds = new ArrayList<>();

    public LaunchTemplateData() {}

    public LaunchTemplateData(LaunchTemplateData source) {
        this.imageId = source.imageId;
        this.instanceType = source.instanceType;
        this.keyName = source.keyName;
        this.userData = source.userData;
        this.securityGroupIds = new ArrayList<>(source.securityGroupIds);
    }

    public String getImageId() { return imageId; }
    public void setImageId(String imageId) { this.imageId = imageId; }

    public String getInstanceType() { return instanceType; }
    public void setInstanceType(String instanceType) { this.instanceType = instanceType; }

    public String getKeyName() { return keyName; }
    public void setKeyName(String keyName) { this.keyName = keyName; }

    public String getUserData() { return userData; }
    public void setUserData(String userData) { this.userData = userData; }

    public List<String> getSecurityGroupIds() { return securityGroupIds; }
    public void setSecurityGroupIds(List<String> securityGroupIds) {
        this.securityGroupIds = securityGroupIds != null ? new ArrayList<>(securityGroupIds) : new ArrayList<>();
    }
}
