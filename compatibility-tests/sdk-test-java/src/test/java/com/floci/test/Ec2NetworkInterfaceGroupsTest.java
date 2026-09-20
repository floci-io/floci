package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.GroupIdentifier;

import static org.assertj.core.api.Assertions.assertThat;

class Ec2NetworkInterfaceGroupsTest {

    @Test
    @DisplayName("ModifyNetworkInterfaceAttribute replaces groups through SDK Query serialization")
    void replacesGroupsOnStandaloneInterface() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            String vpcId = ec2.createVpc(request -> request.cidrBlock("10.91.0.0/16")).vpc().vpcId();
            try {
                String subnetId = ec2.createSubnet(request -> request.vpcId(vpcId)
                        .cidrBlock("10.91.1.0/24")).subnet().subnetId();
                try {
                    String groupId = ec2.createSecurityGroup(request -> request.vpcId(vpcId)
                            .groupName(TestFixtures.uniqueName("eni-groups"))
                            .description("SDK ENI reassociation test")).groupId();
                    try {
                        String eniId = ec2.createNetworkInterface(request -> request.subnetId(subnetId))
                                .networkInterface().networkInterfaceId();
                        try {
                            ec2.modifyNetworkInterfaceAttribute(request -> request
                                    .networkInterfaceId(eniId).groups(groupId));

                            assertThat(ec2.describeNetworkInterfaces(request -> request.networkInterfaceIds(eniId))
                                    .networkInterfaces().getFirst().groups())
                                    .extracting(GroupIdentifier::groupId).containsExactly(groupId);
                        } finally {
                            ec2.deleteNetworkInterface(request -> request.networkInterfaceId(eniId));
                        }
                    } finally {
                        ec2.deleteSecurityGroup(request -> request.groupId(groupId));
                    }
                } finally {
                    ec2.deleteSubnet(request -> request.subnetId(subnetId));
                }
            } finally {
                ec2.deleteVpc(request -> request.vpcId(vpcId));
            }
        }
    }
}
