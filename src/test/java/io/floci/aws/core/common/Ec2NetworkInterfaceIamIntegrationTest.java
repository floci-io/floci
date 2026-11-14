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
import software.amazon.awssdk.services.ec2.model.NetworkInterface;
import software.amazon.awssdk.services.ec2.model.TagSpecification;

import java.net.URI;
import java.util.UUID;

import static io.floci.aws.testing.AwsSdkErrors.assertAwsError;
import static io.floci.aws.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class Ec2NetworkInterfaceIamIntegrationTest {
    @TestHTTPResource("/")
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void creationChecksSubnetAndEverySecurityGroupWithTheirOwnTags() {
        String user = "eni-create-" + UUID.randomUUID();
        UserCredentials credentials = EnforcementFixtures.createUser(cleanup, endpoint.getPort(),
                auth("000000000000", "iam"), user);
        try (Ec2Client admin = AwsTestClients.create(Ec2Client.builder(), "000000000000", Region.US_EAST_1, endpoint);
             Ec2Client client = AwsTestClients.create(Ec2Client.builder(), credentials.accessKeyId(),
                     credentials.secretAccessKey(), Region.US_EAST_1, endpoint)) {
            String vpc = admin.createVpc(r -> r.cidrBlock("10.203.0.0/16")).vpc().vpcId();
            String subnet = admin.createSubnet(r -> r.vpcId(vpc).cidrBlock("10.203.1.0/24")
                    .tagSpecifications(tags("subnet", "subnet-owner"))).subnet().subnetId();
            String group = admin.createSecurityGroup(r -> r.vpcId(vpc).groupName("eni-test")
                    .description("IAM fixture").tagSpecifications(tags("security-group", "group-owner"))).groupId();
            String second = admin.createSecurityGroup(r -> r.vpcId(vpc).groupName("eni-test-second")
                    .description("IAM fixture").tagSpecifications(tags("security-group", "group-owner"))).groupId();
            String create = """
                    {"Effect":"Allow","Action":"ec2:CreateNetworkInterface",
                     "Resource":"arn:aws:ec2:us-east-1:000000000000:network-interface/*",
                     "Condition":{"StringEquals":{"aws:RequestTag/ManagedBy":"integration-test"}}}
                    """;
            String tag = """
                    {"Effect":"Allow","Action":"ec2:CreateTags",
                     "Resource":"arn:aws:ec2:us-east-1:000000000000:network-interface/*",
                     "Condition":{"StringEquals":{"ec2:CreateAction":"CreateNetworkInterface"}}}
                    """;
            String subnetGrant = grant("subnet/" + subnet, "subnet-owner");
            String firstGrant = grant("security-group/" + group, "group-owner");
            String secondGrant = grant("security-group/" + second, "group-owner");
            String all = create + "," + tag + "," + subnetGrant + "," + firstGrant + "," + secondGrant;
            Runnable request = () -> create(client, subnet, group, second, "integration-test");
            try {
                policy(user, create + "," + subnetGrant + "," + firstGrant + "," + secondGrant);
                assertAwsError("UnauthorizedOperation", 403, request::run);
                policy(user, create + "," + tag + "," + firstGrant + "," + secondGrant);
                assertAwsError("UnauthorizedOperation", 403, request::run);
                policy(user, create + "," + tag + "," + subnetGrant + "," + firstGrant);
                assertAwsError("UnauthorizedOperation", 403, request::run);
                policy(user, all.replace("subnet-owner", "group-owner"));
                assertAwsError("UnauthorizedOperation", 403, request::run);
                policy(user, all + "," + secondGrant.replace("Allow", "Deny"));
                assertAwsError("UnauthorizedOperation", 403, request::run);
                policy(user, all);
                assertAwsError("UnauthorizedOperation", 403,
                        () -> create(client, subnet, group, second, "wrong-owner"));
                String eni = create(client, subnet, group, second, "integration-test");
                assertEquals(2, admin.describeNetworkInterfaces(r -> r.networkInterfaceIds(eni))
                        .networkInterfaces().getFirst().groups().size());
                admin.deleteNetworkInterface(r -> r.networkInterfaceId(eni));
            } finally {
                for (NetworkInterface eni : admin.describeNetworkInterfaces().networkInterfaces()) {
                    if (subnet.equals(eni.subnetId())) {
                        admin.deleteNetworkInterface(r -> r.networkInterfaceId(eni.networkInterfaceId()));
                    }
                }
                admin.deleteSecurityGroup(r -> r.groupId(second));
                admin.deleteSecurityGroup(r -> r.groupId(group));
                admin.deleteSubnet(r -> r.subnetId(subnet));
                admin.deleteVpc(r -> r.vpcId(vpc));
            }
        }
    }

    @Test
    void creationTagsAuthorizeLaterMutationsAndStayInSync() {
        String user = "eni-tags-" + UUID.randomUUID();
        UserCredentials credentials = EnforcementFixtures.createUser(cleanup, endpoint.getPort(),
                auth("000000000000", "iam"), user);
        try (Ec2Client admin = AwsTestClients.create(Ec2Client.builder(), "000000000000", Region.US_EAST_1, endpoint);
             Ec2Client client = AwsTestClients.create(Ec2Client.builder(), credentials.accessKeyId(),
                     credentials.secretAccessKey(), Region.US_EAST_1, endpoint)) {
            String vpc = admin.createVpc(r -> r.cidrBlock("10.204.0.0/16")).vpc().vpcId();
            String subnet = admin.createSubnet(r -> r.vpcId(vpc).cidrBlock("10.204.1.0/24")).subnet().subnetId();
            String eni = admin.createNetworkInterface(r -> r.subnetId(subnet)
                    .tagSpecifications(tags("network-interface", "team-a"))).networkInterface().networkInterfaceId();
            try {
                policy(user, """
                        {"Effect":"Allow","Action":["ec2:CreateTags","ec2:DeleteTags"],
                         "Resource":"arn:aws:ec2:us-east-1:000000000000:network-interface/%s",
                         "Condition":{"StringEquals":{"aws:ResourceTag/ManagedBy":"team-a",
                         "ec2:ResourceTag/ManagedBy":"team-a"}}}
                        """.formatted(eni));
                client.createTags(r -> r.resources(eni).tags(t -> t.key("Purpose").value("fixture")));
                assertEquals(2, admin.describeNetworkInterfaces(r -> r.networkInterfaceIds(eni))
                        .networkInterfaces().getFirst().tagSet().size());
                client.createTags(r -> r.resources(eni).tags(t -> t.key("ManagedBy").value("team-b")));
                assertEquals("team-b", admin.describeNetworkInterfaces(r -> r.networkInterfaceIds(eni))
                        .networkInterfaces().getFirst().tagSet().stream().filter(t -> t.key().equals("ManagedBy"))
                        .findFirst().orElseThrow().value());
                assertAwsError("UnauthorizedOperation", 403,
                        () -> client.createTags(r -> r.resources(eni).tags(t -> t.key("Purpose").value("denied"))));
                admin.createTags(r -> r.resources(eni).tags(t -> t.key("ManagedBy").value("team-a")));
                client.deleteTags(r -> r.resources(eni).tags(t -> t.key("ManagedBy")));
                assertAwsError("UnauthorizedOperation", 403,
                        () -> client.createTags(r -> r.resources(eni).tags(t -> t.key("Purpose").value("denied"))));
            } finally {
                admin.deleteNetworkInterface(r -> r.networkInterfaceId(eni));
                assertEquals(0, admin.describeTags(r -> r.filters(f -> f.name("resource-id").values(eni))).tags().size());
                admin.deleteSubnet(r -> r.subnetId(subnet));
                admin.deleteVpc(r -> r.vpcId(vpc));
            }
        }
    }

    private String create(Ec2Client client, String subnet, String group, String second, String owner) {
        return client.createNetworkInterface(r -> r.subnetId(subnet).groups(group, second)
                .tagSpecifications(tags("network-interface", owner))).networkInterface().networkInterfaceId();
    }

    private TagSpecification tags(String type, String owner) {
        return TagSpecification.builder().resourceType(type).tags(t -> t.key("ManagedBy").value(owner)).build();
    }

    private String grant(String resource, String owner) {
        return """
                {"Effect":"Allow","Action":"ec2:CreateNetworkInterface",
                 "Resource":"arn:aws:ec2:us-east-1:000000000000:%s",
                 "Condition":{"StringEquals":{"aws:ResourceTag/ManagedBy":"%s"}}}
                """.formatted(resource, owner);
    }

    private void policy(String user, String statements) {
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), auth("000000000000", "iam"), user,
                "network", "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}");
    }
}
