package io.github.hectorvent.floci.services.ec2;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;

/**
 * Integration tests for VPC Flow Logs via the EC2 Query Protocol
 * (form-encoded POST, XML response).
 *
 * <p>Covers {@code CreateFlowLogs} / {@code DescribeFlowLogs} / {@code DeleteFlowLogs}.</p>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2FlowLogIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private static String vpcId;
    private static String flowLogId;
    private static String cloudWatchFlowLogId;

    // =========================================================================
    // Fixture: a VPC to attach the flow log to
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
    void deleteFlowLogsInAnotherRegionLeavesTheLogIntact() {
        given()
            .formParam("Action", "DeleteFlowLogs")
            .formParam("FlowLogId.1", flowLogId)
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260205/eu-west-1/ec2/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml");

        given()
            .formParam("Action", "DescribeFlowLogs")
            .formParam("FlowLogId.1", flowLogId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeFlowLogsResponse.flowLogSet.item[0].flowLogId", equalTo(flowLogId));
    }

    @Test
    @Order(13)
    void createFlowLogsWithACloudWatchDestinationKeepsTheDeliverLogsPermissionArn() {
        // Terraform reads aws_flow_log.iam_role_arn back from DescribeFlowLogs. A dropped ARN
        // reads as empty and every later plan proposes replacing the flow log.
        cloudWatchFlowLogId = given()
            .formParam("Action", "CreateFlowLogs")
            .formParam("ResourceType", "VPC")
            .formParam("ResourceId.1", vpcId)
            .formParam("TrafficType", "ALL")
            .formParam("LogDestinationType", "cloud-watch-logs")
            .formParam("LogDestination", "arn:aws:logs:us-east-1:000000000000:log-group:/aws/vpc/flow-logs")
            .formParam("DeliverLogsPermissionArn", "arn:aws:iam::000000000000:role/flow-logs-role")
            .formParam("MaxAggregationInterval", "600")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("CreateFlowLogsResponse.flowLogIdSet.item[0]", startsWith("fl-"))
            .extract().path("CreateFlowLogsResponse.flowLogIdSet.item[0]");

        given()
            .formParam("Action", "DescribeFlowLogs")
            .formParam("FlowLogId.1", cloudWatchFlowLogId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("DescribeFlowLogsResponse.flowLogSet.item[0].flowLogId", equalTo(cloudWatchFlowLogId))
            .body("DescribeFlowLogsResponse.flowLogSet.item[0].logDestinationType", equalTo("cloud-watch-logs"))
            .body("DescribeFlowLogsResponse.flowLogSet.item[0].deliverLogsPermissionArn",
                    equalTo("arn:aws:iam::000000000000:role/flow-logs-role"));

        given()
            .formParam("Action", "DeleteFlowLogs")
            .formParam("FlowLogId.1", cloudWatchFlowLogId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml");
    }

    @Test
    @Order(14)
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

    @Test
    @Order(15)
    void flowLogKeepsTheTagsOfItsOwnTagSpecification() {
        // Terraform tags aws_flow_log at create and reads the tags back from DescribeFlowLogs.
        String id = given()
            .formParam("Action", "CreateFlowLogs")
            .formParam("ResourceType", "VPC")
            .formParam("ResourceId.1", vpcId)
            .formParam("TrafficType", "ALL")
            .formParam("LogDestinationType", "s3")
            .formParam("LogDestination", "arn:aws:s3:::flow-logs-bucket")
            .formParam("TagSpecification.1.ResourceType", "vpc")
            .formParam("TagSpecification.1.Tag.1.Key", "Decoy")
            .formParam("TagSpecification.1.Tag.1.Value", "not-mine")
            .formParam("TagSpecification.2.ResourceType", "vpc-flow-log")
            .formParam("TagSpecification.2.Tag.1.Key", "Environment")
            .formParam("TagSpecification.2.Tag.1.Value", "dev")
            .formParam("TagSpecification.2.Tag.2.Key", "Layer")
            .formParam("TagSpecification.2.Tag.2.Value", "vpc")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateFlowLogsResponse.flowLogIdSet.item[0]");

        String tags = "DescribeFlowLogsResponse.flowLogSet.item[0].tagSet.item";
        given()
            .formParam("Action", "DescribeFlowLogs")
            .formParam("FlowLogId.1", id)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(tags + ".size()", equalTo(2))
            .body(tags + ".find { it.key == 'Environment' }.value", equalTo("dev"))
            .body(tags + ".find { it.key == 'Layer' }.value", equalTo("vpc"));

        given()
            .formParam("Action", "CreateTags")
            .formParam("ResourceId.1", id)
            .formParam("Tag.1.Key", "Owner")
            .formParam("Tag.1.Value", "platform")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "DescribeFlowLogs")
            .formParam("FlowLogId.1", id)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(tags + ".size()", equalTo(3))
            .body(tags + ".find { it.key == 'Owner' }.value", equalTo("platform"));

        given()
            .formParam("Action", "DescribeTags")
            .formParam("Filter.1.Name", "resource-type")
            .formParam("Filter.1.Value.1", "vpc-flow-log")
            .formParam("Filter.2.Name", "resource-id")
            .formParam("Filter.2.Value.1", id)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeTagsResponse.tagSet.item.size()", equalTo(3))
            .body("DescribeTagsResponse.tagSet.item.findAll { it.resourceType != 'vpc-flow-log' }.size()", equalTo(0));

        given()
            .formParam("Action", "DeleteFlowLogs")
            .formParam("FlowLogId.1", id)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // A deleted flow log's tags must not outlive it.
        given()
            .formParam("Action", "DescribeTags")
            .formParam("Filter.1.Name", "resource-id")
            .formParam("Filter.1.Value.1", id)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeTagsResponse.tagSet.item.size()", equalTo(0));
    }

    @Test
    @Order(16)
    void everyFlowLogOfOneRequestGetsTheTags() {
        String secondVpc = given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.91.0.0/16")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");

        List<String> ids = given()
            .formParam("Action", "CreateFlowLogs")
            .formParam("ResourceType", "VPC")
            .formParam("ResourceId.1", vpcId)
            .formParam("ResourceId.2", secondVpc)
            .formParam("TrafficType", "ALL")
            .formParam("LogDestinationType", "s3")
            .formParam("LogDestination", "arn:aws:s3:::flow-logs-bucket")
            .formParam("TagSpecifications.1.ResourceType", "vpc-flow-log")
            .formParam("TagSpecifications.1.Tag.1.Key", "Environment")
            .formParam("TagSpecifications.1.Tag.1.Value", "dev")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getList("CreateFlowLogsResponse.flowLogIdSet.item", String.class);

        assertEquals(2, ids.size());
        for (String id : ids) {
            given()
                .formParam("Action", "DescribeFlowLogs")
                .formParam("FlowLogId.1", id)
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeFlowLogsResponse.flowLogSet.item[0].tagSet.item.key", equalTo("Environment"));
        }
    }
}
