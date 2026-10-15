package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.config;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class CloudWatchQueryGetIamEnforcementIntegrationTest {

    @TestHTTPResource("/")
    static URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @ValueSource(strings = {"Action", "Operation"})
    void unsignedWritesReturnQueryAuthenticationErrors(String actionParameter) {
        given()
                .queryParam(actionParameter, "PutMetricData")
                .queryParam("Version", "2010-08-01")
                .queryParam("Namespace", "query-auth-" + UUID.randomUUID())
                .queryParam("MetricData.member.1.MetricName", "Denied")
                .queryParam("MetricData.member.1.Value", "1")
        .when().get("/")
        .then()
                .statusCode(403)
                .contentType(containsString("xml"))
                .body("ErrorResponse.Error.Code", equalTo("MissingAuthenticationToken"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"rpc-v2-cbor", "unknown"})
    void anUnclaimedGetCannotDeleteAnAlarm(String smithyProtocol) {
        String alarm = "query-unclaimed-" + UUID.randomUUID();
        String root = auth("000000000000", "monitoring");
        given().header("Authorization", root).contentType("application/x-www-form-urlencoded")
                .formParam("Action", "PutMetricAlarm").formParam("Version", "2010-08-01")
                .formParam("AlarmName", alarm).formParam("Namespace", "Example/QueryGet")
                .formParam("MetricName", "Requests").formParam("Statistic", "Sum").formParam("Period", "60")
                .formParam("EvaluationPeriods", "1").formParam("Threshold", "1")
                .formParam("ComparisonOperator", "GreaterThanThreshold")
                .post("/").then().statusCode(200);
        cleanup.register(() -> given().port(baseUri.getPort()).header("Authorization", root)
                .contentType("application/x-www-form-urlencoded").formParam("Action", "DeleteAlarms")
                .formParam("AlarmNames.member.1", alarm).post("/").then().statusCode(200));
        given().header("Smithy-Protocol", smithyProtocol).queryParam("Action", "DeleteAlarms")
                .queryParam("Version", "2010-08-01").queryParam("AlarmNames.member.1", alarm)
                .get("/").then().body(not(containsString("DeleteAlarmsResponse")));
        given().header("Authorization", root).queryParam("Action", "DescribeAlarms")
                .queryParam("Version", "2010-08-01").queryParam("AlarmNames.member.1", alarm)
                .get("/").then().statusCode(200).body(containsString(alarm));
    }

    @ParameterizedTest
    @ValueSource(strings = {"rpc-v2-cbor", "unknown"})
    void anUnclaimedGetCannotPublishMetrics(String smithyProtocol) {
        String namespace = "query-unclaimed-" + UUID.randomUUID();
        given().header("Smithy-Protocol", smithyProtocol).queryParam("Action", "PutMetricData")
                .queryParam("Version", "2010-08-01").queryParam("Namespace", namespace)
                .queryParam("MetricData.member.1.MetricName", "Denied").queryParam("MetricData.member.1.Value", "1")
                .get("/").then().body(not(containsString("PutMetricDataResponse")));
        given().header("Authorization", auth("000000000000", "monitoring"))
                .queryParam("Action", "ListMetrics").queryParam("Version", "2010-08-01")
                .queryParam("Namespace", namespace).get("/").then().statusCode(200)
                .body(not(containsString(namespace)));
    }

    @Test
    void internalRouteCannotBeCalledDirectly() {
        given().header("Authorization", auth("000000000000", "monitoring"))
                .queryParam("Action", "ListMetrics").get("/_cloudwatch-query")
                .then().statusCode(404);
    }

    @ParameterizedTest
    @CsvSource({"Action,false", "Operation,false", "Action,true", "Operation,true"})
    void deniedSignedReadsReturnQueryAuthorizationErrors(String actionParameter, boolean presigned) {
        String user = "query-denied-" + UUID.randomUUID();
        String accessKey = EnforcementFixtures.createUser(
                cleanup, baseUri.getPort(), auth("000000000000", "iam"), user).accessKeyId();

        signedRequest(accessKey, presigned)
                .queryParam(actionParameter, "ListMetrics")
                .queryParam("Version", "2010-08-01")
        .when().get("/")
        .then()
                .statusCode(403)
                .contentType(containsString("xml"))
                .body("ErrorResponse.Error.Code", equalTo("AccessDenied"));
    }

    @ParameterizedTest
    @CsvSource({"GET,Action", "GET,Operation", "PRESIGNED,Action", "PRESIGNED,Operation",
            "POST,Action", "JSON,Action"})
    void anAllowedSignedReadStillReachesCloudWatch(String protocol, String actionParameter) {
        String user = "query-allowed-" + UUID.randomUUID();
        String admin = auth("000000000000", "iam");
        String accessKey = EnforcementFixtures.createUser(
                cleanup, baseUri.getPort(), admin, user).accessKeyId();
        given()
                .header("Authorization", admin)
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "PutUserPolicy")
                .formParam("Version", "2010-05-08")
                .formParam("UserName", user)
                .formParam("PolicyName", "list-metrics")
                .formParam("PolicyDocument", """
                        {"Version":"2012-10-17","Statement":[{
                          "Effect":"Allow","Action":"cloudwatch:ListMetrics","Resource":"*"
                        }]}
                        """)
        .when().post("/")
        .then().statusCode(200);

        RequestSpecification request = signedRequest(accessKey, "PRESIGNED".equals(protocol));
        Response response = switch (protocol) {
            case "JSON" -> request.contentType("application/x-amz-json-1.0")
                    .config(config().encoderConfig(EncoderConfig.encoderConfig()
                            .encodeContentTypeAs("application/x-amz-json-1.0", ContentType.JSON)))
                    .header("X-Amz-Target", "GraniteServiceVersion20100801.ListMetrics").body("{}").post("/");
            case "POST" -> request.contentType("application/x-www-form-urlencoded")
                    .formParam(actionParameter, "ListMetrics").formParam("Version", "2010-08-01").post("/");
            default -> request.queryParam(actionParameter, "ListMetrics")
                    .queryParam("Version", "2010-08-01").get("/");
        };
        response.then().statusCode(200);
        if ("JSON".equals(protocol)) {
            response.then().contentType(containsString("json"));
        } else {
            response.then().contentType(containsString("xml")).body(containsString("ListMetricsResponse"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void userPolicyAndAlarmStorageUseTheCredentialAccount(boolean presigned) {
        String account = "111122223333";
        String admin = auth(account, "iam");
        String user = "query-account-" + UUID.randomUUID();
        String alarm = "query-account-" + UUID.randomUUID();
        String key = EnforcementFixtures.createUser(cleanup, baseUri.getPort(), admin, user).accessKeyId();
        given().header("Authorization", admin).contentType("application/x-www-form-urlencoded")
                .formParam("Action", "PutUserPolicy").formParam("UserName", user).formParam("PolicyName", "cloudwatch")
                .formParam("PolicyDocument", """
                        {"Version":"2012-10-17","Statement":[{
                          "Effect":"Allow","Action":"cloudwatch:*","Resource":"*",
                          "Condition":{"StringEquals":{"aws:PrincipalAccount":"111122223333"}}
                        }]}
                        """).post("/").then().statusCode(200);
        cleanup.register(() -> given().port(baseUri.getPort()).header("Authorization", auth(account, "monitoring"))
                .contentType("application/x-www-form-urlencoded").formParam("Action", "DeleteAlarms")
                .formParam("AlarmNames.member.1", alarm).post("/").then().statusCode(200));
        signedRequest(key, presigned).queryParam("Action", "PutMetricAlarm").queryParam("Version", "2010-08-01")
                .queryParam("AlarmName", alarm).queryParam("Namespace", "Example/QueryGet")
                .queryParam("MetricName", "Requests").queryParam("Statistic", "Sum").queryParam("Period", "60")
                .queryParam("EvaluationPeriods", "1").queryParam("Threshold", "1")
                .queryParam("ComparisonOperator", "GreaterThanThreshold")
                .get("/").then().statusCode(200);
        signedRequest(key, presigned).queryParam("Action", "DescribeAlarms").queryParam("Version", "2010-08-01")
                .queryParam("AlarmNames.member.1", alarm).get("/").then().statusCode(200)
                .body(containsString("arn:aws:cloudwatch:us-east-1:" + account + ":alarm:" + alarm));
        signedRequest("000000000000", presigned).queryParam("Action", "DescribeAlarms")
                .queryParam("Version", "2010-08-01").queryParam("AlarmNames.member.1", alarm)
                .get("/").then().statusCode(200).body(not(containsString(alarm)));
    }

    private static RequestSpecification signedRequest(String accessKey, boolean presigned) {
        return presigned
                ? given().queryParam("X-Amz-Algorithm", "AWS4-HMAC-SHA256")
                        .queryParam("X-Amz-Credential", accessKey + "/20261013/us-east-1/monitoring/aws4_request")
                : given().header("Authorization", auth(accessKey, "monitoring"));
    }
}
