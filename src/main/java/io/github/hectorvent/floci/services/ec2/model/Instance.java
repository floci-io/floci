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
public class Instance {

    private String instanceId;
    private String imageId;
    private InstanceState state;
    private String stateTransitionReason;
    private String instanceType;
    private Placement placement;
    private String subnetId;
    private String vpcId;
    private String privateIpAddress;
    private String publicIpAddress;
    private String privateDnsName;
    private String publicDnsName;
    private String keyName;
    private List<GroupIdentifier> securityGroups = new ArrayList<>();
    private List<InstanceNetworkInterface> networkInterfaces = new ArrayList<>();
    private String architecture;
    private String hypervisor = "xen";
    private String virtualizationType = "hvm";
    private String rootDeviceName = "/dev/xvda";
    private String rootDeviceType = "ebs";
    private Instant launchTime;
    private int amiLaunchIndex;
    private String clientToken;
    private String monitoring = "disabled";
    private boolean sourceDestCheck = true;
    private boolean ebsOptimized = false;
    private boolean enaSupport = true;
    private String iamInstanceProfileArn;
    private String stateReasonCode;
    private String stateReasonMessage;
    private String region;
    private List<Tag> tags = new ArrayList<>();

    private String rootVolumeId;

    // NOTE: disableApiStop and disableApiTermination are stored but ModifyInstanceAttribute
    // does not yet wire them through. Terraform reads these via DescribeInstanceAttribute;
    // without model backing, a modify → plan cycle would show drift. Tracked as a known limitation.
    private boolean disableApiStop = false;
    private boolean disableApiTermination = false;

    // Docker backing fields (not serialised to AWS wire format)
    private String dockerContainerId;
    private String containerBridgeIp;
    private String userData;
    private int sshHostPort;
    private long terminatedAt;

    // Security-group ingress ports published on the host via socat sidecars:
    // container app port -> allocated host port. Not part of the AWS wire format.
    private Map<Integer, Integer> publishedPorts = new LinkedHashMap<>();

