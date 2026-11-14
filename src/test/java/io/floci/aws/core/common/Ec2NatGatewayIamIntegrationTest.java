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
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;

import java.net.URI;
import java.util.UUID;

import static io.floci.aws.testing.AwsSdkErrors.assertAwsError;
import static io.floci.aws.testing.EnforcementFixtures.auth;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class Ec2NatGatewayIamIntegrationTest {

    @TestHTTPResource("/")
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void natCreationRequiresBothOwnedParentsAndSeparatelyAuthorizedTags() {
        String name = "nat-iam-" + UUID.randomUUID();
        UserCredentials credentials = EnforcementFixtures.createUser(cleanup, endpoint.getPort(),
                auth("000000000000", "iam"), name);
        try (Ec2Client admin = AwsTestClients.create(Ec2Client.builder(), "000000000000", Region.US_EAST_1, endpoint);
             Ec2Client client = AwsTestClients.create(Ec2Client.builder(), credentials.accessKeyId(),
                     credentials.secretAccessKey(), Region.US_EAST_1, endpoint)) {
            String vpc = admin.createVpc(r -> r.cidrBlock("10.205.0.0/16")).vpc().vpcId();
            String subnet = admin.createSubnet(r -> r.vpcId(vpc).cidrBlock("10.205.1.0/24")
                    .tagSpecifications(tags("subnet", "owner"))).subnet().subnetId();
            String allocation = admin.allocateAddress(r -> r.domain("vpc")
                    .tagSpecifications(tags("elastic-ip", "foreign"))).allocationId();
            String created = """
                    {"Effect":"Allow","Action":"ec2:CreateNatGateway",
                     "Resource":"arn:aws:ec2:us-east-1:000000000000:natgateway/*",
                     "Condition":{"StringEquals":{"aws:RequestTag/Owner":"owner"}}}
                    """;
            String parents = """
                    {"Effect":"Allow","Action":"ec2:CreateNatGateway",
                     "Resource":["arn:aws:ec2:us-east-1:000000000000:subnet/%s",
                                 "arn:aws:ec2:us-east-1:000000000000:elastic-ip/%s"],
                     "Condition":{"StringEquals":{"aws:ResourceTag/Owner":"owner"}}}
                    """.formatted(subnet, allocation);
            String tag = """
                    {"Effect":"Allow","Action":"ec2:CreateTags",
                     "Resource":"arn:aws:ec2:us-east-1:000000000000:natgateway/*",
                     "Condition":{"StringEquals":{"ec2:CreateAction":"CreateNatGateway"}}}
                    """;
            try {
                policy(name, created + "," + parents + "," + tag);
                assertAwsError("UnauthorizedOperation", 403, () -> create(client, subnet, allocation));
                admin.createTags(r -> r.resources(allocation).tags(Tag.builder().key("Owner").value("owner").build()));
                policy(name, created + "," + tag);
                assertAwsError("UnauthorizedOperation", 403, () -> create(client, subnet, allocation));
                policy(name, created + "," + parents);
                assertAwsError("UnauthorizedOperation", 403, () -> create(client, subnet, allocation));
                policy(name, created + "," + parents + "," + tag + "," + """
                        {"Effect":"Deny","Action":"ec2:CreateNatGateway",
                         "Resource":"arn:aws:ec2:us-east-1:000000000000:elastic-ip/%s"}
                        """.formatted(allocation));
                assertAwsError("UnauthorizedOperation", 403, () -> create(client, subnet, allocation));
                policy(name, created + "," + parents + "," + tag);
                create(client, subnet, allocation);
            } finally {
                admin.describeNatGateways().natGateways().stream().filter(n -> vpc.equals(n.vpcId()))
                        .forEach(n -> admin.deleteNatGateway(r -> r.natGatewayId(n.natGatewayId())));
                admin.releaseAddress(r -> r.allocationId(allocation));
                admin.deleteSubnet(r -> r.subnetId(subnet));
                admin.deleteVpc(r -> r.vpcId(vpc));
            }
        }
    }

    private void policy(String name, String statements) {
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), auth("000000000000", "iam"), name, "nat",
                "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}");
    }

    private static void create(Ec2Client client, String subnet, String allocation) {
        client.createNatGateway(r -> r.subnetId(subnet).allocationId(allocation)
                .tagSpecifications(tags("natgateway", "owner")));
    }

    private static TagSpecification tags(String type, String owner) {
        return TagSpecification.builder().resourceType(type)
                .tags(Tag.builder().key("Owner").value(owner).build()).build();
    }
}
