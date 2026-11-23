package io.floci.aws.services.ec2;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.SignedIamEnforcementProfile;
import io.floci.aws.testing.SignedIamUsers;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.AttributeValue;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.Volume;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static io.floci.aws.testing.AwsSdkErrors.assertAwsError;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A dry run checks the caller's permission and changes nothing else: under IAM enforcement a caller the
 * policy does not allow gets UnauthorizedOperation for a dry run, as for the real call, and one it allows
 * gets DryRunOperation, with nothing created, deleted, terminated or authorized either way.
 */
@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class Ec2DryRunIamIntegrationTest {

    private static final String ZONE = "us-east-1a";

    @TestHTTPResource("/")
    URI endpoint;

    @Test
    void aCallerWithoutPermissionCannotUseAVolumeDryRunToGetPastIt() throws Exception {
        try (Ec2Client admin = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_EAST_1, endpoint);
                SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "volume-dryrun-denied-");
                Ec2Client caller = AwsTestClients.create(Ec2Client.builder(), user.accessKeyId(),
                        user.secretAccessKey(), Region.US_EAST_1, endpoint)) {
            String volumeId = admin.createVolume(r -> r.availabilityZone(ZONE).size(1)).volumeId();
            try {
                List<Volume> before = admin.describeVolumes().volumes();

                assertAwsError("UnauthorizedOperation", 403,
                        () -> caller.createVolume(r -> r.availabilityZone(ZONE).size(1).dryRun(true)));
                assertAwsError("UnauthorizedOperation", 403,
                        () -> caller.deleteVolume(r -> r.volumeId(volumeId).dryRun(true)));

                assertEquals(before, admin.describeVolumes().volumes());
            } finally {
                admin.deleteVolume(r -> r.volumeId(volumeId));
            }
        }
    }

    @Test
    void aVolumeDryRunNeedsTheCallersCurrentPermission() throws Exception {
        String policy = """
                {"Version":"2012-10-17","Statement":[{
                  "Effect":"Allow","Action":["ec2:CreateVolume","ec2:DeleteVolume"],"Resource":"*"
                }]}
                """;
        try (Ec2Client admin = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_EAST_1, endpoint);
                SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "volume-dryrun-allowed-", "volumes",
                        policy);
                Ec2Client caller = AwsTestClients.create(Ec2Client.builder(), user.accessKeyId(),
                        user.secretAccessKey(), Region.US_EAST_1, endpoint)) {
            String volumeId = admin.createVolume(r -> r.availabilityZone(ZONE).size(1)).volumeId();
            try {
                List<Volume> before = admin.describeVolumes().volumes();

                assertAwsError("DryRunOperation", 412,
                        () -> caller.createVolume(r -> r.availabilityZone(ZONE).size(1).dryRun(true)));
                assertAwsError("DryRunOperation", 412, () -> caller.deleteVolume(r -> r.volumeId(volumeId).dryRun(true)));
                SignedIamUsers.iam(endpoint, "DeleteUserPolicy", Map.of("UserName", user.name(), "PolicyName", "volumes"))
                        .then().statusCode(200);
                assertAwsError("UnauthorizedOperation", 403,
                        () -> caller.deleteVolume(r -> r.volumeId(volumeId).dryRun(true)));

                assertEquals(before, admin.describeVolumes().volumes());
            } finally {
                admin.deleteVolume(r -> r.volumeId(volumeId));
            }
        }
    }

    @Test
    void aTerminateDryRunNeedsTheCallersCurrentPermission() throws Exception {
        assertAnInstanceDryRunNeedsTheCallersCurrentPermission("TerminateInstances",
                (caller, instanceId) -> caller.terminateInstances(r -> r.instanceIds(instanceId).dryRun(true)));
    }

    @Test
    void aModifyInstanceAttributeDryRunNeedsTheCallersCurrentPermission() throws Exception {
        assertAnInstanceDryRunNeedsTheCallersCurrentPermission("ModifyInstanceAttribute",
                (caller, instanceId) -> caller.modifyInstanceAttribute(r -> r.instanceId(instanceId)
                        .instanceType(AttributeValue.builder().value("t3.small").build()).dryRun(true)));
    }

    @Test
    void aDeleteVpcDryRunNeedsTheCallersCurrentPermission() throws Exception {
        try (Ec2Client admin = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_EAST_1, endpoint)) {
            String vpcId = admin.createVpc(r -> r.cidrBlock("10.95.0.0/16")).vpc().vpcId();
            try {
                assertADryRunNeedsTheCallersCurrentPermission("DeleteVpc",
                        () -> admin.describeVpcs(r -> r.vpcIds(vpcId)).vpcs(),
                        caller -> caller.deleteVpc(r -> r.vpcId(vpcId).dryRun(true)));
            } finally {
                // There unless a dry run deleted it.
                if (!admin.describeVpcs(r -> r.filters(f -> f.name("vpc-id").values(vpcId))).vpcs().isEmpty()) {
                    admin.deleteVpc(r -> r.vpcId(vpcId));
                }
            }
        }
    }

    @Test
    void anAuthorizeSecurityGroupIngressDryRunNeedsTheCallersCurrentPermission() throws Exception {
        try (Ec2Client admin = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_EAST_1, endpoint)) {
            String groupId = admin.createSecurityGroup(r -> r.groupName("dryrun-" + UUID.randomUUID())
                    .description("dry run")).groupId();
            try {
                assertADryRunNeedsTheCallersCurrentPermission("AuthorizeSecurityGroupIngress",
                        () -> admin.describeSecurityGroupRules(r -> r.filters(f -> f.name("group-id")
                                .values(groupId))).securityGroupRules(),
                        caller -> caller.authorizeSecurityGroupIngress(r -> r.groupId(groupId).dryRun(true)
                                .ipPermissions(p -> p.ipProtocol("tcp").fromPort(22).toPort(22)
                                        .ipRanges(range -> range.cidrIp("10.0.0.0/8")))));
            } finally {
                admin.deleteSecurityGroup(r -> r.groupId(groupId));
            }
        }
    }

    /**
     * A caller the policy allows {@code action} gets DryRunOperation for {@code dryRun} on a running instance,
     * and UnauthorizedOperation once the policy is removed; the instance is unchanged either way.
     */
    private void assertAnInstanceDryRunNeedsTheCallersCurrentPermission(String action,
            BiConsumer<Ec2Client, String> dryRun) throws Exception {
        try (Ec2Client admin = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_EAST_1, endpoint)) {
            String instanceId = admin.runInstances(r -> r.imageId("ami-1234567890abcdef0")
                    .instanceType(InstanceType.T3_MICRO).minCount(1).maxCount(1)).instances().get(0).instanceId();
            try {
                await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
                        .untilAsserted(() -> assertEquals("running", instance(admin, instanceId).state().nameAsString()));

                assertADryRunNeedsTheCallersCurrentPermission(action, () -> instance(admin, instanceId),
                        caller -> dryRun.accept(caller, instanceId));
            } finally {
                admin.terminateInstances(r -> r.instanceIds(instanceId));
            }
        }
    }

    @Test
    void aKeyPairDryRunNeedsTheCallersCurrentPermission() throws Exception {
        String policy = """
                {"Version":"2012-10-17","Statement":[{
                  "Effect":"Allow","Action":"ec2:DeleteKeyPair","Resource":"*"
                }]}
                """;
        try (Ec2Client admin = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_EAST_1, endpoint);
                SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "keypair-dryrun-allowed-", "keys",
                        policy);
                Ec2Client caller = AwsTestClients.create(Ec2Client.builder(), user.accessKeyId(),
                        user.secretAccessKey(), Region.US_EAST_1, endpoint)) {
            String name = "dry-run-iam-key";
            admin.createKeyPair(r -> r.keyName(name));
            try {
                assertAwsError("DryRunOperation", 412, () -> caller.deleteKeyPair(r -> r.keyName(name).dryRun(true)));
                SignedIamUsers.iam(endpoint, "DeleteUserPolicy", Map.of("UserName", user.name(), "PolicyName", "keys"))
                        .then().statusCode(200);
                assertAwsError("UnauthorizedOperation", 403,
                        () -> caller.deleteKeyPair(r -> r.keyName(name).dryRun(true)));

                assertEquals(1, admin.describeKeyPairs(r -> r.keyNames(name)).keyPairs().size());
            } finally {
                admin.deleteKeyPair(r -> r.keyName(name));
            }
        }
    }

    /**
     * A caller the policy allows {@code action} gets DryRunOperation for {@code dryRun}, and UnauthorizedOperation
     * once the policy is removed; {@code state} reads the same before and after.
     */
    private void assertADryRunNeedsTheCallersCurrentPermission(String action, Supplier<?> state,
            Consumer<Ec2Client> dryRun) throws Exception {
        String policy = """
                {"Version":"2012-10-17","Statement":[{
                  "Effect":"Allow","Action":"ec2:%s","Resource":"*"
                }]}
                """.formatted(action);
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint,
                        "dryrun-" + action.toLowerCase(Locale.ROOT) + "-", "dryrun", policy);
                Ec2Client caller = AwsTestClients.create(Ec2Client.builder(), user.accessKeyId(),
                        user.secretAccessKey(), Region.US_EAST_1, endpoint)) {
            Object before = state.get();

            assertAwsError("DryRunOperation", 412, () -> dryRun.accept(caller));
            SignedIamUsers.iam(endpoint, "DeleteUserPolicy", Map.of("UserName", user.name(), "PolicyName", "dryrun"))
                    .then().statusCode(200);
            assertAwsError("UnauthorizedOperation", 403, () -> dryRun.accept(caller));

            assertEquals(before, state.get());
        }
    }

    private static Instance instance(Ec2Client client, String instanceId) {
        return client.describeInstances(r -> r.instanceIds(instanceId)).reservations().get(0).instances().get(0);
    }
}
