package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CreateVpcRequest;
import software.amazon.awssdk.services.ec2.model.DeleteVpcRequest;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.CreateHostedZoneRequest;
import software.amazon.awssdk.services.route53.model.AssociateVpcWithHostedZoneRequest;
import software.amazon.awssdk.services.route53.model.CreateHostedZoneResponse;
import software.amazon.awssdk.services.route53.model.CreateVpcAssociationAuthorizationRequest;
import software.amazon.awssdk.services.route53.model.DeleteHostedZoneRequest;
import software.amazon.awssdk.services.route53.model.DeleteVpcAssociationAuthorizationRequest;
import software.amazon.awssdk.services.route53.model.DisassociateVpcFromHostedZoneRequest;
import software.amazon.awssdk.services.route53.model.GetHostedZoneRequest;
import software.amazon.awssdk.services.route53.model.GetHostedZoneResponse;
import software.amazon.awssdk.services.route53.model.ListHostedZonesByVpcRequest;
import software.amazon.awssdk.services.route53.model.ListVpcAssociationAuthorizationsRequest;
import software.amazon.awssdk.services.route53.model.Route53Exception;
import software.amazon.awssdk.services.route53.model.VPC;
import software.amazon.awssdk.services.route53.model.VPCRegion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

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
    void crossAccountVpcAssociationLifecycleUsesAwsSdkWireContract() {
        assumeFalse(TestFixtures.isRealAws(),
                "Cross-account compatibility uses Floci account-routing credentials");

        String zoneAccount = "000000000401";
        String vpcAccount = "000000000402";

        try (Route53Client zoneRoute53 = TestFixtures.route53Client(zoneAccount);
             Route53Client vpcRoute53 = TestFixtures.route53Client(vpcAccount);
             Ec2Client zoneEc2 = TestFixtures.ec2Client(zoneAccount);
             Ec2Client vpcEc2 = TestFixtures.ec2Client(vpcAccount)) {

            String zoneVpcId = zoneEc2.createVpc(CreateVpcRequest.builder()
                    .cidrBlock("10.91.0.0/16")
                    .build()).vpc().vpcId();
            String spokeVpcId = vpcEc2.createVpc(CreateVpcRequest.builder()
                    .cidrBlock("10.92.0.0/16")
                    .build()).vpc().vpcId();

            assertThat(zoneEc2.describeVpcs().vpcs())
                    .anySatisfy(vpc -> {
                        assertThat(vpc.isDefault()).isTrue();
                        assertThat(vpc.ownerId()).isEqualTo(zoneAccount);
                    });
            assertThat(vpcEc2.describeVpcs().vpcs())
                    .anySatisfy(vpc -> {
                        assertThat(vpc.isDefault()).isTrue();
                        assertThat(vpc.ownerId()).isEqualTo(vpcAccount);
                    });
            try (Ec2Client defaultEc2 = TestFixtures.ec2Client()) {
                assertThat(defaultEc2.describeVpcs().vpcs())
                        .anySatisfy(vpc -> assertThat(vpc.isDefault()).isTrue());
            }

            String zoneId = null;

            try {
                VPC zoneVpc = VPC.builder()
                        .vpcId(zoneVpcId)
                        .vpcRegion(VPCRegion.US_EAST_1)
                        .build();
                VPC spokeVpc = VPC.builder()
                        .vpcId(spokeVpcId)
                        .vpcRegion(VPCRegion.US_EAST_1)
                        .build();

                zoneId = zoneRoute53.createHostedZone(CreateHostedZoneRequest.builder()
                        .name(TestFixtures.uniqueName("cross-account") + ".example.com")
                        .callerReference(TestFixtures.uniqueName("cross-account-ref"))
                        .vpc(zoneVpc)
                        .build()).hostedZone().id();
                final String activeZoneId = zoneId;

                Route53Exception notAuthorized = catchThrowableOfType(
                        () -> vpcRoute53.associateVPCWithHostedZone(
                                AssociateVpcWithHostedZoneRequest.builder()
                                        .hostedZoneId(activeZoneId)
                                        .vpc(spokeVpc)
                                        .build()),
                        Route53Exception.class);
                assertThat(notAuthorized).isNotNull();
                assertThat(notAuthorized.statusCode()).isEqualTo(401);
                assertThat(notAuthorized.awsErrorDetails().errorCode())
                        .isEqualTo("NotAuthorizedException");

                zoneRoute53.createVPCAssociationAuthorization(
                        CreateVpcAssociationAuthorizationRequest.builder()
                                .hostedZoneId(activeZoneId)
                                .vpc(spokeVpc)
                                .build());

                assertThat(zoneRoute53.listVPCAssociationAuthorizations(
                        ListVpcAssociationAuthorizationsRequest.builder()
                                .hostedZoneId(activeZoneId)
                                .build()).vpCs())
                        .singleElement()
                        .satisfies(vpc -> {
                            assertThat(vpc.vpcId()).isEqualTo(spokeVpcId);
                            assertThat(vpc.vpcRegion()).isEqualTo(VPCRegion.US_EAST_1);
                        });

                vpcRoute53.associateVPCWithHostedZone(
                        AssociateVpcWithHostedZoneRequest.builder()
                                .hostedZoneId(activeZoneId)
                                .vpc(spokeVpc)
                                .build());

                assertThat(vpcRoute53.listHostedZonesByVPC(
                        ListHostedZonesByVpcRequest.builder()
                                .vpcId(spokeVpcId)
                                .vpcRegion(VPCRegion.US_EAST_1)
                                .build()).hostedZoneSummaries())
                        .singleElement()
                        .satisfies(summary -> {
                            assertThat(summary.hostedZoneId()).isEqualTo(stripHostedZonePrefix(activeZoneId));
                            assertThat(summary.owner().owningAccount()).isEqualTo(zoneAccount);
                        });

                zoneRoute53.deleteVPCAssociationAuthorization(
                        DeleteVpcAssociationAuthorizationRequest.builder()
                                .hostedZoneId(activeZoneId)
                                .vpc(spokeVpc)
                                .build());

                assertThat(zoneRoute53.listVPCAssociationAuthorizations(
                        ListVpcAssociationAuthorizationsRequest.builder()
                                .hostedZoneId(activeZoneId)
                                .build()).vpCs()).isEmpty();

                assertThat(vpcRoute53.listHostedZonesByVPC(
                        ListHostedZonesByVpcRequest.builder()
                                .vpcId(spokeVpcId)
                                .vpcRegion(VPCRegion.US_EAST_1)
                                .build()).hostedZoneSummaries())
                        .singleElement();

                vpcRoute53.disassociateVPCFromHostedZone(
                        DisassociateVpcFromHostedZoneRequest.builder()
                                .hostedZoneId(activeZoneId)
                                .vpc(spokeVpc)
                                .build());

                Route53Exception requiresNewAuthorization = catchThrowableOfType(
                        () -> vpcRoute53.associateVPCWithHostedZone(
                                AssociateVpcWithHostedZoneRequest.builder()
                                        .hostedZoneId(activeZoneId)
                                        .vpc(spokeVpc)
                                        .build()),
                        Route53Exception.class);
                assertThat(requiresNewAuthorization).isNotNull();
                assertThat(requiresNewAuthorization.awsErrorDetails().errorCode())
                        .isEqualTo("NotAuthorizedException");
            } finally {
                if (zoneId != null) {
                    zoneRoute53.deleteHostedZone(DeleteHostedZoneRequest.builder().id(zoneId).build());
                }
                vpcEc2.deleteVpc(DeleteVpcRequest.builder().vpcId(spokeVpcId).build());
                zoneEc2.deleteVpc(DeleteVpcRequest.builder().vpcId(zoneVpcId).build());
            }
        }
    }

    private static String stripHostedZonePrefix(String hostedZoneId) {
        String prefix = "/hostedzone/";
        return hostedZoneId != null && hostedZoneId.startsWith(prefix)
                ? hostedZoneId.substring(prefix.length())
                : hostedZoneId;
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
