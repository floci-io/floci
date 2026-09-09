package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The security group a {@link SecurityGroupRule} allows traffic from or to, as returned in the
 * {@code referencedGroupInfo} member of DescribeSecurityGroupRules.
 *
 * <p>A rule carries exactly one source, so this is the group-reference counterpart of
 * {@code cidrIpv4} / {@code cidrIpv6}. Note the shape has no group name: a reference authorized by
 * name is resolved to its group id before the rule is stored.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReferencedSecurityGroup {

    private String groupId;
    private String userId;
    private String vpcId;
    private String vpcPeeringConnectionId;
    private String peeringStatus;

    public ReferencedSecurityGroup() {}

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }

    public String getVpcPeeringConnectionId() { return vpcPeeringConnectionId; }
    public void setVpcPeeringConnectionId(String vpcPeeringConnectionId) {
        this.vpcPeeringConnectionId = vpcPeeringConnectionId;
    }

    public String getPeeringStatus() { return peeringStatus; }
    public void setPeeringStatus(String peeringStatus) { this.peeringStatus = peeringStatus; }
}
