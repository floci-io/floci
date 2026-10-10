package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * With enforcement on, a deactivated access key "can no longer be used by API calls" until it is
 * activated again (IAM User Guide, "Manage access keys for IAM users"). It is refused as a key that
 * exists nowhere is, in each protocol's own vocabulary.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class InactiveAccessKeyEnforcementIntegrationTest {

    private static final String DEFAULT_ACCOUNT = "000000000000";
    private static final String OTHER_ACCOUNT = "222233334444";
    private static final String LIST_USERS = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"iam:ListUsers","Resource":"*"}]}""";
    private static final String PUT_OBJECT = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"s3:PutObject","Resource":"*"}]}""";

    /**
     * The teardown runs after Quarkus has reset the port RestAssured points at, so it has to say
     * where the application is. The test port is random, which leaves this as the only place to
     * read it from.
     */
    @TestHTTPResource("/")
    static URI baseUri;

    /**
     * A QuarkusTest shares one application with every other test class, so a user, an access key
     * or a bucket left here outlives this class and is visible to anything that lists them
     * unscoped. Each create registers its removal below, before it runs: the registered call
     * reads back what is actually attached, so a setup that fails part way through still has the
     * whole fixture taken away, and one that created nothing leaves it with nothing to do.
     */
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aDeactivatedKeyIsRefusedUntilItIsActivatedAgain() {
        String userName = "inactive-key-" + UUID.randomUUID().toString().substring(0, 8);
        String accessKeyId = userWithKey(DEFAULT_ACCOUNT, userName, LIST_USERS);
        query(accessKeyId, "iam", "ListUsers").then().statusCode(200);

        setStatus(DEFAULT_ACCOUNT, userName, accessKeyId, "Inactive");
        query(accessKeyId, "iam", "ListUsers")
                .then().statusCode(403).body(containsString("InvalidClientTokenId"));
        // Not even a call that needs no permission.
        query(accessKeyId, "sts", "GetCallerIdentity")
                .then().statusCode(403).body(containsString("InvalidClientTokenId"));

        setStatus(DEFAULT_ACCOUNT, userName, accessKeyId, "Active");
        query(accessKeyId, "iam", "ListUsers").then().statusCode(200);
    }

    @Test
    void aDeactivatedKeyOfAnotherAccountIsRefusedRatherThanAllowedInTheDefaultOne() {
        // Routing skips an inactive key, so its requests land in the default account, where no user
        // holds it. The key's own user may do nothing at all.
        String userName = "inactive-elsewhere-" + UUID.randomUUID().toString().substring(0, 8);
        String accessKeyId = userWithKey(OTHER_ACCOUNT, userName, null);
        query(accessKeyId, "iam", "CreateUser", "UserName", userName + "-made")
                .then().statusCode(403).body(containsString("AccessDenied"));

        setStatus(OTHER_ACCOUNT, userName, accessKeyId, "Inactive");
        query(accessKeyId, "iam", "CreateUser", "UserName", userName + "-made")
                .then().statusCode(403).body(containsString("InvalidClientTokenId"));
        query(accessKeyId, "sts", "GetSessionToken")
                .then().statusCode(403).body(containsString("InvalidClientTokenId"));
        given()
                .header("Authorization", authorization(accessKeyId, "s3"))
        .when()
                .get("/")
        .then()
                .statusCode(403)
                .body(containsString("InvalidAccessKeyId"));
        given()
                .header("Authorization", authorization(accessKeyId, "lambda"))
                .contentType("application/json")
        .when()
                .get("/2015-03-31/functions")
        .then()
                .statusCode(403)
                .body(containsString("UnrecognizedClientException"));
    }

    @Test
    void aDeactivatedKeyCannotUploadThroughAPresignedPost() {
        // A presigned POST carries its credential in the form body, which only S3 reads.
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "inactive-key-post-" + suffix;
        String userName = "inactive-key-post-" + suffix;
        cleanup.register(() -> EnforcementFixtures.removeBucket(baseUri.getPort(),
                authorization("test", "s3"), bucket));
        given()
                .header("Authorization", authorization("test", "s3"))
        .when()
                .put("/" + bucket)
        .then()
                .statusCode(200);
        String accessKeyId = userWithKey(DEFAULT_ACCOUNT, userName, PUT_OBJECT);
        presignedPost(accessKeyId, bucket, "before.txt").then().statusCode(204);

        setStatus(DEFAULT_ACCOUNT, userName, accessKeyId, "Inactive");
        presignedPost(accessKeyId, bucket, "after.txt")
                .then().statusCode(403).body(containsString("<Code>InvalidAccessKeyId</Code>"));
        given()
                .header("Authorization", authorization("test", "s3"))
        .when()
                .head("/" + bucket + "/after.txt")
        .then()
                .statusCode(404);
    }

    /** Creates a user in {@code account}, with an inline policy when one is given, and returns its access key ID. */
    private String userWithKey(String account, String userName, String policy) {
        cleanup.register(() -> EnforcementFixtures.removeUser(baseUri.getPort(),
                authorization(account, "iam"), userName));
        query(account, "iam", "CreateUser", "UserName", userName).then().statusCode(200);
        if (policy != null) {
            query(account, "iam", "PutUserPolicy", "UserName", userName, "PolicyName", "inline",
                    "PolicyDocument", policy).then().statusCode(200);
        }
        return query(account, "iam", "CreateAccessKey", "UserName", userName)
                .then().statusCode(200)
                .extract().path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId");
    }

    private static void setStatus(String account, String userName, String accessKeyId, String status) {
        query(account, "iam", "UpdateAccessKey", "UserName", userName, "AccessKeyId", accessKeyId,
                "Status", status).then().statusCode(200);
    }

    private static Response presignedPost(String accessKeyId, String bucket, String key) {
        return given()
                .multiPart("key", key)
                .multiPart("x-amz-credential", accessKeyId + "/20260101/us-east-1/s3/aws4_request")
                .multiPart("file", key, "x".getBytes(StandardCharsets.UTF_8), "text/plain")
        .when()
                .post("/" + bucket);
    }

    private static Response query(String accessKeyId, String service, String action, String... params) {
        RequestSpecification request = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", authorization(accessKeyId, service))
                .formParam("Action", action);
        for (int i = 0; i < params.length; i += 2) {
            request = request.formParam(params[i], params[i + 1]);
        }
        return request.when().post("/");
    }

    private static String authorization(String accessKeyId, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/20260101/us-east-1/" + service
                + "/aws4_request, SignedHeaders=host, Signature=not-verified";
    }
}
