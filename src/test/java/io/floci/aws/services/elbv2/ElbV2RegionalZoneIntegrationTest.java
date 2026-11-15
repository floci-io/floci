package io.floci.aws.services.elbv2;

import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.ElasticLoadBalancingV2Exception;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancerNotFoundException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class ElbV2RegionalZoneIntegrationTest {
    @Test
    void oneServerUsesTheSdkRequestRegionForCreateAndSetSubnets() {
        try (ElasticLoadBalancingV2Client osaka = AwsTestClients.create(ElasticLoadBalancingV2Client.builder(),
                "000000000000", Region.AP_NORTHEAST_3);
             ElasticLoadBalancingV2Client virginia = AwsTestClients.create(ElasticLoadBalancingV2Client.builder(),
                     "000000000000", Region.US_EAST_1)) {
            String name = "regional-" + UUID.randomUUID().toString().substring(0, 12);
            ElasticLoadBalancingV2Exception rejected = assertThrows(ElasticLoadBalancingV2Exception.class,
                    () -> virginia.createLoadBalancer(r -> r.name(name).scheme("internal")
                            .type("application").subnets(Ec2Service.defaultSubnetId("us-east-1", "a"))));
            assertEquals("ValidationError", rejected.awsErrorDetails().errorCode());
            String arn = osaka.createLoadBalancer(r -> r.name(name).scheme("internal").type("application")
                    .subnets(Ec2Service.defaultSubnetId("ap-northeast-3", "a")))
                    .loadBalancers().getFirst().loadBalancerArn();
            try {
                osaka.setSubnets(r -> r.loadBalancerArn(arn)
                        .subnets(Ec2Service.defaultSubnetId("ap-northeast-3", "b")));
                assertEquals(1, osaka.describeLoadBalancers(r -> r.loadBalancerArns(arn))
                        .loadBalancers().getFirst().availabilityZones().size());
                assertEquals(Ec2Service.defaultSubnetId("ap-northeast-3", "b"),
                        osaka.describeLoadBalancers(r -> r.loadBalancerArns(arn))
                                .loadBalancers().getFirst().availabilityZones().getFirst().subnetId());
            } finally {
                osaka.deleteLoadBalancer(r -> r.loadBalancerArn(arn));
                assertThrows(LoadBalancerNotFoundException.class,
                        () -> osaka.describeLoadBalancers(r -> r.loadBalancerArns(arn)));
            }
        }
    }
}
