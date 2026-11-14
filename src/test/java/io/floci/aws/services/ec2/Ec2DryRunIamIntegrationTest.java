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
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.Volume;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static io.floci.aws.testing.AwsSdkErrors.assertAwsError;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A dry run checks the caller's permission and changes nothing else: under IAM enforcement a caller the
 * policy does not allow gets UnauthorizedOperation for a dry run, as for the real call, and one it allows
 * gets DryRunOperation, with nothing created, deleted or terminated either way.
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
        String policy = """
                {"Version":"2012-10-17","Statement":[{
                  "Effect":"Allow","Action":"ec2:TerminateInstances","Resource":"*"
                }]}
                """;
        try (Ec2Client admin = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_EAST_1, endpoint);
                SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "instance-dryrun-allowed-", "instances",
                        policy);
                Ec2Client caller = AwsTestClients.create(Ec2Client.builder(), user.accessKeyId(),
                        user.secretAccessKey(), Region.US_EAST_1, endpoint)) {
            String instanceId = admin.runInstances(r -> r.imageId("ami-1234567890abcdef0")
                    .instanceType(InstanceType.T3_MICRO).minCount(1).maxCount(1)).instances().get(0).instanceId();
            try {
                await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
                        .untilAsserted(() -> assertEquals("running", state(admin, instanceId)));

                assertAwsError("DryRunOperation", 412,
                        () -> caller.terminateInstances(r -> r.instanceIds(instanceId).dryRun(true)));
                SignedIamUsers.iam(endpoint, "DeleteUserPolicy", Map.of("UserName", user.name(), "PolicyName", "instances"))
                        .then().statusCode(200);
                assertAwsError("UnauthorizedOperation", 403,
                        () -> caller.terminateInstances(r -> r.instanceIds(instanceId).dryRun(true)));

                assertEquals("running", state(admin, instanceId));
            } finally {
                admin.terminateInstances(r -> r.instanceIds(instanceId));
            }
        }
    }

    private static String state(Ec2Client client, String instanceId) {
        return client.describeInstances(r -> r.instanceIds(instanceId)).reservations().get(0).instances().get(0)
                .state().nameAsString();
    }
}
