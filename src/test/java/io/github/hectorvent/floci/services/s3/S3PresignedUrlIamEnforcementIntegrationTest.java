package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * Regression tests for issue #3195: {@code IamEnforcementFilter} only ever read the signing
 * credential from the {@code Authorization} header, so a presigned URL - which signs via the
 * {@code X-Amz-Credential} query parameter instead - skipped IAM identity-policy evaluation
 * entirely. A principal with an explicit {@code Deny} (or no grant at all) could still write
 * through a presigned URL as long as the request was otherwise well-formed, exactly the
 * privilege-escalation path a header-signed request was already correctly denied on.
 *
 * <p>Only {@code floci.services.iam.enforcement-enabled} is turned on here (matching
 * {@code S3CopyObjectSourcePermissionIntegrationTest}); {@code floci.services.s3.enforce-auth}
 * stays off, so these requests carry a syntactically valid but unverified {@code X-Amz-Signature}
 * - IAM policy evaluation does not depend on the signature itself being checked.
 */
@QuarkusTest
@TestProfile(S3PresignedUrlIamEnforcementIntegrationTest.IamEnforcementProfile.class)
class S3PresignedUrlIamEnforcementIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final DateTimeFormatter AMZ_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    @Test
    void presignedPutIsDeniedWhenIdentityPolicyDeniesPutObject() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "presigned-iam-deny-" + suffix;
        String userName = "presigned-denied-user-" + suffix;

        createBucketAsRoot(bucket);
        String accessKeyId = createUser(userName);
        putUserPolicy(userName, "DenyPutObject", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Deny","Action":"s3:PutObject","Resource":"*"}
                ]}""");

        given()
                .urlEncodingEnabled(false)
                .contentType("text/plain")
                .body("should not be stored")
        .when()
                .put(presignedPutPath(bucket, "denied.txt", accessKeyId))
        .then()
                .statusCode(403)
                .body(containsString("<Code>AccessDenied</Code>"));
    }

    @Test
    void presignedPutIsDeniedWhenNoPolicyGrantsPutObject() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "presigned-iam-nogrant-" + suffix;
        String userName = "presigned-nogrant-user-" + suffix;

        createBucketAsRoot(bucket);
        String accessKeyId = createUser(userName);
        // No PutUserPolicy call at all: a real IAM user with no grants, matching the issue's
        // reproduction (a user with an explicit Deny or, equally, simply no grant).

        given()
                .urlEncodingEnabled(false)
                .contentType("text/plain")
                .body("should not be stored")
        .when()
                .put(presignedPutPath(bucket, "no-grant.txt", accessKeyId))
        .then()
                .statusCode(403)
                .body(containsString("<Code>AccessDenied</Code>"));
    }

    @Test
    void presignedPutSucceedsWhenIdentityPolicyAllowsPutObject() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "presigned-iam-allow-" + suffix;
        String userName = "presigned-allowed-user-" + suffix;

        createBucketAsRoot(bucket);
        String accessKeyId = createUser(userName);
        putUserPolicy(userName, "AllowPutObject", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:PutObject","Resource":"arn:aws:s3:::%1$s/*"}
                ]}""".formatted(bucket));

        given()
                .urlEncodingEnabled(false)
                .contentType("text/plain")
                .body("uploaded via presigned URL")
        .when()
                .put(presignedPutPath(bucket, "allowed.txt", accessKeyId))
        .then()
                .statusCode(200);
    }

    private static String presignedPutPath(String bucket, String key, String accessKeyId) {
        String amzDate = AMZ_DATE_FMT.format(Instant.now());
        String dateStamp = amzDate.substring(0, 8);
        String credential = URLEncoder.encode(
                accessKeyId + "/" + dateStamp + "/" + REGION + "/s3/aws4_request", StandardCharsets.UTF_8);
        return "/" + bucket + "/" + key
                + "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
                + "&X-Amz-Credential=" + credential
                + "&X-Amz-Date=" + amzDate
                + "&X-Amz-Expires=3600"
                + "&X-Amz-SignedHeaders=host"
                + "&X-Amz-Signature=fakesig";
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

    public static final class IamEnforcementProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.iam.enforcement-enabled", "true");
        }
    }
}
