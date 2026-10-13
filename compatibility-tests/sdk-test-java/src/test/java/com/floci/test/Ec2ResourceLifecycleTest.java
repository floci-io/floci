package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Image;
import software.amazon.awssdk.services.ec2.model.KeyPairInfo;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancerSchemeEnum;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancerTypeEnum;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("EC2 resource identity and lifecycle dependencies")
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

    @Test
    @DisplayName("DescribeKeyPairs returns the public key only when IncludePublicKey is true")
    void describeKeyPairsReturnsThePublicKeyOnlyWhenAsked() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            String name = TestFixtures.uniqueName("lifecycle-key");
            String keyPairId = ec2.createKeyPair(r -> r.keyName(name)).keyPairId();
            try {
                KeyPairInfo without = ec2.describeKeyPairs(r -> r.keyPairIds(keyPairId)).keyPairs().get(0);
                KeyPairInfo with = ec2.describeKeyPairs(r -> r.keyPairIds(keyPairId).includePublicKey(true))
                        .keyPairs().get(0);

                assertThat(without.publicKey()).isNull();
                assertThat(with.publicKey()).startsWith("ssh-rsa ").endsWith(" " + name);
            } finally {
                ec2.deleteKeyPair(r -> r.keyPairId(keyPairId));
            }
        }
    }

    @Test
    @DisplayName("A copied image reports its source image and region, and the source filters find it")
    void aCopiedImageReportsItsSourceAndTheSourceFiltersFindIt() {
        String region = TestFixtures.region().id();
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            String sourceId = ec2.registerImage(r -> r.name(TestFixtures.uniqueName("lifecycle-source")))
                    .imageId();
            try {
                String copyId = ec2.copyImage(r -> r.sourceImageId(sourceId).sourceRegion(region)
                        .name(TestFixtures.uniqueName("lifecycle-copy"))).imageId();
                try {
                    Image copy = ec2.describeImages(r -> r.imageIds(copyId)).images().get(0);

                    assertThat(copy.sourceImageId()).isEqualTo(sourceId);
                    assertThat(copy.sourceImageRegion()).isEqualTo(region);
                    assertThat(copy.sourceInstanceId()).isNull();
                    assertThat(ec2.describeImages(r -> r.filters(Filter.builder()
                                    .name("source-image-id").values(sourceId).build())).images())
                            .extracting(Image::imageId).containsExactly(copyId);
                } finally {
                    ec2.deregisterImage(r -> r.imageId(copyId));
                }
            } finally {
                ec2.deregisterImage(r -> r.imageId(sourceId));
            }
        }
    }
}
