package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.xml.HasXPath.hasXPath;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2RouteTargetIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private static final String CIDR_INST = "10.10.1.0/24";
    private static final String CIDR_ENI = "10.10.2.0/24";
    private static final String CIDR_REPLACE = "10.10.3.0/24";
    private static final String ENI_ID = "eni-0123456789abcdef0";

    private static String routeTableId;
    private static String routeTableOwnerId;
    private static String instanceId;

    private static RequestSpecification ec2() {
        return given().header("Authorization", AUTH_HEADER);
    }

    @Test
    @Order(1)
    void setUpRouteTableAndInstance() {
        String vpcId = ec2()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.10.0.0/16")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");

        ExtractableResponse<Response> response = ec2()
            .formParam("Action", "CreateRouteTable")
            .formParam("VpcId", vpcId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract();

        routeTableId = response.path("CreateRouteTableResponse.routeTable.routeTableId");
        routeTableOwnerId = response.path("CreateRouteTableResponse.routeTable.ownerId");

        instanceId = ec2()
            .formParam("Action", "RunInstances")
            .formParam("ImageId", "ami-12345678")
            .formParam("MinCount", "1")
            .formParam("MaxCount", "1")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
    }

    @Test
    @Order(2)
    void createRouteWithInstanceIdAndDescribe() {
        ec2()
            .formParam("Action", "CreateRoute")
            .formParam("RouteTableId", routeTableId)
            .formParam("DestinationCidrBlock", CIDR_INST)
            .formParam("InstanceId", instanceId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(hasXPath("/*[local-name()='CreateRouteResponse']/*[local-name()='return']", equalTo("true")));

        ec2()
            .formParam("Action", "DescribeRouteTables")
            .formParam("RouteTableId.1", routeTableId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(hasXPath("//*[local-name()='routeSet']/*[local-name()='item'][*[local-name()='destinationCidrBlock']='" + CIDR_INST + "']/*[local-name()='instanceId']", equalTo(instanceId)))
            .body(hasXPath("//*[local-name()='routeSet']/*[local-name()='item'][*[local-name()='destinationCidrBlock']='" + CIDR_INST + "']/*[local-name()='instanceOwnerId']", equalTo(routeTableOwnerId)));
    }

    @Test
    @Order(3)
    void createRouteRejectsNonExistentInstanceId() {
        ec2()
            .formParam("Action", "CreateRoute")
            .formParam("RouteTableId", routeTableId)
            .formParam("DestinationCidrBlock", "10.10.88.0/24")
            .formParam("InstanceId", "i-doesnotexist")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("InvalidInstanceID.NotFound"));
    }

    @Test
    @Order(4)
    void createRouteWithNetworkInterfaceIdAndDescribe() {
        ec2()
            .formParam("Action", "CreateRoute")
            .formParam("RouteTableId", routeTableId)
            .formParam("DestinationCidrBlock", CIDR_ENI)
            .formParam("NetworkInterfaceId", ENI_ID)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(hasXPath("/*[local-name()='CreateRouteResponse']/*[local-name()='return']", equalTo("true")));

        ec2()
            .formParam("Action", "DescribeRouteTables")
            .formParam("RouteTableId.1", routeTableId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(hasXPath("//*[local-name()='routeSet']/*[local-name()='item'][*[local-name()='destinationCidrBlock']='" + CIDR_ENI + "']/*[local-name()='networkInterfaceId']", equalTo(ENI_ID)))
            .body(hasXPath("count(//*[local-name()='routeSet']/*[local-name()='item'][*[local-name()='destinationCidrBlock']='" + CIDR_ENI + "']/*[local-name()='instanceOwnerId'])", equalTo("0")));
    }

    @Test
    @Order(5)
    void createRouteRejectsMultipleTargets() {
        ec2()
            .formParam("Action", "CreateRoute")
            .formParam("RouteTableId", routeTableId)
            .formParam("DestinationCidrBlock", "10.10.99.0/24")
            .formParam("InstanceId", instanceId)
            .formParam("NetworkInterfaceId", ENI_ID)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("InvalidParameterCombination"));

        ec2()
            .formParam("Action", "CreateRoute")
            .formParam("RouteTableId", routeTableId)
            .formParam("DestinationCidrBlock", "10.10.99.0/24")
            .formParam("InstanceId", instanceId)
            .formParam("GatewayId", "igw-12345678")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("InvalidParameterCombination"));
    }

    @Test
    @Order(6)
    void replaceRouteWithInstanceAndNetworkInterface() {
        ec2()
            .formParam("Action", "CreateRoute")
            .formParam("RouteTableId", routeTableId)
            .formParam("DestinationCidrBlock", CIDR_REPLACE)
            .formParam("GatewayId", "igw-replace-init")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        ec2()
            .formParam("Action", "ReplaceRoute")
            .formParam("RouteTableId", routeTableId)
            .formParam("DestinationCidrBlock", CIDR_REPLACE)
            .formParam("InstanceId", instanceId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(hasXPath("/*[local-name()='ReplaceRouteResponse']/*[local-name()='return']", equalTo("true")));

        ec2()
            .formParam("Action", "DescribeRouteTables")
            .formParam("RouteTableId.1", routeTableId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(hasXPath("//*[local-name()='routeSet']/*[local-name()='item'][*[local-name()='destinationCidrBlock']='" + CIDR_REPLACE + "']/*[local-name()='instanceId']", equalTo(instanceId)))
            .body(hasXPath("//*[local-name()='routeSet']/*[local-name()='item'][*[local-name()='destinationCidrBlock']='" + CIDR_REPLACE + "']/*[local-name()='instanceOwnerId']", equalTo(routeTableOwnerId)));

        ec2()
            .formParam("Action", "ReplaceRoute")
            .formParam("RouteTableId", routeTableId)
            .formParam("DestinationCidrBlock", CIDR_REPLACE)
            .formParam("NetworkInterfaceId", ENI_ID)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        ec2()
            .formParam("Action", "DescribeRouteTables")
            .formParam("RouteTableId.1", routeTableId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(hasXPath("//*[local-name()='routeSet']/*[local-name()='item'][*[local-name()='destinationCidrBlock']='" + CIDR_REPLACE + "']/*[local-name()='networkInterfaceId']", equalTo(ENI_ID)))
            .body(hasXPath("count(//*[local-name()='routeSet']/*[local-name()='item'][*[local-name()='destinationCidrBlock']='" + CIDR_REPLACE + "']/*[local-name()='instanceOwnerId'])", equalTo("0")));
    }

    @Test
    @Order(7)
    void replaceRouteRejectsNonExistentInstanceId() {
        ec2()
            .formParam("Action", "ReplaceRoute")
            .formParam("RouteTableId", routeTableId)
            .formParam("DestinationCidrBlock", CIDR_REPLACE)
            .formParam("InstanceId", "i-doesnotexist")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("InvalidInstanceID.NotFound"));
    }

    @Test
    @Order(8)
    void replaceRouteRejectsMultipleTargets() {
        ec2()
            .formParam("Action", "ReplaceRoute")
            .formParam("RouteTableId", routeTableId)
            .formParam("DestinationCidrBlock", CIDR_REPLACE)
            .formParam("InstanceId", instanceId)
            .formParam("NetworkInterfaceId", ENI_ID)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("InvalidParameterCombination"));
    }

    @Test
    @Order(9)
    void filterRouteTablesByTarget() {
        ec2()
            .formParam("Action", "DescribeRouteTables")
            .formParam("Filter.1.Name", "route.instance-id")
            .formParam("Filter.1.Value.1", instanceId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(hasXPath("count(//*[local-name()='routeTableSet']/*[local-name()='item'][*[local-name()='routeTableId']='" + routeTableId + "'])", equalTo("1")));

        ec2()
            .formParam("Action", "DescribeRouteTables")
            .formParam("Filter.1.Name", "route.network-interface-id")
            .formParam("Filter.1.Value.1", ENI_ID)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(hasXPath("count(//*[local-name()='routeTableSet']/*[local-name()='item'][*[local-name()='routeTableId']='" + routeTableId + "'])", equalTo("1")));
    }

    @Test
    @Order(10)
    void deleteRouteCleansUp() {
        ec2()
            .formParam("Action", "DeleteRoute")
            .formParam("RouteTableId", routeTableId)
            .formParam("DestinationCidrBlock", CIDR_INST)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        ec2()
            .formParam("Action", "DescribeRouteTables")
            .formParam("RouteTableId.1", routeTableId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(hasXPath("count(//*[local-name()='routeSet']/*[local-name()='item'][*[local-name()='destinationCidrBlock']='" + CIDR_INST + "'])", equalTo("0")));
    }
}
