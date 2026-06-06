package io.github.hectorvent.floci.services.elbv2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class LoadBalancer {

    private String loadBalancerArn;
    private String dnsName;
    private String canonicalHostedZoneId;
    private Instant createdTime;
    private String loadBalancerName;
    private String scheme;
    private String vpcId;
    private String state;
    private String type;
    private List<AvailabilityZone> availabilityZones = new ArrayList<>();
    private List<String> securityGroups = new ArrayList<>();
    private String ipAddressType;
    private String region;
    private Map<String, String> attributes = new LinkedHashMap<>();

    public LoadBalancer() {}

    public String getLoadBalancerArn() { return loadBalancerArn; }
    public void setLoadBalancerArn(String loadBalancerArn) { this.loadBalancerArn = loadBalancerArn; }

    public String getDnsName() { return dnsName; }
    public void setDnsName(String dnsName) { this.dnsName = dnsName; }

    public String getCanonicalHostedZoneId() { return canonicalHostedZoneId; }
    public void setCanonicalHostedZoneId(String canonicalHostedZoneId) { this.canonicalHostedZoneId = canonicalHostedZoneId; }

    public Instant getCreatedTime() { return createdTime; }
    public void setCreatedTime(Instant createdTime) { this.createdTime = createdTime; }

    public String getLoadBalancerName() { return loadBalancerName; }
    public void setLoadBalancerName(String loadBalancerName) { this.loadBalancerName = loadBalancerName; }

    public String getScheme() { return scheme; }
    public void setScheme(String scheme) { this.scheme = scheme; }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public List<AvailabilityZone> getAvailabilityZones() { return availabilityZones; }
    public void setAvailabilityZones(List<AvailabilityZone> availabilityZones) { this.availabilityZones = availabilityZones; }

    public List<String> getSecurityGroups() { return securityGroups; }
    public void setSecurityGroups(List<String> securityGroups) { this.securityGroups = securityGroups; }

    public String getIpAddressType() { return ipAddressType; }
    public void setIpAddressType(String ipAddressType) { this.ipAddressType = ipAddressType; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public Map<String, String> getAttributes() { return attributes; }
    public void setAttributes(Map<String, String> attributes) { this.attributes = attributes; }
}
