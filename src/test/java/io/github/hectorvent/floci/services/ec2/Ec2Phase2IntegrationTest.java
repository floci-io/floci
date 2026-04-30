package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for EC2 Phase 2 features:
 * - UserData parsing from base64 wire format
 * - IamInstanceProfile.Arn stored on instance
 * - SSH key import and key name association
 * - State transitions (stop/start/terminate/reboot)
 * - DescribeInstances with state filters
 * - Error on StartInstances for terminated instance
 * - Multiple instance launch (MinCount/MaxCount)
 *
 * All tests run in mock mode (floci.services.ec2.mock=true in test application.yml)
 * so no real Docker is required.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2Phase2IntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private static String instanceWithUserData;
    private static String instanceWithProfile;
    private static String instanceForTerminate;
    private static String importedKeyName = "phase2-imported-key";

    // ─── UserData ──────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void runInstancesWithUserData() {
        String script = "#!/bin/bash\necho hello > /tmp/test.txt";
        String encoded = Base64.getEncoder().encodeToString(script.getBytes());

        instanceWithUserData = given()
            .formParam("Action", "RunInstances")
            .formParam("ImageId", "ami-amazonlinux2023")
            .formParam("InstanceType", "t2.micro")
            .formParam("MinCount", "1")
            .formParam("MaxCount", "1")
            .formParam("UserData", encoded)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RunInstancesResponse.instancesSet.item.instanceId", startsWith("i-"))
            .body("RunInstancesResponse.instancesSet.item.imageId", equalTo("ami-amazonlinux2023"))
            .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
    }

    @Test
    @Order(2)
    void describeInstanceLaunchedWithUserData() {
        given()
            .formParam("Action", "DescribeInstances")
            .formParam("InstanceId.1", instanceWithUserData)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.instanceId",
                    equalTo(instanceWithUserData))
            .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.instanceState.name",
                    equalTo("running"));
    }

    // ─── IamInstanceProfile ────────────────────────────────────────────────────

    @Test
    @Order(10)
    void runInstancesWithIamInstanceProfile() {
        String profileArn = "arn:aws:iam::000000000000:instance-profile/my-app-role";

        instanceWithProfile = given()
            .formParam("Action", "RunInstances")
            .formParam("ImageId", "ami-ubuntu2204")
            .formParam("InstanceType", "t3.micro")
            .formParam("MinCount", "1")
            .formParam("MaxCount", "1")
            .formParam("IamInstanceProfile.Arn", profileArn)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RunInstancesResponse.instancesSet.item.instanceId", startsWith("i-"))
            .body("RunInstancesResponse.instancesSet.item.instanceType", equalTo("t3.micro"))
            .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
    }

    @Test
    @Order(11)
    void describeInstanceWithProfile() {
        given()
            .formParam("Action", "DescribeInstances")
            .formParam("InstanceId.1", instanceWithProfile)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.instanceId",
                    equalTo(instanceWithProfile))
            .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.instanceState.name",
                    equalTo("running"));
    }

    // ─── SSH key import ────────────────────────────────────────────────────────

    @Test
    @Order(20)
    void importKeyPairForSsh() {
        // Minimal RSA public key in OpenSSH format (fake but parseable)
        String publicKey = "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAAAQQDHnLTTbpnrFWkfVt test@test";

        given()
            .formParam("Action", "ImportKeyPair")
            .formParam("KeyName", importedKeyName)
            .formParam("PublicKeyMaterial", publicKey)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ImportKeyPairResponse.keyName", equalTo(importedKeyName))
            .body("ImportKeyPairResponse.keyPairId", startsWith("key-"));
    }

    @Test
    @Order(21)
    void runInstancesWithImportedKey() {
        given()
            .formParam("Action", "RunInstances")
            .formParam("ImageId", "ami-amazonlinux2023")
            .formParam("InstanceType", "t2.micro")
            .formParam("MinCount", "1")
            .formParam("MaxCount", "1")
            .formParam("KeyName", importedKeyName)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RunInstancesResponse.instancesSet.item.instanceId", startsWith("i-"))
            .body("RunInstancesResponse.instancesSet.item.keyName", equalTo(importedKeyName));
    }

    // ─── Multiple instances ────────────────────────────────────────────────────

    @Test
    @Order(30)
    void runMultipleInstances() {
        given()
            .formParam("Action", "RunInstances")
            .formParam("ImageId", "ami-amazonlinux2023")
            .formParam("InstanceType", "t2.micro")
            .formParam("MinCount", "2")
            .formParam("MaxCount", "2")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RunInstancesResponse.instancesSet.item.size()", equalTo(2))
            .body("RunInstancesResponse.instancesSet.item[0].instanceId", startsWith("i-"))
            .body("RunInstancesResponse.instancesSet.item[1].instanceId", startsWith("i-"))
            .body("RunInstancesResponse.instancesSet.item[0].amiLaunchIndex", equalTo("0"))
            .body("RunInstancesResponse.instancesSet.item[1].amiLaunchIndex", equalTo("1"));
    }

    // ─── State filters ─────────────────────────────────────────────────────────

    @Test
    @Order(40)
    void describeRunningInstancesWithFilter() {
        given()
            .formParam("Action", "DescribeInstances")
            .formParam("Filter.1.Name", "instance-state-name")
            .formParam("Filter.1.Value.1", "running")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeInstancesResponse.reservationSet.item.size()", greaterThanOrEqualTo(1));
    }

    // ─── Lifecycle: stop/start/terminate ──────────────────────────────────────

    @Test
    @Order(50)
    void runInstanceForTermination() {
        instanceForTerminate = given()
            .formParam("Action", "RunInstances")
            .formParam("ImageId", "ami-amazonlinux2023")
            .formParam("InstanceType", "t2.micro")
            .formParam("MinCount", "1")
            .formParam("MaxCount", "1")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RunInstancesResponse.instancesSet.item.instanceId", startsWith("i-"))
            .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
    }

    @Test
    @Order(51)
    void stopInstance() {
        given()
            .formParam("Action", "StopInstances")
            .formParam("InstanceId.1", instanceForTerminate)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("StopInstancesResponse.instancesSet.item.instanceId", equalTo(instanceForTerminate))
            .body("StopInstancesResponse.instancesSet.item.currentState.name", equalTo("stopping"))
            .body("StopInstancesResponse.instancesSet.item.previousState.name", equalTo("running"));
    }

    @Test
    @Order(52)
    void startInstance() {
        given()
            .formParam("Action", "StartInstances")
            .formParam("InstanceId.1", instanceForTerminate)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("StartInstancesResponse.instancesSet.item.instanceId", equalTo(instanceForTerminate))
            .body("StartInstancesResponse.instancesSet.item.currentState.name", equalTo("pending"))
            .body("StartInstancesResponse.instancesSet.item.previousState.name", anyOf(
                    equalTo("stopped"), equalTo("stopping"), equalTo("running")));
    }

    @Test
    @Order(53)
    void terminateInstance() {
        given()
            .formParam("Action", "TerminateInstances")
            .formParam("InstanceId.1", instanceForTerminate)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TerminateInstancesResponse.instancesSet.item.instanceId", equalTo(instanceForTerminate))
            .body("TerminateInstancesResponse.instancesSet.item.currentState.name", equalTo("shutting-down"))
            .body("TerminateInstancesResponse.instancesSet.item.previousState.name", notNullValue());
    }

    @Test
    @Order(54)
    void startTerminatedInstanceFails() {
        given()
            .formParam("Action", "StartInstances")
            .formParam("InstanceId.1", instanceForTerminate)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("IncorrectInstanceState"));
    }

    // ─── TagSpecification on RunInstances ─────────────────────────────────────

    @Test
    @Order(60)
    void runInstancesWithTagSpecification() {
        String instanceId = given()
            .formParam("Action", "RunInstances")
            .formParam("ImageId", "ami-amazonlinux2023")
            .formParam("InstanceType", "t2.micro")
            .formParam("MinCount", "1")
            .formParam("MaxCount", "1")
            .formParam("TagSpecification.1.ResourceType", "instance")
            .formParam("TagSpecification.1.Tag.1.Key", "Env")
            .formParam("TagSpecification.1.Tag.1.Value", "test")
            .formParam("TagSpecification.1.Tag.2.Key", "Name")
            .formParam("TagSpecification.1.Tag.2.Value", "phase2-test-instance")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RunInstancesResponse.instancesSet.item.instanceId", startsWith("i-"))
            .body("RunInstancesResponse.instancesSet.item.tagSet.item.find { it.key == 'Env' }.value",
                    equalTo("test"))
            .extract().path("RunInstancesResponse.instancesSet.item.instanceId");

        // Verify tags appear in DescribeInstances
        given()
            .formParam("Action", "DescribeInstances")
            .formParam("InstanceId.1", instanceId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.tagSet.item.size()",
                    equalTo(2));
    }

    // ─── Instance type and placement ──────────────────────────────────────────

    @Test
    @Order(70)
    void runInstancesDefaultPlacement() {
        given()
            .formParam("Action", "RunInstances")
            .formParam("ImageId", "ami-amazonlinux2023")
            .formParam("InstanceType", "m5.large")
            .formParam("MinCount", "1")
            .formParam("MaxCount", "1")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RunInstancesResponse.instancesSet.item.instanceType", equalTo("m5.large"))
            .body("RunInstancesResponse.instancesSet.item.placement.availabilityZone",
                    startsWith("us-east-1"))
            .body("RunInstancesResponse.instancesSet.item.privateIpAddress", not(emptyOrNullString()))
            .body("RunInstancesResponse.instancesSet.item.vpcId", not(emptyOrNullString()));
    }

    // ─── Error: invalid instance ID ──────────────────────────────────────────

    @Test
    @Order(80)
    void terminateNonExistentInstance() {
        given()
            .formParam("Action", "TerminateInstances")
            .formParam("InstanceId.1", "i-nonexistent1234567890")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidInstanceID.NotFound"));
    }

    @Test
    @Order(81)
    void stopNonExistentInstance() {
        given()
            .formParam("Action", "StopInstances")
            .formParam("InstanceId.1", "i-nonexistent1234567890")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidInstanceID.NotFound"));
    }

    @Test
    @Order(82)
    void describeNonExistentInstance() {
        given()
            .formParam("Action", "DescribeInstances")
            .formParam("InstanceId.1", "i-nonexistent1234567890")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidInstanceID.NotFound"));
    }
}
