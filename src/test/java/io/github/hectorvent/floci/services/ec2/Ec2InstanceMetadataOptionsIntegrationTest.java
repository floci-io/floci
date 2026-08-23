package io.github.hectorvent.floci.services.ec2;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * lex00/floci#114: RunInstances/DescribeInstances hardcoded every
 * metadataOptions field ("optional"/"1"/"enabled"/"disabled"/"disabled"),
 * ignoring both the launch's own MetadataOptions.* arguments and
 * ModifyInstanceMetadataOptions (previously UnsupportedOperation). Found via
 * terraform-aws-modules/terraform-aws-ec2-instance's "complete" example,
 * whose module defaults to {@code http_tokens = "required"} - every launch
 * of it produced a permanent non-empty second plan because the emulator
 * always reported "optional" back regardless of what was requested.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2InstanceMetadataOptionsIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/me-south-1/ec2/aws4_request";

    private static String instanceId;

    @Test
    @Order(1)
    void aLaunchWithNoMetadataOptionsGetsAwsOwnDefaults() {
        instanceId = given()
                .formParam("Action", "RunInstances")
                .formParam("ImageId", "ami-0abcdef1234567890")
                .formParam("InstanceType", "t3.micro")
                .formParam("MinCount", "1")
                .formParam("MaxCount", "1")
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("RunInstancesResponse.instancesSet.item.metadataOptions.httpTokens", equalTo("optional"))
                .body("RunInstancesResponse.instancesSet.item.metadataOptions.httpPutResponseHopLimit", equalTo("1"))
                .body("RunInstancesResponse.instancesSet.item.metadataOptions.httpEndpoint", equalTo("enabled"))
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
    }

    @Test
    @Order(2)
    void anExplicitLaunchRequestIsHonouredNotHardcoded() {
        String id = given()
                .formParam("Action", "RunInstances")
                .formParam("ImageId", "ami-0abcdef1234567890")
                .formParam("InstanceType", "t3.micro")
                .formParam("MinCount", "1")
                .formParam("MaxCount", "1")
                .formParam("MetadataOptions.HttpTokens", "required")
                .formParam("MetadataOptions.HttpPutResponseHopLimit", "2")
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("RunInstancesResponse.instancesSet.item.metadataOptions.httpTokens", equalTo("required"))
                .body("RunInstancesResponse.instancesSet.item.metadataOptions.httpPutResponseHopLimit", equalTo("2"))
                // Untouched fields still get AWS's own default, not a value smeared
                // across from the fields that were set.
                .body("RunInstancesResponse.instancesSet.item.metadataOptions.httpEndpoint", equalTo("enabled"))
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");

        given()
                .formParam("Action", "DescribeInstances")
                .formParam("InstanceId.1", id)
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.metadataOptions.httpTokens",
                        equalTo("required"));
    }

    @Test
    @Order(3)
    void modifyInstanceMetadataOptionsChangesOnlyWhatItNames() {
        given()
                .formParam("Action", "ModifyInstanceMetadataOptions")
                .formParam("InstanceId", instanceId)
                .formParam("HttpTokens", "required")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ModifyInstanceMetadataOptionsResponse.metadataOptions.httpTokens", equalTo("required"))
            // Never named in the modify call - must still read back the launch default.
            .body("ModifyInstanceMetadataOptionsResponse.metadataOptions.httpPutResponseHopLimit", equalTo("1"))
            .body("ModifyInstanceMetadataOptionsResponse.metadataOptions.httpEndpoint", equalTo("enabled"));

        given()
                .formParam("Action", "DescribeInstances")
                .formParam("InstanceId.1", instanceId)
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.metadataOptions.httpTokens",
                        equalTo("required"));
    }

    @Test
    @Order(4)
    void aRegionLevelDefaultIsInheritedByALaunchThatSetsNothing() {
        given()
                .formParam("Action", "ModifyInstanceMetadataDefaults")
                .formParam("HttpTokens", "required")
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200);

        given()
                .formParam("Action", "RunInstances")
                .formParam("ImageId", "ami-0abcdef1234567890")
                .formParam("InstanceType", "t3.micro")
                .formParam("MinCount", "1")
                .formParam("MaxCount", "1")
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("RunInstancesResponse.instancesSet.item.metadataOptions.httpTokens", equalTo("required"));
    }
}
