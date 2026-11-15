package io.floci.aws.services.emrserverless;

import io.floci.aws.testing.SignedIamEnforcementProfile;
import io.floci.aws.testing.SignedIamUsers;
import io.floci.aws.testutil.AwsRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * EMR Serverless requests are checked against the caller's policies through its generated routes, at the model's
 * paths, although its route filter moves them onto an internal prefix: as the action of the operation each route
 * names, on the application its path names ({@code arn:aws:emr-serverless:<region>:<account>:/applications/<id>}). A
 * request sent to the prefix itself is checked as the same route.
 */
@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class EmrServerlessIamIntegrationTest {

    private final AwsRequestSigner admin = AwsRequestSigner.signedAs("test", "test", "emr-serverless");

    @TestHTTPResource("/")
    URI endpoint;

    @Test
    void callerWithoutPermissionsIsRefusedBeforeAnApplicationIsCreated() throws Exception {
        String name = "iam-none-" + UUID.randomUUID().toString().substring(0, 8);
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "emrs-iam-")) {
            denied(send(user.signer("emr-serverless"), "POST", "/applications", application(name)),
                    "emr-serverless:CreateApplication");
            denied(send(user.signer("emr-serverless"), "GET", "/applications", null),
                    "emr-serverless:ListApplications");
        }
        send(admin, "GET", "/applications", null).then().statusCode(200).body(not(containsString(name)));
    }

    @Test
    void anApplicationScopedAllowReadsThatApplicationAndRefusesAnother() throws Exception {
        String allowed = createApplication();
        String other = createApplication();
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "emrs-iam-", "access",
                allow("\"emr-serverless:GetApplication\"", applicationArn(allowed)))) {
            send(user.signer("emr-serverless"), "GET", "/applications/" + allowed, null).then().statusCode(200)
                    .body("application.applicationId", equalTo(allowed));
            denied(send(user.signer("emr-serverless"), "GET", "/applications/" + other, null),
                    "emr-serverless:GetApplication");
            denied(send(user.signer("emr-serverless"), "DELETE", "/applications/" + allowed, null),
                    "emr-serverless:DeleteApplication");
            send(user.signer("emr-serverless"), "GET", "/_emrserverless/applications/" + allowed, null).then()
                    .statusCode(200).body("application.applicationId", equalTo(allowed));
            denied(send(user.signer("emr-serverless"), "GET", "/_emrserverless/applications/" + other, null),
                    "emr-serverless:GetApplication");
        } finally {
            send(admin, "DELETE", "/applications/" + allowed, null).then().statusCode(200);
            send(admin, "DELETE", "/applications/" + other, null).then().statusCode(200);
        }
    }

    /** Tags a create sends are checked as {@code emr-serverless:TagResource} too, as AWS checks them. */
    @Test
    void tagsOnACreateNeedTagResourceToo() throws Exception {
        String name = "iam-tags-" + UUID.randomUUID().toString().substring(0, 8);
        String tagged = application(name).replace("\"type\":\"SPARK\"", "\"type\":\"SPARK\",\"tags\":{\"team\":\"data\"}");
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "emrs-iam-", "create", allow(
                "\"emr-serverless:CreateApplication\"", "*"))) {
            denied(send(user.signer("emr-serverless"), "POST", "/applications", tagged), "emr-serverless:TagResource");
        }
        send(admin, "GET", "/applications", null).then().statusCode(200).body(not(containsString(name)));
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "emrs-iam-", "create", allow(
                "[\"emr-serverless:CreateApplication\",\"emr-serverless:TagResource\"]", "*"))) {
            String id = send(user.signer("emr-serverless"), "POST", "/applications", tagged).then().statusCode(200)
                    .extract().path("applicationId");
            send(admin, "DELETE", "/applications/" + id, null).then().statusCode(200);
        }
    }

    /** A job run's execution role is checked as {@code iam:PassRole} before the run starts. */
    @Test
    void aJobRunsExecutionRoleNeedsPassRole() throws Exception {
        String application = createApplication();
        String role = "arn:aws:iam::000000000000:role/emrs-job-" + UUID.randomUUID().toString().substring(0, 8);
        String run = "{\"clientToken\":\"" + UUID.randomUUID() + "\",\"executionRoleArn\":\"" + role
                + "\",\"jobDriver\":{\"sparkSubmit\":{\"entryPoint\":\"s3://bucket/job.py\"}}}";
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "emrs-iam-", "run", allow(
                "\"emr-serverless:StartJobRun\"", applicationArn(application)))) {
            denied(send(user.signer("emr-serverless"), "POST", "/applications/" + application + "/jobruns", run),
                    "iam:PassRole");
        } finally {
            send(admin, "DELETE", "/applications/" + application, null).then().statusCode(200);
        }
    }

    private static String allow(String actions, String resource) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":" + actions
                + ",\"Resource\":\"" + resource + "\"}]}";
    }

    private String createApplication() throws Exception {
        String name = "iam-" + UUID.randomUUID().toString().substring(0, 8);
        return send(admin, "POST", "/applications", application(name)).then().statusCode(200)
                .extract().path("applicationId");
    }

    private static String application(String name) {
        return "{\"name\":\"" + name + "\",\"releaseLabel\":\"emr-6.6.0\",\"type\":\"SPARK\",\"clientToken\":\""
                + UUID.randomUUID() + "\"}";
    }

    private static String applicationArn(String id) {
        return "arn:aws:emr-serverless:us-east-1:000000000000:/applications/" + id;
    }

    private Response send(AwsRequestSigner signer, String method, String path, String body) throws Exception {
        URI uri = endpoint.resolve(path);
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        RequestSpecification request = given().urlEncodingEnabled(false)
                .headers(signer.headersFor(method, uri, Map.of(), bytes));
        if (body != null) {
            request = request.contentType("application/json").body(bytes);
        }
        return request.request(method, uri);
    }

    private static void denied(Response response, String action) {
        response.then().statusCode(403).body(containsString("not authorized to perform: " + action));
    }
}
