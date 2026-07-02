package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class LaunchTemplate {

    private String launchTemplateId;
    private String launchTemplateName;
    private String defaultVersionNumber = "1";
    private String latestVersionNumber = "1";
    private Instant createTime;
    private String createdBy;
    private String region;
    private String imageId;
    private String instanceType;
    private String keyName;
    private String userData;
    private String iamInstanceProfileArn;
    private List<String> securityGroupIds = new ArrayList<>();
    private List<Tag> tags = new ArrayList<>();
    private List<Tag> instanceTags = new ArrayList<>();
    private Map<String, LaunchTemplateData> versions = new LinkedHashMap<>();

    public LaunchTemplate() {}

    public String getLaunchTemplateId() { return launchTemplateId; }
    public void setLaunchTemplateId(String launchTemplateId) { this.launchTemplateId = launchTemplateId; }

    public String getLaunchTemplateName() { return launchTemplateName; }
    public void setLaunchTemplateName(String launchTemplateName) { this.launchTemplateName = launchTemplateName; }

    public String getDefaultVersionNumber() { return defaultVersionNumber; }
    public void setDefaultVersionNumber(String defaultVersionNumber) { this.defaultVersionNumber = defaultVersionNumber; }

    public String getLatestVersionNumber() { return latestVersionNumber; }
    public void setLatestVersionNumber(String latestVersionNumber) { this.latestVersionNumber = latestVersionNumber; }

    public Instant getCreateTime() { return createTime; }
    public void setCreateTime(Instant createTime) { this.createTime = createTime; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getImageId() { return imageId; }
    public void setImageId(String imageId) { this.imageId = imageId; }

    public String getInstanceType() { return instanceType; }
    public void setInstanceType(String instanceType) { this.instanceType = instanceType; }

    public String getKeyName() { return keyName; }
    public void setKeyName(String keyName) { this.keyName = keyName; }

    public String getUserData() { return userData; }
    public void setUserData(String userData) { this.userData = userData; }

    public String getIamInstanceProfileArn() { return iamInstanceProfileArn; }
    public void setIamInstanceProfileArn(String iamInstanceProfileArn) { this.iamInstanceProfileArn = iamInstanceProfileArn; }

    public List<String> getSecurityGroupIds() { return securityGroupIds; }
    public void setSecurityGroupIds(List<String> securityGroupIds) { this.securityGroupIds = securityGroupIds; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }

    public List<Tag> getInstanceTags() { return instanceTags; }
    public void setInstanceTags(List<Tag> instanceTags) {
        this.instanceTags = instanceTags != null ? new ArrayList<>(instanceTags) : new ArrayList<>();
    }

    public Map<String, LaunchTemplateData> getVersions() { return versions; }
    public void setVersions(Map<String, LaunchTemplateData> versions) {
        this.versions = versions != null ? new LinkedHashMap<>(versions) : new LinkedHashMap<>();
    }
}
