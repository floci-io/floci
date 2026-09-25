package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * DescribeFlowLogs reports the custom LogFormat the flow log was created with.
 *
 * <p>CreateFlowLogs parsed LogFormat and stored it, and the describe never emitted it. A client
 * comparing its declared format against the live flow log therefore saw the field as unset every
 * time and proposed the same change on every plan.
 */
@QuarkusTest
class Ec2FlowLogFormatIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260908/us-east-1/ec2/aws4_request";
    private static final String FORMAT =
            "${version} ${account-id} ${interface-id} ${srcaddr} ${dstaddr} ${action}";

    private static ValidatableResponse ec2(String action, String... formParams) {
        RequestSpecification request = given().header("Authorization", AUTH)
                .formParam("Action", action);
        for (int i = 0; i < formParams.length; i += 2) {
            request = request.formParam(formParams[i], formParams[i + 1]);
        }
        return request.when().post("/").then();
    }

    private static String vpc() {
        return ec2("CreateVpc", "CidrBlock", "10.71.0.0/16")
            .statusCode(200).extract().path("CreateVpcResponse.vpc.vpcId");
    }

    /** DescribeFlowLogs serves no filters, so every assertion here names the flow log by id. */
    private static String createFlowLog(String vpcId, String... extraParams) {
        String[] params = {
            "ResourceId.1", vpcId,
            "ResourceType", "VPC",
            "TrafficType", "ALL",
            "LogDestinationType", "s3",
            "LogDestination", "arn:aws:s3:::flow-logs-bucket"
        };
        String[] all = new String[params.length + extraParams.length];
        System.arraycopy(params, 0, all, 0, params.length);
        System.arraycopy(extraParams, 0, all, params.length, extraParams.length);
        return ec2("CreateFlowLogs", all)
            .statusCode(200).extract().path("CreateFlowLogsResponse.flowLogIdSet.item");
    }

    @Test
    void aCustomLogFormatSurvivesTheRoundTrip() {
        String flowLogId = createFlowLog(vpc(), "LogFormat", FORMAT);

        ec2("DescribeFlowLogs", "FlowLogId.1", flowLogId)
            .statusCode(200)
            .body("DescribeFlowLogsResponse.flowLogSet.item.flowLogId", equalTo(flowLogId))
            .body("DescribeFlowLogsResponse.flowLogSet.item.logFormat", equalTo(FORMAT));
    }

    /**
     * A flow log created without a LogFormat reports the default one.
     *
     * <p>The model says CreateFlowLogs builds it with the default format when the parameter is
     * omitted, so the flow log has a format from that point on and DescribeFlowLogs reports it.
     */
    @Test
    void noCustomFormatReportsTheDefault() {
        String flowLogId = createFlowLog(vpc());

        ec2("DescribeFlowLogs", "FlowLogId.1", flowLogId)
            .statusCode(200)
            .body("DescribeFlowLogsResponse.flowLogSet.item.flowLogId", equalTo(flowLogId))
            .body("DescribeFlowLogsResponse.flowLogSet.item.logFormat",
                    equalTo(FlowLogService.DEFAULT_LOG_FORMAT));
    }
}