    public Instance() {}

    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }

    public String getImageId() { return imageId; }
    public void setImageId(String imageId) { this.imageId = imageId; }

    public InstanceState getState() { return state; }
    public void setState(InstanceState state) { this.state = state; }

    public String getStateTransitionReason() { return stateTransitionReason; }
    public void setStateTransitionReason(String stateTransitionReason) { this.stateTransitionReason = stateTransitionReason; }

    public String getInstanceType() { return instanceType; }
    public void setInstanceType(String instanceType) { this.instanceType = instanceType; }

    public Placement getPlacement() { return placement; }
    public void setPlacement(Placement placement) { this.placement = placement; }

    public String getSubnetId() { return subnetId; }
    public void setSubnetId(String subnetId) { this.subnetId = subnetId; }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }

    public String getPrivateIpAddress() { return privateIpAddress; }
    public void setPrivateIpAddress(String privateIpAddress) { this.privateIpAddress = privateIpAddress; }

    public String getPublicIpAddress() { return publicIpAddress; }
    public void setPublicIpAddress(String publicIpAddress) { this.publicIpAddress = publicIpAddress; }

    public String getPrivateDnsName() { return privateDnsName; }
    public void setPrivateDnsName(String privateDnsName) { this.privateDnsName = privateDnsName; }

    public String getPublicDnsName() { return publicDnsName; }
    public void setPublicDnsName(String publicDnsName) { this.publicDnsName = publicDnsName; }

    public String getKeyName() { return keyName; }
    public void setKeyName(String keyName) { this.keyName = keyName; }

    public List<GroupIdentifier> getSecurityGroups() { return securityGroups; }
    public void setSecurityGroups(List<GroupIdentifier> securityGroups) { this.securityGroups = securityGroups; }

    public List<InstanceNetworkInterface> getNetworkInterfaces() { return networkInterfaces; }
    public void setNetworkInterfaces(List<InstanceNetworkInterface> networkInterfaces) { this.networkInterfaces = networkInterfaces; }

    public String getArchitecture() { return architecture; }
    public void setArchitecture(String architecture) { this.architecture = architecture; }

    public String getHypervisor() { return hypervisor; }
    public void setHypervisor(String hypervisor) { this.hypervisor = hypervisor; }

    public String getVirtualizationType() { return virtualizationType; }
    public void setVirtualizationType(String virtualizationType) { this.virtualizationType = virtualizationType; }

    public String getRootDeviceName() { return rootDeviceName; }
    public void setRootDeviceName(String rootDeviceName) { this.rootDeviceName = rootDeviceName; }

    public String getRootDeviceType() { return rootDeviceType; }
    public void setRootDeviceType(String rootDeviceType) { this.rootDeviceType = rootDeviceType; }

    public Instant getLaunchTime() { return launchTime; }
    public void setLaunchTime(Instant launchTime) { this.launchTime = launchTime; }

    public int getAmiLaunchIndex() { return amiLaunchIndex; }
    public void setAmiLaunchIndex(int amiLaunchIndex) { this.amiLaunchIndex = amiLaunchIndex; }

    public String getClientToken() { return clientToken; }
    public void setClientToken(String clientToken) { this.clientToken = clientToken; }

    public String getMonitoring() { return monitoring; }
    public void setMonitoring(String monitoring) { this.monitoring = monitoring; }

    public boolean isSourceDestCheck() { return sourceDestCheck; }
    public void setSourceDestCheck(boolean sourceDestCheck) { this.sourceDestCheck = sourceDestCheck; }

    public boolean isEbsOptimized() { return ebsOptimized; }
    public void setEbsOptimized(boolean ebsOptimized) { this.ebsOptimized = ebsOptimized; }

    public boolean isEnaSupport() { return enaSupport; }
    public void setEnaSupport(boolean enaSupport) { this.enaSupport = enaSupport; }

    public String getIamInstanceProfileArn() { return iamInstanceProfileArn; }
    public void setIamInstanceProfileArn(String iamInstanceProfileArn) { this.iamInstanceProfileArn = iamInstanceProfileArn; }

    public String getStateReasonCode() { return stateReasonCode; }
    public void setStateReasonCode(String stateReasonCode) { this.stateReasonCode = stateReasonCode; }

    public String getStateReasonMessage() { return stateReasonMessage; }
    public void setStateReasonMessage(String stateReasonMessage) { this.stateReasonMessage = stateReasonMessage; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }

    public String getDockerContainerId() { return dockerContainerId; }
    public void setDockerContainerId(String dockerContainerId) { this.dockerContainerId = dockerContainerId; }

    public String getUserData() { return userData; }
    public void setUserData(String userData) { this.userData = userData; }

    public int getSshHostPort() { return sshHostPort; }
    public void setSshHostPort(int sshHostPort) { this.sshHostPort = sshHostPort; }

    public long getTerminatedAt() { return terminatedAt; }
    public void setTerminatedAt(long terminatedAt) { this.terminatedAt = terminatedAt; }

    public String getContainerBridgeIp() { return containerBridgeIp; }
    public void setContainerBridgeIp(String containerBridgeIp) { this.containerBridgeIp = containerBridgeIp; }

    public Map<Integer, Integer> getPublishedPorts() {
        if (publishedPorts == null) {
            publishedPorts = new LinkedHashMap<>();
        }
        return publishedPorts;
    }
    public void setPublishedPorts(Map<Integer, Integer> publishedPorts) { this.publishedPorts = publishedPorts; }

    public String getRootVolumeId() { return rootVolumeId; }
    public void setRootVolumeId(String rootVolumeId) { this.rootVolumeId = rootVolumeId; }

    public boolean isDisableApiStop() { return disableApiStop; }
    public void setDisableApiStop(boolean disableApiStop) { this.disableApiStop = disableApiStop; }

    public boolean isDisableApiTermination() { return disableApiTermination; }
    public void setDisableApiTermination(boolean disableApiTermination) { this.disableApiTermination = disableApiTermination; }
}
