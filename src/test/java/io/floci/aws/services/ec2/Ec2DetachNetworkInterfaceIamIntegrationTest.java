package io.floci.aws.services.ec2;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.AccessKey;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.SignedIamEnforcementProfile;
import io.floci.aws.testing.SignedIamUsers;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceType;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class Ec2DetachNetworkInterfaceIamIntegrationTest {
    @TestHTTPResource("/")
    URI endpoint;

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1", "cn-north-1"})
    void bothEndpointGrantsPermitDetach(String regionName) throws Exception {
        Region region = Region.of(regionName);
        try (Ec2Client admin = admin(region); Attachment fixture = attach(admin)) {
            String policy = policy("Allow", List.of(arn(region, "instance/" + fixture.instanceId()),
                    arn(region, "network-interface/" + fixture.interfaceId())));
            try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "detach-", "detach", policy);
                    Ec2Client caller = caller(user, region)) {
                assertTrue(caller.detachNetworkInterface(r -> r.attachmentId(fixture.attachmentId()))
                        .sdkHttpResponse().isSuccessful());
                assertNull(admin.describeNetworkInterfaces(r -> r.networkInterfaceIds(fixture.interfaceId()))
                        .networkInterfaces().getFirst().attachment());
                assertTrue(instance(admin, fixture.instanceId()).networkInterfaces().stream()
                        .noneMatch(eni -> fixture.interfaceId().equals(eni.networkInterfaceId())));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"instance", "network-interface", "explicit-deny"})
    void missingEndpointGrantOrExplicitDenyPreservesAttachment(String grant) throws Exception {
        Region region = Region.US_EAST_1;
        try (Ec2Client admin = admin(region); Attachment fixture = attach(admin)) {
            String instanceArn = arn(region, "instance/" + fixture.instanceId());
            String interfaceArn = arn(region, "network-interface/" + fixture.interfaceId());
            String policy = "explicit-deny".equals(grant)
                    ? "{\"Version\":\"2012-10-17\",\"Statement\":["
                            + statement("Allow", List.of("*")) + ","
                            + statement("Deny", List.of(interfaceArn)) + "]}"
                    : policy("Allow", List.of("instance".equals(grant) ? instanceArn : interfaceArn));
            try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "detach-deny-", "detach", policy);
                    Ec2Client caller = caller(user, region)) {
                error(caller, fixture.attachmentId(), "UnauthorizedOperation", 403);
                fixture.assertAttached();
            }
        }
    }

    @Test
    void typedWildcardFallbackPreservesMissingAttachmentError() throws Exception {
        Region region = Region.US_EAST_1;
        String policy = policy("Allow", List.of(arn(region, "instance/*"), arn(region, "network-interface/*")));
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "detach-missing-", "detach", policy);
                Ec2Client caller = caller(user, region)) {
            error(caller, "eni-attach-0123456789abcdef0", "InvalidAttachmentID.NotFound", 400);
        }
    }

    @Test
    void foreignRegionAttachmentCannotSupplyLocalAuthorizationResources() throws Exception {
        try (Ec2Client remote = admin(Region.US_WEST_2); Attachment fixture = attach(remote)) {
            String remotePolicy = policy("Allow", List.of(arn(Region.US_WEST_2, "instance/" + fixture.instanceId()),
                    arn(Region.US_WEST_2, "network-interface/" + fixture.interfaceId())));
            try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "detach-region-", "detach", remotePolicy);
                    Ec2Client caller = caller(user, Region.US_EAST_1)) {
                error(caller, fixture.attachmentId(), "UnauthorizedOperation", 403);
                String local = policy("Allow", List.of(arn(Region.US_EAST_1, "instance/*"),
                        arn(Region.US_EAST_1, "network-interface/*")));
                SignedIamUsers.iam(endpoint, "PutUserPolicy", Map.of("UserName", user.name(),
                        "PolicyName", "detach", "PolicyDocument", local)).then().statusCode(200);
                error(caller, fixture.attachmentId(), "InvalidAttachmentID.NotFound", 400);
                fixture.assertAttached();
            }
        }
    }

    @Inject
    IamService iam;

    @Test
    void foreignAccountCannotResolveOrDetachTheOwnersAttachment() throws Exception {
        String foreign = "111122223333";
        String name = "detach-foreign-" + UUID.randomUUID();
        try (Ec2Client admin = admin(Region.US_EAST_1); Attachment fixture = attach(admin)) {
            RequestScopes.runAs(foreign, "us-east-1", () -> iam.createUser(name, "/"));
            try {
                String ownerPolicy = policy("Allow", List.of(arn(Region.US_EAST_1, "instance/" + fixture.instanceId()),
                        arn(Region.US_EAST_1, "network-interface/" + fixture.interfaceId())));
                RequestScopes.runAs(foreign, "us-east-1", () -> iam.putUserPolicy(name, "detach", ownerPolicy));
                AccessKey key = RequestScopes.callAs(foreign, "us-east-1", () -> iam.createAccessKey(name));
                try (Ec2Client caller = AwsTestClients.create(Ec2Client.builder(), key.getAccessKeyId(),
                        key.getSecretAccessKey(), Region.US_EAST_1, endpoint)) {
                    error(caller, fixture.attachmentId(), "UnauthorizedOperation", 403);
                    fixture.assertAttached();
                    String localPolicy = policy("Allow", List.of(
                            AwsArnUtils.Arn.of("ec2", "us-east-1", foreign, "instance/*").toString(),
                            AwsArnUtils.Arn.of("ec2", "us-east-1", foreign, "network-interface/*").toString()));
                    RequestScopes.runAs(foreign, "us-east-1", () -> iam.putUserPolicy(name, "detach", localPolicy));
                    error(caller, fixture.attachmentId(), "InvalidAttachmentID.NotFound", 400);
                    fixture.assertAttached();
                }
            } finally {
                RequestScopes.runAs(foreign, "us-east-1", () -> {
                    for (AccessKey key : iam.listAccessKeys(name)) {
                        iam.deleteAccessKey(name, key.getAccessKeyId());
                    }
                    for (String policyName : iam.listUserPolicies(name)) {
                        iam.deleteUserPolicy(name, policyName);
                    }
                    iam.deleteUser(name);
                });
            }
        }
    }

    private Attachment attach(Ec2Client admin) {
        String instanceId = admin.runInstances(r -> r.imageId("ami-1234567890abcdef0")
                .instanceType(InstanceType.T3_MICRO).minCount(1).maxCount(1)).instances().getFirst().instanceId();
        String interfaceId = null;
        try {
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertEquals("running", instance(admin, instanceId).state().nameAsString()));
            String subnet = instance(admin, instanceId).subnetId();
            interfaceId = admin.createNetworkInterface(r -> r.subnetId(subnet)).networkInterface().networkInterfaceId();
            String id = interfaceId;
            String attachmentId = admin.attachNetworkInterface(r -> r.networkInterfaceId(id)
                    .instanceId(instanceId).deviceIndex(1)).attachmentId();
            return new Attachment(admin, instanceId, interfaceId, attachmentId);
        } catch (RuntimeException | AssertionError failure) {
            try {
                new Attachment(admin, instanceId, interfaceId, null).close();
            } catch (RuntimeException | AssertionError cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private record Attachment(Ec2Client admin, String instanceId, String interfaceId, String attachmentId)
            implements AutoCloseable {
        void assertAttached() {
            assertEquals(attachmentId, admin.describeNetworkInterfaces(r -> r.networkInterfaceIds(interfaceId))
                    .networkInterfaces().getFirst().attachment().attachmentId());
            assertTrue(instance(admin, instanceId).networkInterfaces().stream()
                    .anyMatch(eni -> interfaceId.equals(eni.networkInterfaceId())));
        }

        @Override
        public void close() {
            admin.terminateInstances(r -> r.instanceIds(instanceId));
            if (interfaceId != null) {
                admin.deleteNetworkInterface(r -> r.networkInterfaceId(interfaceId));
            }
        }
    }

    private Ec2Client admin(Region region) {
        return AwsTestClients.create(Ec2Client.builder(), "test", region, endpoint);
    }

    private Ec2Client caller(SignedIamUsers.User user, Region region) {
        return AwsTestClients.create(Ec2Client.builder(), user.accessKeyId(), user.secretAccessKey(), region, endpoint);
    }

    private static Instance instance(Ec2Client client, String id) {
        return client.describeInstances(r -> r.instanceIds(id)).reservations().getFirst().instances().getFirst();
    }

    private static void error(Ec2Client caller, String id, String code, int status) {
        Ec2Exception error = assertThrows(Ec2Exception.class, () -> caller.detachNetworkInterface(r -> r.attachmentId(id)));
        assertEquals(status, error.statusCode());
        assertEquals(code, error.awsErrorDetails().errorCode());
    }

    private static String arn(Region region, String resource) {
        return AwsArnUtils.Arn.of("ec2", region.id(), "000000000000", resource).toString();
    }

    private static String policy(String effect, List<String> resources) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[" + statement(effect, resources) + "]}";
    }

    private static String statement(String effect, List<String> resources) {
        return "{\"Effect\":\"" + effect + "\",\"Action\":\"ec2:DetachNetworkInterface\",\"Resource\":[\""
                + String.join("\",\"", resources) + "\"]}";
    }
}
