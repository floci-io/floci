package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * TagResource and UntagResource have an empty output structure in the CloudWatch API model,
 * so their Query responses still carry the Result wrapper element. The AWS Go SDK v2
 * unmarshaler behind the Terraform AWS provider fails on a response without it.
 */
@QuarkusTest
class CloudWatchMetricsTagResourceIntegrationTest {

    private static final String CW_SCOPE =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/monitoring/aws4_request";
    private static final String ALARM_NAME = "tag-wrapper-alarm";
    private static final String ALARM_ARN =
            "arn:aws:cloudwatch:us-east-1:000000000000:alarm:" + ALARM_NAME;

    @Test
    void tagAndUntagResponsesCarryTheirResultElements() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CW_SCOPE)
            .formParam("Action", "PutMetricAlarm")
            .formParam("AlarmName", ALARM_NAME)
            .formParam("MetricName", "CPUUtilization")
            .formParam("Namespace", "AWS/EC2")
            .formParam("ComparisonOperator", "GreaterThanThreshold")
            .formParam("EvaluationPeriods", "1")
            .formParam("Period", "60")
            .formParam("Statistic", "Average")
            .formParam("Threshold", "80")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CW_SCOPE)
            .formParam("Action", "TagResource")
            .formParam("ResourceARN", ALARM_ARN)
            .formParam("Tags.member.1.Key", "env")
            .formParam("Tags.member.1.Value", "prod")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body(containsString("<TagResourceResult"))
            .body("TagResourceResponse.ResponseMetadata.RequestId", not(equalTo("")));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CW_SCOPE)
            .formParam("Action", "ListTagsForResource")
            .formParam("ResourceARN", ALARM_ARN)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ListTagsForResourceResponse.ListTagsForResourceResult.Tags.member.Key", equalTo("env"))
            .body("ListTagsForResourceResponse.ListTagsForResourceResult.Tags.member.Value", equalTo("prod"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CW_SCOPE)
            .formParam("Action", "UntagResource")
            .formParam("ResourceARN", ALARM_ARN)
            .formParam("TagKeys.member.1", "env")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body(containsString("<UntagResourceResult"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CW_SCOPE)
            .formParam("Action", "ListTagsForResource")
            .formParam("ResourceARN", ALARM_ARN)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("<Key>env</Key>")));
    }
}
