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
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;
import software.amazon.awssdk.services.ec2.model.VpcAttributeName;

import java.net.URI;
import java.util.UUID;

import static io.floci.aws.testing.AwsSdkErrors.assertAwsError;
import static io.floci.aws.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class Ec2VpcResourceTagsIntegrationTest {

    @TestHTTPResource("/")
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @ValueSource(strings = {"aws:ResourceTag/", "ec2:ResourceTag/"})
    void modifyVpcAttributeUsesTheTargetsCurrentTagsAndHonorsDeny(String prefix) {
        String user = "vpc-tag-" + UUID.randomUUID();
        UserCredentials credentials = EnforcementFixtures.createUser(cleanup, endpoint.getPort(),
                auth("000000000000", "iam"), user);
        String allow = """
                {"Effect":"Allow","Action":"ec2:ModifyVpcAttribute",
                 "Resource":"arn:aws:ec2:us-east-1:000000000000:vpc/*",
                 "Condition":{"StringEquals":{"%sManagedBy":"integration-test"}}}
                """.formatted(prefix);
        try (Ec2Client admin = AwsTestClients.create(Ec2Client.builder(), "000000000000", Region.US_EAST_1, endpoint);
             Ec2Client client = AwsTestClients.create(Ec2Client.builder(), credentials.accessKeyId(),
                     credentials.secretAccessKey(), Region.US_EAST_1, endpoint)) {
            String owned = create(admin, "integration-test");
            String other = create(admin, "other");
            String untagged = admin.createVpc(r -> r.cidrBlock("10.203.0.0/16")).vpc().vpcId();
            try {
                policy(user, allow);
                client.modifyVpcAttribute(r -> r.vpcId(owned).enableDnsHostnames(a -> a.value(true)));
                assertTrue(admin.describeVpcAttribute(r -> r.vpcId(owned)
                        .attribute(VpcAttributeName.ENABLE_DNS_HOSTNAMES)).enableDnsHostnames().value());
                denied(client, other);
                denied(client, untagged);
                policy(user, allow + "," + """
                        {"Effect":"Deny","Action":"ec2:ModifyVpcAttribute",
                         "Resource":"arn:aws:ec2:us-east-1:000000000000:vpc/%s"}
                        """.formatted(owned));
                denied(client, owned);
                policy(user, allow);
                admin.createTags(r -> r.resources(owned).tags(Tag.builder().key("ManagedBy").value("other").build()));
                denied(client, owned);
            } finally {
                for (String id : new String[]{owned, other, untagged}) {
                    admin.deleteVpc(r -> r.vpcId(id));
                }
            }
        }
    }

    private String create(Ec2Client client, String owner) {
        return client.createVpc(r -> r.cidrBlock("10.202.0.0/16")
                .tagSpecifications(TagSpecification.builder().resourceType(ResourceType.VPC)
                        .tags(Tag.builder().key("ManagedBy").value(owner).build()).build())).vpc().vpcId();
    }

    private void denied(Ec2Client client, String vpcId) {
        assertAwsError("UnauthorizedOperation", 403,
                () -> client.modifyVpcAttribute(r -> r.vpcId(vpcId).enableDnsHostnames(a -> a.value(true))));
    }

    private void policy(String user, String statements) {
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), auth("000000000000", "iam"), user,
                "modify", "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}");
    }
}
