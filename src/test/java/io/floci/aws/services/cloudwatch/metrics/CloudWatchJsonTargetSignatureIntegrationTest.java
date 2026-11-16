package io.floci.aws.services.cloudwatch.metrics;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.floci.aws.testing.SignedIamEnforcementProfile;
import io.floci.aws.testing.SignedIamUsers;
import io.floci.aws.testutil.AwsRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * A CloudWatch JSON request is authenticated and then decided by IAM the same way whichever target
 * prefix names its operation: the console API's {@code CloudWatchVersion20130116.} prefix gets the
 * header and presigned signature checks, the unsigned-call refusal and the {@code cloudwatch:}
 * action of the operation it names, exactly as the SDKs' {@code GraniteServiceVersion20100801.}
 * prefix does.
 */
@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class CloudWatchJsonTargetSignatureIntegrationTest {

    private static final String CURRENT_TARGET = "GraniteServiceVersion20100801.";
    private static final String CONSOLE_TARGET = "CloudWatchVersion20130116.";

    @TestHTTPResource("/")
    URI endpoint;

    @BeforeAll
    static void configureAwsJson() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(strings = {CURRENT_TARGET, CONSOLE_TARGET})
    void genuineSignaturesAreServedAndTamperingIsRefused(String prefix) {
        AwsRequestSigner root = AwsRequestSigner.signedAs("test", "test", "monitoring");
        json(prefix, "ListMetrics").filter(root).post("/")
                .then().statusCode(200).body("Metrics", notNullValue());
        json(prefix, "ListMetrics").filter(root.withSecret("wrong-secret")).post("/")
                .then().statusCode(403).body("__type", equalTo("InvalidSignatureException"));
    }

    @ParameterizedTest
    @ValueSource(strings = {CURRENT_TARGET, CONSOLE_TARGET})
    void presignedRequestsAreServedAndWrongSecretsAreRefused(String prefix) throws Exception {
        AwsRequestSigner root = AwsRequestSigner.signedAs("test", "test", "monitoring");
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        json(prefix, "ListMetrics").urlEncodingEnabled(false)
                .post(root.presignedUri("POST", endpoint, Map.of(), body))
                .then().statusCode(200).body("Metrics", notNullValue());
        json(prefix, "ListMetrics").urlEncodingEnabled(false)
                .post(root.withSecret("wrong-secret").presignedUri("POST", endpoint, Map.of(), body))
                .then().statusCode(403).body("__type", equalTo("InvalidSignatureException"));
    }

    @ParameterizedTest
    @ValueSource(strings = {CURRENT_TARGET, CONSOLE_TARGET})
    void unsignedRequestsAreRefused(String prefix) {
        json(prefix, "ListMetrics").post("/")
                .then().statusCode(403).body("__type", equalTo("MissingAuthenticationTokenException"));
    }

    @ParameterizedTest
    @ValueSource(strings = {CURRENT_TARGET, CONSOLE_TARGET})
    void iamDecidesEachOperationByItsCloudWatchAction(String prefix) throws Exception {
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "cw-json-target-", "list-metrics", """
                {"Version":"2012-10-17","Statement":[{
                  "Effect":"Allow","Action":"cloudwatch:ListMetrics","Resource":"*"
                }]}
                """)) {
            AwsRequestSigner signer = user.signer("monitoring");

            json(prefix, "ListMetrics").filter(signer).post("/")
                    .then().statusCode(200).body("Metrics", notNullValue());
            json(prefix, "DescribeAlarms").filter(signer).post("/")
                    .then().statusCode(400).body("__type", equalTo("AccessDeniedException"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {CURRENT_TARGET, CONSOLE_TARGET})
    void putMetricDataChecksSignaturesAndPermissionsBeforeStoringMetrics(String prefix) throws Exception {
        String namespace = "cw-put-permissions-" + Long.toString(System.nanoTime(), 36);
        String body = """
                {"Namespace":"%s","MetricData":[{"MetricName":"Requests","Value":7}]}
                """.formatted(namespace);
        AwsRequestSigner root = AwsRequestSigner.signedAs("test", "test", "monitoring");
        json(prefix, "PutMetricData").body(body).post("/")
                .then().statusCode(403).body("__type", equalTo("MissingAuthenticationTokenException"));
        json(prefix, "PutMetricData").body(body).filter(root.withSecret("wrong-secret")).post("/")
                .then().statusCode(403).body("__type", equalTo("InvalidSignatureException"));

        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "cw-put-permissions-", "list-only", """
                {"Version":"2012-10-17","Statement":[{
                  "Effect":"Allow","Action":"cloudwatch:ListMetrics","Resource":"*"
                }]}
                """)) {
            json(prefix, "PutMetricData").body(body).filter(user.signer("monitoring")).post("/")
                    .then().statusCode(400).body("__type", equalTo("AccessDeniedException"));
        }
        json(CURRENT_TARGET, "ListMetrics").body("{\"Namespace\":\"" + namespace + "\"}")
                .filter(root).post("/").then().statusCode(200).body("Metrics", empty());

        json(prefix, "PutMetricData").body(body).filter(root).post("/")
                .then().statusCode(200);
        json(CURRENT_TARGET, "ListMetrics").body("{\"Namespace\":\"" + namespace + "\"}")
                .filter(root).post("/").then().statusCode(200)
                .body("Metrics[0].MetricName", equalTo("Requests"));
    }

    @Test
    void alarmActionPermissionsAreCheckedBeforeChangingTheAlarm() throws Exception {
        String name = "cw-action-permissions-" + Long.toString(System.nanoTime(), 36);
        AwsRequestSigner root = AwsRequestSigner.signedAs("test", "test", "monitoring");
        json(CURRENT_TARGET, "PutCompositeAlarm").filter(root)
                .body("""
                        {
                          "AlarmName": "%s",
                          "AlarmRule": "FALSE",
                          "ActionsEnabled": false
                        }
                        """.formatted(name))
                .post("/").then().statusCode(200);
        String selected = """
                {
                  "AlarmNames": ["%s"]
                }
                """.formatted(name);
        String describe = """
                {
                  "AlarmNames": ["%s"],
                  "AlarmTypes": ["CompositeAlarm"]
                }
                """.formatted(name);
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "cw-action-permissions-", "enable-only", """
                {
                  "Version": "2012-10-17",
                  "Statement": [
                    {
                      "Effect": "Allow",
                      "Action": "cloudwatch:EnableAlarmActions",
                      "Resource": "*"
                    }
                  ]
                }
                """)) {
            AwsRequestSigner signer = user.signer("monitoring");
            json(CURRENT_TARGET, "EnableAlarmActions").filter(signer).body(selected).post("/")
                    .then().statusCode(200);
            json(CURRENT_TARGET, "DisableAlarmActions").filter(signer).body(selected).post("/")
                    .then().statusCode(400).body("__type", equalTo("AccessDeniedException"));
            json(CURRENT_TARGET, "DescribeAlarms").filter(root).body(describe).post("/")
                    .then().statusCode(200).body("CompositeAlarms[0].ActionsEnabled", equalTo(true));
        } finally {
            json(CURRENT_TARGET, "DeleteAlarms").filter(root).body(selected).post("/")
                    .then().statusCode(200);
        }
    }

    private static RequestSpecification json(String prefix, String operation) {
        return given().contentType("application/x-amz-json-1.0")
                .header("X-Amz-Target", prefix + operation)
                .body("{}");
    }
}
