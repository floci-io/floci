package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for VPC endpoints over the EC2 Query protocol, driven the way the Terraform
 * AWS provider drives them: create with a policy, describe, change the endpoint through
 * {@code ModifyVpcEndpoint}, describe again and expect the change to have stuck.
 *
 * <p>The shapes asserted here were read off real EC2 (lex00/floci#97): every describe carries
 * {@code policyDocument}, {@code ownerId}, {@code serviceRegion} and {@code ipAddressType}, an
 * endpoint created without a policy reads back the full-access default, and
 * {@code ModifyVpcEndpoint} answers {@code <return>true</return>} rather than
 * {@code UnsupportedOperation}.
 *
 * <p>Ordered because the cases walk one gateway endpoint and one interface endpoint through their
 * lifecycle.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2VpcEndpointIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private static final String POLICY =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":\"*\","
                    + "\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}]}";

    private static String vpcId;
    private static String subnetId;
    private static String securityGroupId;
    private static String routeTableId;
    private static String otherRouteTableId;
    private static String gatewayEndpointId;
    private static String interfaceEndpointId;

    @Test
    @Order(1)
    void createVpcScaffolding() {
        vpcId = given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.40.0.0/16")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");

        subnetId = given()
            .formParam("Action", "CreateSubnet")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", "10.40.1.0/24")
            .formParam("AvailabilityZone", "us-east-1a")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateSubnetResponse.subnet.subnetId");

        securityGroupId = given()
            .formParam("Action", "CreateSecurityGroup")
            .formParam("GroupName", "endpoint-sg")
            .formParam("GroupDescription", "endpoint sg")
            .formParam("VpcId", vpcId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateSecurityGroupResponse.groupId");

        routeTableId = createRouteTable();
        otherRouteTableId = createRouteTable();
    }

    private String createRouteTable() {
        return given()
            .formParam("Action", "CreateRouteTable")
            .formParam("VpcId", vpcId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateRouteTableResponse.routeTable.routeTableId");
    }

    @Test
    @Order(2)
    void createGatewayEndpointKeepsItsPolicyAndRouteTables() {
        gatewayEndpointId = given()
            .formParam("Action", "CreateVpcEndpoint")
            .formParam("VpcId", vpcId)
            .formParam("ServiceName", "com.amazonaws.us-east-1.s3")
            .formParam("VpcEndpointType", "Gateway")
            .formParam("RouteTableId.1", routeTableId)
            .formParam("PolicyDocument", POLICY)
            .formParam("TagSpecification.1.ResourceType", "vpc-endpoint")
            .formParam("TagSpecification.1.Tag.1.Key", "Name")
            .formParam("TagSpecification.1.Tag.1.Value", "s3-endpoint")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateVpcEndpointResponse.vpcEndpoint.vpcEndpointId", startsWith("vpce-"))
            .body("CreateVpcEndpointResponse.vpcEndpoint.policyDocument", equalTo(POLICY))
            .extract().path("CreateVpcEndpointResponse.vpcEndpoint.vpcEndpointId");

        given()
            .formParam("Action", "DescribeVpcEndpoints")
            .formParam("VpcEndpointId.1", gatewayEndpointId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVpcEndpointsResponse.vpcEndpointSet.item.vpcEndpointType", equalTo("Gateway"))
            .body("DescribeVpcEndpointsResponse.vpcEndpointSet.item.policyDocument", equalTo(POLICY))
            .body("DescribeVpcEndpointsResponse.vpcEndpointSet.item.routeTableIdSet.item",
                    equalTo(routeTableId))
            .body("DescribeVpcEndpointsResponse.vpcEndpointSet.item.serviceRegion", equalTo("us-east-1"))
            .body("DescribeVpcEndpointsResponse.vpcEndpointSet.item.ipAddressType", equalTo("ipv4"))
            .body("DescribeVpcEndpointsResponse.vpcEndpointSet.item.requesterManaged", equalTo("false"))
            .body("DescribeVpcEndpointsResponse.vpcEndpointSet.item.dnsOptions.dnsRecordIpType",
                    equalTo("service-defined"))
            .body("DescribeVpcEndpointsResponse.vpcEndpointSet.item.ownerId", equalTo("000000000000"))
            .body("DescribeVpcEndpointsResponse.vpcEndpointSet.item.tagSet.item.value",
                    equalTo("s3-endpoint"));
    }

    @Test
    @Order(3)
    void endpointCreatedWithoutAPolicyReadsBackTheAwsDefault() {
        given()
            .formParam("Action", "DescribeVpcEndpoints")
            .formParam("VpcEndpointId.1", createInterfaceEndpoint())
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVpcEndpointsResponse.vpcEndpointSet.item.policyDocument",
                    containsString("\"Action\":\"*\""))
            // Interface endpoints own one ENI per subnet, and AWS lists them on every describe.
            .body("DescribeVpcEndpointsResponse.vpcEndpointSet.item.networkInterfaceIdSet.item",
                    startsWith("eni-"))
            .body("DescribeVpcEndpointsResponse.vpcEndpointSet.item.subnetIdSet.item", equalTo(subnetId))
            .body("DescribeVpcEndpointsResponse.vpcEndpointSet.item.dnsOptions.dnsRecordIpType",
                    equalTo("ipv4"));
    }

    private String createInterfaceEndpoint() {
        interfaceEndpointId = given()
            .formParam("Action", "CreateVpcEndpoint")
            .formParam("VpcId", vpcId)
            .formParam("ServiceName", "com.amazonaws.us-east-1.ecs")
            .formParam("VpcEndpointType", "Interface")
            .formParam("SubnetId.1", subnetId)
            .formParam("SecurityGroupId.1", securityGroupId)
            .formParam("PrivateDnsEnabled", "true")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcEndpointResponse.vpcEndpoint.vpcEndpointId");
        assertTrue(interfaceEndpointId.startsWith("vpce-"));
        return interfaceEndpointId;
    }

    @Test
    @Order(4)
    void theEndpointsEnisAreDescribable() {
        String eniId = given()
            .formParam("Action", "DescribeVpcEndpoints")
            .formParam("VpcEndpointId.1", interfaceEndpointId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("DescribeVpcEndpointsResponse.vpcEndpointSet.item.networkInterfaceIdSet.item");

        // The provider follows networkInterfaceIdSet into DescribeNetworkInterfaces to build the
        // endpoint's subnet_configuration, and fails the whole read if an id it was handed is
        // unknown — reporting the ids without answering for them is worse than not reporting them.
        given()
            .formParam("Action", "DescribeNetworkInterfaces")
            .formParam("NetworkInterfaceId.1", eniId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.networkInterfaceId",
                    equalTo(eniId))
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.subnetId", equalTo(subnetId))
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.interfaceType",
                    equalTo("vpc_endpoint"));
    }

    @Test
    @Order(5)
    void modifyReplacesThePolicyAndTheRouteTableSet() {
        String newPolicy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Deny\","
                + "\"Principal\":\"*\",\"Action\":\"s3:DeleteObject\",\"Resource\":\"*\"}]}";

        given()
            .formParam("Action", "ModifyVpcEndpoint")
            .formParam("VpcEndpointId", gatewayEndpointId)
            .formParam("PolicyDocument", newPolicy)
            .formParam("AddRouteTableId.1", otherRouteTableId)
            .formParam("RemoveRouteTableId.1", routeTableId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ModifyVpcEndpointResponse.return", equalTo("true"));

        given()
            .formParam("Action", "DescribeVpcEndpoints")
            .formParam("VpcEndpointId.1", gatewayEndpointId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVpcEndpointsResponse.vpcEndpointSet.item.policyDocument", equalTo(newPolicy))
            .body("DescribeVpcEndpointsResponse.vpcEndpointSet.item.routeTableIdSet.item",
                    equalTo(otherRouteTableId));
    }

    @Test
    @Order(6)
    void addingAnAlreadyAttachedRouteTableIsANoOp() {
        given()
            .formParam("Action", "ModifyVpcEndpoint")
            .formParam("VpcEndpointId", gatewayEndpointId)
            .formParam("AddRouteTableId.1", otherRouteTableId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ModifyVpcEndpointResponse.return", equalTo("true"));

        given()
            .formParam("Action", "DescribeVpcEndpoints")
            .formParam("VpcEndpointId.1", gatewayEndpointId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVpcEndpointsResponse.vpcEndpointSet.item.routeTableIdSet.item",
                    equalTo(otherRouteTableId));
    }

    @Test
    @Order(7)
    void resetPolicyRestoresTheFullAccessDocument() {
        given()
            .formParam("Action", "ModifyVpcEndpoint")
            .formParam("VpcEndpointId", gatewayEndpointId)
            .formParam("ResetPolicy", "true")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ModifyVpcEndpointResponse.return", equalTo("true"));

        given()
            .formParam("Action", "DescribeVpcEndpoints")
            .formParam("VpcEndpointId.1", gatewayEndpointId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVpcEndpointsResponse.vpcEndpointSet.item.policyDocument",
                    containsString("\"Action\":\"*\""));
    }

    @Test
    @Order(8)
    void modifyMovesAnInterfaceEndpointsSubnetsAndSecurityGroups() {
        String secondSubnetId = given()
            .formParam("Action", "CreateSubnet")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", "10.40.2.0/24")
            .formParam("AvailabilityZone", "us-east-1b")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateSubnetResponse.subnet.subnetId");

        String secondGroupId = given()
            .formParam("Action", "CreateSecurityGroup")
            .formParam("GroupName", "endpoint-sg-2")
            .formParam("GroupDescription", "endpoint sg 2")
            .formParam("VpcId", vpcId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateSecurityGroupResponse.groupId");

        given()
            .formParam("Action", "ModifyVpcEndpoint")
            .formParam("VpcEndpointId", interfaceEndpointId)
            .formParam("AddSubnetId.1", secondSubnetId)
            .formParam("AddSecurityGroupId.1", secondGroupId)
            .formParam("RemoveSecurityGroupId.1", securityGroupId)
            .formParam("PrivateDnsEnabled", "false")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ModifyVpcEndpointResponse.return", equalTo("true"));

        given()
            .formParam("Action", "DescribeVpcEndpoints")
            .formParam("VpcEndpointId.1", interfaceEndpointId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVpcEndpointsResponse.vpcEndpointSet.item.subnetIdSet.item",
                    contains(subnetId, secondSubnetId))
            .body("DescribeVpcEndpointsResponse.vpcEndpointSet.item.groupSet.item.groupId",
                    equalTo(secondGroupId))
            .body("DescribeVpcEndpointsResponse.vpcEndpointSet.item.networkInterfaceIdSet.item.size()",
                    equalTo(2))
            .body("DescribeVpcEndpointsResponse.vpcEndpointSet.item.privateDnsEnabled", equalTo("false"));
    }

    @Test
    @Order(9)
    void tagsAddedAfterCreationSurviveTheRoundTrip() {
        given()
            .formParam("Action", "CreateTags")
            .formParam("ResourceId.1", gatewayEndpointId)
            .formParam("Tag.1.Key", "tofu-slot")
            .formParam("Tag.1.Value", "slot-7")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "DescribeVpcEndpoints")
            .formParam("VpcEndpointId.1", gatewayEndpointId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVpcEndpointsResponse.vpcEndpointSet.item.tagSet.item.find "
                    + "{ it.key == 'tofu-slot' }.value", equalTo("slot-7"));
    }

    @Test
    @Order(10)
    void modifyRejectsUnknownEndpointsAndCrossTypeParameters() {
        given()
            .formParam("Action", "ModifyVpcEndpoint")
            .formParam("VpcEndpointId", "vpce-00000000000000000")
            .formParam("ResetPolicy", "true")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("InvalidVpcEndpointId.NotFound"));

        // Real EC2: subnets belong to Interface/GatewayLoadBalancer endpoints only.
        given()
            .formParam("Action", "ModifyVpcEndpoint")
            .formParam("VpcEndpointId", gatewayEndpointId)
            .formParam("AddSubnetId.1", subnetId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("Subnet IDs are only supported"));

        given()
            .formParam("Action", "ModifyVpcEndpoint")
            .formParam("VpcEndpointId", interfaceEndpointId)
            .formParam("AddRouteTableId.1", routeTableId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("Route table IDs are only supported"));

        given()
            .formParam("Action", "ModifyVpcEndpoint")
            .formParam("VpcEndpointId", gatewayEndpointId)
            .formParam("AddRouteTableId.1", "rtb-00000000000000000")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("InvalidRouteTableID.NotFound"));
    }

    @Test
    @Order(11)
    void deleteEndpoints() {
        given()
            .formParam("Action", "DeleteVpcEndpoints")
            .formParam("VpcEndpointId.1", gatewayEndpointId)
            .formParam("VpcEndpointId.2", interfaceEndpointId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }
}
