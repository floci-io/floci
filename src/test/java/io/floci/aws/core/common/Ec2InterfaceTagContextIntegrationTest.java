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
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;

import java.net.URI;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class Ec2InterfaceTagContextIntegrationTest {
    @TestHTTPResource("/")
    URI endpoint;
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @CsvSource({"us-east-1,aws", "us-gov-west-1,aws-us-gov", "cn-north-1,aws-cn"})
    void interfaceTaggingUsesEachTargetsVpc(String region, String partition) {
        String user = "eni-context-" + UUID.randomUUID();
        UserCredentials credentials = EnforcementFixtures.createUser(cleanup, endpoint.getPort(),
                auth("000000000000", region, "iam"), user);
        String arn = "arn:" + partition + ":ec2:" + region + ":000000000000:";
        try (Ec2Client admin = AwsTestClients.create(Ec2Client.builder(), "000000000000", Region.of(region), endpoint);
             Ec2Client client = AwsTestClients.create(Ec2Client.builder(), credentials.accessKeyId(),
                     credentials.secretAccessKey(), Region.of(region), endpoint)) {
            String vpc = admin.createVpc(r -> r.cidrBlock("10.205.0.0/16")).vpc().vpcId();
            String foreignVpc = admin.createVpc(r -> r.cidrBlock("10.206.0.0/16")).vpc().vpcId();
            String subnet = admin.createSubnet(r -> r.vpcId(vpc).cidrBlock("10.205.1.0/24")).subnet().subnetId();
            String foreignSubnet = admin.createSubnet(r -> r.vpcId(foreignVpc).cidrBlock("10.206.1.0/24"))
                    .subnet().subnetId();
            String eni = admin.createNetworkInterface(r -> r.subnetId(subnet).tagSpecifications(t -> t
                    .resourceType("network-interface").tags(tag -> tag.key("Owner").value("lab"))))
                    .networkInterface().networkInterfaceId();
            String foreign = admin.createNetworkInterface(r -> r.subnetId(foreignSubnet).tagSpecifications(t -> t
                    .resourceType("network-interface").tags(tag -> tag.key("Owner").value("lab"))))
                    .networkInterface().networkInterfaceId();
            String policy = """
                    {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                    "Action":["ec2:CreateTags","ec2:DeleteTags"],"Resource":"%snetwork-interface/*",
                    "Condition":{"ArnEquals":{"ec2:Vpc":"%svpc/%s"},
                    "StringEquals":{"aws:ResourceTag/Owner":"lab"}}}]}
                    """.formatted(arn, arn, vpc);
            EnforcementFixtures.putUserPolicy(endpoint.getPort(), auth("000000000000", region, "iam"),
                    user, "tag-vpc", policy);
            try {
                client.createTags(r -> r.resources(eni).tags(t -> t.key("Runtime").value("cni")));
                assertEquals("cni", runtimeTag(admin, eni));
                assertEquals("UnauthorizedOperation", assertThrows(Ec2Exception.class, () -> client.createTags(r -> r
                        .resources(eni, foreign).tags(t -> t.key("Runtime").value("changed"))))
                        .awsErrorDetails().errorCode());
                assertEquals("cni", runtimeTag(admin, eni));
                assertEquals("UnauthorizedOperation", assertThrows(Ec2Exception.class, () -> client.deleteTags(r -> r
                        .resources(eni, foreign).tags(t -> t.key("Runtime")))).awsErrorDetails().errorCode());
                assertEquals("cni", runtimeTag(admin, eni));
                client.deleteTags(r -> r.resources(eni).tags(t -> t.key("Runtime")));
                assertEquals("", runtimeTag(admin, eni));
                String narrow = policy.replace("\"ec2:Vpc\":\"" + arn + "vpc/" + vpc + "\"",
                        "\"ec2:Vpc\":\"" + arn + "vpc/" + vpc + "\",\"ec2:Subnet\":\"" + arn
                                + "subnet/" + subnet + "\"");
                EnforcementFixtures.putUserPolicy(endpoint.getPort(), auth("000000000000", region, "iam"),
                        user, "tag-vpc", narrow.replace("subnet/" + subnet, "subnet/" + foreignSubnet));
                assertEquals("UnauthorizedOperation", assertThrows(Ec2Exception.class, () -> client.createTags(r -> r
                        .resources(eni).tags(t -> t.key("Runtime").value("cni")))).awsErrorDetails().errorCode());
                EnforcementFixtures.putUserPolicy(endpoint.getPort(), auth("000000000000", region, "iam"),
                        user, "tag-vpc", narrow);
                String instanceId = admin.runInstances(r -> r.imageId("ami-0abcdef1234567891").instanceType("t3.micro")
                        .subnetId(subnet).minCount(1).maxCount(1).tagSpecifications(t -> t.resourceType("network-interface")
                                .tags(tag -> tag.key("Owner").value("lab")))).instances().getFirst().instanceId();
                try {
                    String primary = admin.describeInstances(r -> r.instanceIds(instanceId)).reservations().getFirst()
                            .instances().getFirst().networkInterfaces().getFirst().networkInterfaceId();
                    client.createTags(r -> r.resources(primary).tags(t -> t.key("Runtime").value("cni")));
                    assertEquals("cni", runtimeTag(admin, primary));
                    client.deleteTags(r -> r.resources(primary).tags(t -> t.key("Runtime")));
                } finally {
                    admin.terminateInstances(r -> r.instanceIds(instanceId));
                }
                admin.createTags(r -> r.resources(eni).tags(t -> t.key("Owner").value("other")));
                assertEquals("UnauthorizedOperation", assertThrows(Ec2Exception.class, () -> client.createTags(r -> r
                        .resources(eni).tags(t -> t.key("Runtime").value("cni")))).awsErrorDetails().errorCode());
            } finally {
                admin.deleteNetworkInterface(r -> r.networkInterfaceId(eni));
                admin.deleteNetworkInterface(r -> r.networkInterfaceId(foreign));
                admin.deleteSubnet(r -> r.subnetId(subnet));
                admin.deleteSubnet(r -> r.subnetId(foreignSubnet));
                admin.deleteVpc(r -> r.vpcId(vpc));
                admin.deleteVpc(r -> r.vpcId(foreignVpc));
            }
        }
    }

    private String runtimeTag(Ec2Client client, String id) {
        return client.describeNetworkInterfaces(r -> r.networkInterfaceIds(id)).networkInterfaces().getFirst()
                .tagSet().stream().filter(t -> "Runtime".equals(t.key())).map(t -> t.value()).findFirst().orElse("");
    }
}
