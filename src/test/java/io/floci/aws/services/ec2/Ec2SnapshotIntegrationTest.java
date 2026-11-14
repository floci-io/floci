package io.floci.aws.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CreateSnapshotResponse;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.Snapshot;
import software.amazon.awssdk.services.ec2.model.SnapshotState;
import software.amazon.awssdk.services.ec2.model.Tag;

import java.net.URI;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CreateSnapshot, DeleteSnapshot and CopySnapshot for EBS volumes, through the AWS SDK.
 *
 * <p>Runs in its own regions so the snapshots other test classes leave behind in the same
 * application do not show up in its listings.</p>
 */
@QuarkusTest
class Ec2SnapshotIntegrationTest {

    private static final String REGION = "eu-south-2";
    private static final String COPY_REGION = "eu-central-2";

    private static Ec2Client client(String region) {
        return Ec2Client.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }

    private static String createVolume(Ec2Client ec2, int size, boolean encrypted) {
        return ec2.createVolume(request -> request
                .availabilityZone(REGION + "a").size(size).encrypted(encrypted)).volumeId();
    }

    private static Snapshot describe(Ec2Client ec2, String snapshotId) {
        return ec2.describeSnapshots(request -> request.snapshotIds(snapshotId)).snapshots().getFirst();
    }

    private static String errorCode(Ec2Exception error) {
        return error.awsErrorDetails().errorCode();
    }

    /** A raw Query request to {@code region}, for requests the SDK would not send. */
    private static RequestSpecification ec2(String region, String action, String... params) {
        RequestSpecification request = given()
                .formParam("Action", action)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260205/" + region + "/ec2/aws4_request");
        for (int i = 0; i + 1 < params.length; i += 2) {
            request = request.formParam(params[i], params[i + 1]);
        }
        return request;
    }

    private static String createSnapshot(Ec2Client ec2) {
        return ec2.createSnapshot(request -> request.volumeId(createVolume(ec2, 1, false))).snapshotId();
    }

