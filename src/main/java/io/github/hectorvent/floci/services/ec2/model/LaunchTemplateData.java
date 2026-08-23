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
    private String encodedUserData;
    private String iamInstanceProfileArn;
    private List<String> securityGroupIds = new ArrayList<>();
    private List<Tag> instanceTags = new ArrayList<>();
    private MetadataOptions metadataOptions;
    private Boolean monitoringEnabled;

    public LaunchTemplateData() {}

    public LaunchTemplateData(LaunchTemplateData source) {
        this.imageId = source.imageId;
        this.instanceType = source.instanceType;
        this.keyName = source.keyName;
        this.userData = source.userData;
        this.encodedUserData = source.encodedUserData;
        this.iamInstanceProfileArn = source.iamInstanceProfileArn;
        this.securityGroupIds = new ArrayList<>(source.securityGroupIds);
        this.instanceTags = new ArrayList<>(source.instanceTags);
        this.metadataOptions = source.metadataOptions;
        this.monitoringEnabled = source.monitoringEnabled;
    }

    public String getImageId() { return imageId; }
    public void setImageId(String imageId) { this.imageId = imageId; }

    public String getInstanceType() { return instanceType; }
    public void setInstanceType(String instanceType) { this.instanceType = instanceType; }

    public String getKeyName() { return keyName; }
    public void setKeyName(String keyName) { this.keyName = keyName; }

    public String getUserData() { return userData; }
    public void setUserData(String userData) { this.userData = userData; }

    public String getEncodedUserData() { return encodedUserData; }
    public void setEncodedUserData(String encodedUserData) { this.encodedUserData = encodedUserData; }

    public String getIamInstanceProfileArn() { return iamInstanceProfileArn; }
    public void setIamInstanceProfileArn(String iamInstanceProfileArn) { this.iamInstanceProfileArn = iamInstanceProfileArn; }

    public List<String> getSecurityGroupIds() { return securityGroupIds; }
    public void setSecurityGroupIds(List<String> securityGroupIds) {
        this.securityGroupIds = securityGroupIds != null ? new ArrayList<>(securityGroupIds) : new ArrayList<>();
    }

    public List<Tag> getInstanceTags() { return instanceTags; }
    public void setInstanceTags(List<Tag> instanceTags) {
        this.instanceTags = instanceTags != null ? new ArrayList<>(instanceTags) : new ArrayList<>();
    }

    public MetadataOptions getMetadataOptions() { return metadataOptions; }
    public void setMetadataOptions(MetadataOptions metadataOptions) { this.metadataOptions = metadataOptions; }

    public Boolean getMonitoringEnabled() { return monitoringEnabled; }
    public void setMonitoringEnabled(Boolean monitoringEnabled) { this.monitoringEnabled = monitoringEnabled; }

    // aws_launch_template's metadata_options and monitoring blocks
    // (DescribeLaunchTemplateVersions.LaunchTemplateData.MetadataOptions /
    // .Monitoring in the real API) had no field on this model at all, so
    // @JsonIgnoreProperties(ignoreUnknown = true) silently dropped both on
    // create and DescribeLaunchTemplateVersions echoed back neither -
    // confirmed directly against this floci build with the AWS CLI, no
    // Terraform in the loop: CreateLaunchTemplate accepted both, and the
    // immediate DescribeLaunchTemplateVersions omitted them entirely.
    // Surfaced by live/e2e/corpus-autoscaling-complete: every module call
    // in that crossing's example sets metadata_options (the module's own
    // variable default is non-null), so a stateless replan proposed
    // `+ metadata_options {...}` and `+ monitoring {...}` on every launch
    // template, forever.
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MetadataOptions {
        private String httpEndpoint;
        private String httpProtocolIpv6;
        private Integer httpPutResponseHopLimit;
        private String httpTokens;
        private String instanceMetadataTags;

        public MetadataOptions() {}

        public String getHttpEndpoint() { return httpEndpoint; }
        public void setHttpEndpoint(String v) { this.httpEndpoint = v; }

        public String getHttpProtocolIpv6() { return httpProtocolIpv6; }
        public void setHttpProtocolIpv6(String v) { this.httpProtocolIpv6 = v; }

        public Integer getHttpPutResponseHopLimit() { return httpPutResponseHopLimit; }
        public void setHttpPutResponseHopLimit(Integer v) { this.httpPutResponseHopLimit = v; }

        public String getHttpTokens() { return httpTokens; }
        public void setHttpTokens(String v) { this.httpTokens = v; }

        public String getInstanceMetadataTags() { return instanceMetadataTags; }
        public void setInstanceMetadataTags(String v) { this.instanceMetadataTags = v; }

        public boolean isEmpty() {
            return httpEndpoint == null && httpProtocolIpv6 == null && httpPutResponseHopLimit == null
                    && httpTokens == null && instanceMetadataTags == null;
        }
    }
}
