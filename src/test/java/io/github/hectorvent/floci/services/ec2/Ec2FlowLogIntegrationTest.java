package io.github.hectorvent.floci.services.ec2;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.startsWith;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Integration tests for VPC Flow Logs via the EC2 Query Protocol
 * (form-encoded POST, XML response).
 *
 * <p>Covers {@code CreateFlowLogs} / {@code DescribeFlowLogs} / {@code DeleteFlowLogs}, including
 * tags: inline {@code TagSpecification} on create (lex00/floci#78), and the incremental
 * {@code CreateTags} an already-existing flow log gets tagged with afterwards, the same shape
 * choudoufu's live-import stamp uses to write its markers onto an object it did not create.</p>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2FlowLogIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private static String vpcId;
    private static String flowLogId;
    private static String cloudwatchFlowLogId;

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
            .formParam("TagSpecification.1.ResourceType", "vpc-flow-log")
            .formParam("TagSpecification.1.Tag.1.Key", "Name")
            .formParam("TagSpecification.1.Tag.1.Value", "main-flow-log")
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
    void describeFlowLogsReturnsTheCreatedLogWithItsInlineTag() {
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
            .body("DescribeFlowLogsResponse.flowLogSet.item[0].maxAggregationInterval", equalTo("60"))
            .body("DescribeFlowLogsResponse.flowLogSet.item[0].tagSet.item.key", equalTo("Name"))
            .body("DescribeFlowLogsResponse.flowLogSet.item[0].tagSet.item.value", equalTo("main-flow-log"));
    }

    // Not what CreateFlowLogs writes: this is the write choudoufu's own live-import stamp makes
    // against a flow log it did not create — a plain ec2:CreateTags naming the flow log id, the
    // same call every other EC2 resource type in this file already round-trips through
    // Ec2Service#createTags. lex00/floci#78's own symptom ("the write reported no error, but the
    // object read back afterwards does not carry the new markers") is this call landing in the
    // side-store with nothing in DescribeFlowLogs ever reading it back — this is the case that
    // regresses if that read path is ever removed again.
    @Test
    @Order(12)
    void createTagsAfterCreateIsVisibleOnDescribe() {
        given()
            .formParam("Action", "CreateTags")
            .formParam("ResourceId.1", flowLogId)
            .formParam("Tag.1.Key", "tofu-estate")
            .formParam("Tag.1.Value", "xancloud-iac-crossing")
            .header("Authorization", AUTH_HEADER)
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
            .body("DescribeFlowLogsResponse.flowLogSet.item[0].tagSet.item.key",
                    hasItems("Name", "tofu-estate"))
            .body("DescribeFlowLogsResponse.flowLogSet.item[0].tagSet.item.value",
                    hasItems("main-flow-log", "xancloud-iac-crossing"));
    }

    @Test
    @Order(13)
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

    // lex00/floci#87: CreateFlowLogs/DescribeFlowLogs never carried DeliverLogsPermissionArn
    // (the IAM role ARN a CloudWatch-Logs-destination flow log delivers through), so
    // aws_flow_log.iam_role_arn always read back empty and a real tofu plan proposed
    // replacing the flow log forever, even right after a real apply that passed a real ARN.
    // Same shape as #78 (a CreateFlowLogs-family field silently dropped), a different field.
    @Test
    @Order(14)
    void createFlowLogsWithCloudWatchDestinationCarriesDeliverLogsPermissionArn() {
        cloudwatchFlowLogId = given()
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
    }

    @Test
    @Order(15)
    void describeFlowLogsReturnsTheDeliverLogsPermissionArn() {
        given()
            .formParam("Action", "DescribeFlowLogs")
            .formParam("FlowLogId.1", cloudwatchFlowLogId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("DescribeFlowLogsResponse.flowLogSet.item[0].flowLogId", equalTo(cloudwatchFlowLogId))
            .body("DescribeFlowLogsResponse.flowLogSet.item[0].logDestinationType", equalTo("cloud-watch-logs"))
            .body("DescribeFlowLogsResponse.flowLogSet.item[0].deliverLogsPermissionArn",
                    equalTo("arn:aws:iam::000000000000:role/flow-logs-role"));
    }

    @Test
    @Order(16)
    void deleteCloudwatchFlowLog() {
        given()
            .formParam("Action", "DeleteFlowLogs")
            .formParam("FlowLogId.1", cloudwatchFlowLogId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml");
    }

    @Test
    @Order(20)
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
}
