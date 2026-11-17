package io.floci.aws.core.common;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.EnforcementFixtures.UserCredentials;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;

import java.net.URI;
import java.util.UUID;

import static io.floci.aws.testing.AwsSdkErrors.assertAwsError;
import static io.floci.aws.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class Ec2NetworkCreateIamIntegrationTest {

    @TestHTTPResource("/")
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void gatewayEndpointChecksEverySuppliedRouteTable() {
        String user = "endpoint-routes-" + UUID.randomUUID();
        UserCredentials credentials = EnforcementFixtures.createUser(cleanup, endpoint.getPort(),
                auth("000000000000", "iam"), user);
        try (Ec2Client admin = AwsTestClients.create(Ec2Client.builder(), "000000000000", Region.US_EAST_1, endpoint);
             Ec2Client client = AwsTestClients.create(Ec2Client.builder(), credentials.accessKeyId(),
                     credentials.secretAccessKey(), Region.US_EAST_1, endpoint)) {
            String vpc = admin.createVpc(r -> r.cidrBlock("10.204.0.0/16")).vpc().vpcId();
            String first = admin.createRouteTable(r -> r.vpcId(vpc)).routeTable().routeTableId();
            String second = admin.createRouteTable(r -> r.vpcId(vpc)).routeTable().routeTableId();
            String allow = """
                    {"Effect":"Allow","Action":"ec2:CreateVpcEndpoint",
                     "Resource":"arn:aws:ec2:us-east-1:000000000000:*"}
                    """;
            String deny = """
                    {"Effect":"Deny","Action":"ec2:CreateVpcEndpoint",
                     "Resource":"arn:aws:ec2:us-east-1:000000000000:route-table/%s"}
                    """.formatted(second);
            try {
                policy(user, allow + "," + deny);
                assertAwsError("UnauthorizedOperation", 403,
                        () -> client.createVpcEndpoint(r -> r.vpcId(vpc).vpcEndpointType("Gateway")
                        .serviceName("com.amazonaws.us-east-1.s3").routeTableIds(first, second)));
                policy(user, allow);
                String id = client.createVpcEndpoint(r -> r.vpcId(vpc).vpcEndpointType("Gateway")
                        .serviceName("com.amazonaws.us-east-1.s3").routeTableIds(first, second))
                        .vpcEndpoint().vpcEndpointId();
                admin.deleteVpcEndpoints(r -> r.vpcEndpointIds(id));
            } finally {
                admin.describeVpcEndpoints().vpcEndpoints().stream().filter(e -> vpc.equals(e.vpcId()))
                        .forEach(e -> admin.deleteVpcEndpoints(r -> r.vpcEndpointIds(e.vpcEndpointId())));
                admin.deleteRouteTable(r -> r.routeTableId(second));
                admin.deleteRouteTable(r -> r.routeTableId(first));
                admin.deleteVpc(r -> r.vpcId(vpc));
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"CreateSubnet,subnet", "CreateRouteTable,route-table", "CreateSecurityGroup,security-group",
            "CreateInternetGateway,internet-gateway", "CreateVpcEndpoint,vpc-endpoint", "AllocateAddress,elastic-ip"})
    void creationChecksNewResourceTagsAndParentOwnershipSeparately(String action, String type) {
        String name = "network-create-" + UUID.randomUUID();
        UserCredentials credentials = EnforcementFixtures.createUser(cleanup, endpoint.getPort(),
                auth("000000000000", "iam"), name);
        String resource = "arn:aws:ec2:us-east-1:000000000000:" + type + "/*";
        String create = """
                {"Effect":"Allow","Action":"ec2:%s","Resource":"%s",
                 "Condition":{"StringEquals":{"aws:RequestTag/ManagedBy":"integration-test"}}}
                """.formatted(action, resource);
        String tags = """
                {"Effect":"Allow","Action":"ec2:CreateTags","Resource":"%s",
                 "Condition":{"StringEquals":{"ec2:CreateAction":"%s"}}}
                """.formatted(resource, action);
        try (Ec2Client client = AwsTestClients.create(Ec2Client.builder(), credentials.accessKeyId(),
                credentials.secretAccessKey(), Region.US_EAST_1, endpoint);
             Ec2Client admin = AwsTestClients.create(Ec2Client.builder(), "000000000000", Region.US_EAST_1, endpoint)) {
            String vpc = admin.createVpc(r -> r.cidrBlock("10.201.0.0/16").tagSpecifications(
                    specification("vpc", "parent-owner"))).vpc().vpcId();
            String parentArn = "arn:aws:ec2:us-east-1:000000000000:vpc/" + vpc;
            String parent = """
                    {"Effect":"Allow","Action":"ec2:%s","Resource":"%s",
                     "Condition":{"StringEquals":{"aws:ResourceTag/ManagedBy":"parent-owner"}}}
                    """.formatted(action, parentArn);
            try {
                policy(name, create + "," + parent);
                assertAwsError("UnauthorizedOperation", 403,
                        () -> create(client, action, type, vpc, "integration-test"));
                policy(name, create + "," + tags + "," + parent);
                assertAwsError("UnauthorizedOperation", 403, () -> create(client, action, type, vpc, "wrong-owner"));
                String id = create(client, action, type, vpc, "integration-test");
                assertEquals("integration-test", admin.describeTags(r -> r.filters(f -> f.name("resource-id")
                        .values(id))).tags().stream().filter(t -> t.key().equals("ManagedBy")).findFirst()
                        .orElseThrow().value());
                delete(admin, action, id);
                policy(name, create + "," + tags + "," + parent + "," + create.replace("Allow", "Deny"));
                assertAwsError("UnauthorizedOperation", 403,
                        () -> create(client, action, type, vpc, "integration-test"));
                policy(name, create.replace(":000000000000:", ":111122223333:") + "," + tags + "," + parent);
                assertAwsError("UnauthorizedOperation", 403,
                        () -> create(client, action, type, vpc, "integration-test"));
                if (!action.equals("CreateInternetGateway") && !action.equals("AllocateAddress")) {
                    policy(name, create + "," + tags);
                    assertAwsError("UnauthorizedOperation", 403,
                            () -> create(client, action, type, vpc, "integration-test"));
                    policy(name, create + "," + tags + "," + parent.replace("parent-owner", "integration-test"));
                    assertAwsError("UnauthorizedOperation", 403,
                            () -> create(client, action, type, vpc, "integration-test"));
                    policy(name, create + "," + tags + "," + parent + "," + parent.replace("Allow", "Deny"));
                    assertAwsError("UnauthorizedOperation", 403,
                            () -> create(client, action, type, vpc, "integration-test"));
                } else {
                    policy(name, create + "," + tags);
                    delete(admin, action, create(client, action, type, vpc, "integration-test"));
                }
            } finally {
                admin.deleteVpc(r -> r.vpcId(vpc));
            }
        }
    }

    private String create(Ec2Client client, String action, String type, String vpc, String owner) {
        TagSpecification tags = specification(type, owner);
        return switch (action) {
            case "CreateSubnet" -> client.createSubnet(r -> r.vpcId(vpc).cidrBlock("10.201.1.0/24")
                    .tagSpecifications(tags)).subnet().subnetId();
            case "CreateRouteTable" -> client.createRouteTable(r -> r.vpcId(vpc).tagSpecifications(tags))
                    .routeTable().routeTableId();
            case "CreateSecurityGroup" -> client.createSecurityGroup(r -> r.vpcId(vpc)
                    .groupName("network-" + UUID.randomUUID()).description("IAM integration test")
                    .tagSpecifications(tags)).groupId();
            case "CreateVpcEndpoint" -> client.createVpcEndpoint(r -> r.vpcId(vpc).vpcEndpointType("Gateway")
                    .serviceName("com.amazonaws.us-east-1.s3").tagSpecifications(tags)).vpcEndpoint().vpcEndpointId();
            case "CreateInternetGateway" -> client.createInternetGateway(r -> r.tagSpecifications(tags))
                    .internetGateway().internetGatewayId();
            case "AllocateAddress" -> client.allocateAddress(r -> r.domain("vpc").tagSpecifications(tags)).allocationId();
            default -> throw new IllegalArgumentException(action);
        };
    }

    private void delete(Ec2Client admin, String action, String id) {
        switch (action) {
            case "CreateSubnet" -> admin.deleteSubnet(r -> r.subnetId(id));
            case "CreateRouteTable" -> admin.deleteRouteTable(r -> r.routeTableId(id));
            case "CreateSecurityGroup" -> admin.deleteSecurityGroup(r -> r.groupId(id));
            case "CreateVpcEndpoint" -> admin.deleteVpcEndpoints(r -> r.vpcEndpointIds(id));
            case "CreateInternetGateway" -> admin.deleteInternetGateway(r -> r.internetGatewayId(id));
            case "AllocateAddress" -> admin.releaseAddress(r -> r.allocationId(id));
            default -> throw new IllegalArgumentException(action);
        }
    }

    private TagSpecification specification(String type, String owner) {
        return TagSpecification.builder().resourceType(type)
                .tags(Tag.builder().key("ManagedBy").value(owner).build()).build();
    }


    private void policy(String name, String statements) {
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), auth("000000000000", "iam"), name,
                "network", "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}");
    }
}
