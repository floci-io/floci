package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancerSchemeEnum;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancerTypeEnum;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("EC2 subnet and VPC lifecycle dependencies")
class Ec2ResourceLifecycleTest {

    @Test
    @DisplayName("DeleteSubnet and DeleteVpc are refused while a load balancer uses the subnet")
    void subnetAndVpcAreRefusedWhileALoadBalancerUsesThem() {
        String region = TestFixtures.region().id();
        try (Ec2Client ec2 = TestFixtures.ec2Client();
             ElasticLoadBalancingV2Client elb = TestFixtures.elbV2Client()) {
            String vpcId = ec2.createVpc(r -> r.cidrBlock("10.94.0.0/16")).vpc().vpcId();
            String first = ec2.createSubnet(r -> r.vpcId(vpcId).cidrBlock("10.94.1.0/24")
                    .availabilityZone(region + "a")).subnet().subnetId();
            String second = ec2.createSubnet(r -> r.vpcId(vpcId).cidrBlock("10.94.2.0/24")
                    .availabilityZone(region + "b")).subnet().subnetId();
            String loadBalancerArn = elb.createLoadBalancer(r -> r.name(TestFixtures.uniqueName("lifecycle"))
                    .type(LoadBalancerTypeEnum.APPLICATION).scheme(LoadBalancerSchemeEnum.INTERNAL)
                    .subnets(first, second)).loadBalancers().get(0).loadBalancerArn();
            try {
                assertThatThrownBy(() -> ec2.deleteSubnet(r -> r.subnetId(first)))
                        .isInstanceOfSatisfying(Ec2Exception.class, e ->
                                assertThat(e.awsErrorDetails().errorCode()).isEqualTo("DependencyViolation"));
                assertThatThrownBy(() -> ec2.deleteVpc(r -> r.vpcId(vpcId)))
                        .isInstanceOfSatisfying(Ec2Exception.class, e ->
                                assertThat(e.awsErrorDetails().errorCode()).isEqualTo("DependencyViolation"));
            } finally {
                elb.deleteLoadBalancer(r -> r.loadBalancerArn(loadBalancerArn));
            }
            ec2.deleteSubnet(r -> r.subnetId(first));
            ec2.deleteSubnet(r -> r.subnetId(second));
            ec2.deleteVpc(r -> r.vpcId(vpcId));
        }
    }
}
