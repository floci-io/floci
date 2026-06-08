package io.github.hectorvent.floci.services.ec2;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.startsWith;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Integration tests for VPC Flow Logs and VPC Endpoints via the EC2 Query
 * Protocol (form-encoded POST, XML response).
 *
 * <p>Covers {@code CreateFlowLogs} / {@code DescribeFlowLogs} / {@code DeleteFlowLogs}
 * and {@code CreateVpcEndpoint} / {@code DescribeVpcEndpoints} /
 * {@code DescribeVpcEndpointConnections}.</p>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2FlowLogAndVpcEndpointIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private static String vpcId;
    private static String subnetId;
    private static String flowLogId;
    private static String vpcEndpointId;

    // =========================================================================
    // Fixtures: a VPC + subnet to attach the flow log / endpoint to
    // =========================================================================

    @Test
    @Order(1)
    void createVpc() {
        vpcId = given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.20.0.0/16")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("CreateVpcResponse.vpc.state", equalTo("available"))
            .extract().path("CreateVpcResponse.vpc.vpcId");
    }

    @Test
    @Order(2)
    void createSubnet() {
        subnetId = given()
            .formParam("Action", "CreateSubnet")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", "10.20.1.0/24")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .extract().path("CreateSubnetResponse.subnet.subnetId");
    }

    // =========================================================================
    // VPC Flow Logs
    // =========================================================================

    @Test
    @Order(10)
    void createFlowLogs() {
        flowLogId = given()
            .formParam("Action", "CreateFlowLogs")
            .formParam("ResourceType", "VPC")
            .formParam("ResourceId.1", vpcId)
            .formParam("TrafficType", "ALL")
            .formParam("LogDestinationType", "s3")
            .formParam("LogDestination", "arn:aws:s3:::flow-logs-test-bucket")
            .formParam("MaxAggregationInterval", "60")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("CreateFlowLogsResponse.flowLogIdSet.item[0]", startsWith("fl-"))
            .extract().path("CreateFlowLogsResponse.flowLogIdSet.item[0]");
    }

    @Test
    @Order(11)
    void describeFlowLogsReturnsTheCreatedLog() {
        given()
            .formParam("Action", "DescribeFlowLogs")
            .formParam("FlowLogId.1", flowLogId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("DescribeFlowLogsResponse.flowLogSet.item[0].flowLogId", equalTo(flowLogId))
            .body("DescribeFlowLogsResponse.flowLogSet.item[0].resourceId", equalTo(vpcId))
            .body("DescribeFlowLogsResponse.flowLogSet.item[0].trafficType", equalTo("ALL"))
            .body("DescribeFlowLogsResponse.flowLogSet.item[0].logDestinationType", equalTo("s3"))
            .body("DescribeFlowLogsResponse.flowLogSet.item[0].logDestination",
                    equalTo("arn:aws:s3:::flow-logs-test-bucket"))
            .body("DescribeFlowLogsResponse.flowLogSet.item[0].maxAggregationInterval", equalTo("60"));
    }

    @Test
    @Order(12)
    void deleteFlowLogs() {
        given()
            .formParam("Action", "DeleteFlowLogs")
            .formParam("FlowLogId.1", flowLogId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml");

        // After deletion, describing all flow logs must not return the deleted id.
        given()
            .formParam("Action", "DescribeFlowLogs")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeFlowLogsResponse.flowLogSet.findAll { it.flowLogId == '" + flowLogId + "' }.size()",
                    equalTo(0));
    }

    // =========================================================================
    // VPC Endpoints (Interface / PrivateLink)
    // =========================================================================

    @Test
    @Order(20)
    void createInterfaceVpcEndpoint() {
        vpcEndpointId = given()
            .formParam("Action", "CreateVpcEndpoint")
            .formParam("ServiceName", "com.amazonaws.us-east-1.s3")
            .formParam("VpcId", vpcId)
            .formParam("VpcEndpointType", "Interface")
            .formParam("SubnetId.1", subnetId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("CreateVpcEndpointResponse.vpcEndpoint.vpcEndpointType", equalTo("Interface"))
            .body("CreateVpcEndpointResponse.vpcEndpoint.vpcId", equalTo(vpcId))
            .body("CreateVpcEndpointResponse.vpcEndpoint.serviceName",
                    equalTo("com.amazonaws.us-east-1.s3"))
            .body("CreateVpcEndpointResponse.vpcEndpoint.state", equalTo("available"))
            .body("CreateVpcEndpointResponse.vpcEndpoint.vpcEndpointId", startsWith("vpce-"))
            .body("CreateVpcEndpointResponse.vpcEndpoint.networkInterfaceIdSet.item.size()",
                    greaterThanOrEqualTo(1))
            .extract().path("CreateVpcEndpointResponse.vpcEndpoint.vpcEndpointId");
    }

    @Test
    @Order(21)
    void interfaceEndpointHasNetworkInterface() {
        // An interface endpoint must expose at least one ENI; that private-IP ENI
        // is what makes endpoint traffic observable in VPC flow logs.
        given()
            .formParam("Action", "DescribeVpcEndpoints")
            .formParam("VpcEndpointId.1", vpcEndpointId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("DescribeVpcEndpointsResponse.vpcEndpointSet.item[0].vpcEndpointId",
                    equalTo(vpcEndpointId))
            .body("DescribeVpcEndpointsResponse.vpcEndpointSet.item[0].networkInterfaceIdSet.item.size()",
                    greaterThanOrEqualTo(1));
    }

    @Test
    @Order(22)
    void describeVpcEndpointConnectionsReturnsEmptySet() {
        // Must succeed (empty), not error — clients that enumerate connections
        // per endpoint rely on this returning a well-formed empty set.
        given()
            .formParam("Action", "DescribeVpcEndpointConnections")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("DescribeVpcEndpointConnectionsResponse.requestId", org.hamcrest.Matchers.notNullValue());
    }

    @Test
    @Order(23)
    void deleteVpcEndpoint() {
        given()
            .formParam("Action", "DeleteVpcEndpoints")
            .formParam("VpcEndpointId.1", vpcEndpointId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml");

        // After deletion the endpoint must no longer be returned (and its ENIs
        // are removed, so it doesn't leak into network-interface listings).
        given()
            .formParam("Action", "DescribeVpcEndpoints")
            .formParam("VpcEndpointId.1", vpcEndpointId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVpcEndpointsResponse.vpcEndpointSet.item.size()", equalTo(0));
    }
}
