package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * Regression tests for issue #2333: CopyObject and UploadPartCopy only authorized
 * {@code s3:PutObject} on the destination and never checked {@code s3:GetObject} on
 * the copy source, which only ever appears in the {@code x-amz-copy-source} header.
 * A caller with write-only access to a bucket could therefore copy the contents of
 * any object it could not otherwise read, as long as it could guess the bucket/key.
 */
@QuarkusTest
@TestProfile(S3CopyObjectSourcePermissionIntegrationTest.IamEnforcementProfile.class)
class S3CopyObjectSourcePermissionIntegrationTest {

    private static final String REGION = "us-east-1";

    @Test
    void copyObjectIsDeniedWhenCallerCannotReadTheSource() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String sourceBucket = "copy-source-" + suffix;
        String destBucket = "copy-dest-" + suffix;
        String userName = "copy-write-only-" + suffix;

        createBucketAsRoot(sourceBucket);
        createBucketAsRoot(destBucket);
        putObjectAsRoot(sourceBucket, "private-secret.txt", "top secret payload");

        String accessKeyId = createUser(userName);
        putUserPolicy(userName, "DestWriteOnly", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:PutObject","Resource":"arn:aws:s3:::%1$s/*"}
                ]}""".formatted(destBucket));

        given()
                .header("Authorization", auth(accessKeyId, "s3"))
                .header("x-amz-copy-source", "/" + sourceBucket + "/private-secret.txt")
        .when()
                .put("/" + destBucket + "/exfiltrated.txt")
        .then()
                .statusCode(403)
                .body(containsString("<Code>AccessDenied</Code>"));
    }

    @Test
    void copyObjectSucceedsWhenCallerCanReadSourceAndWriteDestination() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String sourceBucket = "copy-source-ok-" + suffix;
        String destBucket = "copy-dest-ok-" + suffix;
        String userName = "copy-read-write-" + suffix;

        createBucketAsRoot(sourceBucket);
        createBucketAsRoot(destBucket);
        putObjectAsRoot(sourceBucket, "allowed.txt", "not secret");

        String accessKeyId = createUser(userName);
        putUserPolicy(userName, "SourceReadDestWrite", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:GetObject","Resource":"arn:aws:s3:::%1$s/*"},
                  {"Effect":"Allow","Action":"s3:PutObject","Resource":"arn:aws:s3:::%2$s/*"}
                ]}""".formatted(sourceBucket, destBucket));

        given()
                .header("Authorization", auth(accessKeyId, "s3"))
                .header("x-amz-copy-source", "/" + sourceBucket + "/allowed.txt")
        .when()
                .put("/" + destBucket + "/copied.txt")
        .then()
                .statusCode(200);
    }

    @Test
    void uploadPartCopyIsDeniedWhenCallerCannotReadTheSource() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String sourceBucket = "part-copy-source-" + suffix;
        String destBucket = "part-copy-dest-" + suffix;
        String userName = "part-copy-write-only-" + suffix;

        createBucketAsRoot(sourceBucket);
        createBucketAsRoot(destBucket);
        putObjectAsRoot(sourceBucket, "private-secret.txt", "top secret payload");

        String accessKeyId = createUser(userName);
        putUserPolicy(userName, "DestWriteOnly", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:PutObject","Resource":"arn:aws:s3:::%1$s/*"}
                ]}""".formatted(destBucket));

        String uploadId = initiateMultipartUploadAsRoot(destBucket, "exfiltrated.txt");

        given()
                .header("Authorization", auth(accessKeyId, "s3"))
                .header("x-amz-copy-source", "/" + sourceBucket + "/private-secret.txt")
                .queryParam("uploadId", uploadId)
                .queryParam("partNumber", 1)
        .when()
                .put("/" + destBucket + "/exfiltrated.txt")
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

    private static void putObjectAsRoot(String bucket, String key, String body) {
        given()
                .header("Authorization", auth("test", "s3"))
                .contentType("text/plain")
                .body(body)
        .when()
                .put("/" + bucket + "/" + key)
        .then()
                .statusCode(200);
    }

    private static String initiateMultipartUploadAsRoot(String bucket, String key) {
        return given()
                .header("Authorization", auth("test", "s3"))
                .queryParam("uploads", "")
        .when()
                .post("/" + bucket + "/" + key)
        .then()
                .statusCode(200)
                .extract()
                .body()
                .xmlPath()
                .getString("InitiateMultipartUploadResult.UploadId");
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
