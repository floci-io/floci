package io.github.hectorvent.floci.services.ssm;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * Parameter Store authorizes GetParameters against "each requested parameter", DeleteParameters the
 * same way, and GetParametersByPath against "the path specified in the request, not the parameters
 * returned", as the Systems Manager user guide's "How Parameter Store authorizes parameter retrieval"
 * describes. A statement naming one parameter or path has to bind to it.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class SsmParameterResourceIamEnforcementIntegrationTest {

    private static final String ACCOUNT_ID = "000000000000";
    private static final String PARAMETER_ARN = "arn:aws:ssm:us-east-1:" + ACCOUNT_ID + ":parameter";

    @TestHTTPResource("/")
    URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getParametersIsRefusedByADenyOnAnyParameterItRequests() {
        String root = "/" + unique();
        putParameter(root + "/public");
        putParameter(root + "/secret");
        String caller = userWithPolicy("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"ssm:*","Resource":"*"},
                  {"Effect":"Deny","Action":"ssm:GetParameters","Resource":"%s%s/secret"}
                ]}""".formatted(PARAMETER_ARN, root));

        assertAccessDenied(ssm(caller, "GetParameters", names(root + "/secret")), "ssm:GetParameters");
        assertAccessDenied(ssm(caller, "GetParameters", names(root + "/public", root + "/secret")),
                "ssm:GetParameters");
        ssm(caller, "GetParameters", names(root + "/public"))
                .statusCode(200)
                .body("Parameters[0].Name", equalTo(root + "/public"));
    }

    @Test
    void getParametersNeedsAnAllowOnEveryParameterItRequests() {
        String root = "/" + unique();
        putParameter(root + "/a");
        putParameter(root + "/b");
        String caller = userWithPolicy("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"ssm:GetParameters","Resource":"%s%s/a"}
                ]}""".formatted(PARAMETER_ARN, root));

        ssm(caller, "GetParameters", names(root + "/a"))
                .statusCode(200)
                .body("Parameters[0].Name", equalTo(root + "/a"));
        assertAccessDenied(ssm(caller, "GetParameters", names(root + "/a", root + "/b")), "ssm:GetParameters");
    }

    @Test
    void deleteParametersIsRefusedWholeByADenyOnOneParameterItNames() {
        String root = "/" + unique();
        putParameter(root + "/a");
        putParameter(root + "/b");
        String caller = userWithPolicy("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"ssm:*","Resource":"*"},
                  {"Effect":"Deny","Action":"ssm:DeleteParameters","Resource":"%s%s/b"}
                ]}""".formatted(PARAMETER_ARN, root));

        assertAccessDenied(ssm(caller, "DeleteParameters", names(root + "/a", root + "/b")),
                "ssm:DeleteParameters");
        ssm(ACCOUNT_ID, "GetParameter", "{\"Name\":\"" + root + "/a\"}").statusCode(200);
    }

    @Test
    void getParametersByPathIsCheckedAgainstThePathItRequests() {
        String root = "/" + unique();
        putParameter(root + "/app/a");
        putParameter(root + "/other/a");
        String caller = userWithPolicy("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"ssm:GetParametersByPath","Resource":"%s%s/app"}
                ]}""".formatted(PARAMETER_ARN, root));

        ssm(caller, "GetParametersByPath", "{\"Path\":\"" + root + "/app\"}")
                .statusCode(200)
                .body("Parameters[0].Name", equalTo(root + "/app/a"));
        assertAccessDenied(ssm(caller, "GetParametersByPath", "{\"Path\":\"" + root + "/other\"}"),
                "ssm:GetParametersByPath");
    }

    @Test
    void getParametersByPathIsRefusedByADenyOnThePath() {
        String root = "/" + unique();
        putParameter(root + "/app/a");
        String caller = userWithPolicy("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"ssm:*","Resource":"*"},
                  {"Effect":"Deny","Action":"ssm:GetParametersByPath","Resource":"%s%s/app"}
                ]}""".formatted(PARAMETER_ARN, root));

        assertAccessDenied(ssm(caller, "GetParametersByPath", "{\"Path\":\"" + root + "/app\"}"),
                "ssm:GetParametersByPath");
    }

    private static void assertAccessDenied(ValidatableResponse response, String action) {
        response.statusCode(400)
                .body("__type", equalTo("AccessDeniedException"))
                .body("message", containsString(action));
    }

    private static String names(String... names) {
        return "{\"Names\":[\"" + String.join("\",\"", names) + "\"]}";
    }

    private void putParameter(String name) {
        ssm(ACCOUNT_ID, "PutParameter", "{\"Name\":\"" + name + "\",\"Value\":\"v\",\"Type\":\"String\"}")
                .statusCode(200);
        int port = baseUri.getPort();
        cleanup.register(() -> given().port(port)
                .header("Authorization", auth(ACCOUNT_ID, "ssm"))
                .header("X-Amz-Target", "AmazonSSM.DeleteParameters")
                .contentType("application/x-amz-json-1.1")
                .body(names(name))
                .when().post("/").then().statusCode(200));
    }

    private String userWithPolicy(String document) {
        String userName = "ssm-res-" + unique();
        String accessKeyId = EnforcementFixtures.createUser(cleanup, baseUri.getPort(),
                auth(ACCOUNT_ID, "iam"), userName).accessKeyId();
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), auth(ACCOUNT_ID, "iam"), userName, "only", document);
        return accessKeyId;
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static ValidatableResponse ssm(String accessKeyId, String action, String body) {
        return given()
                .header("Authorization", auth(accessKeyId, "ssm"))
                .header("X-Amz-Target", "AmazonSSM." + action)
                .contentType("application/x-amz-json-1.1")
                .body(body)
        .when()
                .post("/")
        .then();
    }
}
