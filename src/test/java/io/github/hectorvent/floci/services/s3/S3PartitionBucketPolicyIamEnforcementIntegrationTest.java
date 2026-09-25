package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testutil.S3RequestSigner;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * A bucket policy names its bucket in the partition of the bucket's own region, and IAM
 * enforcement has to match it that way whatever partition the request was signed for. Runs with
 * IAM enforcement alone, so S3's own bucket-policy check cannot deny first and hide the filter's
 * decision.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class S3PartitionBucketPolicyIamEnforcementIntegrationTest {

    private static final String ACCOUNT = "444455556666";

    @Test
    void chinaBucketPolicyNamedInItsOwnPartitionHolds() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "cn-policy-" + suffix;
        String key = "guarded.txt";
        S3RequestSigner china = createAccountAdmin("cn-admin-" + suffix).inRegion("cn-north-1");

        createBucket(bucket, china);
        putObject(bucket, key, china);
        putBucketPolicy(bucket, denyObjectReadWritePolicy("aws-cn", bucket), china);

        assertPutObjectDenied(bucket, key, china);
        assertGetObjectDenied(bucket, key, china);
    }

    @Test
    void commercialBucketPolicyHoldsForARequestSignedInAnotherPartition() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "us-policy-" + suffix;
        String key = "guarded.txt";
        S3RequestSigner commercial = createAccountAdmin("us-admin-" + suffix);

        createBucket(bucket, commercial);
        putObject(bucket, key, commercial);
        putBucketPolicy(bucket, denyObjectReadWritePolicy("aws", bucket), commercial);

        S3RequestSigner china = commercial.inRegion("cn-north-1");
        assertPutObjectDenied(bucket, key, china);
        assertGetObjectDenied(bucket, key, china);
    }

    private static String denyObjectReadWritePolicy(String partition, String bucket) {
        return """
                {
                  "Version": "2012-10-17",
                  "Statement": [
                    {
                      "Effect": "Deny",
                      "Principal": "*",
                      "Action": ["s3:GetObject", "s3:PutObject"],
                      "Resource": "arn:%s:s3:::%s/*"
                    }
                  ]
                }""".formatted(partition, bucket);
    }

    private static void assertPutObjectDenied(String bucket, String key, S3RequestSigner signer) {
        given()
            .filter(signer)
            .body("overwritten")
        .when()
            .put("/" + bucket + "/" + key)
        .then()
            .statusCode(403)
            .body(containsString("AccessDenied"));
    }

    private static void assertGetObjectDenied(String bucket, String key, S3RequestSigner signer) {
        given()
            .filter(signer)
        .when()
            .get("/" + bucket + "/" + key)
        .then()
            .statusCode(403)
            .body(containsString("AccessDenied"));
    }

    private static void createBucket(String bucket, S3RequestSigner signer) {
        given().filter(signer).when().put("/" + bucket).then().statusCode(200);
    }

    private static void putObject(String bucket, String key, S3RequestSigner signer) {
        given().filter(signer).body("original").when().put("/" + bucket + "/" + key).then().statusCode(200);
    }

    private static void putBucketPolicy(String bucket, String policy, S3RequestSigner signer) {
        given()
            .filter(signer)
            .contentType("application/json")
            .body(policy)
        .when()
            .put("/" + bucket + "?policy")
        .then()
            .statusCode(200);
    }

    /** An IAM user in {@link #ACCOUNT} whose identity policy allows every S3 action. */
    private static S3RequestSigner createAccountAdmin(String userName) {
        iam("CreateUser", "UserName", userName);
        iam("PutUserPolicy", "UserName", userName,
                "PolicyName", "S3Admin",
                "PolicyDocument", """
                        {"Version": "2012-10-17",
                         "Statement": [{"Effect": "Allow", "Action": "s3:*", "Resource": "*"}]}""");
        ExtractableResponse<Response> key = iam("CreateAccessKey", "UserName", userName);
        return S3RequestSigner.signedAs(
                key.path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId"),
                key.path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.SecretAccessKey"));
    }

    private static ExtractableResponse<Response> iam(String action, String... params) {
        RequestSpecification request = given()
                .formParam("Action", action)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
                        + "/20260629/us-east-1/iam/aws4_request, SignedHeaders=host, Signature=abc");
        for (int i = 0; i < params.length; i += 2) {
            request = request.formParam(params[i], params[i + 1]);
        }
        return request.when().post("/").then().statusCode(200).extract();
    }
}
