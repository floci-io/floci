package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class NatGateway {

    private String clientToken;
    private String connectivityType;
    private Instant createTime;
    private String natGatewayId;
    private String region;
    private String state;
    private String subnetId;
    private String vpcId;
    private List<NatGatewayAddress> natGatewayAddressSet = new ArrayList<>();
    private List<Tag> tagSet = new ArrayList<>();

    public NatGateway() {}

    public String getClientToken() { return clientToken; }
    public void setClientToken(String clientToken) { this.clientToken = clientToken; }

    public String getConnectivityType() { return connectivityType; }
    public void setConnectivityType(String connectivityType) { this.connectivityType = connectivityType; }

    public Instant getCreateTime() { return createTime; }
    public void setCreateTime(Instant createTime) { this.createTime = createTime; }

    public String getNatGatewayId() { return natGatewayId; }
    public void setNatGatewayId(String natGatewayId) { this.natGatewayId = natGatewayId; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getSubnetId() { return subnetId; }
    public void setSubnetId(String subnetId) { this.subnetId = subnetId; }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }

    public List<NatGatewayAddress> getNatGatewayAddressSet() { return natGatewayAddressSet; }
    public void setNatGatewayAddressSet(List<NatGatewayAddress> natGatewayAddressSet) {
        this.natGatewayAddressSet = natGatewayAddressSet;
    }

    public List<Tag> getTagSet() { return tagSet; }
    public void setTagSet(List<Tag> tagSet) { this.tagSet = tagSet; }
}