    @ParameterizedTest
    @CsvSource({
            "CreateSnapshot, Description, none",
            "DeleteSnapshot, DryRun, false",
            "CopySnapshot, SourceSnapshotId, snap-0123456789abcdef0",
            "CopySnapshot, SourceRegion, eu-south-2"
    })
    void aRequestMissingARequiredParameterIsRefused(String action, String otherParam, String otherValue) {
        ec2(COPY_REGION, action, otherParam, otherValue).post("/").then()
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("MissingParameter"));
    }

    @ParameterizedTest
    @CsvSource({
            "CreateSnapshot, VolumeId, vol-0123456789abcdef0, InvalidVolume.NotFound",
            "DeleteSnapshot, SnapshotId, snap-0123456789abcdef0, InvalidSnapshot.NotFound",
            "CopySnapshot, SourceSnapshotId, snap-0123456789abcdef0, InvalidSnapshot.NotFound"
    })
    void aDryRunTheRealCallWouldRefuseKeepsItsError(String action, String idParam, String id, String errorCode) {
        ec2(REGION, action, idParam, id, "SourceRegion", REGION, "DryRun", "true").post("/").then()
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo(errorCode));
    }

    @Test
    void aDryRunDeleteOfASnapshotAnImageUsesKeepsInUse() {
        try (Ec2Client ec2 = client(REGION)) {
            String snapshotId = createSnapshot(ec2);
            ec2.registerImage(request -> request
                    .name("snapshot-dry-run-" + snapshotId)
                    .rootDeviceName("/dev/xvda")
                    .blockDeviceMappings(mapping -> mapping.deviceName("/dev/xvda")
                            .ebs(ebs -> ebs.snapshotId(snapshotId))));

            Ec2Exception error = assertThrows(Ec2Exception.class,
                    () -> ec2.deleteSnapshot(request -> request.snapshotId(snapshotId).dryRun(true)));

            assertEquals("InvalidSnapshot.InUse", errorCode(error));
        }
    }

    @Test
    void aDryRunDeleteOrCopyChangesNothing() {
        try (Ec2Client ec2 = client(REGION)) {
            String snapshotId = createSnapshot(ec2);
            int before = ec2.describeSnapshots(request -> request.ownerIds("self")).snapshots().size();

            ec2(REGION, "DeleteSnapshot", "SnapshotId", snapshotId, "DryRun", "true").post("/").then()
                    .statusCode(412)
                    .body("Response.Errors.Error.Code", equalTo("DryRunOperation"));
            ec2(REGION, "CopySnapshot", "SourceSnapshotId", snapshotId, "SourceRegion", REGION, "DryRun", "true")
                    .post("/").then()
                    .statusCode(412)
                    .body("Response.Errors.Error.Code", equalTo("DryRunOperation"));

            assertEquals(snapshotId, describe(ec2, snapshotId).snapshotId());
            assertEquals(before, ec2.describeSnapshots(request -> request.ownerIds("self")).snapshots().size());
        }
    }

    @Test
    void copySnapshotWithAMalformedEncryptedIsRefused() {
        try (Ec2Client ec2 = client(REGION)) {
            String snapshotId = createSnapshot(ec2);

            ec2(REGION, "CopySnapshot", "SourceSnapshotId", snapshotId, "SourceRegion", REGION,
                    "Encrypted", "invalid").post("/").then()
                    .statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));
        }
    }

    @Test
    void tagsCannotBeCreatedOnADeletedSnapshot() {
        try (Ec2Client ec2 = client(REGION)) {
            String snapshotId = createSnapshot(ec2);
            ec2.deleteSnapshot(request -> request.snapshotId(snapshotId));

            Ec2Exception error = assertThrows(Ec2Exception.class, () -> ec2.createTags(request -> request
                    .resources(snapshotId).tags(Tag.builder().key("Name").value("late").build())));

            assertEquals("InvalidSnapshot.NotFound", errorCode(error));
            assertTrue(ec2.describeTags(request -> request.filters(filter -> filter
                    .name("resource-id").values(snapshotId))).tags().isEmpty());
        }
    }

    @Test
    void createSnapshotIsPendingThenCompletedWithTheVolumeSize() {
        try (Ec2Client ec2 = client(REGION)) {
            String volumeId = createVolume(ec2, 3, false);

            CreateSnapshotResponse created = ec2.createSnapshot(request -> request
                    .volumeId(volumeId)
                    .description("nightly")
                    .tagSpecifications(spec -> spec.resourceType(ResourceType.SNAPSHOT)
                            .tags(Tag.builder().key("Name").value("backup").build())));

            assertTrue(created.snapshotId().startsWith("snap-"), created.snapshotId());
            assertEquals(SnapshotState.PENDING, created.state());
            assertEquals(volumeId, created.volumeId());
            assertEquals(3, created.volumeSize());
            assertEquals("nightly", created.description());
            assertFalse(created.encrypted());
            assertEquals(List.of(Tag.builder().key("Name").value("backup").build()), created.tags());

            Snapshot described = describe(ec2, created.snapshotId());
            assertEquals(SnapshotState.COMPLETED, described.state());
            assertEquals("100%", described.progress());
            assertEquals(volumeId, described.volumeId());
            assertEquals(3, described.volumeSize());
            assertEquals(created.ownerId(), described.ownerId());
            assertEquals(created.tags(), described.tags());

            List<Snapshot> owned = ec2.describeSnapshots(request -> request.ownerIds("self")).snapshots();
            assertTrue(owned.stream().anyMatch(snapshot -> created.snapshotId().equals(snapshot.snapshotId())));
        }
    }

    @Test
    void createSnapshotOfAnEncryptedVolumeIsEncrypted() {
        try (Ec2Client ec2 = client(REGION)) {
            String volumeId = createVolume(ec2, 1, true);

            CreateSnapshotResponse created = ec2.createSnapshot(request -> request.volumeId(volumeId));

            assertTrue(created.encrypted());
            assertTrue(describe(ec2, created.snapshotId()).encrypted());
        }
    }

    @Test
    void createSnapshotOfAnUnknownVolumeFails() {
        try (Ec2Client ec2 = client(REGION)) {
            Ec2Exception error = assertThrows(Ec2Exception.class,
                    () -> ec2.createSnapshot(request -> request.volumeId("vol-0123456789abcdef0")));

            assertEquals("InvalidVolume.NotFound", errorCode(error));
        }
    }

    @Test
    void createSnapshotDryRunCreatesNothing() {
        try (Ec2Client ec2 = client(REGION)) {
            String volumeId = createVolume(ec2, 1, false);

            Ec2Exception error = assertThrows(Ec2Exception.class,
                    () -> ec2.createSnapshot(request -> request.volumeId(volumeId).dryRun(true)));

            assertEquals("DryRunOperation", errorCode(error));
            assertTrue(ec2.describeSnapshots(request -> request.filters(filter -> filter
                    .name("volume-id").values(volumeId))).snapshots().isEmpty());
        }
    }

    @Test
    void deleteSnapshotRemovesIt() {
        try (Ec2Client ec2 = client(REGION)) {
            String snapshotId = ec2.createSnapshot(request -> request
                    .volumeId(createVolume(ec2, 1, false))).snapshotId();

            ec2.deleteSnapshot(request -> request.snapshotId(snapshotId));

            Ec2Exception described = assertThrows(Ec2Exception.class, () -> describe(ec2, snapshotId));
            assertEquals("InvalidSnapshot.NotFound", errorCode(described));
            Ec2Exception deletedAgain = assertThrows(Ec2Exception.class,
                    () -> ec2.deleteSnapshot(request -> request.snapshotId(snapshotId)));
            assertEquals("InvalidSnapshot.NotFound", errorCode(deletedAgain));
        }
    }

    @Test
    void deleteSnapshotInUseByAnImageFailsUntilTheImageIsDeregistered() {
        try (Ec2Client ec2 = client(REGION)) {
            String snapshotId = ec2.createSnapshot(request -> request
                    .volumeId(createVolume(ec2, 1, false))).snapshotId();
            String imageId = ec2.registerImage(request -> request
                    .name("snapshot-in-use-" + snapshotId)
                    .rootDeviceName("/dev/xvda")
                    .blockDeviceMappings(mapping -> mapping.deviceName("/dev/xvda")
                            .ebs(ebs -> ebs.snapshotId(snapshotId)))).imageId();

            Ec2Exception error = assertThrows(Ec2Exception.class,
                    () -> ec2.deleteSnapshot(request -> request.snapshotId(snapshotId)));
            assertEquals("InvalidSnapshot.InUse", errorCode(error));

            ec2.deregisterImage(request -> request.imageId(imageId));
            ec2.deleteSnapshot(request -> request.snapshotId(snapshotId));
            assertEquals("InvalidSnapshot.NotFound",
                    errorCode(assertThrows(Ec2Exception.class, () -> describe(ec2, snapshotId))));
        }
    }

    @Test
    void copySnapshotAcrossRegionsCreatesAnIndependentSnapshot() {
        try (Ec2Client source = client(REGION); Ec2Client destination = client(COPY_REGION)) {
            String sourceId = source.createSnapshot(request -> request
                    .volumeId(createVolume(source, 2, true)).description("source")).snapshotId();

            // A raw request: the SDK presigns a cross-Region copy against the source Region's
            // endpoint and cannot derive that from a localhost endpoint override.
            String copyId = ec2(COPY_REGION, "CopySnapshot",
                    "SourceRegion", REGION,
                    "SourceSnapshotId", sourceId,
                    "Description", "copy",
                    "TagSpecification.1.ResourceType", "snapshot",
                    "TagSpecification.1.Tag.1.Key", "Name",
                    "TagSpecification.1.Tag.1.Value", "copy")
                    .post("/").then()
                    .statusCode(200)
                    .body("CopySnapshotResponse.tagSet.item.key", equalTo("Name"))
                    .extract().xmlPath().getString("CopySnapshotResponse.snapshotId");

            assertNotEquals(sourceId, copyId);
            Snapshot copy = describe(destination, copyId);
            assertEquals(SnapshotState.COMPLETED, copy.state());
            assertEquals(2, copy.volumeSize());
            assertEquals("vol-ffffffff", copy.volumeId());
            assertEquals("copy", copy.description());
            assertTrue(copy.encrypted());
            assertEquals(List.of(Tag.builder().key("Name").value("copy").build()), copy.tags());

            source.deleteSnapshot(request -> request.snapshotId(sourceId));
            assertEquals(copyId, describe(destination, copyId).snapshotId());
        }
    }

    @Test
    void copySnapshotOfAnUnknownSnapshotFails() {
        ec2(COPY_REGION, "CopySnapshot", "SourceRegion", REGION, "SourceSnapshotId", "snap-0123456789abcdef0")
                .post("/").then()
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidSnapshot.NotFound"));
    }
}
