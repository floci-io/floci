package io.github.hectorvent.floci.services.elb.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A Classic (2012-06-01) load balancer — the {@code LoadBalancerDescription} shape.
 *
 * <p>Deliberately not the ELBv2 {@code LoadBalancer}: a Classic load balancer has no ARN, no
 * target groups, and is addressed by name for the whole life of the record. Sharing the v2 model
 * is what produced the defect this type exists to fix.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClassicLoadBalancer {

    private String loadBalancerName;
    private String dnsName;
    private String canonicalHostedZoneName;
    private String canonicalHostedZoneNameId;
    private String scheme;
    private String vpcId;
    private String region;
    private Instant createdTime;

    private List<ClassicListener> listeners = new ArrayList<>();
    private List<String> availabilityZones = new ArrayList<>();
    private List<String> subnets = new ArrayList<>();
    private List<String> securityGroups = new ArrayList<>();
    private List<String> instanceIds = new ArrayList<>();

    private String sourceSecurityGroupOwnerAlias;
    private String sourceSecurityGroupName;

    private ClassicHealthCheck healthCheck = ClassicHealthCheck.defaults();
    private ClassicLoadBalancerAttributes attributes = new ClassicLoadBalancerAttributes();
    private Map<String, String> tags = new LinkedHashMap<>();

    public ClassicLoadBalancer() {}

    public String getLoadBalancerName() { return loadBalancerName; }
    public void setLoadBalancerName(String v) { this.loadBalancerName = v; }

    public String getDnsName() { return dnsName; }
    public void setDnsName(String v) { this.dnsName = v; }

    public String getCanonicalHostedZoneName() { return canonicalHostedZoneName; }
    public void setCanonicalHostedZoneName(String v) { this.canonicalHostedZoneName = v; }

    public String getCanonicalHostedZoneNameId() { return canonicalHostedZoneNameId; }
    public void setCanonicalHostedZoneNameId(String v) { this.canonicalHostedZoneNameId = v; }

    public String getScheme() { return scheme; }
    public void setScheme(String v) { this.scheme = v; }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String v) { this.vpcId = v; }

    public String getRegion() { return region; }
    public void setRegion(String v) { this.region = v; }

    public Instant getCreatedTime() { return createdTime; }
    public void setCreatedTime(Instant v) { this.createdTime = v; }

    public List<ClassicListener> getListeners() { return listeners; }
    public void setListeners(List<ClassicListener> v) { this.listeners = v; }

    public List<String> getAvailabilityZones() { return availabilityZones; }
    public void setAvailabilityZones(List<String> v) { this.availabilityZones = v; }

    public List<String> getSubnets() { return subnets; }
    public void setSubnets(List<String> v) { this.subnets = v; }

    public List<String> getSecurityGroups() { return securityGroups; }
    public void setSecurityGroups(List<String> v) { this.securityGroups = v; }

    public List<String> getInstanceIds() { return instanceIds; }
    public void setInstanceIds(List<String> v) { this.instanceIds = v; }

    public String getSourceSecurityGroupOwnerAlias() { return sourceSecurityGroupOwnerAlias; }
    public void setSourceSecurityGroupOwnerAlias(String v) { this.sourceSecurityGroupOwnerAlias = v; }

    public String getSourceSecurityGroupName() { return sourceSecurityGroupName; }
    public void setSourceSecurityGroupName(String v) { this.sourceSecurityGroupName = v; }

    public ClassicHealthCheck getHealthCheck() { return healthCheck; }
    public void setHealthCheck(ClassicHealthCheck v) { this.healthCheck = v; }

    public ClassicLoadBalancerAttributes getAttributes() { return attributes; }
    public void setAttributes(ClassicLoadBalancerAttributes v) { this.attributes = v; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> v) { this.tags = v; }
}
