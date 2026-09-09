package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.Matchers.containsString;

/**
 * Integration tests for standalone elastic network interfaces over the EC2 Query protocol,
 * floci-kt9: {@code CreateNetworkInterface} previously returned {@code UnsupportedOperation}
 * unconditionally, blocking every root that attaches a standalone ENI (the attach-eni and
 * override-default-eni patterns in terraform-aws-server/terraform-aws-asg/
 * terraform-aws-service-catalog).
 *
 * <p>Covers the full lifecycle the blocked roots exercise: create, describe (including the
 * pagination/filter surface DescribeNetworkInterfaces already had), attach to a running instance,
 * detach, and delete, plus RunInstances accepting a pre-existing standalone ENI as an instance's
 * primary interface (override-default-eni).
 *
 * <p>Ordered because the cases build on one ENI and one instance, mirroring how a client drives
 * the resources through their lifecycle. Runs in mock mode (floci.services.ec2.mock=true in test
 * application.yml) so no real Docker is required.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2NetworkInterfaceIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private static String subnetId;
    private static String securityGroupId;
    private static String eniId;
    private static String instanceId;
    private static String attachmentId;

    @Test
    @Order(1)
    void discoverDefaultSubnetAndSecurityGroup() {
        subnetId = given()
            .formParam("Action", "DescribeSubnets")
            .formParam("Filter.1.Name", "default-for-az")
            .formParam("Filter.1.Value.1", "true")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("DescribeSubnetsResponse.subnetSet.item[0].subnetId");

        securityGroupId = given()
            .formParam("Action", "DescribeSecurityGroups")
            .formParam("Filter.1.Name", "group-name")
            .formParam("Filter.1.Value.1", "default")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("DescribeSecurityGroupsResponse.securityGroupInfo.item[0].groupId");

        org.junit.jupiter.api.Assertions.assertTrue(subnetId.startsWith("subnet-"));
        org.junit.jupiter.api.Assertions.assertTrue(securityGroupId.startsWith("sg-"));
    }

    // ─── Create ────────────────────────────────────────────────────────────────

    @Test
    @Order(2)
    void createNetworkInterface() {
        eniId = given()
            .formParam("Action", "CreateNetworkInterface")
            .formParam("SubnetId", subnetId)
            .formParam("Description", "attach-eni example ENI")
            .formParam("SecurityGroupId.1", securityGroupId)
            .formParam("TagSpecification.1.ResourceType", "network-interface")
            .formParam("TagSpecification.1.Tag.1.Key", "Name")
            .formParam("TagSpecification.1.Tag.1.Value", "example")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateNetworkInterfaceResponse.networkInterface.subnetId", equalTo(subnetId))
            .body("CreateNetworkInterfaceResponse.networkInterface.status", equalTo("available"))
            .body("CreateNetworkInterfaceResponse.networkInterface.description", equalTo("attach-eni example ENI"))
            .body("CreateNetworkInterfaceResponse.networkInterface.privateIpAddress", not(emptyOrNullString()))
            .body("CreateNetworkInterfaceResponse.networkInterface.macAddress", not(emptyOrNullString()))
            .body("CreateNetworkInterfaceResponse.networkInterface.groupSet.item.groupId", equalTo(securityGroupId))
            .body("CreateNetworkInterfaceResponse.networkInterface.tagSet.item.value", equalTo("example"))
            .extract().path("CreateNetworkInterfaceResponse.networkInterface.networkInterfaceId");

        org.junit.jupiter.api.Assertions.assertTrue(eniId.startsWith("eni-"));
    }

    @Test
    @Order(3)
    void describeReturnsTheCreatedInterfaceAsAvailable() {
        given()
            .formParam("Action", "DescribeNetworkInterfaces")
            .formParam("NetworkInterfaceId.1", eniId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.networkInterfaceId", equalTo(eniId))
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.status", equalTo("available"));
    }

    @Test
    @Order(4)
    void describeFiltersByNetworkInterfaceId() {
        given()
            .formParam("Action", "DescribeNetworkInterfaces")
            .formParam("Filter.1.Name", "network-interface-id")
            .formParam("Filter.1.Value.1", eniId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.networkInterfaceId", equalTo(eniId));
    }

    // ─── Attach / Detach (the attach-eni pattern) ────────────────────────────────

    @Test
    @Order(5)
    void launchAnInstanceToAttachTo() {
        instanceId = given()
            .formParam("Action", "RunInstances")
            .formParam("ImageId", "ami-amazonlinux2023")
            .formParam("InstanceType", "t2.micro")
            .formParam("MinCount", "1")
            .formParam("MaxCount", "1")
            .formParam("SubnetId", subnetId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("RunInstancesResponse.instancesSet.item.instanceId");

        org.junit.jupiter.api.Assertions.assertTrue(instanceId.startsWith("i-"));

        // Mock mode settles a pending instance to "running" on the next describe (see
        // Ec2Service#describeInstances), AttachNetworkInterface requires running/stopped.
        given()
            .formParam("Action", "DescribeInstances")
            .formParam("InstanceId.1", instanceId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.instanceState.name",
                    equalTo("running"));
    }

    @Test
    @Order(6)
    void attachNetworkInterfaceToInstance() {
        attachmentId = given()
            .formParam("Action", "AttachNetworkInterface")
            .formParam("NetworkInterfaceId", eniId)
            .formParam("InstanceId", instanceId)
            .formParam("DeviceIndex", "1")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AttachNetworkInterfaceResponse.attachmentId", not(emptyOrNullString()))
            .extract().path("AttachNetworkInterfaceResponse.attachmentId");
    }

    @Test
    @Order(7)
    void describeShowsTheInterfaceAsInUseAndAttached() {
        given()
            .formParam("Action", "DescribeNetworkInterfaces")
            .formParam("NetworkInterfaceId.1", eniId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.status", equalTo("in-use"))
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.attachment.attachmentId", equalTo(attachmentId))
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.attachment.instanceId", equalTo(instanceId))
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.attachment.deviceIndex", equalTo("1"));
    }

    @Test
    @Order(8)
    void attachingAnAlreadyAttachedInterfaceFails() {
        given()
            .formParam("Action", "AttachNetworkInterface")
            .formParam("NetworkInterfaceId", eniId)
            .formParam("InstanceId", instanceId)
            .formParam("DeviceIndex", "2")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidNetworkInterface.InUse"));
    }

    @Test
    @Order(9)
    void deletingAnAttachedInterfaceFails() {
        given()
            .formParam("Action", "DeleteNetworkInterface")
            .formParam("NetworkInterfaceId", eniId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));
    }

    @Test
    @Order(10)
    void detachNetworkInterface() {
        given()
            .formParam("Action", "DetachNetworkInterface")
            .formParam("AttachmentId", attachmentId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DetachNetworkInterfaceResponse.return", equalTo("true"));
    }

    @Test
    @Order(11)
    void describeShowsTheInterfaceAvailableAgainAfterDetach() {
        given()
            .formParam("Action", "DescribeNetworkInterfaces")
            .formParam("NetworkInterfaceId.1", eniId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.status", equalTo("available"))
            .body(not(containsString("<attachment>")));
    }

    // ─── Delete ────────────────────────────────────────────────────────────────

    @Test
    @Order(12)
    void deleteNetworkInterface() {
        given()
            .formParam("Action", "DeleteNetworkInterface")
            .formParam("NetworkInterfaceId", eniId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeleteNetworkInterfaceResponse.return", equalTo("true"));
    }

    @Test
    @Order(13)
    void describeAfterDeleteReturnsNotFound() {
        given()
            .formParam("Action", "DescribeNetworkInterfaces")
            .formParam("NetworkInterfaceId.1", eniId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidNetworkInterfaceID.NotFound"));
    }

    // ─── RunInstances with a pre-existing ENI (the override-default-eni pattern) ─

    @Test
    @Order(14)
    void runInstancesAcceptsAPreExistingNetworkInterfaceAsThePrimary() {
        String overrideEniId = given()
            .formParam("Action", "CreateNetworkInterface")
            .formParam("SubnetId", subnetId)
            .formParam("SecurityGroupId.1", securityGroupId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateNetworkInterfaceResponse.networkInterface.networkInterfaceId");

        String overrideInstanceId = given()
            .formParam("Action", "RunInstances")
            .formParam("ImageId", "ami-amazonlinux2023")
            .formParam("InstanceType", "t2.micro")
            .formParam("MinCount", "1")
            .formParam("MaxCount", "1")
            .formParam("NetworkInterface.1.NetworkInterfaceId", overrideEniId)
            .formParam("NetworkInterface.1.DeviceIndex", "0")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RunInstancesResponse.instancesSet.item.networkInterfaceSet.item.networkInterfaceId",
                    equalTo(overrideEniId))
            .extract().path("RunInstancesResponse.instancesSet.item.instanceId");

        org.junit.jupiter.api.Assertions.assertTrue(overrideInstanceId.startsWith("i-"));

        // The interface keeps its own standalone record, that is the side that knows its real
        // attach time and its deleteOnTermination, while the instance carries a copy. Describe
        // reports it exactly once regardless, from the standalone record.
        given()
            .formParam("Action", "DescribeNetworkInterfaces")
            .formParam("NetworkInterfaceId.1", overrideEniId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.size()", equalTo(1))
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.attachment.instanceId",
                    equalTo(overrideInstanceId));
    }

    @Test
    @Order(15)
    void runInstancesRejectsAPreExistingInterfaceWithMoreThanOneInstance() {
        String eni = given()
            .formParam("Action", "CreateNetworkInterface")
            .formParam("SubnetId", subnetId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateNetworkInterfaceResponse.networkInterface.networkInterfaceId");

        given()
            .formParam("Action", "RunInstances")
            .formParam("ImageId", "ami-amazonlinux2023")
            .formParam("InstanceType", "t2.micro")
            .formParam("MinCount", "2")
            .formParam("MaxCount", "2")
            .formParam("NetworkInterface.1.NetworkInterfaceId", eni)
            .formParam("NetworkInterface.1.DeviceIndex", "0")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidParameterCombination"));
    }

    /**
     * An attachment has to be visible from both ends. Recording it only on the standalone ENI let
     * DescribeNetworkInterfaces report an attachment DescribeInstances denied, and left the
     * device-index conflict check, which reads the instance's own list, unable to see anything
     * this operation had attached.
     */
    @Test
    @Order(16)
    void attachingAnInterfaceAlsoRecordsItOnTheInstance() {
        String eni = given()
            .formParam("Action", "CreateNetworkInterface")
            .formParam("SubnetId", subnetId)
            .formParam("SecurityGroupId.1", securityGroupId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateNetworkInterfaceResponse.networkInterface.networkInterfaceId");

        String host = given()
            .formParam("Action", "RunInstances")
            .formParam("ImageId", "ami-amazonlinux2023")
            .formParam("InstanceType", "t2.micro")
            .formParam("MinCount", "1")
            .formParam("MaxCount", "1")
            .formParam("SubnetId", subnetId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("RunInstancesResponse.instancesSet.item.instanceId");

        // Mock mode settles a pending instance to "running" on the next describe; attach needs it.
        given()
            .formParam("Action", "DescribeInstances")
            .formParam("InstanceId.1", host)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "AttachNetworkInterface")
            .formParam("NetworkInterfaceId", eni)
            .formParam("InstanceId", host)
            .formParam("DeviceIndex", "1")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "DescribeInstances")
            .formParam("InstanceId.1", host)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item."
                    + "networkInterfaceSet.item.networkInterfaceId", hasItem(eni));

        // ... and the device index it now occupies is refused to a second interface.
        String second = given()
            .formParam("Action", "CreateNetworkInterface")
            .formParam("SubnetId", subnetId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateNetworkInterfaceResponse.networkInterface.networkInterfaceId");

        given()
            .formParam("Action", "AttachNetworkInterface")
            .formParam("NetworkInterfaceId", second)
            .formParam("InstanceId", host)
            .formParam("DeviceIndex", "1")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));
    }

    /**
     * An interface the caller created is not the instance's to destroy. AWS attaches it with
     * deleteOnTermination false, so terminating the instance returns it to "available" rather
     * than making it disappear, which is what a client that reuses one ENI across successive
     * instances depends on.
     */
    @Test
    @Order(17)
    void terminatingTheInstanceReturnsAPreExistingInterfaceToAvailable() {
        String eni = given()
            .formParam("Action", "CreateNetworkInterface")
            .formParam("SubnetId", subnetId)
            .formParam("SecurityGroupId.1", securityGroupId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateNetworkInterfaceResponse.networkInterface.networkInterfaceId");

        String host = given()
            .formParam("Action", "RunInstances")
            .formParam("ImageId", "ami-amazonlinux2023")
            .formParam("InstanceType", "t2.micro")
            .formParam("MinCount", "1")
            .formParam("MaxCount", "1")
            .formParam("NetworkInterface.1.NetworkInterfaceId", eni)
            .formParam("NetworkInterface.1.DeviceIndex", "0")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("RunInstancesResponse.instancesSet.item.instanceId");

        given()
            .formParam("Action", "TerminateInstances")
            .formParam("InstanceId.1", host)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // The terminated instance lets go of it too. Leaving it on that record would have two
        // instances claiming the interface once it is reused below.
        given()
            .formParam("Action", "DescribeInstances")
            .formParam("InstanceId.1", host)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString(eni)));

        given()
            .formParam("Action", "DescribeNetworkInterfaces")
            .formParam("NetworkInterfaceId.1", eni)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.status",
                    equalTo("available"));

        // Free again, so it can be attached to a new instance, and this is the assertion that
        // proves the attachment is really gone, not just the status text: RunInstances resolves
        // the interface through takeNetworkInterfaceForLaunch, which refuses one that still
        // carries an attachment with InvalidNetworkInterface.InUse.
        String replacement = given()
            .formParam("Action", "RunInstances")
            .formParam("ImageId", "ami-amazonlinux2023")
            .formParam("InstanceType", "t2.micro")
            .formParam("MinCount", "1")
            .formParam("MaxCount", "1")
            .formParam("NetworkInterface.1.NetworkInterfaceId", eni)
            .formParam("NetworkInterface.1.DeviceIndex", "0")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("RunInstancesResponse.instancesSet.item.instanceId");

        org.junit.jupiter.api.Assertions.assertTrue(replacement.startsWith("i-"));
    }
}
