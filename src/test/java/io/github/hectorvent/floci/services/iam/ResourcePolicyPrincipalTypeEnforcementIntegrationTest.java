package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.S3IamEnforcementProfile;
import io.github.hectorvent.floci.testutil.S3RequestSigner;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * With IAM enforcement on, a bucket policy's {@code Principal} grants a cross-account caller only
 * through a principal type that names it (IAM User Guide, "AWS JSON policy elements: Principal").
 */
@QuarkusTest
@TestProfile(S3IamEnforcementProfile.class)
class ResourcePolicyPrincipalTypeEnforcementIntegrationTest {

    private static final String CALLER_ACCOUNT = "222222222222";
    private static final S3RequestSigner OWNER = S3RequestSigner.signedAs("test", "test");

    @Test
    void aServiceWildcardGrantsNoIamCaller() {
        // {"Service": "*"} is not a form AWS accepts. It used to be compared with the caller's IAM ARN
        // by glob, so a bucket policy written that way let every IAM caller in from any account.
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "principal-type-" + suffix;
        given().filter(OWNER).when().put("/" + bucket).then().statusCode(200);
        given().filter(OWNER).contentType("text/plain").body("payload")
                .when().put("/" + bucket + "/object.txt").then().statusCode(200);
        S3RequestSigner caller = crossAccountReader("reader-" + suffix);

        putBucketPolicy(bucket, """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"*"},
                  "Action":"s3:GetObject","Resource":"arn:aws:s3:::%s/*"}]}""".formatted(bucket));
        given().filter(caller).when().get("/" + bucket + "/object.txt")
                .then().statusCode(403).body(containsString("<Code>AccessDenied</Code>"));

        // The same caller is let in once the policy names its account.
        putBucketPolicy(bucket, """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":"%s"},
                  "Action":"s3:GetObject","Resource":"arn:aws:s3:::%s/*"}]}""".formatted(CALLER_ACCOUNT, bucket));
        given().filter(caller).when().get("/" + bucket + "/object.txt")
                .then().statusCode(200).body(equalTo("payload"));
    }

    /** An IAM user in another account, allowed s3:GetObject on anything by its own policy. */
    private static S3RequestSigner crossAccountReader(String user) {
        given().formParam("Action", "CreateUser").formParam("UserName", user)
                .header("Authorization", auth(CALLER_ACCOUNT, "iam")).when().post("/").then().statusCode(200);
        given().formParam("Action", "PutUserPolicy").formParam("UserName", user).formParam("PolicyName", "read")
                .formParam("PolicyDocument", """
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"s3:GetObject","Resource":"*"}]}""")
                .header("Authorization", auth(CALLER_ACCOUNT, "iam")).when().post("/").then().statusCode(200);
        ExtractableResponse<Response> key = given().formParam("Action", "CreateAccessKey").formParam("UserName", user)
                .header("Authorization", auth(CALLER_ACCOUNT, "iam")).when().post("/")
                .then().statusCode(200).extract();
        return S3RequestSigner.signedAs(
                key.path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId"),
                key.path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.SecretAccessKey"));
    }

    private static void putBucketPolicy(String bucket, String policy) {
        given().filter(OWNER).contentType("application/json").body(policy)
                .when().put("/" + bucket + "?policy").then().statusCode(200);
    }

    private static String auth(String accessKeyId, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/20261001/us-east-1/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
