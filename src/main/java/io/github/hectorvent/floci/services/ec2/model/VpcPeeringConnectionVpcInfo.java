package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One side (requester or accepter) of a {@link VpcPeeringConnection}. The accepter side may name a
 * VPC that does not exist in this store at all — a cross-account or "external" peer, per the
 * {@code vpc-peering-cross-accounts} and {@code vpc-peering-external} examples — so {@code cidrBlock}
 * is best-effort and can be {@code null}.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class VpcPeeringConnectionVpcInfo {

    private String vpcId;
    private String ownerId;
    private String region;
    private String cidrBlock;

    public VpcPeeringConnectionVpcInfo() {}

    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getCidrBlock() { return cidrBlock; }
    public void setCidrBlock(String cidrBlock) { this.cidrBlock = cidrBlock; }
}
