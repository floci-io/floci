package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.github.hectorvent.floci.testing.SignedIamUsers;
import io.github.hectorvent.floci.testutil.AwsRequestSigner;
import io.github.hectorvent.floci.testutil.S3RequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * Verifies that a CloudWatch Query GET request, signed in its headers or presigned, is
 * authenticated and then decided by IAM in the region it was signed for, and that an S3-signed
 * request to the root stays with S3.
 *
 * <p>Signature validation, IAM enforcement and S3 auth enforcement come from
 * {@link SignedIamEnforcementProfile}, which this class shares with the other classes that test
 * signed requests under IAM enforcement.
 */
@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class CloudWatchQueryGetSignatureIntegrationTest {

    @TestHTTPResource("/")
    URI endpoint;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void genuineSignaturesReachCloudWatchAndTamperingIsRefused(boolean presigned) throws Exception {
        AwsRequestSigner signer = AwsRequestSigner.signedAs("test", "test", "monitoring");
        Map<String, String> query = Map.of("Action", "ListMetrics", "Version", "2010-08-01");
        request(signer, query, presigned).then().statusCode(200).body(containsString("ListMetricsResponse"));
        request(signer.withSecret("wrong-secret"), query, presigned).then().statusCode(403)
                .contentType(containsString("xml"))
                .body("ErrorResponse.Error.Code", equalTo("SignatureDoesNotMatch"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void regionalWritesAndIamConditionsUseTheSigningRegion(boolean presigned) throws Exception {
        String alarm = "cw-query-" + UUID.randomUUID();
        AwsRequestSigner root = AwsRequestSigner.signedAs("test", "test", "monitoring").inRegion("us-west-2");
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "cw-query-", "regional-cloudwatch", """
                {"Version":"2012-10-17","Statement":[{
                  "Effect":"Allow","Action":["cloudwatch:PutMetricAlarm","cloudwatch:DescribeAlarms"],
                  "Resource":"*","Condition":{"StringEquals":{
                    "aws:RequestedRegion":"us-west-2","aws:PrincipalAccount":"000000000000"}}
                }]}
                """)) {
            AwsRequestSigner signer = user.signer("monitoring").inRegion("us-west-2");
            try {
                request(signer, Map.of("Action", "PutMetricAlarm", "Version", "2010-08-01", "AlarmName", alarm,
                        "Namespace", "Example/QueryGet", "MetricName", "Requests", "Statistic", "Sum", "Period", "60",
                        "EvaluationPeriods", "1", "Threshold", "1", "ComparisonOperator", "GreaterThanThreshold"),
                        presigned).then().statusCode(200);
                Map<String, String> describe = Map.of("Action", "DescribeAlarms", "Version", "2010-08-01",
                        "AlarmNames.member.1", alarm);
                request(signer, describe, presigned).then().statusCode(200)
                        .body(containsString("arn:aws:cloudwatch:us-west-2:000000000000:alarm:" + alarm));
                request(signer.inRegion("us-east-1"), describe, presigned).then().statusCode(403)
                        .body("ErrorResponse.Error.Code", equalTo("AccessDenied"));
                request(root.inRegion("us-east-1"), describe, presigned).then().statusCode(200)
                        .body(not(containsString(alarm)));
                request(signer, Map.of("Action", "DeleteAlarms", "Version", "2010-08-01",
                        "AlarmNames.member.1", alarm), presigned).then().statusCode(403)
                        .body("ErrorResponse.Error.Code", equalTo("AccessDenied"));
                request(signer, describe, presigned).then().statusCode(200).body(containsString(alarm));
            } finally {
                request(root, Map.of("Action", "DeleteAlarms", "Version", "2010-08-01",
                        "AlarmNames.member.1", alarm), false).then().statusCode(200);
            }
        }
    }

    @Test
    void signedS3ControlRequestStaysWithS3() throws Exception {
        given().filter(S3RequestSigner.signedAs("test", "test")).queryParam("Action", "ListMetrics")
                .get("/").then().statusCode(200).body(containsString("ListAllMyBucketsResult"));
    }

    private Response request(AwsRequestSigner signer, Map<String, String> query, boolean presigned) throws Exception {
        if (presigned) {
            return given().urlEncodingEnabled(false).get(signer.presignedUri("GET", endpoint, query, new byte[0]));
        }
        URI uri = URI.create(endpoint + "?" + encode(query));
        return given().urlEncodingEnabled(false).headers(signer.headersFor("GET", uri, Map.of(), new byte[0])).get(uri);
    }

    private static String encode(Map<String, String> parameters) {
        return parameters.entrySet().stream().map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
    }
}
