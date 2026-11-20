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
import software.amazon.awssdk.services.iam.IamClient;

import java.net.URI;
import java.util.UUID;

import static io.floci.aws.testing.AwsSdkErrors.assertAwsError;
import static io.floci.aws.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A snapshot's and an image's ARN carries no account ({@code arn:aws:ec2:<region>::snapshot/<id>}), so a condition on
 * their stored tags is read from the caller's account, as for every other EC2 resource.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class Ec2SnapshotImageTagsIamIntegrationTest {

    private static final String CLUSTER_TAG = "ebs.csi.aws.com/cluster";

    @TestHTTPResource("/")
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    /**
     * The EBS CSI driver's managed policy lets it create a snapshot only with its cluster tag, and delete one only while
     * the snapshot carries it.
     */
    @Test
    void theEbsCsiDriverPolicyDeletesOnlySnapshotsCarryingItsClusterTag() {
        String user = "ebs-snapshots-" + UUID.randomUUID();
        UserCredentials credentials = EnforcementFixtures.createUser(cleanup, endpoint.getPort(),
                auth("000000000000", "iam"), user);
        try (IamClient iam = AwsTestClients.create(IamClient.builder(), "000000000000", Region.US_EAST_1, endpoint);
             Ec2Client admin = AwsTestClients.create(Ec2Client.builder(), "000000000000", Region.US_EAST_1, endpoint);
             Ec2Client client = AwsTestClients.create(Ec2Client.builder(), credentials.accessKeyId(),
                     credentials.secretAccessKey(), Region.US_EAST_1, endpoint)) {
            iam.attachUserPolicy(r -> r.userName(user)
                    .policyArn("arn:aws:iam::aws:policy/service-role/AmazonEBSCSIDriverPolicy"));
            String volume = admin.createVolume(r -> r.availabilityZone("us-east-1a").size(1)).volumeId();
            String foreign = admin.createSnapshot(r -> r.volumeId(volume)).snapshotId();
            String own = null;
            try {
                assertAwsError("UnauthorizedOperation", 403, () -> client.createSnapshot(r -> r.volumeId(volume)));
                own = client.createSnapshot(r -> r.volumeId(volume).tagSpecifications(TagSpecification.builder()
                        .resourceType("snapshot").tags(clusterTag()).build())).snapshotId();
                String owned = own;
                assertAwsError("UnauthorizedOperation", 403, () -> client.deleteSnapshot(r -> r.snapshotId(foreign)));
                admin.deleteTags(r -> r.resources(owned).tags(t -> t.key(CLUSTER_TAG)));
                assertAwsError("UnauthorizedOperation", 403, () -> client.deleteSnapshot(r -> r.snapshotId(owned)));
                admin.createTags(r -> r.resources(owned).tags(clusterTag()));
                client.deleteSnapshot(r -> r.snapshotId(owned));
                own = null;
                assertTrue(admin.describeSnapshots(r -> r.ownerIds("self")).snapshots().stream()
                        .noneMatch(s -> s.snapshotId().equals(owned)));
            } finally {
                admin.deleteSnapshot(r -> r.snapshotId(foreign));
                if (own != null) {
                    String left = own;
                    admin.deleteSnapshot(r -> r.snapshotId(left));
                }
                admin.deleteVolume(r -> r.volumeId(volume));
            }
        }
    }

    /** An image is deregistered under a condition on its stored tags as a snapshot is deleted under one. */
    @Test
    void anImageIsDeregisteredOnlyWhileItCarriesTheTagThePolicyNames() {
        String user = "ec2-images-" + UUID.randomUUID();
        UserCredentials credentials = EnforcementFixtures.createUser(cleanup, endpoint.getPort(),
                auth("000000000000", "iam"), user);
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), auth("000000000000", "iam"), user, "images", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"ec2:DeregisterImage",
                 "Resource":"arn:aws:ec2:us-east-1::image/*",
                 "Condition":{"StringEquals":{"aws:ResourceTag/Owner":"owner"}}}]}
                """);
        try (Ec2Client admin = AwsTestClients.create(Ec2Client.builder(), "000000000000", Region.US_EAST_1, endpoint);
             Ec2Client client = AwsTestClients.create(Ec2Client.builder(), credentials.accessKeyId(),
                     credentials.secretAccessKey(), Region.US_EAST_1, endpoint)) {
            String image = admin.registerImage(r -> r.name("tags-" + UUID.randomUUID()).architecture("x86_64")
                    .rootDeviceName("/dev/xvda")).imageId();
            boolean registered = true;
            try {
                assertAwsError("UnauthorizedOperation", 403, () -> client.deregisterImage(r -> r.imageId(image)));
                admin.createTags(r -> r.resources(image).tags(Tag.builder().key("Owner").value("owner").build()));
                client.deregisterImage(r -> r.imageId(image));
                registered = false;
            } finally {
                if (registered) {
                    admin.deregisterImage(r -> r.imageId(image));
                }
            }
        }
    }

    private static Tag clusterTag() {
        return Tag.builder().key(CLUSTER_TAG).value("true").build();
    }
}
