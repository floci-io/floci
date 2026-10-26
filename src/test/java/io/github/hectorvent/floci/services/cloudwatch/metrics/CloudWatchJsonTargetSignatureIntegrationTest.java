package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.github.hectorvent.floci.testing.SignedIamUsers;
import io.github.hectorvent.floci.testutil.AwsRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static io.restassured.RestAssured.given;
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

    private static RequestSpecification json(String prefix, String operation) {
        return given().contentType("application/x-amz-json-1.0")
                .header("X-Amz-Target", prefix + operation)
                .body("{}");
    }
}
