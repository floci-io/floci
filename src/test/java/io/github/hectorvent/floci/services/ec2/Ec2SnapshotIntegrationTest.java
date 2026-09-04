package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class Ec2SnapshotIntegrationTest {

    private static final String ACCOUNT_ID = "111122223333";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=" + ACCOUNT_ID + "/20260904/us-east-1/ec2/aws4_request";

    @Test
    void createSnapshotConsumesFixtureOverridesAndSurvivesSourceVolumeDeletion() {
        String volumeId = given()
                .formParam("Action", "CreateVolume")
                .formParam("AvailabilityZone", "us-east-1a")
                .formParam("VolumeType", "gp3")
                .formParam("Size", "24")
                .formParam("Encrypted", "true")
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().path("CreateVolumeResponse.volumeId");
        String snapshotId = "snap-" + UUID.randomUUID().toString().replace("-", "").substring(0, 17);

        given()
                .formParam("Action", "CreateSnapshot")
                .formParam("VolumeId", volumeId)
                .formParam("Description", "N2WS retention copy")
                .formParam("TagSpecification.1.ResourceType", "snapshot")
                .formParam("TagSpecification.1.Tag.1.Key", "floci:override-id")
                .formParam("TagSpecification.1.Tag.1.Value", snapshotId)
                .formParam("TagSpecification.1.Tag.2.Key", "floci:override-start-time")
                .formParam("TagSpecification.1.Tag.2.Value", "2026-06-01T00:00:00Z")
                .formParam("TagSpecification.1.Tag.3.Key", "cpm_policy_name")
                .formParam("TagSpecification.1.Tag.3.Value", "two-week-retention")
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("CreateSnapshotResponse.snapshotId", equalTo(snapshotId))
                .body("CreateSnapshotResponse.ownerId", equalTo(ACCOUNT_ID))
                .body("CreateSnapshotResponse.volumeId", equalTo(volumeId))
                .body("CreateSnapshotResponse.volumeSize", equalTo("24"))
                .body("CreateSnapshotResponse.startTime", equalTo("2026-06-01T00:00:00.000Z"))
                .body("CreateSnapshotResponse.tagSet.item.key", equalTo("cpm_policy_name"))
                .body("CreateSnapshotResponse.tagSet.item.value", equalTo("two-week-retention"));

        given()
                .formParam("Action", "DeleteVolume")
                .formParam("VolumeId", volumeId)
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200);

        given()
                .formParam("Action", "DescribeSnapshots")
                .formParam("SnapshotId.1", snapshotId)
                .formParam("OwnerId.1", "self")
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeSnapshotsResponse.snapshotSet.item.snapshotId", equalTo(snapshotId))
                .body("DescribeSnapshotsResponse.snapshotSet.item.startTime", equalTo("2026-06-01T00:00:00.000Z"))
                .body("DescribeSnapshotsResponse.snapshotSet.item.tagSet.item.key", equalTo("cpm_policy_name"));

        given()
                .formParam("Action", "DescribeVolumes")
                .formParam("VolumeId.1", volumeId)
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidVolume.NotFound"));
    }

    @Test
    void snapshotTagsUseTheSharedTagApi() {
        String volumeId = createVolume();
        String snapshotId = snapshotId();

        given()
                .formParam("Action", "CreateSnapshot")
                .formParam("VolumeId", volumeId)
                .formParam("TagSpecification.1.ResourceType", "snapshot")
                .formParam("TagSpecification.1.Tag.1.Key", "floci:override-id")
                .formParam("TagSpecification.1.Tag.1.Value", snapshotId)
                .formParam("TagSpecification.1.Tag.2.Key", "Name")
                .formParam("TagSpecification.1.Tag.2.Value", "initial")
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200);

        given()
                .formParam("Action", "DescribeTags")
                .formParam("Filter.1.Name", "resource-id")
                .formParam("Filter.1.Value.1", snapshotId)
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeTagsResponse.tagSet.item.resourceType", equalTo("snapshot"))
                .body("DescribeTagsResponse.tagSet.item.key", equalTo("Name"))
                .body("DescribeTagsResponse.tagSet.item.value", equalTo("initial"));

        given()
                .formParam("Action", "CreateTags")
                .formParam("ResourceId.1", snapshotId)
                .formParam("Tag.1.Key", "Name")
                .formParam("Tag.1.Value", "updated")
                .formParam("Tag.2.Key", "floci:override-start-time")
                .formParam("Tag.2.Value", "2020-01-01T00:00:00Z")
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200);

        given()
                .formParam("Action", "DescribeSnapshots")
                .formParam("SnapshotId.1", snapshotId)
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeSnapshotsResponse.snapshotSet.item.tagSet.item.key", equalTo("Name"))
                .body("DescribeSnapshotsResponse.snapshotSet.item.tagSet.item.value", equalTo("updated"));

        given()
                .formParam("Action", "DescribeTags")
                .formParam("Filter.1.Name", "resource-id")
                .formParam("Filter.1.Value.1", snapshotId)
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeTagsResponse.tagSet.item.key", equalTo("Name"))
                .body("DescribeTagsResponse.tagSet.item.value", equalTo("updated"));

        given()
                .formParam("Action", "DeleteTags")
                .formParam("ResourceId.1", snapshotId)
                .formParam("Tag.1.Key", "Name")
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200);

        given()
                .formParam("Action", "DescribeSnapshots")
                .formParam("SnapshotId.1", snapshotId)
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeSnapshotsResponse.snapshotSet.item.tagSet.item.size()", equalTo(0));
    }

    @Test
    void registerImageSnapshotBelongsToTheRequestCaller() {
        String snapshotId = snapshotId();

        given()
                .formParam("Action", "RegisterImage")
                .formParam("Name", "caller-image-" + UUID.randomUUID())
                .formParam("BlockDeviceMapping.1.DeviceName", "/dev/sda1")
                .formParam("BlockDeviceMapping.1.Ebs.SnapshotId", snapshotId)
                .formParam("BlockDeviceMapping.1.Ebs.VolumeSize", "8")
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200);

        given()
                .formParam("Action", "DescribeSnapshots")
                .formParam("SnapshotId.1", snapshotId)
                .formParam("OwnerId.1", "self")
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeSnapshotsResponse.snapshotSet.item.snapshotId", equalTo(snapshotId))
                .body("DescribeSnapshotsResponse.snapshotSet.item.ownerId", equalTo(ACCOUNT_ID));
    }

    @Test
    void createSnapshotRequiresVolumeId() {
        given()
                .formParam("Action", "CreateSnapshot")
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("MissingParameter"));
    }

    @Test
    void unsignedCreateSnapshotRoutesToEc2() {
        String volumeId = given()
                .formParam("Action", "CreateVolume")
                .formParam("AvailabilityZone", "us-east-1a")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().path("CreateVolumeResponse.volumeId");

        given()
                .formParam("Action", "CreateSnapshot")
                .formParam("VolumeId", volumeId)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("CreateSnapshotResponse.snapshotId", startsWith("snap-"))
                .body("CreateSnapshotResponse.volumeId", equalTo(volumeId));
    }

    private String createVolume() {
        return given()
                .formParam("Action", "CreateVolume")
                .formParam("AvailabilityZone", "us-east-1a")
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().path("CreateVolumeResponse.volumeId");
    }

    private String snapshotId() {
        return "snap-" + UUID.randomUUID().toString().replace("-", "").substring(0, 17);
    }
}
