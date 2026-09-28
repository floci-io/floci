package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * Regression tests for the virtual-hosted bucket ARN reported in lex00/floci#213.
 *
 * <p>{@code S3VirtualHostFilter} rewrites a bucket-level virtual-hosted request
 * ({@code GET /} with a {@code bucket.localhost} Host header) to the path {@code /bucket/},
 * and {@code ResourceArnBuilder} used to turn that into {@code arn:aws:s3:::bucket/}. A policy
 * naming the bucket as {@code arn:aws:s3:::bucket} then failed to match, so an allowed
 * ListBucket came back denied for every SDK that defaults to virtual-hosted addressing while
 * the identical path-style call was allowed.
 *
 * <p>Only {@code floci.services.iam.enforcement-enabled} is turned on, matching
 * {@code S3PresignedUrlIamEnforcementIntegrationTest}, so the requests carry a syntactically
 * valid but unverified signature. IAM policy evaluation does not depend on the signature
 * itself being checked.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class S3VirtualHostIamEnforcementIntegrationTest {

    private static final String REGION = "us-east-1";

    @Test
    void virtualHostedListBucketIsAllowedByABucketArnPolicy() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "vhost-iam-allow-" + suffix;
        String userName = "vhost-allowed-user-" + suffix;

        createBucketAsRoot(bucket);
        String accessKeyId = createUser(userName);
        putUserPolicy(userName, "AllowListBucket", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:ListBucket","Resource":"arn:aws:s3:::%1$s"}
                ]}""".formatted(bucket));

        given()
                .header("Host", bucket + ".localhost")
                .header("Authorization", auth(accessKeyId, "s3"))
        .when()
                .get("/")
        .then()
                .statusCode(200)
                .body(containsString("<Name>" + bucket + "</Name>"));
    }

    @Test
    void pathStyleListBucketIsAllowedByTheSamePolicy() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "path-iam-allow-" + suffix;
        String userName = "path-allowed-user-" + suffix;

        createBucketAsRoot(bucket);
        String accessKeyId = createUser(userName);
        putUserPolicy(userName, "AllowListBucket", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:ListBucket","Resource":"arn:aws:s3:::%1$s"}
                ]}""".formatted(bucket));

        given()
                .header("Authorization", auth(accessKeyId, "s3"))
        .when()
                .get("/" + bucket)
        .then()
                .statusCode(200)
                .body(containsString("<Name>" + bucket + "</Name>"));
    }

    @Test
    void virtualHostedListBucketIsDeniedWithoutAGrant() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "vhost-iam-deny-" + suffix;
        String userName = "vhost-denied-user-" + suffix;

        createBucketAsRoot(bucket);
        String accessKeyId = createUser(userName);

        given()
                .header("Host", bucket + ".localhost")
                .header("Authorization", auth(accessKeyId, "s3"))
        .when()
                .get("/")
        .then()
                .statusCode(403)
                .body(containsString("<Code>AccessDenied</Code>"));
    }

    private static void createBucketAsRoot(String bucket) {
        given()
                .header("Authorization", auth("test", "s3"))
        .when()
                .put("/" + bucket)
        .then()
                .statusCode(200);
    }

    private static String createUser(String userName) {
        given()
                .formParam("Action", "CreateUser")
                .formParam("UserName", userName)
                .header("Authorization", auth("test", "iam"))
        .when()
                .post("/")
        .then()
                .statusCode(200);

        return given()
                .formParam("Action", "CreateAccessKey")
                .formParam("UserName", userName)
                .header("Authorization", auth("test", "iam"))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .extract()
                .path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId");
    }

    private static void putUserPolicy(String userName, String policyName, String policyDocument) {
        given()
                .formParam("Action", "PutUserPolicy")
                .formParam("UserName", userName)
                .formParam("PolicyName", policyName)
                .formParam("PolicyDocument", policyDocument)
                .header("Authorization", auth("test", "iam"))
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    private static String auth(String accessKeyId, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/20260629/" + REGION + "/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
