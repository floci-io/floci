package io.github.hectorvent.floci.services.cloudwatch;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * Tagging a CloudWatch resource whose ARN names nothing has to fail, on every one of the three
 * tag operations and for every taggable kind CloudWatch owns.
 *
 * <p>Each kind is checked twice: once on an ARN that resolves, so the operation is known to work
 * at all, and once on the same ARN with the resource name swapped for one that was never created.
 * The two ARNs are byte-identical in shape, so "the ARN was malformed" cannot explain the second
 * answer. Without that pairing a test asserting only the failure would also pass against a build
 * whose tag operations were broken outright.
 *
 * <p>The failure to look for is {@code ResourceNotFoundException}. CloudWatch's model declares
 * that code, not the shorter {@code ResourceNotFound} that {@code GetDashboard} and
 * {@code SetAlarmState} answer with, for {@code TagResource}, {@code UntagResource} and
 * {@code ListTagsForResource}; the SDK maps the two to different exception classes, so a caller
 * catching the documented one would not see the other.
 */
@QuarkusTest
class CloudWatchTagUnknownResourceIntegrationTest {

    private static final String CW_SCOPE =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/monitoring/aws4_request";
    private static final String LOGS_SCOPE =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/logs/aws4_request";

    private static final String JSON_1_0 = "application/x-amz-json-1.0";
    private static final String JSON_1_1 = "application/x-amz-json-1.1";

