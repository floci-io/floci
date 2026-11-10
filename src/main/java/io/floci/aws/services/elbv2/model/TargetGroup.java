package io.floci.aws.services.elbv2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class TargetGroup {

    private String targetGroupArn;
    private String targetGroupName;
    private String protocol;
    private String protocolVersion;
    private Integer port;
    private String vpcId;
    private String healthCheckProtocol;
    private String healthCheckPort;
    private Boolean healthCheckEnabled;
    private Integer healthCheckIntervalSeconds;
    private Integer healthCheckTimeoutSeconds;
    private Integer healthyThresholdCount;
    private Integer unhealthyThresholdCount;
    private String healthCheckPath;
    private String matcher;
    private List<String> loadBalancerArns = new ArrayList<>();
    private String targetType;
    private String ipAddressType;
    private String region;
    private Map<String, String> attributes = new LinkedHashMap<>();
    private List<TargetDescription> targets = new ArrayList<>();

    public TargetGroup() {}

    public String getTargetGroupArn() { return targetGroupArn; }
    public void setTargetGroupArn(String targetGroupArn) { this.targetGroupArn = targetGroupArn; }

    public String getTargetGroupName() { return targetGroupName; }
    public void setTargetGroupName(String targetGroupName) { this.targetGroupName = targetGroupName; }

    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }

    public String getProtocolVersion() { return protocolVersion; }
    public void setProtocolVersion(String protocolVersion) { this.protocolVersion = protocolVersion; }

    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }

    public String getHealthCheckProtocol() { return healthCheckProtocol; }
    public void setHealthCheckProtocol(String healthCheckProtocol) { this.healthCheckProtocol = healthCheckProtocol; }

    public String getHealthCheckPort() { return healthCheckPort; }
    public void setHealthCheckPort(String healthCheckPort) { this.healthCheckPort = healthCheckPort; }

    public Boolean getHealthCheckEnabled() { return healthCheckEnabled; }
    public void setHealthCheckEnabled(Boolean healthCheckEnabled) { this.healthCheckEnabled = healthCheckEnabled; }

    public Integer getHealthCheckIntervalSeconds() { return healthCheckIntervalSeconds; }
    public void setHealthCheckIntervalSeconds(Integer healthCheckIntervalSeconds) { this.healthCheckIntervalSeconds = healthCheckIntervalSeconds; }

    public Integer getHealthCheckTimeoutSeconds() { return healthCheckTimeoutSeconds; }
    public void setHealthCheckTimeoutSeconds(Integer healthCheckTimeoutSeconds) { this.healthCheckTimeoutSeconds = healthCheckTimeoutSeconds; }

    public Integer getHealthyThresholdCount() { return healthyThresholdCount; }
    public void setHealthyThresholdCount(Integer healthyThresholdCount) { this.healthyThresholdCount = healthyThresholdCount; }

    public Integer getUnhealthyThresholdCount() { return unhealthyThresholdCount; }
    public void setUnhealthyThresholdCount(Integer unhealthyThresholdCount) { this.unhealthyThresholdCount = unhealthyThresholdCount; }

    public String getHealthCheckPath() { return healthCheckPath; }
    public void setHealthCheckPath(String healthCheckPath) { this.healthCheckPath = healthCheckPath; }

    public String getMatcher() { return matcher; }
    public void setMatcher(String matcher) { this.matcher = matcher; }

    public List<String> getLoadBalancerArns() { return loadBalancerArns; }
    public void setLoadBalancerArns(List<String> loadBalancerArns) { this.loadBalancerArns = loadBalancerArns; }

    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }

    public String getIpAddressType() { return ipAddressType; }
    public void setIpAddressType(String ipAddressType) { this.ipAddressType = ipAddressType; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public Map<String, String> getAttributes() { return attributes; }
    public void setAttributes(Map<String, String> attributes) { this.attributes = attributes; }

    public List<TargetDescription> getTargets() { return targets; }
    public void setTargets(List<TargetDescription> targets) { this.targets = targets; }
}
