package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;

/**
 * EBS encryption by default has to be applied, not only stored: a volume created, or an instance
 * root volume launched, while the setting is on is encrypted, as on AWS.
 *
 * <p>Runs in its own region. The setting is region-scoped and persists across test classes in the same
 * application, so using us-east-1 would leak encrypted volumes into every other EC2 test. The last test
 * turns it off again; a static @AfterAll cannot, because the application is already stopped by then.</p>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2EbsEncryptionByDefaultAppliedIntegrationTest {

    private static final String REGION = "ap-southeast-3";
    private static final String AZ = REGION + "a";

    private static String authHeader(String region) {
        return "AWS4-HMAC-SHA256 Credential=test/20260205/" + region + "/ec2/aws4_request";
    }

    private static final String AUTH_HEADER = authHeader(REGION);

    private static String createVolume(String... extraParams) {
        var request = given()
                .formParam("Action", "CreateVolume")
                .formParam("AvailabilityZone", AZ)
                .formParam("Size", "1")
                .header("Authorization", AUTH_HEADER);
        for (int i = 0; i + 1 < extraParams.length; i += 2) {
            request = request.formParam(extraParams[i], extraParams[i + 1]);
        }
        return request.when().post("/").then().statusCode(200)
                .extract().path("CreateVolumeResponse.encrypted");
    }

    private static void setDefault(boolean enabled) {
        given().formParam("Action", enabled ? "EnableEbsEncryptionByDefault" : "DisableEbsEncryptionByDefault")
                .header("Authorization", AUTH_HEADER)
                .when().post("/").then().statusCode(200);
    }

    private static String runInstanceInRegion(String region) {
        return given()
                .formParam("Action", "RunInstances")
                .formParam("ImageId", "ami-0abcdef1234567890")
                .formParam("InstanceType", "t3.micro")
                .formParam("MinCount", "1")
                .formParam("MaxCount", "1")
                .header("Authorization", authHeader(region))
                .when().post("/").then().statusCode(200)
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
    }

    private static String rootVolumeEncrypted(String region, String instanceId) {
        String volumeId = given()
                .formParam("Action", "DescribeInstances")
                .formParam("InstanceId.1", instanceId)
                .header("Authorization", authHeader(region))
                .when().post("/").then().statusCode(200)
                .extract().path("DescribeInstancesResponse.reservationSet.item.instancesSet.item"
                        + ".blockDeviceMapping.item.ebs.volumeId");
        return given()
                .formParam("Action", "DescribeVolumes")
                .formParam("VolumeId.1", volumeId)
                .header("Authorization", authHeader(region))
                .when().post("/").then().statusCode(200)
                .extract().path("DescribeVolumesResponse.volumeSet.item.encrypted");
    }

    @Test
    @Order(1)
    void volumesAreUnencryptedWhileTheDefaultIsOff() {
        assertEquals("false", createVolume());
    }

    @Test
    @Order(2)
    void aNewVolumeIsEncryptedWhileTheDefaultIsOn() {
        setDefault(true);
        assertEquals("true", createVolume());
    }

    @Test
    @Order(3)
    void theEncryptedFlagIsPersistedAndReportedByDescribeVolumes() {
        String volumeId = given()
                .formParam("Action", "CreateVolume")
                .formParam("AvailabilityZone", AZ)
                .formParam("Size", "1")
                .header("Authorization", AUTH_HEADER)
                .when().post("/").then().statusCode(200)
                .extract().path("CreateVolumeResponse.volumeId");
        given()
                .formParam("Action", "DescribeVolumes")
                .formParam("Filter.1.Name", "encrypted")
                .formParam("Filter.1.Value.1", "true")
                .header("Authorization", AUTH_HEADER)
                .when().post("/").then().statusCode(200)
                .body("DescribeVolumesResponse.volumeSet.item.volumeId", hasItem(volumeId));
    }

    @Test
    @Order(4)
    void encryptedFalseCannotOptOutOfTheDefault() {
        assertEquals("true", createVolume("Encrypted", "false"));
    }

    @Test
    @Order(5)
    void anInstanceRootVolumeIsEncryptedWhileTheDefaultIsOn() {
        String instanceId = runInstanceInRegion(REGION);
        assertEquals("true", rootVolumeEncrypted(REGION, instanceId));
    }

    @Test
    @Order(6)
    void theDefaultIsRegionScoped() {
        // The default is on for REGION only; another region keeps creating unencrypted volumes.
        String other = "ap-southeast-4";
        String encrypted = given()
                .formParam("Action", "CreateVolume")
                .formParam("AvailabilityZone", other + "a")
                .formParam("Size", "1")
                .header("Authorization", authHeader(other))
                .when().post("/").then().statusCode(200)
                .extract().path("CreateVolumeResponse.encrypted");
        assertEquals("false", encrypted);
    }

    @Test
    @Order(7)
    void anExplicitlyEncryptedVolumeStaysEncrypted() {
        setDefault(false);
        assertEquals("true", createVolume("Encrypted", "true"));
    }

    @Test
    @Order(8)
    void disablingTheDefaultStopsEncryptingNewVolumesAndRootVolumes() {
        assertEquals("false", createVolume());
        String instanceId = runInstanceInRegion(REGION);
        assertEquals("false", rootVolumeEncrypted(REGION, instanceId));
    }

    private static void assertEquals(String expected, String actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}
