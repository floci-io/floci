package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.ElasticLoadBalancingV2Exception;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Ec2DescribeSubnetsNotFoundTest {
    private static final String MISSING = "subnet-0000000000000dead";

    @Test
    @DisplayName("DescribeSubnets raises InvalidSubnetID.NotFound for an id that does not exist")
    void missingSubnetIdIsAnSdkError() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            Ec2Exception error = assertThrows(Ec2Exception.class,
                    () -> ec2.describeSubnets(r -> r.subnetIds(MISSING)));
            assertThat(error.statusCode()).isEqualTo(400);
            assertThat(error.awsErrorDetails().errorCode()).isEqualTo("InvalidSubnetID.NotFound");

            String vpcId = ec2.createVpc(r -> r.cidrBlock("10.75.0.0/16")).vpc().vpcId();
            String subnetId = null;
            try {
                subnetId = ec2.createSubnet(r -> r.vpcId(vpcId).cidrBlock("10.75.1.0/24"))
                        .subnet().subnetId();
                String created = subnetId;

                assertThat(ec2.describeSubnets(r -> r.subnetIds(created)).subnets())
                        .singleElement().satisfies(s -> assertThat(s.vpcId()).isEqualTo(vpcId));

                Ec2Exception mixed = assertThrows(Ec2Exception.class,
                        () -> ec2.describeSubnets(r -> r.subnetIds(created, MISSING)));
                assertThat(mixed.awsErrorDetails().errorCode()).isEqualTo("InvalidSubnetID.NotFound");
            } finally {
                if (subnetId != null) {
                    String cleanup = subnetId;
                    ec2.deleteSubnet(r -> r.subnetId(cleanup));
                }
                ec2.deleteVpc(r -> r.vpcId(vpcId));
            }
        }
    }

    /**
     * describeSubnets is shared with the services that build on it, and each reports a missing
     * subnet in its own words. ELBv2 owes its callers SubnetNotFound, so the EC2 error must not
     * reach through.
     */
    @Test
    @DisplayName("ELBv2 still reports its own SubnetNotFound for a missing subnet")
    void elbV2KeepsItsOwnSubnetError() {
        try (ElasticLoadBalancingV2Client elb = TestFixtures.elbV2Client()) {
            ElasticLoadBalancingV2Exception error = assertThrows(
                    ElasticLoadBalancingV2Exception.class,
                    () -> elb.createLoadBalancer(r -> r.name("lb-" + UUID.randomUUID().toString().substring(0, 8))
                            .type("application")
                            .subnets(MISSING)));
            assertThat(error.awsErrorDetails().errorCode()).isEqualTo("SubnetNotFound");
        }
    }
}
