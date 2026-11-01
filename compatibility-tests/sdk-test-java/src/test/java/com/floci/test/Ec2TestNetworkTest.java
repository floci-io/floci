package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Ec2TestNetworkTest {
    @Test
    void unrelatedNetworkRemovalLeavesOwnedSubnetUsableAndCloseRemovesOnlyOwnedResources() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            String vpcId;
            String subnetId;
            try (Ec2TestNetwork survivor = Ec2TestNetwork.create(ec2)) {
                try (Ec2TestNetwork owned = Ec2TestNetwork.create(ec2)) {
                    vpcId = owned.vpcId();
                    subnetId = owned.subnetId();
                    try (Ec2TestNetwork unrelated = Ec2TestNetwork.create(ec2)) {
                        assertThat(unrelated.subnetId()).isNotEqualTo(subnetId);
                    }
                    assertThat(ec2.describeSubnets(request -> request.subnetIds(subnetId)).subnets())
                            .singleElement().satisfies(subnet -> assertThat(subnet.vpcId()).isEqualTo(vpcId));
                }
                assertThatThrownBy(() -> ec2.describeSubnets(request -> request.subnetIds(subnetId)))
                        .isInstanceOfSatisfying(Ec2Exception.class,
                                error -> assertThat(error.awsErrorDetails().errorCode()).isEqualTo("InvalidSubnetID.NotFound"));
                assertThatThrownBy(() -> ec2.describeVpcs(request -> request.vpcIds(vpcId)))
                        .isInstanceOfSatisfying(Ec2Exception.class,
                                error -> assertThat(error.awsErrorDetails().errorCode()).isEqualTo("InvalidVpcID.NotFound"));
                assertThat(ec2.describeSubnets(request -> request.subnetIds(survivor.subnetId())).subnets())
                        .singleElement().satisfies(subnet -> assertThat(subnet.vpcId()).isEqualTo(survivor.vpcId()));
            }
        }
    }
}
