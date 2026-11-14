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

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static io.floci.aws.testing.AwsSdkErrors.assertAwsError;
import static io.floci.aws.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class Ec2NetworkTagsIamIntegrationTest {

    @TestHTTPResource("/")
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void networkTagBatchesAuthorizeEachResourceAndItsOwnStoredTags() {
        String user = "network-tags-" + UUID.randomUUID();
        UserCredentials credentials = EnforcementFixtures.createUser(cleanup, endpoint.getPort(),
                auth("000000000000", "iam"), user);
        try (Ec2Client admin = AwsTestClients.create(Ec2Client.builder(), "000000000000", Region.US_EAST_1, endpoint);
             Ec2Client client = AwsTestClients.create(Ec2Client.builder(), credentials.accessKeyId(),
                     credentials.secretAccessKey(), Region.US_EAST_1, endpoint)) {
            String vpc = admin.createVpc(r -> r.cidrBlock("10.206.0.0/16")).vpc().vpcId();
            String subnet = admin.createSubnet(r -> r.vpcId(vpc).cidrBlock("10.206.1.0/24")).subnet().subnetId();
            String group = admin.describeSecurityGroups(r -> r.filters(f -> f.name("vpc-id").values(vpc)))
                    .securityGroups().getFirst().groupId();
            String acl = admin.describeNetworkAcls(r -> r.filters(f -> f.name("vpc-id").values(vpc)))
                    .networkAcls().getFirst().networkAclId();
            String route = admin.describeRouteTables(r -> r.filters(f -> f.name("vpc-id").values(vpc)))
                    .routeTables().getFirst().routeTableId();
            String eni = admin.createNetworkInterface(r -> r.subnetId(subnet).groups(group))
                    .networkInterface().networkInterfaceId();
            List<String> ids = List.of(group, acl, route, eni);
            String resources = """
                    ["arn:aws:ec2:us-east-1:000000000000:security-group/*",
                     "arn:aws:ec2:us-east-1:000000000000:network-acl/*",
                     "arn:aws:ec2:us-east-1:000000000000:route-table/*",
                     "arn:aws:ec2:us-east-1:000000000000:network-interface/*"]
                    """;
            String grant = """
                    {"Effect":"Allow","Action":"ec2:CreateTags","Resource":%s,
                     "Condition":{"StringEquals":{"aws:RequestTag/Owner":"owner"},
                      "ForAllValues:StringEquals":{"aws:TagKeys":["Owner"]},
                      "StringEqualsIfExists":{"aws:ResourceTag/Owner":"${aws:RequestTag/Owner}"},
                      "Null":{"aws:ResourceTag/ManagedBy":"true"}}},
                    {"Effect":"Allow","Action":"ec2:DeleteTags","Resource":%s,
                     "Condition":{"StringEquals":{"aws:ResourceTag/Owner":"owner"}}}
                    """.formatted(resources, resources);
            try {
                policy(user, grant);
                client.createTags(r -> r.resources(ids).tags(owner("owner")));
                assertEquals(4, admin.describeTags(r -> r.filters(f -> f.name("resource-id").values(ids)))
                        .tags().stream().filter(t -> "Owner".equals(t.key()) && "owner".equals(t.value())).count());
                admin.createTags(r -> r.resources(eni).tags(owner("foreign")));
                assertAwsError("UnauthorizedOperation", 403,
                        () -> client.createTags(r -> r.resources(ids).tags(owner("owner"))));
                assertAwsError("UnauthorizedOperation", 403,
                        () -> client.deleteTags(r -> r.resources(ids).tags(owner("owner"))));
                assertEquals(4, admin.describeTags(r -> r.filters(f -> f.name("resource-id").values(ids)))
                        .tags().stream().filter(t -> "Owner".equals(t.key())).count());
                admin.createTags(r -> r.resources(eni).tags(owner("owner")));
                policy(user, grant + "," + """
                        {"Effect":"Deny","Action":["ec2:CreateTags","ec2:DeleteTags"],
                         "Resource":"arn:aws:ec2:us-east-1:000000000000:network-interface/%s"}
                        """.formatted(eni));
                assertAwsError("UnauthorizedOperation", 403,
                        () -> client.createTags(r -> r.resources(ids).tags(owner("owner"))));
                assertAwsError("UnauthorizedOperation", 403,
                        () -> client.deleteTags(r -> r.resources(ids).tags(owner("owner"))));
                policy(user, grant);
                // A resource type not covered by the grants must not borrow a preceding target's ARN.
                assertAwsError("UnauthorizedOperation", 403,
                        () -> client.createTags(r -> r.resources(group, vpc).tags(owner("owner"))));
                client.deleteTags(r -> r.resources(ids).tags(owner("owner")));
                assertTrue(admin.describeTags(r -> r.filters(f -> f.name("resource-id").values(ids))).tags().stream()
                        .noneMatch(t -> "Owner".equals(t.key())));
            } finally {
                admin.deleteNetworkInterface(r -> r.networkInterfaceId(eni));
                admin.deleteSubnet(r -> r.subnetId(subnet));
                admin.deleteVpc(r -> r.vpcId(vpc));
            }
        }
    }

    private void policy(String user, String statements) {
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), auth("000000000000", "iam"), user, "tags",
                "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}");
    }

    private static Tag owner(String value) {
        return Tag.builder().key("Owner").value(value).build();
    }
}
