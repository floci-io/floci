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
import software.amazon.awssdk.services.ec2.model.CreateVpcRequest;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;

import java.net.URI;
import java.util.UUID;

import static io.floci.aws.testing.AwsSdkErrors.assertAwsError;
import static io.floci.aws.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class Ec2CreateVpcIamIntegrationTest {

    @TestHTTPResource("/")
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1", "cn-north-1"})
    void scopedCreateRequiresRequestTagsAndTagPermissionAndHonorsExplicitDeny(String region) {
        String name = "vpc-create-" + UUID.randomUUID();
        UserCredentials credentials = EnforcementFixtures.createUser(cleanup, endpoint.getPort(),
                auth("000000000000", "iam"), name);
        String resource = AwsArnUtils.Arn.of("ec2", region, "000000000000", "vpc/*").toString();
        String create = """
                {"Effect":"Allow","Action":"ec2:CreateVpc","Resource":"%s",
                 "Condition":{"StringEquals":{"aws:RequestTag/ManagedBy":"integration-test"}}}
                """.formatted(resource);
        String tag = """
                {"Effect":"Allow","Action":"ec2:CreateTags","Resource":"%s",
                 "Condition":{"StringEquals":{"ec2:CreateAction":"CreateVpc"}}}
                """.formatted(resource);
        try (Ec2Client client = AwsTestClients.create(Ec2Client.builder(), credentials.accessKeyId(),
                credentials.secretAccessKey(), Region.of(region), endpoint);
             Ec2Client admin = AwsTestClients.create(Ec2Client.builder(), "000000000000", Region.of(region), endpoint)) {
            CreateVpcRequest request = tagged("integration-test");
            policy(name, create);
            denied(client, request);
            policy(name, create + "," + tag);
            denied(client, CreateVpcRequest.builder().cidrBlock("10.200.0.0/16").build());
            denied(client, tagged("other"));
            String vpcId = client.createVpc(request).vpc().vpcId();
            try {
                assertTrue(vpcId.startsWith("vpc-"));
                assertTrue(admin.describeVpcs(r -> r.vpcIds(vpcId)).vpcs().getFirst().tags().stream()
                        .anyMatch(t -> t.key().equals("ManagedBy") && t.value().equals("integration-test")));
            } finally {
                admin.deleteVpc(r -> r.vpcId(vpcId));
            }
            policy(name, create + "," + tag + "," + """
                    {"Effect":"Deny","Action":"ec2:CreateVpc","Resource":"%s"}
                    """.formatted(resource));
            denied(client, request);
            policy(name, create.replace(":000000000000:", ":111122223333:") + "," + tag);
            denied(client, request);
            policy(name, create + "," + tag + "," + """
                    {"Effect":"Deny","Action":"ec2:CreateTags","Resource":"%s"}
                    """.formatted(resource));
            denied(client, request);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ipv4-ipam", "ipv6-ipam", "ipv6-pool"})
    void optionalPoolsNeedTheirOwnGrantAndTagsAreCheckedOnlyOnTheVpc(String pool) {
        String name = "vpc-pool-" + UUID.randomUUID();
        UserCredentials credentials = EnforcementFixtures.createUser(cleanup, endpoint.getPort(),
                auth("000000000000", "iam"), name);
        String resource = pool.equals("ipv6-pool")
                ? "arn:aws:ec2:us-east-1:000000000000:ipv6pool-ec2/ipv6pool-one"
                : "arn:aws:ec2::000000000000:ipam-pool/ipam-pool-one";
        CreateVpcRequest.Builder request = tagged("integration-test").toBuilder();
        switch (pool) {
            case "ipv4-ipam" -> request.ipv4IpamPoolId("ipam-pool-one");
            case "ipv6-ipam" -> request.ipv6IpamPoolId("ipam-pool-one");
            default -> request.ipv6Pool("ipv6pool-one");
        }
        String vpc = """
                {"Effect":"Allow","Action":["ec2:CreateVpc","ec2:CreateTags"],
                 "Resource":"arn:aws:ec2:us-east-1:000000000000:vpc/*"}
                """;
        String grant = """
                {"Effect":"Allow","Action":"ec2:CreateVpc","Resource":"%s"}
                """.formatted(resource);
        try (Ec2Client client = AwsTestClients.create(Ec2Client.builder(), credentials.accessKeyId(),
                credentials.secretAccessKey(), Region.US_EAST_1, endpoint);
             Ec2Client admin = AwsTestClients.create(Ec2Client.builder(), "000000000000", Region.US_EAST_1, endpoint)) {
            policy(name, vpc);
            denied(client, request.build());
            policy(name, vpc + "," + grant);
            String vpcId = client.createVpc(request.build()).vpc().vpcId();
            admin.deleteVpc(r -> r.vpcId(vpcId));
            policy(name, vpc + "," + grant + "," + grant.replace("Allow", "Deny"));
            denied(client, request.build());
        }
    }

    private CreateVpcRequest tagged(String owner) {
        return CreateVpcRequest.builder().cidrBlock("10.200.0.0/16")
                .tagSpecifications(TagSpecification.builder().resourceType(ResourceType.VPC)
                        .tags(Tag.builder().key("ManagedBy").value(owner).build()).build()).build();
    }

    private void denied(Ec2Client client, CreateVpcRequest request) {
        assertAwsError("UnauthorizedOperation", 403, () -> client.createVpc(request));
    }

    private void policy(String name, String statements) {
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), auth("000000000000", "iam"), name,
                "create", "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}");
    }
}
