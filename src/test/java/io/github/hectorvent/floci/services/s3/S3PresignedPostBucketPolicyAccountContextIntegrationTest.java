package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.IamEnforcementFilter;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Regression coverage for a presigned POST bucket-policy-only grant where the bucket and the
 * signer both live in a non-default account, distinct from
 * {@code S3PresignedPostIamOnlyEnforcementIntegrationTest}'s identity-policy coverage of the
 * same account-context gap. {@link IamEnforcementFilter#authorizeAdditionalResource(String,
 * String, String)} must resolve the applicable bucket policy under the signing credential's own
 * account context (pushed onto {@code RequestContext} for the duration of that call), or a bucket
 * owned by a non-default account is invisible to it and the grant is silently dropped.
 *
 * <p>{@code global-bucket-namespace} is enabled so bucket resolution (both here and in the
 * eventual object write) does not itself depend on the ambient account, isolating the assertion
 * to the account used for the identity/resource-policy relationship rather than to bucket lookup
 * scoping.
 */
@QuarkusTest
@TestProfile(S3PresignedPostBucketPolicyAccountContextIntegrationTest.GlobalNamespaceIamOnlyProfile.class)
class S3PresignedPostBucketPolicyAccountContextIntegrationTest {

    private static final String REGION = "us-east-1";
    /** A non-default account, distinct from the configured {@code 000000000000} default. */
    private static final String NON_DEFAULT_ACCOUNT = "222233334444";
    private static final DateTimeFormatter AMZ_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    public static final class GlobalNamespaceIamOnlyProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.services.iam.enforcement-enabled", "true",
                    "floci.services.s3.enforce-auth", "false",
                    "floci.services.s3.global-bucket-namespace", "true");
        }
    }

    @Test
    void presignedPostSucceedsForNonDefaultAccountBucketAndSignerWhenOnlyBucketPolicyAllows() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "presigned-post-bp-xacct-" + suffix;
        String userName = "presigned-post-bp-xacct-user-" + suffix;

        createBucketAsRoot(bucket, NON_DEFAULT_ACCOUNT);
        String accessKeyId = createUser(userName, NON_DEFAULT_ACCOUNT);
        putBucketPolicy(bucket, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow",
                   "Principal":{"AWS":"arn:aws:iam::%1$s:user/%2$s"},
                   "Action":"s3:PutObject",
                   "Resource":"arn:aws:s3:::%3$s/*"}
                ]}""".formatted(NON_DEFAULT_ACCOUNT, userName, bucket), NON_DEFAULT_ACCOUNT);

        given()
                .multiPart("key", "bp-allowed-xacct.txt")
                .multiPart("x-amz-credential", presignedCredential(accessKeyId))
                .multiPart("file", "bp-allowed-xacct.txt",
                        "uploaded via presigned POST".getBytes(StandardCharsets.UTF_8), "text/plain")
        .when()
                .post("/" + bucket)
        .then()
                .statusCode(204);
    }

    private static String presignedCredential(String accessKeyId) {
        String amzDate = AMZ_DATE_FMT.format(Instant.now());
        String dateStamp = amzDate.substring(0, 8);
        return accessKeyId + "/" + dateStamp + "/" + REGION + "/s3/aws4_request";
    }

    private static void createBucketAsRoot(String bucket, String accountId) {
        given()
                .header("Authorization", auth(accountId, "s3"))
        .when()
                .put("/" + bucket)
        .then()
                .statusCode(200);
    }

    private static void putBucketPolicy(String bucket, String policyDocument, String accountId) {
        given()
                .header("Authorization", auth(accountId, "s3"))
                .contentType("application/json")
                .body(policyDocument)
        .when()
                .put("/" + bucket + "?policy")
        .then()
                .statusCode(200);
    }

    /** Creates the user (and its access key) as root in {@code accountId}. */
    private static String createUser(String userName, String accountId) {
        String authHeader = auth(accountId, "iam");
        given()
                .formParam("Action", "CreateUser")
                .formParam("UserName", userName)
                .header("Authorization", authHeader)
        .when()
                .post("/")
        .then()
                .statusCode(200);

        return given()
                .formParam("Action", "CreateAccessKey")
                .formParam("UserName", userName)
                .header("Authorization", authHeader)
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .extract()
                .path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId");
    }

    private static String auth(String accessKeyId, String service) {
        String amzDate = AMZ_DATE_FMT.format(Instant.now()).substring(0, 8);
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/" + amzDate + "/" + REGION + "/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
