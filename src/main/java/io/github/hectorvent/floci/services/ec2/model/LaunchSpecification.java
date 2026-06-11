package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class LaunchSpecification {

    private String imageId;
    private String instanceType;
    private String keyName;
    private String subnetId;
    private List<GroupIdentifier> securityGroups = new ArrayList<>();
    private String userData;
    private String iamInstanceProfileArn;

    public LaunchSpecification() {}

    public String getImageId() {
        return imageId;
    }

    public void setImageId(String imageId) {
        this.imageId = imageId;
    }

    public String getInstanceType() {
        return instanceType;
    }

    public void setInstanceType(String instanceType) {
        this.instanceType = instanceType;
    }

    public String getKeyName() {
        return keyName;
    }

    public void setKeyName(String keyName) {
        this.keyName = keyName;
    }

    public String getSubnetId() {
        return subnetId;
    }

    public void setSubnetId(String subnetId) {
        this.subnetId = subnetId;
    }

    public List<GroupIdentifier> getSecurityGroups() {
        return securityGroups;
    }

    public void setSecurityGroups(List<GroupIdentifier> securityGroups) {
        this.securityGroups = securityGroups;
    }

    public String getUserData() {
        return userData;
    }

    public void setUserData(String userData) {
        this.userData = userData;
    }

    public String getIamInstanceProfileArn() {
        return iamInstanceProfileArn;
    }

    public void setIamInstanceProfileArn(String iamInstanceProfileArn) {
        this.iamInstanceProfileArn = iamInstanceProfileArn;
    }
}
