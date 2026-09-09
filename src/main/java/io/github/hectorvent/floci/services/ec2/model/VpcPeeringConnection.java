package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/**
 * Stored keyed by id alone rather than {@code region::id} like every other EC2 resource here
 * (see Ec2Service#createVpcPeeringConnection) — a deliberate simplification. A peering connection
 * is meaningfully addressable from both the requester's and the accepter's side, which can be a
 * different region (the {@code vpc-peering-cross-accounts} example sets {@code accepter_region}
 * independently of the requester region). Region-scoped storage would make the accepter's
 * {@code AcceptVpcPeeringConnection}/{@code DescribeVpcPeeringConnections} calls unable to find a
 * connection created under the requester's region key.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class VpcPeeringConnection {

    private String vpcPeeringConnectionId;
    // The region the connection was created in (the requester's region). Informational only —
    // lookups are by id, not region::id; see the class Javadoc.
    private String region;
    private VpcPeeringConnectionVpcInfo requesterVpcInfo;
    private VpcPeeringConnectionVpcInfo accepterVpcInfo;
    private VpcPeeringConnectionStateReason status;
    // aws_vpc_peering_connection_options models one boolean per side here (allow_remote_vpc_dns_resolution),
    // the only PeeringConnectionOptions field the vpc-peering / vpc-peering-cross-accounts examples set.
    // The ClassicLink-era options (long deprecated) are not modelled.
    private boolean accepterAllowRemoteVpcDnsResolution;
    private boolean requesterAllowRemoteVpcDnsResolution;
    private List<Tag> tags = new ArrayList<>();

    public VpcPeeringConnection() {}

    public String getVpcPeeringConnectionId() { return vpcPeeringConnectionId; }
    public void setVpcPeeringConnectionId(String vpcPeeringConnectionId) { this.vpcPeeringConnectionId = vpcPeeringConnectionId; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public VpcPeeringConnectionVpcInfo getRequesterVpcInfo() { return requesterVpcInfo; }
    public void setRequesterVpcInfo(VpcPeeringConnectionVpcInfo requesterVpcInfo) { this.requesterVpcInfo = requesterVpcInfo; }

    public VpcPeeringConnectionVpcInfo getAccepterVpcInfo() { return accepterVpcInfo; }
    public void setAccepterVpcInfo(VpcPeeringConnectionVpcInfo accepterVpcInfo) { this.accepterVpcInfo = accepterVpcInfo; }

    public VpcPeeringConnectionStateReason getStatus() { return status; }
    public void setStatus(VpcPeeringConnectionStateReason status) { this.status = status; }

    public boolean isAccepterAllowRemoteVpcDnsResolution() { return accepterAllowRemoteVpcDnsResolution; }
    public void setAccepterAllowRemoteVpcDnsResolution(boolean accepterAllowRemoteVpcDnsResolution) {
        this.accepterAllowRemoteVpcDnsResolution = accepterAllowRemoteVpcDnsResolution;
    }

    public boolean isRequesterAllowRemoteVpcDnsResolution() { return requesterAllowRemoteVpcDnsResolution; }
    public void setRequesterAllowRemoteVpcDnsResolution(boolean requesterAllowRemoteVpcDnsResolution) {
        this.requesterAllowRemoteVpcDnsResolution = requesterAllowRemoteVpcDnsResolution;
    }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }
}
