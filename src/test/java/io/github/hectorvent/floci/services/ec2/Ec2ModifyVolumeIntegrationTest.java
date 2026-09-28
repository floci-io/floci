package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2ModifyVolumeIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20250101/us-east-1/ec2/aws4_request";

    private static String volumeId;
    private static String unmodifiedVolumeId;

    private String createVolume(String type, int size) {
        return given().header("Authorization", AUTH_HEADER)
                .formParam("Action", "CreateVolume")
                .formParam("AvailabilityZone", "us-east-1a")
                .formParam("VolumeType", type)
                .formParam("Size", String.valueOf(size))
            .when().post("/")
            .then().statusCode(200)
                .body("CreateVolumeResponse.volumeId", startsWith("vol-"))
                .extract().path("CreateVolumeResponse.volumeId");
    }

    @Test
    @Order(1)
    void createInitialVolumes() {
        volumeId = createVolume("gp3", 10);
        unmodifiedVolumeId = createVolume("gp3", 10);
    }

    @Test
    @Order(2)
    void modifyVolumeIncreasesSizeAndReportsCompletedState() {
        given().header("Authorization", AUTH_HEADER)
                .formParam("Action", "ModifyVolume")
                .formParam("VolumeId", volumeId)
                .formParam("Size", "20")
            .when().post("/")
            .then().statusCode(200)
                .body("ModifyVolumeResponse.volumeModification.volumeId", equalTo(volumeId))
                .body("ModifyVolumeResponse.volumeModification.targetSize", equalTo("20"))
                .body("ModifyVolumeResponse.volumeModification.originalSize", equalTo("10"))
                .body("ModifyVolumeResponse.volumeModification.modificationState", equalTo("completed"))
                .body("ModifyVolumeResponse.volumeModification.progress", equalTo("100"))
                .body("ModifyVolumeResponse.volumeModification.startTime", notNullValue())
                .body("ModifyVolumeResponse.volumeModification.endTime", notNullValue());

        given().header("Authorization", AUTH_HEADER)
                .formParam("Action", "DescribeVolumes")
                .formParam("VolumeId.1", volumeId)
            .when().post("/")
            .then().statusCode(200)
                .body("DescribeVolumesResponse.volumeSet.item.volumeId", equalTo(volumeId))
                .body("DescribeVolumesResponse.volumeSet.item.size", equalTo("20"));
    }

    @Test
    @Order(3)
    void describeVolumesModificationsReturnsCompletedModification() {
        given().header("Authorization", AUTH_HEADER)
                .formParam("Action", "DescribeVolumesModifications")
                .formParam("VolumeId.1", volumeId)
            .when().post("/")
            .then().statusCode(200)
                .body("DescribeVolumesModificationsResponse.volumeModificationSet.item.volumeId", equalTo(volumeId))
                .body("DescribeVolumesModificationsResponse.volumeModificationSet.item.targetSize", equalTo("20"))
                .body("DescribeVolumesModificationsResponse.volumeModificationSet.item.originalSize", equalTo("10"))
                .body("DescribeVolumesModificationsResponse.volumeModificationSet.item.modificationState", equalTo("completed"))
                .body("DescribeVolumesModificationsResponse.volumeModificationSet.item.progress", equalTo("100"));
    }

    @Test
    @Order(4)
    void modifyVolumeRejectsSizeDecrease() {
        given().header("Authorization", AUTH_HEADER)
                .formParam("Action", "ModifyVolume")
                .formParam("VolumeId", volumeId)
                .formParam("Size", "15")
            .when().post("/")
            .then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"))
                .body("Response.Errors.Error.Message", equalTo("New size cannot be smaller than existing size"));
    }

    @Test
    @Order(5)
    void modifyVolumeRejectsUnknownVolume() {
        given().header("Authorization", AUTH_HEADER)
                .formParam("Action", "ModifyVolume")
                .formParam("VolumeId", "vol-0123456789abcdef0")
                .formParam("Size", "30")
            .when().post("/")
            .then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidVolume.NotFound"))
                .body("Response.Errors.Error.Message", equalTo("The volume 'vol-0123456789abcdef0' does not exist."));
    }

    @Test
    @Order(6)
    void describeVolumesModificationsRejectsUnmodifiedVolume() {
        given().header("Authorization", AUTH_HEADER)
                .formParam("Action", "DescribeVolumesModifications")
                .formParam("VolumeId.1", unmodifiedVolumeId)
            .when().post("/")
            .then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidVolumeModification.NotFound"))
                .body("Response.Errors.Error.Message", equalTo("Modification for volume '" + unmodifiedVolumeId + "' does not exist."));
    }

    @Test
    @Order(7)
    void describeVolumesModificationsWithoutIdsExcludesUnmodifiedVolume() {
        given().header("Authorization", AUTH_HEADER)
                .formParam("Action", "DescribeVolumesModifications")
            .when().post("/")
            .then().statusCode(200)
                .body("DescribeVolumesModificationsResponse.volumeModificationSet.item.volumeId",
                        equalTo(volumeId));

        given().header("Authorization", AUTH_HEADER)
                .formParam("Action", "DescribeVolumesModifications")
                .formParam("Filter.1.Name", "volume-id")
                .formParam("Filter.1.Value.1", volumeId)
            .when().post("/")
            .then().statusCode(200)
                .body("DescribeVolumesModificationsResponse.volumeModificationSet.item.volumeId", equalTo(volumeId));
    }

    @Test
    @Order(8)
    void modifyVolumeRejectsInvalidParameterCombinations() {
        String standardVolId = createVolume("standard", 10);

        given().header("Authorization", AUTH_HEADER)
                .formParam("Action", "ModifyVolume")
                .formParam("VolumeId", standardVolId)
                .formParam("Throughput", "250")
            .when().post("/")
            .then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidParameterCombination"))
                .body("Response.Errors.Error.Message", equalTo("The parameter Throughput is not supported for standard volumes"));

        given().header("Authorization", AUTH_HEADER)
                .formParam("Action", "ModifyVolume")
                .formParam("VolumeId", standardVolId)
                .formParam("Iops", "1000")
            .when().post("/")
            .then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidParameterCombination"))
                .body("Response.Errors.Error.Message", equalTo("The parameter iops is not supported for standard volumes"));

        given().header("Authorization", AUTH_HEADER)
                .formParam("Action", "ModifyVolume")
                .formParam("VolumeId", volumeId)
                .formParam("MultiAttachEnabled", "true")
            .when().post("/")
            .then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidParameterCombination"))
                .body("Response.Errors.Error.Message", equalTo("The parameter MultiAttachEnabled is not supported for gp3 volumes"));
    }

    @Test
    @Order(9)
    void modifyVolumeSupportsDryRun() {
        given().header("Authorization", AUTH_HEADER)
                .formParam("Action", "ModifyVolume")
                .formParam("VolumeId", volumeId)
                .formParam("Size", "30")
                .formParam("DryRun", "true")
            .when().post("/")
            .then().statusCode(412)
                .body("Response.Errors.Error.Code", equalTo("DryRunOperation"));
    }
}
