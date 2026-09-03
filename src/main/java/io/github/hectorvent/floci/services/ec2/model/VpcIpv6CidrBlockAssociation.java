package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * An IPv6 CIDR block associated with a VPC. Separate from {@link VpcCidrBlockAssociation} because
 * AWS keeps the two in separate response members, {@code cidrBlockAssociationSet} and
 * {@code ipv6CidrBlockAssociationSet}, with different member names inside each, and a client
 * reading one must not find IPv6 blocks in it.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class VpcIpv6CidrBlockAssociation {

    private String associationId;
    private String ipv6CidrBlock;
    private String ipv6CidrBlockState = "associated";
    /** Amazon's own pool, the only one an AmazonProvidedIpv6CidrBlock request can draw from. */
    private String ipv6Pool = "Amazon";
    private String networkBorderGroup;

    public VpcIpv6CidrBlockAssociation() {}

    public VpcIpv6CidrBlockAssociation(String associationId, String ipv6CidrBlock, String networkBorderGroup) {
        this.associationId = associationId;
        this.ipv6CidrBlock = ipv6CidrBlock;
        this.networkBorderGroup = networkBorderGroup;
    }

    public String getAssociationId() { return associationId; }
    public void setAssociationId(String associationId) { this.associationId = associationId; }

    public String getIpv6CidrBlock() { return ipv6CidrBlock; }
    public void setIpv6CidrBlock(String ipv6CidrBlock) { this.ipv6CidrBlock = ipv6CidrBlock; }

    public String getIpv6CidrBlockState() { return ipv6CidrBlockState; }
    public void setIpv6CidrBlockState(String ipv6CidrBlockState) { this.ipv6CidrBlockState = ipv6CidrBlockState; }

    public String getIpv6Pool() { return ipv6Pool; }
    public void setIpv6Pool(String ipv6Pool) { this.ipv6Pool = ipv6Pool; }

    public String getNetworkBorderGroup() { return networkBorderGroup; }
    public void setNetworkBorderGroup(String networkBorderGroup) { this.networkBorderGroup = networkBorderGroup; }
}
