package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

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

    @Test
    void aCustomLogFormatSurvivesTheRoundTrip() {
        String vpcId = vpc();
        ec2("CreateFlowLogs",
                "ResourceId.1", vpcId,
                "ResourceType", "VPC",
                "TrafficType", "ALL",
                "LogDestinationType", "s3",
                "LogDestination", "arn:aws:s3:::flow-logs-bucket",
                "LogFormat", FORMAT)
            .statusCode(200);

        ec2("DescribeFlowLogs", "Filter.1.Name", "resource-id", "Filter.1.Value.1", vpcId)
            .statusCode(200)
            .body(containsString("<logFormat>"))
            .body(containsString("${srcaddr}"));
    }

    /** A flow log created without one reports no logFormat rather than an empty element. */
    @Test
    void noCustomFormatReportsNoElement() {
        String vpcId = vpc();
        ec2("CreateFlowLogs",
                "ResourceId.1", vpcId,
                "ResourceType", "VPC",
                "TrafficType", "ALL",
                "LogDestinationType", "s3",
                "LogDestination", "arn:aws:s3:::flow-logs-bucket")
            .statusCode(200);

        ec2("DescribeFlowLogs", "Filter.1.Name", "resource-id", "Filter.1.Value.1", vpcId)
            .statusCode(200)
            .body(not(containsString("<logFormat>")));
    }
}
