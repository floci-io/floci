package io.github.hectorvent.floci.services.route53.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A VPC associated with a private hosted zone.
 *
 * <p>Route 53 has no separate association resource: the set of VPCs attached to a zone is part
 * of the zone itself, reported as the {@code VPCs} sibling of {@code HostedZone} in
 * CreateHostedZone and GetHostedZone responses, and edited through AssociateVPCWithHostedZone
 * and DisassociateVPCFromHostedZone.
 */
@RegisterForReflection
public class HostedZoneVpc {

    private String vpcId;
    private String vpcRegion;

    public HostedZoneVpc() {}

    public HostedZoneVpc(String vpcId, String vpcRegion) {
        this.vpcId = vpcId;
        this.vpcRegion = vpcRegion;
    }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }

    public String getVpcRegion() { return vpcRegion; }
    public void setVpcRegion(String vpcRegion) { this.vpcRegion = vpcRegion; }

    /** Two associations are the same association when they name the same VPC in the same region. */
    public boolean sameAs(String otherId, String otherRegion) {
        if (vpcId == null || !vpcId.equals(otherId)) {
            return false;
        }
        return vpcRegion == null || otherRegion == null || vpcRegion.equals(otherRegion);
    }
}
