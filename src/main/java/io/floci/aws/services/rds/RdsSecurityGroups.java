package io.floci.aws.services.rds;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.SecurityGroup;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The checks the RDS API makes on the VPC security group ids a request names, shared with DocumentDB,
 * which serves the same API: each id names a security group of the caller's account and Region, and
 * every group sits in the resource's VPC.
 */
public final class RdsSecurityGroups {

    private RdsSecurityGroups() {
    }

    /**
     * Refuses {@code groupIds} unless each names a security group of {@code region} and, when
     * {@code vpcId} is given, every one of them is in that VPC. A null or empty list names no groups
     * and passes.
     */
    public static void requireInVpc(Ec2Service ec2Service, String region, List<String> groupIds, String vpcId) {
        if (groupIds == null || groupIds.isEmpty()) {
            return;
        }
        Map<String, SecurityGroup> found = new LinkedHashMap<>();
        for (SecurityGroup group : ec2Service.describeSecurityGroups(region, groupIds, List.of(), Map.of())) {
            found.put(group.getGroupId(), group);
        }
        List<String> unknown = groupIds.stream().filter(id -> !found.containsKey(id)).distinct().toList();
        if (!unknown.isEmpty()) {
            throw new AwsException("InvalidParameterValue",
                    "Invalid security group , groupId= " + String.join(", ", unknown) + ", groupName=.", 400);
        }
        if (vpcId == null) {
            return;
        }
        for (String groupId : groupIds) {
            String groupVpcId = found.get(groupId).getVpcId();
            if (!vpcId.equals(groupVpcId)) {
                throw new AwsException("InvalidParameterCombination",
                        "The DB instance and EC2 security group are in different VPCs. The DB instance is in "
                                + vpcId + " and the EC2 security group is in " + groupVpcId, 400);
            }
        }
    }
}
