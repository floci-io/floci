package io.floci.aws.core.common;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.EnforcementFixtures.UserCredentials;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.IpPermission;
import software.amazon.awssdk.services.ec2.model.RouteTableAssociation;
import software.amazon.awssdk.services.ec2.model.TagSpecification;

import java.net.URI;
import java.util.UUID;

import static io.floci.aws.testing.AwsSdkErrors.assertAwsError;
import static io.floci.aws.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class Ec2NetworkMutationIamIntegrationTest {
    @TestHTTPResource("/")
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @ValueSource(strings = {"ModifySubnetAttribute", "AttachInternetGateway", "DetachInternetGateway",
            "CreateRoute", "ReplaceRoute", "DeleteRoute", "AssociateRouteTable",
            "AuthorizeSecurityGroupIngress", "AuthorizeSecurityGroupEgress", "RevokeSecurityGroupIngress",
            "RevokeSecurityGroupEgress", "DeleteVpc", "DeleteSubnet", "DeleteRouteTable",
            "DeleteInternetGateway", "DeleteSecurityGroup"})
    void scopedMutationsReadCurrentOwnershipAndHonorDenies(String action) {
        String user = "network-mutation-" + UUID.randomUUID();
        UserCredentials credentials = EnforcementFixtures.createUser(cleanup, endpoint.getPort(),
                auth("000000000000", "iam"), user);
        try (Ec2Client client = AwsTestClients.create(Ec2Client.builder(), credentials.accessKeyId(),
                credentials.secretAccessKey(), Region.US_EAST_1, endpoint);
             Ec2Client admin = AwsTestClients.create(Ec2Client.builder(), "000000000000", Region.US_EAST_1, endpoint)) {
            String vpc = admin.createVpc(r -> r.cidrBlock("10.202.0.0/16").tagSpecifications(tags("vpc"))).vpc().vpcId();
            String subnet = admin.createSubnet(r -> r.vpcId(vpc).cidrBlock("10.202.1.0/24")
                    .tagSpecifications(tags("subnet"))).subnet().subnetId();
            String table = admin.createRouteTable(r -> r.vpcId(vpc).tagSpecifications(tags("route-table")))
                    .routeTable().routeTableId();
            String gateway = admin.createInternetGateway(r -> r.tagSpecifications(tags("internet-gateway")))
                    .internetGateway().internetGatewayId();
            String group = admin.createSecurityGroup(r -> r.vpcId(vpc).groupName("scoped-" + UUID.randomUUID())
                    .description("IAM mutation fixture").tagSpecifications(tags("security-group"))).groupId();
            IpPermission permission = IpPermission.builder().ipProtocol("tcp").fromPort(443).toPort(443)
                    .ipRanges(r -> r.cidrIp("192.0.2.0/24")).build();
            if (action.equals("DetachInternetGateway") || action.endsWith("Route")) {
                admin.attachInternetGateway(r -> r.internetGatewayId(gateway).vpcId(vpc));
            }
            if (action.equals("ReplaceRoute") || action.equals("DeleteRoute")) {
                admin.createRoute(r -> r.routeTableId(table).gatewayId(gateway).destinationCidrBlock("0.0.0.0/0"));
            }
            if (action.equals("RevokeSecurityGroupIngress")) {
                admin.authorizeSecurityGroupIngress(r -> r.groupId(group).ipPermissions(permission));
            }
            if (action.equals("RevokeSecurityGroupEgress")) {
                admin.authorizeSecurityGroupEgress(r -> r.groupId(group).ipPermissions(permission));
            }
            if (action.equals("DeleteVpc")) {
                admin.deleteSubnet(r -> r.subnetId(subnet));
                admin.deleteRouteTable(r -> r.routeTableId(table));
                admin.deleteSecurityGroup(r -> r.groupId(group));
            }
            Runnable mutate = () -> {
                switch (action) {
                    case "ModifySubnetAttribute" -> client.modifySubnetAttribute(r -> r.subnetId(subnet)
                            .mapPublicIpOnLaunch(v -> v.value(true)));
                    case "AttachInternetGateway" -> client.attachInternetGateway(r -> r.internetGatewayId(gateway).vpcId(vpc));
                    case "DetachInternetGateway" -> client.detachInternetGateway(r -> r.internetGatewayId(gateway).vpcId(vpc));
                    case "CreateRoute" -> client.createRoute(r -> r.routeTableId(table).gatewayId(gateway)
                            .destinationCidrBlock("0.0.0.0/0"));
                    case "ReplaceRoute" -> client.replaceRoute(r -> r.routeTableId(table).gatewayId(gateway)
                            .destinationCidrBlock("0.0.0.0/0"));
                    case "DeleteRoute" -> client.deleteRoute(r -> r.routeTableId(table).destinationCidrBlock("0.0.0.0/0"));
                    case "AssociateRouteTable" -> client.associateRouteTable(r -> r.routeTableId(table).subnetId(subnet));
                    case "AuthorizeSecurityGroupIngress" -> client.authorizeSecurityGroupIngress(r -> r.groupId(group)
                            .ipPermissions(permission));
                    case "AuthorizeSecurityGroupEgress" -> client.authorizeSecurityGroupEgress(r -> r.groupId(group)
                            .ipPermissions(permission));
                    case "RevokeSecurityGroupIngress" -> client.revokeSecurityGroupIngress(r -> r.groupId(group)
                            .ipPermissions(permission));
                    case "RevokeSecurityGroupEgress" -> client.revokeSecurityGroupEgress(r -> r.groupId(group)
                            .ipPermissions(permission));
                    case "DeleteVpc" -> client.deleteVpc(r -> r.vpcId(vpc));
                    case "DeleteSubnet" -> client.deleteSubnet(r -> r.subnetId(subnet));
                    case "DeleteRouteTable" -> client.deleteRouteTable(r -> r.routeTableId(table));
                    case "DeleteInternetGateway" -> client.deleteInternetGateway(r -> r.internetGatewayId(gateway));
                    case "DeleteSecurityGroup" -> client.deleteSecurityGroup(r -> r.groupId(group));
                    default -> throw new IllegalArgumentException(action);
                }
            };
            String grant = """
                    {"Effect":"Allow","Action":"ec2:%s","Resource":"arn:aws:ec2:us-east-1:000000000000:*",
                     "Condition":{"StringEquals":{"aws:ResourceTag/Owner":"integration-test"}}}
                    """.formatted(action);
            // New rule IDs have no stored tags; authorization of the parent group remains required.
            String rules = """
                    ,{"Effect":"Allow","Action":"ec2:%s",
                      "Resource":"arn:aws:ec2:us-east-1:000000000000:security-group-rule/*"}
                    """.formatted(action);
            try {
                policy(user, grant.replace("integration-test", "wrong-owner") + rules);
                assertAwsError("UnauthorizedOperation", 403, mutate::run);
                policy(user, grant.replace(":000000000000:", ":111122223333:") + rules);
                assertAwsError("UnauthorizedOperation", 403, mutate::run);
                policy(user, grant + rules + "," + grant.replace("Allow", "Deny"));
                assertAwsError("UnauthorizedOperation", 403, mutate::run);
                policy(user, grant + rules);
                mutate.run();
                if (action.equals("ModifySubnetAttribute")) {
                    assertTrue(admin.describeSubnets(r -> r.subnetIds(subnet)).subnets().getFirst().mapPublicIpOnLaunch());
                }
            } finally {
                if (!action.equals("DeleteVpc")) {
                    if (!action.equals("DeleteRouteTable")) {
                        for (RouteTableAssociation association : admin.describeRouteTables(r -> r.routeTableIds(table))
                                .routeTables().getFirst().associations()) {
                            if (!Boolean.TRUE.equals(association.main())) {
                                admin.disassociateRouteTable(r -> r.associationId(association.routeTableAssociationId()));
                            }
                        }
                        remove(() -> admin.deleteRouteTable(r -> r.routeTableId(table)));
                    }
                    remove(() -> admin.deleteSubnet(r -> r.subnetId(subnet)));
                    remove(() -> admin.deleteSecurityGroup(r -> r.groupId(group)));
                }
                remove(() -> admin.detachInternetGateway(r -> r.internetGatewayId(gateway).vpcId(vpc)));
                remove(() -> admin.deleteInternetGateway(r -> r.internetGatewayId(gateway)));
                remove(() -> admin.deleteVpc(r -> r.vpcId(vpc)));
            }
        }
    }

    private TagSpecification tags(String type) {
        return TagSpecification.builder().resourceType(type).tags(t -> t.key("Owner").value("integration-test")).build();
    }


    private void remove(Runnable cleanup) {
        try {
            cleanup.run();
        } catch (Ec2Exception expected) {
            // The action under test may already have removed or detached this fixture.
            if (!expected.awsErrorDetails().errorCode().endsWith(".NotFound")
                    && !expected.awsErrorDetails().errorCode().equals("Gateway.NotAttached")) {
                throw expected;
            }
        }
    }

    private void policy(String user, String statements) {
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), auth("000000000000", "iam"), user,
                "mutation", "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}");
    }
}
