package io.github.hectorvent.floci.services.ec2;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.Matchers.containsString;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.quarkus.test.junit.QuarkusTest;
import static io.restassured.RestAssured.given;

import static org.hamcrest.Matchers.matchesRegex;

/**
 * Integration tests for EC2 via the EC2 Query Protocol (form-encoded POST, XML response).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2IntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private static String instanceId;
    private static String vpcId;
    private static String subnetId;
    private static String securityGroupId;
    private static String keyPairId;
    private static String igwId;
    private static String routeTableId;
    private static String rtbAssocId;
    private static String allocationId;
    private static String associationId;
    private static String volumeId;
    private static String rootVolumeId;
    private static String networkInterfaceId;
    private static String launchTemplateId;
    private static String vpcEndpointId;
    private static String natGatewayId;

    // =========================================================================
    // Default resources
    // =========================================================================

    @Test
    @Order(1)
    void describeDefaultVpc() {
        given()
            .formParam("Action", "DescribeVpcs")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("DescribeVpcsResponse.vpcSet.item[0].vpcId", equalTo("vpc-default"))
            .body("DescribeVpcsResponse.vpcSet.item[0].cidrBlock", equalTo("172.31.0.0/16"))
            .body("DescribeVpcsResponse.vpcSet.item[0].isDefault", equalTo("true"));
    }

    @Test
    @Order(2)
    void describeDefaultSubnets() {
        given()
            .formParam("Action", "DescribeSubnets")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("DescribeSubnetsResponse.subnetSet.item.size()", greaterThanOrEqualTo(3))
            .body("DescribeSubnetsResponse.subnetSet.item[0].defaultForAz", equalTo("true"))
            .body("DescribeSubnetsResponse.subnetSet.item[0].mapPublicIpOnLaunch", equalTo("true"));
    }

    @Test
    @Order(3)
    void describeDefaultSecurityGroup() {
        // Filter to the default VPC's default group rather than assuming it is item[0] of an
        // unfiltered list: DescribeSecurityGroups returns groups in the store's iteration order,
        // so any other group in this region (e.g. one left behind by another test class sharing
        // the in-memory EC2 store) could otherwise land at item[0] and flake this assertion.
        given()
            .formParam("Action", "DescribeSecurityGroups")
            .formParam("Filter.1.Name", "group-name")
            .formParam("Filter.1.Value.1", "default")
            .formParam("Filter.2.Name", "vpc-id")
            .formParam("Filter.2.Value.1", "vpc-default")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("DescribeSecurityGroupsResponse.securityGroupInfo.item[0].groupName", equalTo("default"))
            .body("DescribeSecurityGroupsResponse.securityGroupInfo.item[0].groupDescription",
                equalTo("default VPC security group"))
            .body("DescribeSecurityGroupsResponse.securityGroupInfo.item[0].vpcId", equalTo("vpc-default"));
    }

    // =========================================================================
    // Availability Zones & Regions
    // =========================================================================

    @Test
    @Order(4)
    void describeAvailabilityZones() {
        given()
            .formParam("Action", "DescribeAvailabilityZones")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("DescribeAvailabilityZonesResponse.availabilityZoneInfo.item.size()", equalTo(3))
            .body("DescribeAvailabilityZonesResponse.availabilityZoneInfo.item[0].zoneName",
                    startsWith("us-east-1"));
    }

    @Test
    @Order(5)
    void describeRegions() {
        given()
            .formParam("Action", "DescribeRegions")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("DescribeRegionsResponse.regionInfo.item.size()", greaterThan(0));
    }

    @Test
    @Order(6)
    void describeAccountAttributes() {
        given()
            .formParam("Action", "DescribeAccountAttributes")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("DescribeAccountAttributesResponse.accountAttributeSet.item[0].attributeName",
                    notNullValue());
    }

    // =========================================================================
    // AMIs
    // =========================================================================

    @Test
    @Order(7)
    void describeImages() {
        given()
            .formParam("Action", "DescribeImages")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("DescribeImagesResponse.imagesSet.item.size()", greaterThan(0))
            .body("DescribeImagesResponse.imagesSet.item[0].imageId", startsWith("ami-"));
    }

    @Test
    @Order(8)
    void describeImagesWithCatalogFilters() {
        given()
            .formParam("Action", "DescribeImages")
            .formParam("Owner.1", "099720109477")
            .formParam("Filter.1.Name", "name")
            .formParam("Filter.1.Value.1", "ubuntu/images/hvm-ssd/ubuntu-focal-20.04-amd64-server-*")
            .formParam("Filter.2.Name", "architecture")
            .formParam("Filter.2.Value.1", "x86_64")
            .formParam("Filter.3.Name", "state")
            .formParam("Filter.3.Value.1", "available")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("DescribeImagesResponse.imagesSet.item.size()", equalTo(1))
            .body("DescribeImagesResponse.imagesSet.item.imageId", equalTo("ami-0abcdef1234567892"))
            .body("DescribeImagesResponse.imagesSet.item.architecture", equalTo("x86_64"));
    }

    @Test
    @Order(9)
    void describeImagesWithUbuntu2404Arm64Filters() {
        given()
            .formParam("Action", "DescribeImages")
            .formParam("Owner.1", "099720109477")
            .formParam("Filter.1.Name", "name")
            .formParam("Filter.1.Value.1", "ubuntu/images/hvm-ssd-gp3/ubuntu-noble-24.04-arm64-server-*")
            .formParam("Filter.2.Name", "architecture")
            .formParam("Filter.2.Value.1", "arm64")
            .formParam("Filter.3.Name", "state")
            .formParam("Filter.3.Value.1", "available")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("DescribeImagesResponse.imagesSet.item.size()", equalTo(1))
            .body("DescribeImagesResponse.imagesSet.item.imageId", equalTo("ami-ubuntu2404-arm64"))
            .body("DescribeImagesResponse.imagesSet.item.architecture", equalTo("arm64"));
    }

    @Test
    @Order(17)
    void describeInstanceTypes() {
        given()
            .formParam("Action", "DescribeInstanceTypes")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("DescribeInstanceTypesResponse.instanceTypeSet.item.size()", greaterThan(0));
    }

    @Test
    @Order(18)
    void describeInstanceTypeOfferings() {
        given()
            .formParam("Action", "DescribeInstanceTypeOfferings")
            .formParam("LocationType", "availability-zone")
            .formParam("InstanceType.1", "m5.large")
            .formParam("Filter.1.Name", "instance-type")
            .formParam("Filter.1.Value.1", "m5.large")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("DescribeInstanceTypeOfferingsResponse.instanceTypeOfferingSet.item.size()", equalTo(3))
            .body("DescribeInstanceTypeOfferingsResponse.instanceTypeOfferingSet.item[0].instanceType",
                    equalTo("m5.large"))
            .body("DescribeInstanceTypeOfferingsResponse.instanceTypeOfferingSet.item[0].location",
                    startsWith("us-east-1"));
    }

    @Test
    @Order(19)
    void describeArmInstanceTypeOfferingByRegion() {
        given()
            .formParam("Action", "DescribeInstanceTypeOfferings")
            .formParam("LocationType", "region")
            .formParam("Filter.1.Name", "instance-type")
            .formParam("Filter.1.Value.1", "t4g.medium")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("DescribeInstanceTypeOfferingsResponse.instanceTypeOfferingSet.item[0].instanceType",
                    equalTo("t4g.medium"))
            .body("DescribeInstanceTypeOfferingsResponse.instanceTypeOfferingSet.item[0].locationType",
                    equalTo("region"))
            .body("DescribeInstanceTypeOfferingsResponse.instanceTypeOfferingSet.item[0].location",
                    equalTo("us-east-1"));
    }

    @Test
    @Order(20)
    void describeModernGravitonInstanceTypeOfferingsByRegion() {
        given()
            .formParam("Action", "DescribeInstanceTypeOfferings")
            .formParam("LocationType", "region")
            .formParam("Filter.1.Name", "instance-type")
            .formParam("Filter.1.Value.1", "m8gd.2xlarge")
            .formParam("Filter.1.Value.2", "m7gd.2xlarge")
            .formParam("Filter.1.Value.3", "m6gd.2xlarge")
            .formParam("Filter.1.Value.4", "m8gd.medium")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("DescribeInstanceTypeOfferingsResponse.instanceTypeOfferingSet.item.size()", equalTo(4))
            .body("DescribeInstanceTypeOfferingsResponse.instanceTypeOfferingSet.item.instanceType",
                    containsInAnyOrder("m8gd.2xlarge", "m7gd.2xlarge", "m6gd.2xlarge", "m8gd.medium"))
            .body("DescribeInstanceTypeOfferingsResponse.instanceTypeOfferingSet.item.locationType",
                    everyItem(equalTo("region")))
            .body("DescribeInstanceTypeOfferingsResponse.instanceTypeOfferingSet.item.location",
                    everyItem(equalTo("us-east-1")));
    }

    // =========================================================================
    // VPCs
    // =========================================================================

    @Test
    @Order(10)
    void createVpc() {
        vpcId = given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.0.0.0/16")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("CreateVpcResponse.vpc.cidrBlock", equalTo("10.0.0.0/16"))
            .body("CreateVpcResponse.vpc.state", equalTo("available"))
            .extract().path("CreateVpcResponse.vpc.vpcId");
    }

    @Test
    @Order(11)
    void describeVpcById() {
        given()
            .formParam("Action", "DescribeVpcs")
            .formParam("VpcId.1", vpcId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVpcsResponse.vpcSet.item.vpcId", equalTo(vpcId));
    }

    @Test
    @Order(12)
    void modifyVpcAttribute() {
        given()
            .formParam("Action", "ModifyVpcAttribute")
            .formParam("VpcId", vpcId)
            .formParam("EnableDnsSupport.Value", "false")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(13)
    void describeVpcAttribute() {
        given()
            .formParam("Action", "DescribeVpcAttribute")
            .formParam("VpcId", vpcId)
            .formParam("Attribute", "enableDnsSupport")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVpcAttributeResponse.vpcId", equalTo(vpcId))
            .body("DescribeVpcAttributeResponse.enableDnsSupport.value", equalTo("false"));
    }

    @Test
    @Order(14)
    void describeCreatedVpcDefaultSecurityGroup() {
        given()
            .formParam("Action", "DescribeSecurityGroups")
            .formParam("Filter.1.Name", "vpc-id")
            .formParam("Filter.1.Value.1", vpcId)
            .formParam("Filter.2.Name", "group-name")
            .formParam("Filter.2.Value.1", "default")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeSecurityGroupsResponse.securityGroupInfo.item.groupName", equalTo("default"))
            .body("DescribeSecurityGroupsResponse.securityGroupInfo.item.vpcId", equalTo(vpcId));
    }

    @Test
    @Order(15)
    void describeCreatedVpcMainRouteTable() {
        given()
            .formParam("Action", "DescribeRouteTables")
            .formParam("Filter.1.Name", "vpc-id")
            .formParam("Filter.1.Value.1", vpcId)
            .formParam("Filter.2.Name", "association.main")
            .formParam("Filter.2.Value.1", "true")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeRouteTablesResponse.routeTableSet.item.vpcId", equalTo(vpcId))
            .body(containsString("<main>true</main>"));
    }

    @Test
    @Order(16)
    void describeVpcEndpointServices() {
        given()
            .formParam("Action", "DescribeVpcEndpointServices")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(15)
    void describeNatGatewaysInitiallyEmpty() {
        given()
            .formParam("Action", "DescribeNatGateways")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeNatGatewaysResponse.natGatewaySet.item.size()", equalTo(0));
    }

    // =========================================================================
    // Subnets
    // =========================================================================

    @Test
    @Order(20)
    void createSubnet() {
        subnetId = given()
            .formParam("Action", "CreateSubnet")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", "10.0.1.0/24")
            .formParam("AvailabilityZone", "us-east-1a")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateSubnetResponse.subnet.vpcId", equalTo(vpcId))
            .body("CreateSubnetResponse.subnet.cidrBlock", equalTo("10.0.1.0/24"))
            .extract().path("CreateSubnetResponse.subnet.subnetId");
    }

    @Test
    @Order(21)
    void describeSubnetById() {
        given()
            .formParam("Action", "DescribeSubnets")
            .formParam("SubnetId.1", subnetId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeSubnetsResponse.subnetSet.item.subnetId", equalTo(subnetId));
    }

    @Test
    @Order(21)
    void createSubnetHasAssignIpv6AddressOnCreation() {
        given()
            .formParam("Action", "DescribeSubnets")
            .formParam("SubnetId.1", subnetId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeSubnetsResponse.subnetSet.item.assignIpv6AddressOnCreation", equalTo("false"))
            .body("DescribeSubnetsResponse.subnetSet.item.enableDns64", equalTo("false"))
            .body("DescribeSubnetsResponse.subnetSet.item.mapCustomerOwnedIpOnLaunch", equalTo("false"));
    }

    @Test
    @Order(22)
    void modifySubnetAttribute() {
        given()
            .formParam("Action", "ModifySubnetAttribute")
            .formParam("SubnetId", subnetId)
            .formParam("MapPublicIpOnLaunch.Value", "true")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    // =========================================================================
    // Security Groups
    // =========================================================================

    @Test
    @Order(30)
    void createSecurityGroup() {
        securityGroupId = given()
            .formParam("Action", "CreateSecurityGroup")
            .formParam("GroupName", "test-sg")
            .formParam("GroupDescription", "Test SG")
            .formParam("VpcId", vpcId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateSecurityGroupResponse.groupId", startsWith("sg-"))
            .extract().path("CreateSecurityGroupResponse.groupId");
    }

    @Test
    @Order(31)
    void authorizeSecurityGroupIngress() {
        given()
            .formParam("Action", "AuthorizeSecurityGroupIngress")
            .formParam("GroupId", securityGroupId)
            .formParam("IpPermissions.1.IpProtocol", "tcp")
            .formParam("IpPermissions.1.FromPort", "22")
            .formParam("IpPermissions.1.ToPort", "22")
            .formParam("IpPermissions.1.IpRanges.1.CidrIp", "0.0.0.0/0")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(32)
    void describeSecurityGroupById() {
        given()
            .formParam("Action", "DescribeSecurityGroups")
            .formParam("GroupId.1", securityGroupId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeSecurityGroupsResponse.securityGroupInfo.item.groupId", equalTo(securityGroupId))
            .body("DescribeSecurityGroupsResponse.securityGroupInfo.item.ipPermissions.item[0].fromPort",
                    equalTo("22"));
    }

    @Test
    @Order(33)
    void authorizeSecurityGroupEgress() {
        given()
            .formParam("Action", "AuthorizeSecurityGroupEgress")
            .formParam("GroupId", securityGroupId)
            .formParam("IpPermissions.1.IpProtocol", "tcp")
            .formParam("IpPermissions.1.FromPort", "443")
            .formParam("IpPermissions.1.ToPort", "443")
            .formParam("IpPermissions.1.IpRanges.1.CidrIp", "0.0.0.0/0")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    // =========================================================================
    // Key Pairs
    // =========================================================================

    @Test
    @Order(40)
    void createKeyPair() {
        keyPairId = given()
            .formParam("Action", "CreateKeyPair")
            .formParam("KeyName", "test-key")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateKeyPairResponse.keyName", equalTo("test-key"))
            .body("CreateKeyPairResponse.keyMaterial", notNullValue())
            .extract().path("CreateKeyPairResponse.keyPairId");
    }

    @Test
    @Order(41)
    void describeKeyPairs() {
        given()
            .formParam("Action", "DescribeKeyPairs")
            .formParam("KeyName.1", "test-key")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeKeyPairsResponse.keySet.item.keyName", equalTo("test-key"));
    }

    @Test
    @Order(39)
    void importKeyPair() {
        given()
            .formParam("Action", "ImportKeyPair")
            .formParam("KeyName", "imported-key")
            .formParam("PublicKeyMaterial", "c3NoLXJzYSBBQUFBQjNOemFDMXljMkVBQUFBREFRQUJBQUFCQVFD")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ImportKeyPairResponse.keyName", equalTo("imported-key"))
            .body("ImportKeyPairResponse.keyPairId", startsWith("key-"));
    }

    @Test
    @Order(42)
    void createLaunchTemplateRejectsMalformedUserData() {
        given()
            .formParam("Action", "CreateLaunchTemplate")
            .formParam("LaunchTemplateName", "bad-user-data-template")
            .formParam("LaunchTemplateData.ImageId", "ami-0abcdef1234567890")
            .formParam("LaunchTemplateData.InstanceType", "t3.micro")
            .formParam("LaunchTemplateData.UserData", "not-valid-base64")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));
    }

    @Test
    @Order(43)
    void createLaunchTemplate()
            throws IOException {
        launchTemplateId = given()
            .formParam("Action", "CreateLaunchTemplate")
            .formParam("LaunchTemplateName", "sample-template")
            .formParam("LaunchTemplateData.ImageId", "ami-0abcdef1234567890")
            .formParam("LaunchTemplateData.InstanceType", "t3.micro")
            .formParam("LaunchTemplateData.KeyName", "test-key")
            .formParam("LaunchTemplateData.SecurityGroupId.1", securityGroupId)
            .formParam("LaunchTemplateData.UserData", gzipBase64("#!/bin/sh\necho launch-template\n"))
            .formParam("LaunchTemplateData.TagSpecification.1.ResourceType", "instance")
            .formParam("LaunchTemplateData.TagSpecification.1.Tag.1.Key", "example:ClusterId")
            .formParam("LaunchTemplateData.TagSpecification.1.Tag.1.Value", "sample-template")
            .formParam("LaunchTemplateData.TagSpecification.1.Tag.2.Key", "example:NodeType")
            .formParam("LaunchTemplateData.TagSpecification.1.Tag.2.Value", "COORDINATOR")
            .formParam("TagSpecification.1.ResourceType", "launch-template")
            .formParam("TagSpecification.1.Tag.1.Key", "Name")
            .formParam("TagSpecification.1.Tag.1.Value", "sample-template")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateLaunchTemplateResponse.launchTemplate.launchTemplateId", startsWith("lt-"))
            .body("CreateLaunchTemplateResponse.launchTemplate.launchTemplateName",
                    equalTo("sample-template"))
            .extract().path("CreateLaunchTemplateResponse.launchTemplate.launchTemplateId");
    }

    @Test
    @Order(44)
    void describeLaunchTemplateById() {
        given()
            .formParam("Action", "DescribeLaunchTemplates")
            .formParam("LaunchTemplateId.1", launchTemplateId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeLaunchTemplatesResponse.launchTemplates.item.launchTemplateId",
                    equalTo(launchTemplateId))
            .body("DescribeLaunchTemplatesResponse.launchTemplates.item.launchTemplateName",
                    equalTo("sample-template"));
    }

    @Test
    @Order(45)
    void describeLaunchTemplateVersionsById() {
        given()
            .formParam("Action", "DescribeLaunchTemplateVersions")
            .formParam("LaunchTemplateId", launchTemplateId)
            .formParam("Versions.1", "$Latest")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateId",
                    equalTo(launchTemplateId))
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.imageId",
                    equalTo("ami-0abcdef1234567890"))
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.instanceType",
                    equalTo("t3.micro"))
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.userData",
                    equalTo("#!/bin/sh\necho launch-template\n"))
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.tagSpecificationSet.item.resourceType",
                    equalTo("instance"))
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.tagSpecificationSet.item.tagSet.item.find { it.key == 'example:ClusterId' }.value",
                    equalTo("sample-template"))
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.tagSpecificationSet.item.tagSet.item.find { it.key == 'example:NodeType' }.value",
                    equalTo("COORDINATOR"));
    }

    @Test
    @Order(46)
    void createLaunchTemplateVersion()
            throws IOException {
        given()
            .formParam("Action", "CreateLaunchTemplateVersion")
            .formParam("LaunchTemplateId", launchTemplateId)
            .formParam("SourceVersion", "$Latest")
            .formParam("VersionDescription", "updated by test")
            .formParam("LaunchTemplateData.ImageId", "ami-0abcdef1234567890")
            .formParam("LaunchTemplateData.InstanceType", "t3.small")
            .formParam("LaunchTemplateData.KeyName", "test-key")
            .formParam("LaunchTemplateData.SecurityGroupId.1", securityGroupId)
            .formParam("LaunchTemplateData.UserData", gzipBase64("#!/bin/sh\necho launch-template-version\n"))
            .formParam("LaunchTemplateData.TagSpecification.1.ResourceType", "instance")
            .formParam("LaunchTemplateData.TagSpecification.1.Tag.1.Key", "example:NodeType")
            .formParam("LaunchTemplateData.TagSpecification.1.Tag.1.Value", "WORKER")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateLaunchTemplateVersionResponse.launchTemplateVersion.launchTemplateId",
                    equalTo(launchTemplateId))
            .body("CreateLaunchTemplateVersionResponse.launchTemplateVersion.versionNumber",
                    equalTo("2"))
            .body("CreateLaunchTemplateVersionResponse.launchTemplateVersion.defaultVersion",
                    equalTo("false"))
            .body("CreateLaunchTemplateVersionResponse.launchTemplateVersion.launchTemplateData.instanceType",
                    equalTo("t3.small"))
            .body("CreateLaunchTemplateVersionResponse.launchTemplateVersion.launchTemplateData.userData",
                    equalTo("#!/bin/sh\necho launch-template-version\n"))
            .body("CreateLaunchTemplateVersionResponse.launchTemplateVersion.launchTemplateData.tagSpecificationSet.item.tagSet.item.find { it.key == 'example:NodeType' }.value",
                    equalTo("WORKER"));

        given()
            .formParam("Action", "DescribeLaunchTemplateVersions")
            .formParam("LaunchTemplateId", launchTemplateId)
            .formParam("Versions.1", "1")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.versionNumber",
                    equalTo("1"))
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.defaultVersion",
                    equalTo("true"))
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.instanceType",
                    equalTo("t3.micro"))
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.userData",
                    equalTo("#!/bin/sh\necho launch-template\n"))
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.tagSpecificationSet.item.tagSet.item.find { it.key == 'example:NodeType' }.value",
                    equalTo("COORDINATOR"));
    }

    @Test
    @Order(47)
    void modifyLaunchTemplateDefaultVersion() {
        given()
            .formParam("Action", "ModifyLaunchTemplate")
            .formParam("LaunchTemplateId", launchTemplateId)
            .formParam("DefaultVersion", "2")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ModifyLaunchTemplateResponse.launchTemplate.launchTemplateId",
                    equalTo(launchTemplateId))
            .body("ModifyLaunchTemplateResponse.launchTemplate.defaultVersionNumber",
                    equalTo("2"))
            .body("ModifyLaunchTemplateResponse.launchTemplate.latestVersionNumber",
                    equalTo("2"));
    }

    @Test
    @Order(48)
    void describeUpdatedLaunchTemplateVersionById() {
        given()
            .formParam("Action", "DescribeLaunchTemplateVersions")
            .formParam("LaunchTemplateId", launchTemplateId)
            .formParam("Versions.1", "$Latest")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.versionNumber",
                    equalTo("2"))
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.defaultVersion",
                    equalTo("true"))
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.instanceType",
                    equalTo("t3.small"));
    }

    @Test
    @Order(49)
    void deleteLaunchTemplate() {
        given()
            .formParam("Action", "DeleteLaunchTemplate")
            .formParam("LaunchTemplateId", launchTemplateId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeleteLaunchTemplateResponse.launchTemplate.launchTemplateId",
                    equalTo(launchTemplateId));
    }

    private static String gzipBase64(String value)
            throws IOException
    {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
            gzip.write(value.getBytes(StandardCharsets.UTF_8));
        }
        return Base64.getEncoder().encodeToString(buffer.toByteArray());
    }

    // =========================================================================
    // Internet Gateways
    // =========================================================================

    @Test
    @Order(50)
    void createInternetGateway() {
        igwId = given()
            .formParam("Action", "CreateInternetGateway")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateInternetGatewayResponse.internetGateway.internetGatewayId", startsWith("igw-"))
            .extract().path("CreateInternetGatewayResponse.internetGateway.internetGatewayId");
    }

    @Test
    @Order(51)
    void attachInternetGateway() {
        given()
            .formParam("Action", "AttachInternetGateway")
            .formParam("InternetGatewayId", igwId)
            .formParam("VpcId", vpcId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(52)
    void describeInternetGateways() {
        given()
            .formParam("Action", "DescribeInternetGateways")
            .formParam("InternetGatewayId.1", igwId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeInternetGatewaysResponse.internetGatewaySet.item.internetGatewayId",
                    equalTo(igwId))
            .body("DescribeInternetGatewaysResponse.internetGatewaySet.item.attachmentSet.item.vpcId",
                    equalTo(vpcId));
    }

    // =========================================================================
    // Route Tables
    // =========================================================================

    @Test
    @Order(60)
    void createRouteTable() {
        routeTableId = given()
            .formParam("Action", "CreateRouteTable")
            .formParam("VpcId", vpcId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateRouteTableResponse.routeTable.vpcId", equalTo(vpcId))
            .extract().path("CreateRouteTableResponse.routeTable.routeTableId");
    }

    @Test
    @Order(61)
    void createRoute() {
        given()
            .formParam("Action", "CreateRoute")
            .formParam("RouteTableId", routeTableId)
            .formParam("DestinationCidrBlock", "0.0.0.0/0")
            .formParam("GatewayId", igwId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(62)
    void associateRouteTable() {
        rtbAssocId = given()
            .formParam("Action", "AssociateRouteTable")
            .formParam("RouteTableId", routeTableId)
            .formParam("SubnetId", subnetId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AssociateRouteTableResponse.associationId", startsWith("rtbassoc-"))
            .body("AssociateRouteTableResponse.associationState.state", equalTo("associated"))
            .extract().path("AssociateRouteTableResponse.associationId");
    }

    @Test
    @Order(63)
    void describeRouteTables() {
        given()
            .formParam("Action", "DescribeRouteTables")
            .formParam("RouteTableId.1", routeTableId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeRouteTablesResponse.routeTableSet.item.routeTableId", equalTo(routeTableId));
    }

    @Test
    @Order(64)
    void describeRouteTablesByAssociationId() {
        given()
            .formParam("Action", "DescribeRouteTables")
            .formParam("Filter.1.Name", "association.route-table-association-id")
            .formParam("Filter.1.Value.1", rtbAssocId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeRouteTablesResponse.routeTableSet.item.routeTableId", equalTo(routeTableId))
            .body("DescribeRouteTablesResponse.routeTableSet.item.associationSet.item[0].routeTableAssociationId",
                    equalTo(rtbAssocId));
    }

    @Test
    @Order(65)
    void describeRouteTablesBySubnetId() {
        given()
            .formParam("Action", "DescribeRouteTables")
            .formParam("Filter.1.Name", "association.subnet-id")
            .formParam("Filter.1.Value.1", subnetId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeRouteTablesResponse.routeTableSet.item.routeTableId", equalTo(routeTableId));
    }

    @Test
    @Order(66)
    void createVpcEndpoint() {
        vpcEndpointId = given()
            .formParam("Action", "CreateVpcEndpoint")
            .formParam("VpcId", vpcId)
            .formParam("ServiceName", "com.amazonaws.us-east-1.s3")
            .formParam("VpcEndpointType", "Gateway")
            .formParam("RouteTableId.1", routeTableId)
            .formParam("TagSpecification.1.ResourceType", "vpc-endpoint")
            .formParam("TagSpecification.1.Tag.1.Key", "Name")
            .formParam("TagSpecification.1.Tag.1.Value", "sample-s3")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateVpcEndpointResponse.vpcEndpoint.vpcEndpointId", startsWith("vpce-"))
            .body("CreateVpcEndpointResponse.vpcEndpoint.vpcId", equalTo(vpcId))
            .body("CreateVpcEndpointResponse.vpcEndpoint.routeTableIdSet.item.routeTableId",
                    equalTo(routeTableId))
            .extract().path("CreateVpcEndpointResponse.vpcEndpoint.vpcEndpointId");
    }

    @Test
    @Order(67)
    void describeVpcEndpointById() {
        given()
            .formParam("Action", "DescribeVpcEndpoints")
            .formParam("VpcEndpointId.1", vpcEndpointId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVpcEndpointsResponse.vpcEndpointSet.item.vpcEndpointId",
                    equalTo(vpcEndpointId))
            .body("DescribeVpcEndpointsResponse.vpcEndpointSet.item.serviceName",
                    equalTo("com.amazonaws.us-east-1.s3"));
    }

    @Test
    @Order(68)
    void deleteVpcEndpoint() {
        given()
            .formParam("Action", "DeleteVpcEndpoints")
            .formParam("VpcEndpointId.1", vpcEndpointId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    // =========================================================================
    // Elastic IPs
    // =========================================================================

    @Test
    @Order(70)
    void allocateAddress() {
        allocationId = given()
            .formParam("Action", "AllocateAddress")
            .formParam("Domain", "vpc")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AllocateAddressResponse.allocationId", startsWith("eipalloc-"))
            .body("AllocateAddressResponse.publicIp", notNullValue())
            .extract().path("AllocateAddressResponse.allocationId");
    }

    @Test
    @Order(71)
    void describeAddresses() {
        given()
            .formParam("Action", "DescribeAddresses")
            .formParam("AllocationId.1", allocationId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeAddressesResponse.addressesSet.item.allocationId", equalTo(allocationId));
    }

    @Test
    @Order(72)
    void createDescribeAndDeleteNatGateway() {
        natGatewayId = given()
            .formParam("Action", "CreateNatGateway")
            .formParam("SubnetId", subnetId)
            .formParam("AllocationId", allocationId)
            .formParam("TagSpecification.1.ResourceType", "natgateway")
            .formParam("TagSpecification.1.Tag.1.Key", "Name")
            .formParam("TagSpecification.1.Tag.1.Value", "sample-nat")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateNatGatewayResponse.natGateway.natGatewayId", startsWith("nat-"))
            .body("CreateNatGatewayResponse.natGateway.subnetId", equalTo(subnetId))
            .body("CreateNatGatewayResponse.natGateway.natGatewayAddressSet.item.allocationId",
                    equalTo(allocationId))
            .extract().path("CreateNatGatewayResponse.natGateway.natGatewayId");

        given()
            .formParam("Action", "DescribeNatGateways")
            .formParam("NatGatewayId.1", natGatewayId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeNatGatewaysResponse.natGatewaySet.item.natGatewayId",
                    equalTo(natGatewayId))
            .body("DescribeNatGatewaysResponse.natGatewaySet.item.state", equalTo("available"));

        given()
            .formParam("Action", "DeleteNatGateway")
            .formParam("NatGatewayId", natGatewayId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeleteNatGatewayResponse.natGateway.natGatewayId", equalTo(natGatewayId))
            .body("DeleteNatGatewayResponse.natGateway.state", equalTo("deleted"));
    }

    // =========================================================================
    // Instances
    // =========================================================================

    @Test
    @Order(80)
    void runInstances() {
        instanceId = given()
            .formParam("Action", "RunInstances")
            .formParam("ImageId", "ami-0abcdef1234567890")
            .formParam("InstanceType", "t2.micro")
            .formParam("MinCount", "1")
            .formParam("MaxCount", "1")
            .formParam("KeyName", "test-key")
            .formParam("SubnetId", subnetId)
            .formParam("SecurityGroupId.1", securityGroupId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RunInstancesResponse.instancesSet.item.instanceId", startsWith("i-"))
            .body("RunInstancesResponse.instancesSet.item.instanceState.name", equalTo("pending"))
            .body("RunInstancesResponse.instancesSet.item.instanceType", equalTo("t2.micro"))
            .body("RunInstancesResponse.instancesSet.item.keyName", equalTo("test-key"))
            .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
    }

    @Test
    @Order(81)
    void describeInstances() {
        given()
            .formParam("Action", "DescribeInstances")
            .formParam("InstanceId.1", instanceId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.instanceId",
                    equalTo(instanceId))
            .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.instanceState.name",
                    equalTo("running"));
    }

    @Test
    @Order(82)
    void describeInstancesHasNetworkInterfaceAttachTime() {
        given()
            .formParam("Action", "DescribeInstances")
            .formParam("InstanceId.1", instanceId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item"
                    + ".networkInterfaceSet.item.attachment.attachmentId",
                    startsWith("eni-attach-"))
            .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item"
                    + ".networkInterfaceSet.item.attachment.attachTime",
                    matchesRegex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$"))
            .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item"
                    + ".networkInterfaceSet.item.attachment.status",
                    equalTo("attached"))
            .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item"
                    + ".networkInterfaceSet.item.attachment.deleteOnTermination",
                    equalTo("true"));
    }

    @Test
    @Order(83)
    void describeInstancesBlockDeviceMappingHasVolumeId() {
        given()
            .formParam("Action", "DescribeInstances")
            .formParam("InstanceId.1", instanceId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.blockDeviceMapping.item.deviceName",
                    equalTo("/dev/xvda"))
            .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.blockDeviceMapping.item.ebs.volumeId",
                    startsWith("vol-"))
            .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.blockDeviceMapping.item.ebs.status",
                    equalTo("attached"))
            .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.blockDeviceMapping.item.ebs.deleteOnTermination",
                    equalTo("true"));
    }

    @Test
    @Order(84)
    void rootVolumeAppearsInDescribeVolumes() {
        // Extract the root volume ID from DescribeInstances
        String rootVolId = given()
            .formParam("Action", "DescribeInstances")
            .formParam("InstanceId.1", instanceId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .extract().path("DescribeInstancesResponse.reservationSet.item.instancesSet.item.blockDeviceMapping.item.ebs.volumeId");

        // Verify it exists in DescribeVolumes with state=in-use
        given()
            .formParam("Action", "DescribeVolumes")
            .formParam("VolumeId.1", rootVolId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVolumesResponse.volumeSet.item.volumeId", equalTo(rootVolId))
            .body("DescribeVolumesResponse.volumeSet.item.status", equalTo("in-use"));
    }

    @Test
    @Order(85)
    void describeInstanceAttributeDisableApiStop() {
        given()
            .formParam("Action", "DescribeInstanceAttribute")
            .formParam("InstanceId", instanceId)
            .formParam("Attribute", "disableApiStop")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeInstanceAttributeResponse.instanceId", equalTo(instanceId))
            .body("DescribeInstanceAttributeResponse.disableApiStop.value", equalTo("false"));
    }

    @Test
    @Order(86)
    void describeInstanceAttributeDisableApiTermination() {
        given()
            .formParam("Action", "DescribeInstanceAttribute")
            .formParam("InstanceId", instanceId)
            .formParam("Attribute", "disableApiTermination")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeInstanceAttributeResponse.instanceId", equalTo(instanceId))
            .body("DescribeInstanceAttributeResponse.disableApiTermination.value", equalTo("false"));
    }

    @Test
    @Order(87)
    void describeInstancesByFilter() {
        given()
            .formParam("Action", "DescribeInstances")
            .formParam("Filter.1.Name", "instance-state-name")
            .formParam("Filter.1.Value.1", "running")
            .formParam("Filter.2.Name", "instance-id")
            .formParam("Filter.2.Value.1", instanceId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.instanceState.name",
                    equalTo("running"));
    }

    @Test
    @Order(88)
    void describeInstanceStatus() {
        given()
            .formParam("Action", "DescribeInstanceStatus")
            .formParam("InstanceId.1", instanceId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeInstanceStatusResponse.instanceStatusSet.item.instanceId", equalTo(instanceId))
            .body("DescribeInstanceStatusResponse.instanceStatusSet.item.instanceState.name", equalTo("running"));
    }

    @Test
    @Order(89)
    void associateAddressToInstance() {
        associationId = given()
            .formParam("Action", "AssociateAddress")
            .formParam("AllocationId", allocationId)
            .formParam("InstanceId", instanceId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AssociateAddressResponse.associationId", startsWith("eipassoc-"))
            .extract().path("AssociateAddressResponse.associationId");
    }

    @Test
    @Order(90)
    void stopInstance() {
        given()
            .formParam("Action", "StopInstances")
            .formParam("InstanceId.1", instanceId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("StopInstancesResponse.instancesSet.item.instanceId", equalTo(instanceId))
            .body("StopInstancesResponse.instancesSet.item.currentState.name", equalTo("stopping"));
    }

    @Test
    @Order(90)
    void startInstance() {
        given()
            .formParam("Action", "StartInstances")
            .formParam("InstanceId.1", instanceId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("StartInstancesResponse.instancesSet.item.instanceId", equalTo(instanceId))
            .body("StartInstancesResponse.instancesSet.item.currentState.name", equalTo("pending"));
    }

    @Test
    @Order(90)
    void rebootInstance() {
        given()
            .formParam("Action", "RebootInstances")
            .formParam("InstanceId.1", instanceId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RebootInstancesResponse.return", equalTo("true"));
    }

    // =========================================================================
    // Network Interfaces
    // =========================================================================

    @Test
    @Order(79)
    void describeNetworkInterfacesBeforeRun() {
        given()
            .formParam("Action", "DescribeNetworkInterfaces")
            .formParam("Filter.1.Name", "attachment.instance-id")
            .formParam("Filter.1.Value.1", "i-00000000000000000")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.size()", equalTo(0));
    }

    @Test
    @Order(88)
    void describeNetworkInterfacesAfterRun() {
        networkInterfaceId = given()
            .formParam("Action", "DescribeNetworkInterfaces")
            .formParam("Filter.1.Name", "attachment.instance-id")
            .formParam("Filter.1.Value.1", instanceId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.size()", equalTo(1))
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item[0].networkInterfaceId",
                    startsWith("eni-"))
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item[0].vpcId", notNullValue())
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item[0].subnetId", notNullValue())
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item[0].status", equalTo("in-use"))
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item[0].privateIpAddress",
                    notNullValue())
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item[0].attachment.attachmentId",
                    startsWith("eni-attach-"))
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item[0].attachment.deviceIndex",
                    equalTo("0"))
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item[0].attachment.instanceId",
                    equalTo(instanceId))
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item[0].groupSet.item.size()",
                    greaterThanOrEqualTo(1))
            .extract().path("DescribeNetworkInterfacesResponse.networkInterfaceSet.item[0].networkInterfaceId");
    }

    @Test
    @Order(89)
    void describeNetworkInterfacesByFilter() {
        given()
            .formParam("Action", "DescribeNetworkInterfaces")
            .formParam("NetworkInterfaceId.1", networkInterfaceId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.size()", equalTo(1))
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item[0].networkInterfaceId",
                    equalTo(networkInterfaceId));
    }

    // =========================================================================
    // Tags
    // =========================================================================

    @Test
    @Order(90)
    void createTags() {
        given()
            .formParam("Action", "CreateTags")
            .formParam("ResourceId.1", instanceId)
            .formParam("Tag.1.Key", "Name")
            .formParam("Tag.1.Value", "test-instance")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateTagsResponse.return", equalTo("true"));
    }

    @Test
    @Order(91)
    void describeTags() {
        given()
            .formParam("Action", "DescribeTags")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeTagsResponse.tagSet.item.key", equalTo("Name"))
            .body("DescribeTagsResponse.tagSet.item.value", equalTo("test-instance"));
    }

    @Test
    @Order(92)
    void describeTagsFilterByResourceId() {
        given()
            .formParam("Action", "DescribeTags")
            .formParam("Filter.1.Name", "resource-id")
            .formParam("Filter.1.Value.1", instanceId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeTagsResponse.tagSet.item.resourceId", equalTo(instanceId))
            .body("DescribeTagsResponse.tagSet.item.key", equalTo("Name"));
    }

    @Test
    @Order(92)
    void describeTagsFilterByKey() {
        given()
            .formParam("Action", "DescribeTags")
            .formParam("Filter.1.Name", "key")
            .formParam("Filter.1.Value.1", "Name")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeTagsResponse.tagSet.item.key", equalTo("Name"));
    }

    @Test
    @Order(92)
    void describeTagsFilterByKeyNoMatch() {
        given()
            .formParam("Action", "DescribeTags")
            .formParam("Filter.1.Name", "key")
            .formParam("Filter.1.Value.1", "NonExistentKey")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeTagsResponse.tagSet.item.size()", equalTo(0));
    }

    @Test
    @Order(92)
    void describeNetworkInterfacesFullFields() {
        // Phase 3: Full field coverage — privateIpAddressesSet, association, tagSet, enriched attachment
        given()
            .formParam("Action", "DescribeNetworkInterfaces")
            .formParam("NetworkInterfaceId.1", networkInterfaceId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            // availabilityZone from instance placement
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item[0].availabilityZone",
                    startsWith("us-east-1"))
            // tagSet propagated from instance tags (created at Order 90)
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item[0].tagSet.item[0].key",
                    equalTo("Name"))
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item[0].tagSet.item[0].value",
                    equalTo("test-instance"))
            // attachment: attachTime (from instance launchTime) and deleteOnTermination
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item[0].attachment.attachTime",
                    notNullValue())
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item[0].attachment.deleteOnTermination",
                    equalTo("true"))
            // privateIpAddressesSet with primary IP and EIP association (from Order 84)
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item[0]." +
                    "privateIpAddressesSet.item[0].privateIpAddress", notNullValue())
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item[0]." +
                    "privateIpAddressesSet.item[0].primary", equalTo("true"))
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item[0]." +
                    "privateIpAddressesSet.item[0].association.publicIp", notNullValue())
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item[0]." +
                    "privateIpAddressesSet.item[0].association.allocationId",
                    startsWith("eipalloc-"));
    }

    // =========================================================================
    // Network Interfaces — Filter tests (Phase 4)
    // =========================================================================

    @Test
    @Order(92)
    void describeNetworkInterfacesFilterBySubnetId() {
        given()
            .formParam("Action", "DescribeNetworkInterfaces")
            .formParam("Filter.1.Name", "subnet-id")
            .formParam("Filter.1.Value.1", subnetId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.size()",
                    greaterThanOrEqualTo(1))
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item[0].subnetId",
                    equalTo(subnetId));
    }

    @Test
    @Order(92)
    void describeNetworkInterfacesFilterByVpcId() {
        given()
            .formParam("Action", "DescribeNetworkInterfaces")
            .formParam("Filter.1.Name", "vpc-id")
            .formParam("Filter.1.Value.1", vpcId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.size()",
                    greaterThanOrEqualTo(1))
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item[0].vpcId",
                    equalTo(vpcId));
    }

    @Test
    @Order(92)
    void describeNetworkInterfacesFilterByGroupId() {
        given()
            .formParam("Action", "DescribeNetworkInterfaces")
            .formParam("Filter.1.Name", "group-id")
            .formParam("Filter.1.Value.1", securityGroupId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.size()",
                    greaterThanOrEqualTo(1));
    }

    @Test
    @Order(92)
    void describeNetworkInterfacesFilterByStatus() {
        given()
            .formParam("Action", "DescribeNetworkInterfaces")
            .formParam("Filter.1.Name", "status")
            .formParam("Filter.1.Value.1", "in-use")
            .formParam("Filter.2.Name", "attachment.instance-id")
            .formParam("Filter.2.Value.1", instanceId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.size()",
                    equalTo(1))
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item[0].status",
                    equalTo("in-use"));
    }

    @Test
    @Order(92)
    void describeNetworkInterfacesFilterByTag() {
        given()
            .formParam("Action", "DescribeNetworkInterfaces")
            .formParam("Filter.1.Name", "tag:Name")
            .formParam("Filter.1.Value.1", "test-instance")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.size()",
                    greaterThanOrEqualTo(1));
    }

    @Test
    @Order(92)
    void describeNetworkInterfacesFilterNoMatch() {
        given()
            .formParam("Action", "DescribeNetworkInterfaces")
            .formParam("Filter.1.Name", "status")
            .formParam("Filter.1.Value.1", "available")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.size()",
                    equalTo(0));
    }

    // =========================================================================
    // Network Interfaces — Pagination (Phase 5)
    // =========================================================================

    @Test
    @Order(92)
    void describeNetworkInterfacesMaxResultsTooLow() {
        given()
            .formParam("Action", "DescribeNetworkInterfaces")
            .formParam("MaxResults", "4")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidMaxResults"));
    }

    @Test
    @Order(92)
    void describeNetworkInterfacesMaxResultsTooHigh() {
        given()
            .formParam("Action", "DescribeNetworkInterfaces")
            .formParam("MaxResults", "1001")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidMaxResults"));
    }

    @Test
    @Order(92)
    void describeNetworkInterfacesMaxResultsNonNumeric() {
        given()
            .formParam("Action", "DescribeNetworkInterfaces")
            .formParam("MaxResults", "abc")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidMaxResults"));
    }

    @Test
    @Order(92)
    void describeNetworkInterfacesMaxResultsWithNetworkInterfaceId() {
        given()
            .formParam("Action", "DescribeNetworkInterfaces")
            .formParam("NetworkInterfaceId.1", networkInterfaceId)
            .formParam("MaxResults", "5")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidParameterCombination"));
    }

    @Test
    @Order(92)
    void describeNetworkInterfacesInvalidNextToken() {
        given()
            .formParam("Action", "DescribeNetworkInterfaces")
            .formParam("NextToken", "invalid-token")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));
    }

    @Test
    @Order(92)
    void describeNetworkInterfacesWithMaxResultsNoNextToken() {
        // When MaxResults exceeds the number of available ENIs,
        // all results are returned and nextToken is omitted.
        // This test works regardless of how many instances exist
        // (including zero, e.g. when run in isolation).
        String body = given()
            .formParam("Action", "DescribeNetworkInterfaces")
            .formParam("MaxResults", "5")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
        .extract().body().asString();

        org.hamcrest.MatcherAssert.assertThat(body, not(containsString("<nextToken>")));
    }

    // =========================================================================
    // Network Interfaces — Error Handling (Phase 6)
    // =========================================================================

    @Test
    @Order(92)
    void describeNetworkInterfacesNotFound() {
        given()
            .formParam("Action", "DescribeNetworkInterfaces")
            .formParam("NetworkInterfaceId.1", "eni-0000000000000dead")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidNetworkInterfaceID.NotFound"));
    }

    @Test
    @Order(92)
    void describeNetworkInterfacesMalformed() {
        given()
            .formParam("Action", "DescribeNetworkInterfaces")
            .formParam("NetworkInterfaceId.1", "not-an-eni-id")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidNetworkInterfaceID.Malformed"));
    }

    // =========================================================================
    // Network Interfaces — Multipage Pagination (Phase 5 completion)
    // =========================================================================
    //
    // Self-contained test: launches additional instances, tests full
    // pagination cycle (MaxResults truncation + NextToken continuation),
    // then terminates the extra instances. Does not affect other tests.

    @Test
    @Order(92)
    void describeNetworkInterfacesMultipagePagination() {
        // ── Launch 5 additional instances to have 6 total ENIs ──
        List<String> batchIds = given()
            .formParam("Action", "RunInstances")
            .formParam("ImageId", "ami-0abcdef1234567890")
            .formParam("InstanceType", "t2.micro")
            .formParam("MinCount", "5")
            .formParam("MaxCount", "5")
            .formParam("KeyName", "test-key")
            .formParam("SubnetId", subnetId)
            .formParam("SecurityGroupId.1", securityGroupId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
        .extract().xmlPath().getList("RunInstancesResponse.instancesSet.item.instanceId", String.class);

        assert batchIds.size() == 5 : "Expected 5 new instances, got " + batchIds.size();

        // ── Page 1: MaxResults=5, expect 5 ENIs + nextToken ──
        String nextToken = given()
            .formParam("Action", "DescribeNetworkInterfaces")
            .formParam("MaxResults", "5")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.size()", equalTo(5))
            .body("DescribeNetworkInterfacesResponse.nextToken", notNullValue())
        .extract().path("DescribeNetworkInterfacesResponse.nextToken");

        assert nextToken != null && !nextToken.isEmpty() : "Expected non-empty nextToken on truncated page";

        // ── Page 2: use NextToken, expect remaining ENIs, no nextToken ──
        String body = given()
            .formParam("Action", "DescribeNetworkInterfaces")
            .formParam("MaxResults", "5")
            .formParam("NextToken", nextToken)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.size()",
                    org.hamcrest.Matchers.greaterThanOrEqualTo(1))
        .extract().body().asString();

        // Final page must NOT contain a nextToken element
        org.hamcrest.MatcherAssert.assertThat(body,
                not(containsString("<nextToken>")));

        // ── Cleanup: terminate the 5 extra instances ──
        for (String id : batchIds) {
            given()
                .formParam("Action", "TerminateInstances")
                .formParam("InstanceId.1", id)
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200);
        }
    }

    // =========================================================================
    // Volumes
    // =========================================================================

    @Test
    @Order(93)
    void createVolume() {
        volumeId = given()
            .formParam("Action", "CreateVolume")
            .formParam("AvailabilityZone", "us-east-1a")
            .formParam("VolumeType", "gp2")
            .formParam("Size", "20")
            .formParam("TagSpecification.1.ResourceType", "volume")
            .formParam("TagSpecification.1.Tag.1.Key", "Name")
            .formParam("TagSpecification.1.Tag.1.Value", "test-volume")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateVolumeResponse.volumeId", startsWith("vol-"))
            .body("CreateVolumeResponse.volumeType", equalTo("gp2"))
            .body("CreateVolumeResponse.size", equalTo("20"))
            .body("CreateVolumeResponse.status", equalTo("available"))
            .body("CreateVolumeResponse.availabilityZone", equalTo("us-east-1a"))
            .body("CreateVolumeResponse.encrypted", equalTo("false"))
        .extract().path("CreateVolumeResponse.volumeId");
    }

    @Test
    @Order(94)
    void describeVolumes() {
        given()
            .formParam("Action", "DescribeVolumes")
            .formParam("VolumeId.1", volumeId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVolumesResponse.volumeSet.item.volumeId", equalTo(volumeId))
            .body("DescribeVolumesResponse.volumeSet.item.volumeType", equalTo("gp2"))
            .body("DescribeVolumesResponse.volumeSet.item.size", equalTo("20"))
            .body("DescribeVolumesResponse.volumeSet.item.status", equalTo("available"));
    }

    @Test
    @Order(95)
    void describeVolumesByStatusFilter() {
        given()
            .formParam("Action", "DescribeVolumes")
            .formParam("Filter.1.Name", "status")
            .formParam("Filter.1.Value.1", "available")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVolumesResponse.volumeSet.item.status", equalTo("available"));
    }

    @Test
    @Order(96)
    void describeVolumesByVolumeTypeFilter() {
        given()
            .formParam("Action", "DescribeVolumes")
            .formParam("Filter.1.Name", "volume-type")
            .formParam("Filter.1.Value.1", "gp2")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVolumesResponse.volumeSet.item.volumeType", equalTo("gp2"));
    }

    @Test
    @Order(97)
    void deleteVolume() {
        given()
            .formParam("Action", "DeleteVolume")
            .formParam("VolumeId", volumeId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeleteVolumeResponse.return", equalTo("true"));
    }

    @Test
    @Order(98)
    void describeDeletedVolumeReturnsNotFound() {
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

    // =========================================================================
    // Teardown / cleanup
    // =========================================================================

    @Test
    @Order(100)
    void terminateInstance() {
        // Capture root volume ID before termination
        rootVolumeId = given()
            .formParam("Action", "DescribeInstances")
            .formParam("InstanceId.1", instanceId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .extract().path("DescribeInstancesResponse.reservationSet.item.instancesSet.item.blockDeviceMapping.item.ebs.volumeId");

        given()
            .formParam("Action", "TerminateInstances")
            .formParam("InstanceId.1", instanceId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TerminateInstancesResponse.instancesSet.item.instanceId", equalTo(instanceId))
            .body("TerminateInstancesResponse.instancesSet.item.currentState.name", equalTo("shutting-down"));
    }

    @Test
    @Order(100)
    void rootVolumeDeletedAfterTermination() {
        given()
            .formParam("Action", "DescribeVolumes")
            .formParam("VolumeId.1", rootVolumeId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidVolume.NotFound"));
    }

    @Test
    @Order(101)
    void disassociateAddress() {
        given()
            .formParam("Action", "DisassociateAddress")
            .formParam("AssociationId", associationId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(102)
    void releaseAddress() {
        given()
            .formParam("Action", "ReleaseAddress")
            .formParam("AllocationId", allocationId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(103)
    void disassociateRouteTable() {
        given()
            .formParam("Action", "DisassociateRouteTable")
            .formParam("AssociationId", rtbAssocId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(104)
    void detachAndDeleteInternetGateway() {
        given()
            .formParam("Action", "DetachInternetGateway")
            .formParam("InternetGatewayId", igwId)
            .formParam("VpcId", vpcId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "DeleteInternetGateway")
            .formParam("InternetGatewayId", igwId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(105)
    void deleteRouteTable() {
        given()
            .formParam("Action", "DeleteRouteTable")
            .formParam("RouteTableId", routeTableId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(106)
    void deleteSubnet() {
        given()
            .formParam("Action", "DeleteSubnet")
            .formParam("SubnetId", subnetId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(107)
    void deleteSecurityGroup() {
        given()
            .formParam("Action", "DeleteSecurityGroup")
            .formParam("GroupId", securityGroupId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(108)
    void deleteKeyPair() {
        given()
            .formParam("Action", "DeleteKeyPair")
            .formParam("KeyPairId", keyPairId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(109)
    void deleteVpc() {
        given()
            .formParam("Action", "DeleteVpc")
            .formParam("VpcId", vpcId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    // =========================================================================
    // Error cases
    // =========================================================================

    @Test
    @Order(200)
    void describeNonExistentInstance() {
        given()
            .formParam("Action", "DescribeInstances")
            .formParam("InstanceId.1", "i-0000000000000dead")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidInstanceID.NotFound"));
    }

    @Test
    @Order(201)
    void describeNonExistentVpc() {
        given()
            .formParam("Action", "DescribeVpcs")
            .formParam("VpcId.1", "vpc-doesnotexist")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidVpcID.NotFound"));
    }

    @Test
    @Order(202)
    void describeNonExistentVolume() {
        given()
            .formParam("Action", "DescribeVolumes")
            .formParam("VolumeId.1", "vol-0000000000000dead")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidVolume.NotFound"));
    }

    @Test
    @Order(203)
    void unsupportedAction() {
        given()
            .formParam("Action", "SomeUnknownAction")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("UnsupportedOperation"));
    }

    // =========================================================================
    // Wildcard filtering
    // =========================================================================

    @Test
    @Order(300)
    void describeVpcsWithWildcardTagFilter() {
        // Create a VPC with a specific tag value
        String vpcWithWildcard = given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.1.0.0/16")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");

        // Tag it with BEGINANDEND
        given()
            .formParam("Action", "CreateTags")
            .formParam("ResourceId.1", vpcWithWildcard)
            .formParam("Tag.1.Key", "Name")
            .formParam("Tag.1.Value", "BEGINANDEND")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Test exact match still works
        given()
            .formParam("Action", "DescribeVpcs")
            .formParam("Filter.1.Name", "tag:Name")
            .formParam("Filter.1.Value.1", "BEGINANDEND")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVpcsResponse.vpcSet.item.vpcId", equalTo(vpcWithWildcard));

        // Test wildcard with asterisk: BEGIN*END should match BEGINANDEND
        given()
            .formParam("Action", "DescribeVpcs")
            .formParam("Filter.1.Name", "tag:Name")
            .formParam("Filter.1.Value.1", "BEGIN*END")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVpcsResponse.vpcSet.item.vpcId", equalTo(vpcWithWildcard));

        // Test wildcard with middle asterisk: *AND* should match BEGINANDEND
        given()
            .formParam("Action", "DescribeVpcs")
            .formParam("Filter.1.Name", "tag:Name")
            .formParam("Filter.1.Value.1", "*AND*")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVpcsResponse.vpcSet.item.vpcId", equalTo(vpcWithWildcard));

        // Cleanup
        given()
            .formParam("Action", "DeleteVpc")
            .formParam("VpcId", vpcWithWildcard)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/");
    }

    @Test
    @Order(301)
    void describeVpcsWithWildcardQuestionMark() {
        // Create a VPC with a specific tag value
        String vpcId1 = given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.2.0.0/16")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");

        // Tag it with "test1"
        given()
            .formParam("Action", "CreateTags")
            .formParam("ResourceId.1", vpcId1)
            .formParam("Tag.1.Key", "Name")
            .formParam("Tag.1.Value", "test1")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Test wildcard with question mark: test? should match test1
        given()
            .formParam("Action", "DescribeVpcs")
            .formParam("Filter.1.Name", "tag:Name")
            .formParam("Filter.1.Value.1", "test?")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVpcsResponse.vpcSet.item.vpcId", equalTo(vpcId1));

        // Cleanup
        given()
            .formParam("Action", "DeleteVpc")
            .formParam("VpcId", vpcId1)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/");
    }

    @Test
    @Order(150)
    void spotInstanceLifecycle() {
        // 1. Request Spot Instance
        String spotRequestId = given()
            .formParam("Action", "RequestSpotInstances")
            .formParam("SpotPrice", "0.05")
            .formParam("InstanceCount", "1")
            .formParam("Type", "one-time")
            .formParam("LaunchSpecification.ImageId", "ami-0abcdef1234567890")
            .formParam("LaunchSpecification.InstanceType", "t2.micro")
            .formParam("TagSpecification.1.ResourceType", "spot-instances-request")
            .formParam("TagSpecification.1.Tag.1.Key", "SpotKey")
            .formParam("TagSpecification.1.Tag.1.Value", "SpotValue")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("RequestSpotInstancesResponse.spotInstanceRequestSet.item[0].spotInstanceRequestId", startsWith("sir-"))
            .body("RequestSpotInstancesResponse.spotInstanceRequestSet.item[0].spotPrice", equalTo("0.05"))
            .body("RequestSpotInstancesResponse.spotInstanceRequestSet.item[0].state", equalTo("active"))
            .body("RequestSpotInstancesResponse.spotInstanceRequestSet.item[0].status.code", equalTo("fulfilled"))
            .body("RequestSpotInstancesResponse.spotInstanceRequestSet.item[0].launchSpecification.imageId", equalTo("ami-0abcdef1234567890"))
            .body("RequestSpotInstancesResponse.spotInstanceRequestSet.item[0].productDescription", equalTo("Linux/UNIX"))
            .body("RequestSpotInstancesResponse.spotInstanceRequestSet.item[0].tagSet.item[0].key", equalTo("SpotKey"))
            .body("RequestSpotInstancesResponse.spotInstanceRequestSet.item[0].tagSet.item[0].value", equalTo("SpotValue"))
            .extract().path("RequestSpotInstancesResponse.spotInstanceRequestSet.item[0].spotInstanceRequestId");

        // 2. Describe Spot Instance Request by ID
        given()
            .formParam("Action", "DescribeSpotInstanceRequests")
            .formParam("SpotInstanceRequestId.1", spotRequestId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeSpotInstanceRequestsResponse.spotInstanceRequestSet.item[0].spotInstanceRequestId", equalTo(spotRequestId))
            .body("DescribeSpotInstanceRequestsResponse.spotInstanceRequestSet.item[0].state", equalTo("active"));

        // 3. Describe Spot Instance Request using tag filter
        given()
            .formParam("Action", "DescribeSpotInstanceRequests")
            .formParam("Filter.1.Name", "tag:SpotKey")
            .formParam("Filter.1.Value.1", "SpotValue")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeSpotInstanceRequestsResponse.spotInstanceRequestSet.item[0].spotInstanceRequestId", equalTo(spotRequestId));

        // 4. Cancel Spot Instance Request
        given()
            .formParam("Action", "CancelSpotInstanceRequests")
            .formParam("SpotInstanceRequestId.1", spotRequestId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CancelSpotInstanceRequestsResponse.spotInstanceRequestSet.item[0].spotInstanceRequestId", equalTo(spotRequestId))
            .body("CancelSpotInstanceRequestsResponse.spotInstanceRequestSet.item[0].state", equalTo("cancelled"));

        // 5. Describe Spot Instance Request to verify state is cancelled
        given()
            .formParam("Action", "DescribeSpotInstanceRequests")
            .formParam("SpotInstanceRequestId.1", spotRequestId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeSpotInstanceRequestsResponse.spotInstanceRequestSet.item[0].state", equalTo("cancelled"));
    }
}
