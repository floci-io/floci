package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.CreateHostedZoneRequest;
import software.amazon.awssdk.services.route53.model.CreateHostedZoneResponse;
import software.amazon.awssdk.services.route53.model.DeleteHostedZoneRequest;
import software.amazon.awssdk.services.route53.model.GetHostedZoneRequest;
import software.amazon.awssdk.services.route53.model.GetHostedZoneResponse;
import software.amazon.awssdk.services.route53.model.VPC;
import software.amazon.awssdk.services.route53.model.VPCRegion;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Route 53")
class Route53Test {

    private static Route53Client route53;

    @BeforeAll
    static void setup() {
        route53 = TestFixtures.route53Client();
    }

    @AfterAll
    static void cleanup() {
        if (route53 != null) {
            route53.close();
        }
    }

    @Test
    void createAndGetPrivateHostedZonePreservesVpcAssociation() {
        VPC vpc = VPC.builder()
                .vpcId("vpc-sdk-private")
                .vpcRegion(VPCRegion.US_WEST_2)
                .build();
        String zoneId = null;

        try {
            CreateHostedZoneResponse created = route53.createHostedZone(CreateHostedZoneRequest.builder()
                    .name(TestFixtures.uniqueName("private-zone") + ".example.com")
                    .callerReference(TestFixtures.uniqueName("private-zone-ref"))
                    .vpc(vpc)
                    .build());
            zoneId = created.hostedZone().id();

            assertThat(created.hostedZone().config().privateZone()).isTrue();
            assertThat(created.vpc().vpcId()).isEqualTo(vpc.vpcId());
            assertThat(created.vpc().vpcRegion()).isEqualTo(vpc.vpcRegion());

            GetHostedZoneResponse fetched = route53.getHostedZone(
                    GetHostedZoneRequest.builder().id(zoneId).build());

            assertThat(fetched.hostedZone().config().privateZone()).isTrue();
            assertThat(fetched.vpCs()).singleElement().satisfies(association -> {
                assertThat(association.vpcId()).isEqualTo(vpc.vpcId());
                assertThat(association.vpcRegion()).isEqualTo(vpc.vpcRegion());
            });
        } finally {
            if (zoneId != null) {
                route53.deleteHostedZone(DeleteHostedZoneRequest.builder().id(zoneId).build());
            }
        }
    }
}
