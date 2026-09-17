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
        String vpcId = given()
            .formParam("Action", "DescribeVpcs")
            .formParam("Filter.1.Name", "is-default")
            .formParam("Filter.1.Value.1", "true")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("DescribeVpcsResponse.vpcSet.item[0].vpcId");

        subnetId = given()
            .formParam("Action", "DescribeSubnets")
            .formParam("Filter.1.Name", "default-for-az")
            .formParam("Filter.1.Value.1", "true")
            .formParam("Filter.2.Name", "vpc-id")
            .formParam("Filter.2.Value.1", vpcId)
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
            .formParam("Filter.2.Name", "vpc-id")
            .formParam("Filter.2.Value.1", vpcId)
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

    // ─── IPv6 over the wire ────────────────────────────────────────────────────

    /**
     * Builds a dual-stack subnet with {@code AssignIpv6AddressOnCreation} set, in a VPC of its own
     * so the default subnets stay untouched. Returns the new subnet id.
     */
    private String createIpv6SubnetWithAutoAssign(String vpcCidr, String subnetCidr) {
        String vpcId = given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", vpcCidr)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");

        String ipv6Cidr = given()
            .formParam("Action", "AssociateVpcCidrBlock")
            .formParam("VpcId", vpcId)
            .formParam("AmazonProvidedIpv6CidrBlock", "true")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("AssociateVpcCidrBlockResponse.ipv6CidrBlockAssociation.ipv6CidrBlock");

        String ipv6SubnetId = given()
            .formParam("Action", "CreateSubnet")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", subnetCidr)
            .formParam("Ipv6CidrBlock", ipv6Cidr)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateSubnetResponse.subnet.subnetId");

        given()
            .formParam("Action", "ModifySubnetAttribute")
            .formParam("SubnetId", ipv6SubnetId)
            .formParam("AssignIpv6AddressOnCreation.Value", "true")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        return ipv6SubnetId;
    }

    /**
     * Omitting {@code Ipv6AddressCount} leaves the subnet's {@code AssignIpv6AddressOnCreation}
     * in charge, so the interface comes up with one address.
     */
    @Test
    @Order(18)
    void createNetworkInterfaceAutoAssignsIpv6WhenTheCountIsOmitted() {
        String ipv6SubnetId = createIpv6SubnetWithAutoAssign("10.90.0.0/16", "10.90.1.0/24");

        given()
            .formParam("Action", "CreateNetworkInterface")
            .formParam("SubnetId", ipv6SubnetId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateNetworkInterfaceResponse.networkInterface.ipv6AddressesSet.item.size()",
                    equalTo(1));
    }

    /**
     * An explicit {@code Ipv6AddressCount=0} overrides the subnet's
     * {@code AssignIpv6AddressOnCreation} and assigns none, which the API reference calls out
     * directly. Parsing the absent parameter as 0 collapsed the two cases and auto-assigned anyway.
     */
    @Test
    @Order(19)
    void createNetworkInterfaceWithAnExplicitZeroCountOverridesTheSubnetAutoAssign() {
        String ipv6SubnetId = createIpv6SubnetWithAutoAssign("10.91.0.0/16", "10.91.1.0/24");

        given()
            .formParam("Action", "CreateNetworkInterface")
            .formParam("SubnetId", ipv6SubnetId)
            .formParam("Ipv6AddressCount", "0")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateNetworkInterfaceResponse.networkInterface.ipv6AddressesSet.item.size()",
                    equalTo(0));
    }

    /**
     * {@code AssignIpv6Addresses} requires either {@code Ipv6Addresses.N} or
     * {@code Ipv6AddressCount}; with neither it used to report success having assigned nothing.
     */
    @Test
    @Order(20)
    void assignIpv6AddressesRequiresAddressesOrACount() {
        String ipv6SubnetId = createIpv6SubnetWithAutoAssign("10.92.0.0/16", "10.92.1.0/24");

        String eni = given()
            .formParam("Action", "CreateNetworkInterface")
            .formParam("SubnetId", ipv6SubnetId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateNetworkInterfaceResponse.networkInterface.networkInterfaceId");

        given()
            .formParam("Action", "AssignIpv6Addresses")
            .formParam("NetworkInterfaceId", eni)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("MissingParameter"));
    }

    /**
     * {@code assignedIpv6Addresses} carries only the addresses the request actually added: "Existing
     * IPv6 addresses that were assigned to the network interface before the request are not
     * included." Re-requesting an address the interface already holds therefore reports nothing,
     * and does not duplicate it on the interface. Also pins the wire name to {@code Ipv6Addresses.N},
     * which is what the SDKs and the CLI send.
     */
    @Test
    @Order(21)
    void assignIpv6AddressesReportsOnlyTheAddressesItActuallyAdded() {
        String ipv6SubnetId = createIpv6SubnetWithAutoAssign("10.93.0.0/16", "10.93.1.0/24");

        String eni = given()
            .formParam("Action", "CreateNetworkInterface")
            .formParam("SubnetId", ipv6SubnetId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateNetworkInterfaceResponse.networkInterface.networkInterfaceId");

        String existing = given()
            .formParam("Action", "DescribeNetworkInterfaces")
            .formParam("NetworkInterfaceId.1", eni)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("DescribeNetworkInterfacesResponse.networkInterfaceSet.item"
                    + ".ipv6AddressesSet.item.ipv6Address");

        // A fresh address is reported as assigned, under the wire name the SDKs use.
        String fresh = existing.replaceFirst("[^:]+$", "ff01");
        given()
            .formParam("Action", "AssignIpv6Addresses")
            .formParam("NetworkInterfaceId", eni)
            .formParam("Ipv6Addresses.1", fresh)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AssignIpv6AddressesResponse.assignedIpv6Addresses.item", equalTo(fresh));

        // Re-requesting it adds nothing, so nothing is reported as newly assigned.
        given()
            .formParam("Action", "AssignIpv6Addresses")
            .formParam("NetworkInterfaceId", eni)
            .formParam("Ipv6Addresses.1", fresh)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AssignIpv6AddressesResponse.assignedIpv6Addresses.item.size()", equalTo(0));

        // ... and the interface still holds it exactly once, alongside the original.
        given()
            .formParam("Action", "DescribeNetworkInterfaces")
            .formParam("NetworkInterfaceId.1", eni)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item"
                    + ".ipv6AddressesSet.item.ipv6Address", containsInAnyOrder(existing, fresh));
    }

    /**
     * {@code UnassignIpv6Addresses} reads the same {@code Ipv6Addresses.N} wire name, and reports
     * only the addresses that were really there to remove.
     */
    @Test
    @Order(22)
    void unassignIpv6AddressesReadsTheSdkWireNameAndReportsOnlyRemovedAddresses() {
        String ipv6SubnetId = createIpv6SubnetWithAutoAssign("10.94.0.0/16", "10.94.1.0/24");

        String eni = given()
            .formParam("Action", "CreateNetworkInterface")
            .formParam("SubnetId", ipv6SubnetId)
            .formParam("Ipv6AddressCount", "2")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateNetworkInterfaceResponse.networkInterface.networkInterfaceId");

        String assigned = given()
            .formParam("Action", "DescribeNetworkInterfaces")
            .formParam("NetworkInterfaceId.1", eni)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("DescribeNetworkInterfacesResponse.networkInterfaceSet.item"
                    + ".ipv6AddressesSet.item[0].ipv6Address");

        // One address is really on the interface, the other never was.
        given()
            .formParam("Action", "UnassignIpv6Addresses")
            .formParam("NetworkInterfaceId", eni)
            .formParam("Ipv6Addresses.1", assigned)
            .formParam("Ipv6Addresses.2", assigned.replaceFirst("[^:]+$", "ff02"))
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("UnassignIpv6AddressesResponse.unassignedIpv6Addresses.item", equalTo(assigned));

        given()
            .formParam("Action", "DescribeNetworkInterfaces")
            .formParam("NetworkInterfaceId.1", eni)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item"
                    + ".ipv6AddressesSet.item.ipv6Address", not(hasItem(assigned)));
    }
}
