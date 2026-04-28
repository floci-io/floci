package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

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
        given()
            .formParam("Action", "DescribeSecurityGroups")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("DescribeSecurityGroupsResponse.securityGroupInfo.item[0].groupName", equalTo("default"))
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
    void describeVpcEndpointServices() {
        given()
            .formParam("Action", "DescribeVpcEndpointServices")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
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
    @Order(42)
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
            .body("RunInstancesResponse.instancesSet.item.instanceState.name", equalTo("running"))
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
    void describeInstancesByFilter() {
        given()
            .formParam("Action", "DescribeInstances")
            .formParam("Filter.1.Name", "instance-state-name")
            .formParam("Filter.1.Value.1", "running")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.instanceState.name",
                    equalTo("running"));
    }

    @Test
    @Order(83)
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
    @Order(84)
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
    @Order(85)
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
            .body("StopInstancesResponse.instancesSet.item.currentState.name", equalTo("stopped"));
    }

    @Test
    @Order(86)
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
            .body("StartInstancesResponse.instancesSet.item.currentState.name", equalTo("running"));
    }

    @Test
    @Order(87)
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
        given()
            .formParam("Action", "TerminateInstances")
            .formParam("InstanceId.1", instanceId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TerminateInstancesResponse.instancesSet.item.instanceId", equalTo(instanceId))
            .body("TerminateInstancesResponse.instancesSet.item.currentState.name", equalTo("terminated"));
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
}