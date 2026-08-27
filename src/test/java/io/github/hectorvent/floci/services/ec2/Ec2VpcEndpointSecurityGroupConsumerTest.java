package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/**
 * Wire-level tests for {@code GetSecurityGroupsForVpc} and {@code ModifyVpcEndpoint}.
 *
 * <p>New class rather than appended to {@code Ec2IntegrationTest} (which uses ordered
 * class-field fixtures these operations don't need), so falsifiability isolates per
 * operation (CS-001).
 */
@QuarkusTest
class Ec2VpcEndpointSecurityGroupConsumerTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ec2/aws4_request";

    private String createVpc() {
        return given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.77.0.0/16")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");
    }

    @Test
    void getSecurityGroupsForVpc_returnsOnlyGroupsInThatVpc() {
        String vpcId = createVpc();
        String otherVpcId = createVpc();

        String groupId = given()
            .formParam("Action", "CreateSecurityGroup")
            .formParam("GroupName", "vpc-scoped-sg")
            .formParam("GroupDescription", "scoped to one vpc")
            .formParam("VpcId", vpcId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateSecurityGroupResponse.groupId");

        given()
            .formParam("Action", "CreateSecurityGroup")
            .formParam("GroupName", "other-vpc-sg")
            .formParam("GroupDescription", "in a different vpc")
            .formParam("VpcId", otherVpcId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "GetSecurityGroupsForVpc")
            .formParam("VpcId", vpcId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetSecurityGroupsForVpcResponse.securityGroupForVpcSet.item.groupId", hasItem(groupId))
            .body("GetSecurityGroupsForVpcResponse.securityGroupForVpcSet.item.primaryVpcId",
                    everyItem(equalTo(vpcId)));
    }

    @Test
    void getSecurityGroupsForVpc_unknownVpc_returnsAwsErrorShape() {
        given()
            .formParam("Action", "GetSecurityGroupsForVpc")
            .formParam("VpcId", "vpc-doesnotexist")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", startsWith("InvalidVpcID"));
    }

    @Test
    void modifyVpcEndpoint_updatesPolicyAndRouteTables() {
        String vpcId = createVpc();
        String routeTableId = given()
            .formParam("Action", "CreateRouteTable")
            .formParam("VpcId", vpcId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateRouteTableResponse.routeTable.routeTableId");

        String endpointId = given()
            .formParam("Action", "CreateVpcEndpoint")
            .formParam("VpcId", vpcId)
            .formParam("ServiceName", "com.amazonaws.us-east-1.s3")
            .formParam("VpcEndpointType", "Gateway")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcEndpointResponse.vpcEndpoint.vpcEndpointId");

        given()
            .formParam("Action", "ModifyVpcEndpoint")
            .formParam("VpcEndpointId", endpointId)
            .formParam("PolicyDocument", "{\"Version\":\"2012-10-17\"}")
            .formParam("AddRouteTableId.1", routeTableId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ModifyVpcEndpointResponse.return", equalTo("true"));

        given()
            .formParam("Action", "DescribeVpcEndpoints")
            .formParam("VpcEndpointId.1", endpointId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVpcEndpointsResponse.vpcEndpointSet.item.policyDocument",
                    equalTo("{\"Version\":\"2012-10-17\"}"))
            .body("DescribeVpcEndpointsResponse.vpcEndpointSet.item.routeTableIdSet.item",
                    equalTo(routeTableId));
    }

    @Test
    void modifyVpcEndpoint_resetPolicy_clearsPolicyDocument() {
        String vpcId = createVpc();
        String endpointId = given()
            .formParam("Action", "CreateVpcEndpoint")
            .formParam("VpcId", vpcId)
            .formParam("ServiceName", "com.amazonaws.us-east-1.s3")
            .formParam("VpcEndpointType", "Gateway")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcEndpointResponse.vpcEndpoint.vpcEndpointId");

        given()
            .formParam("Action", "ModifyVpcEndpoint")
            .formParam("VpcEndpointId", endpointId)
            .formParam("PolicyDocument", "{\"Version\":\"2012-10-17\"}")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "ModifyVpcEndpoint")
            .formParam("VpcEndpointId", endpointId)
            .formParam("ResetPolicy", "true")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "DescribeVpcEndpoints")
            .formParam("VpcEndpointId.1", endpointId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("policyDocument")));
    }

    @Test
    void modifyVpcEndpoint_unknownId_returnsAwsErrorShape() {
        given()
            .formParam("Action", "ModifyVpcEndpoint")
            .formParam("VpcEndpointId", "vpce-doesnotexist")
            .formParam("PolicyDocument", "{}")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", startsWith("InvalidVpcEndpointId"));
    }
}