    private static final String PREFIX = "arn:aws:cloudwatch:us-east-1:000000000000:";
    private static final String FIREHOSE_ARN =
            "arn:aws:firehose:us-east-1:000000000000:deliverystream/metrics";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/metric-stream-role";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static RequestSpecification query(String action) {
        return given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CW_SCOPE)
                .formParam("Action", action);
    }

    private static RequestSpecification json(String contentType, String scope, String target, String body) {
        return given()
                .contentType(contentType)
                .header("Authorization", scope)
                .header("X-Amz-Target", target)
                .body(body);
    }

    private static RequestSpecification metricsJson(String target, String body) {
        return json(JSON_1_0, CW_SCOPE, "GraniteServiceVersion20100801." + target, body);
    }

    private static RequestSpecification logsJson(String target, String body) {
        return json(JSON_1_1, LOGS_SCOPE, "Logs_20140328." + target, body);
    }

    /** The three Query tag operations against one ARN, asserting the same outcome for each. */
    private static void assertQueryTagOpsNotFound(String arn) {
        query("TagResource")
                .formParam("ResourceARN", arn)
                .formParam("Tags.member.1.Key", "env")
                .formParam("Tags.member.1.Value", "prod")
            .when()
                .post("/")
            .then()
                .statusCode(404)
                .body("ErrorResponse.Error.Code", equalTo("ResourceNotFoundException"));

        query("UntagResource")
                .formParam("ResourceARN", arn)
                .formParam("TagKeys.member.1", "env")
            .when()
                .post("/")
            .then()
                .statusCode(404)
                .body("ErrorResponse.Error.Code", equalTo("ResourceNotFoundException"));

        query("ListTagsForResource")
                .formParam("ResourceARN", arn)
            .when()
                .post("/")
            .then()
                .statusCode(404)
                .body("ErrorResponse.Error.Code", equalTo("ResourceNotFoundException"));
    }

    /** The same three operations against an ARN that does resolve, read back through a list. */
    private static void assertQueryTagOpsSucceed(String arn) {
        query("TagResource")
                .formParam("ResourceARN", arn)
                .formParam("Tags.member.1.Key", "env")
                .formParam("Tags.member.1.Value", "prod")
            .when()
                .post("/")
            .then()
                .statusCode(200);

        query("ListTagsForResource")
                .formParam("ResourceARN", arn)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("ListTagsForResourceResponse.ListTagsForResourceResult.Tags.member.Key",
                        equalTo("env"))
                .body("ListTagsForResourceResponse.ListTagsForResourceResult.Tags.member.Value",
                        equalTo("prod"));

        query("UntagResource")
                .formParam("ResourceARN", arn)
                .formParam("TagKeys.member.1", "env")
            .when()
                .post("/")
            .then()
                .statusCode(200);

        query("ListTagsForResource")
                .formParam("ResourceARN", arn)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(not(containsString("<Key>env</Key>")));
    }

    @Test
    void alarmTagOperationsRejectAnArnThatNamesNoAlarm() {
        query("PutMetricAlarm")
                .formParam("AlarmName", "tag-unknown-alarm")
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

        assertQueryTagOpsSucceed(PREFIX + "alarm:tag-unknown-alarm");
        assertQueryTagOpsNotFound(PREFIX + "alarm:tag-unknown-alarm-nosuch");
    }

    @Test
    void dashboardTagOperationsRejectAnArnThatNamesNoDashboard() {
        query("PutDashboard")
                .formParam("DashboardName", "tag-unknown-dashboard")
                .formParam("DashboardBody", "{\"widgets\":[]}")
            .when()
                .post("/")
            .then()
                .statusCode(200);

        assertQueryTagOpsSucceed(PREFIX + "dashboard/tag-unknown-dashboard");
        assertQueryTagOpsNotFound(PREFIX + "dashboard/tag-unknown-dashboard-nosuch");
    }

    @Test
    void metricStreamTagOperationsRejectAnArnThatNamesNoStream() {
        query("PutMetricStream")
                .formParam("Name", "tag-unknown-stream")
                .formParam("FirehoseArn", FIREHOSE_ARN)
                .formParam("RoleArn", ROLE_ARN)
                .formParam("OutputFormat", "json")
                .formParam("IncludeFilters.member.1.Namespace", "AWS/EC2")
            .when()
                .post("/")
            .then()
                .statusCode(200);

        assertQueryTagOpsSucceed(PREFIX + "metric-stream/tag-unknown-stream");
        assertQueryTagOpsNotFound(PREFIX + "metric-stream/tag-unknown-stream-nosuch");
    }

    /**
     * The dashboard ARN AWS documents carries no region, and the one Floci mints does. A
     * caller sending the documented form for a dashboard that exists must not be told the
     * dashboard does not exist, which is what raising on a miss would otherwise have made of
     * a shape that used to fall through to an empty tag map.
     */
    @Test
    void theRegionlessDashboardArnAwsDocumentsAlsoResolves() {
        query("PutDashboard")
                .formParam("DashboardName", "tag-regionless-dashboard")
                .formParam("DashboardBody", "{\"widgets\":[]}")
            .when()
                .post("/")
            .then()
                .statusCode(200);

        assertQueryTagOpsSucceed("arn:aws:cloudwatch::000000000000:dashboard/tag-regionless-dashboard");

        // Still only an absent region: another region's ARN remains a miss.
        assertQueryTagOpsNotFound(
                "arn:aws:cloudwatch:eu-west-1:000000000000:dashboard/tag-regionless-dashboard");
    }

    /**
     * Both handlers route every ARN that is not a dashboard and not a metric stream to the
     * alarm path, so a Contributor Insights ARN, which AWS documents as taggable and Floci
     * does not serve, lands there. Reporting it as not found is right; calling it an alarm
     * that was not found is not, so the message names the ARN instead.
     */
    @Test
    void anArnOfAKindFlociDoesNotServeIsReportedAgainstTheArn() {
        query("TagResource")
                .formParam("ResourceARN", PREFIX + "insight-rule/no-such-rule")
                .formParam("Tags.member.1.Key", "env")
                .formParam("Tags.member.1.Value", "prod")
            .when()
                .post("/")
            .then()
                .statusCode(404)
                .body("ErrorResponse.Error.Code", equalTo("ResourceNotFoundException"))
                .body("ErrorResponse.Error.Message",
                        containsString("insight-rule/no-such-rule"))
                .body("ErrorResponse.Error.Message",
                        not(containsString("Alarm")));
    }

    /**
     * CloudWatch Metrics serves the same three operations over JSON as well as Query, and
     * AGENTS.md requires the two handlers not to drift. A fix applied to only one of them
     * would leave the CLI and SDK v3 route still answering 200.
     */
    @Test
    void theJsonProtocolRejectsTheSameArns() {
        metricsJson("PutMetricAlarm", """
                {"AlarmName": "tag-unknown-json-alarm", "MetricName": "CPUUtilization",
                 "Namespace": "AWS/EC2", "ComparisonOperator": "GreaterThanThreshold",
                 "EvaluationPeriods": 1, "Period": 60, "Statistic": "Average", "Threshold": 80}""")
            .when()
                .post("/")
            .then()
                .statusCode(200);

        String real = PREFIX + "alarm:tag-unknown-json-alarm";
        String ghost = PREFIX + "alarm:tag-unknown-json-alarm-nosuch";

        metricsJson("TagResource",
                "{\"ResourceARN\": \"" + real + "\", \"Tags\": [{\"Key\": \"env\", \"Value\": \"prod\"}]}")
            .when()
                .post("/")
            .then()
                .statusCode(200);

        metricsJson("ListTagsForResource", "{\"ResourceARN\": \"" + real + "\"}")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("Tags[0].Key", equalTo("env"))
                .body("Tags[0].Value", equalTo("prod"));

        metricsJson("TagResource",
                "{\"ResourceARN\": \"" + ghost + "\", \"Tags\": [{\"Key\": \"env\", \"Value\": \"prod\"}]}")
            .when()
                .post("/")
            .then()
                .statusCode(404)
                .body("__type", containsString("ResourceNotFoundException"));

        metricsJson("UntagResource", "{\"ResourceARN\": \"" + ghost + "\", \"TagKeys\": [\"env\"]}")
            .when()
                .post("/")
            .then()
                .statusCode(404)
                .body("__type", containsString("ResourceNotFoundException"));

        metricsJson("ListTagsForResource", "{\"ResourceARN\": \"" + ghost + "\"}")
            .when()
                .post("/")
            .then()
                .statusCode(404)
                .body("__type", containsString("ResourceNotFoundException"));
    }

    /**
     * CloudWatch Logs serves its own TagResource, UntagResource and ListTagsForResource over
     * a separate handler, and already rejects an ARN that names no log group. Pinning it keeps
     * the two halves of CloudWatch's tagging from diverging again in the other direction.
     */
    @Test
    void logGroupTagOperationsRejectAnArnThatNamesNoGroup() {
        String real = "arn:aws:logs:us-east-1:000000000000:log-group:tag-unknown-group:*";
        String ghost = "arn:aws:logs:us-east-1:000000000000:log-group:tag-unknown-group-nosuch:*";

        logsJson("CreateLogGroup", "{\"logGroupName\": \"tag-unknown-group\"}")
            .when()
                .post("/")
            .then()
                .statusCode(200);

        logsJson("TagResource",
                "{\"resourceArn\": \"" + real + "\", \"tags\": {\"env\": \"prod\"}}")
            .when()
                .post("/")
            .then()
                .statusCode(200);

        logsJson("ListTagsForResource", "{\"resourceArn\": \"" + real + "\"}")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("tags.env", equalTo("prod"));

        logsJson("TagResource",
                "{\"resourceArn\": \"" + ghost + "\", \"tags\": {\"env\": \"prod\"}}")
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("__type", containsString("ResourceNotFoundException"));

        logsJson("UntagResource", "{\"resourceArn\": \"" + ghost + "\", \"tagKeys\": [\"env\"]}")
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("__type", containsString("ResourceNotFoundException"));

        logsJson("ListTagsForResource", "{\"resourceArn\": \"" + ghost + "\"}")
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("__type", containsString("ResourceNotFoundException"));
    }
}
