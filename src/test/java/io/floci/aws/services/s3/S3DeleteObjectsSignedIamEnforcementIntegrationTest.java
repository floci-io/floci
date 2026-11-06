package io.floci.aws.services.s3;

import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.EnforcementFixtures.UserCredentials;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.S3IamEnforcementProfile;
import io.floci.aws.testutil.S3RequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.xml.XmlPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The per-key DeleteObjects authorization of {@link S3DeleteObjectsIamEnforcementIntegrationTest}
 * with S3 auth enforcement and the global bucket namespace on as well, so S3's own signed
 * bucket-policy check runs beside IAM's. That check evaluates a narrower condition context than
 * the IAM layer, so a batch delete must reach the IAM evaluation to be refused exactly where the
 * single-key DeleteObject is.
 */
@QuarkusTest
@TestProfile(S3IamEnforcementProfile.class)
class S3DeleteObjectsSignedIamEnforcementIntegrationTest {

    // S3HeaderSignatureFilter verifies every header signature under this profile, so each S3 call
    // signs for real with the caller's secret.
    private static final S3RequestSigner ROOT_SIGNER = S3RequestSigner.signedAs("test", "test");
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_A = "111122223333";
    private static final String ACCOUNT_B = "222233334444";

    @TestHTTPResource("/")
    static URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void aBucketPolicyDenyOnRequestedRegionAppliesAsItDoesToASingleDelete() {
        String bucket = uniqueName("delobj-region");
        createBucket(bucket, ROOT_SIGNER);
        put(bucket, "single.txt", ROOT_SIGNER);
        put(bucket, "batch.txt", ROOT_SIGNER);
        String user = uniqueName("delobj-region");
        UserCredentials caller = createUser(user, "test");
        userPolicy(user, "test",
                "{\"Effect\":\"Allow\",\"Action\":\"s3:DeleteObject\",\"Resource\":\"arn:aws:s3:::" + bucket + "/*\"}");
        bucketPolicy(bucket, ROOT_SIGNER, """
                {"Effect":"Deny","Principal":"*","Action":"s3:DeleteObject","Resource":"arn:aws:s3:::%s/*",
                 "Condition":{"StringEquals":{"aws:RequestedRegion":"%s"}}}""".formatted(bucket, REGION));

        given().filter(signer(caller)).when().delete("/" + bucket + "/single.txt").then().statusCode(403);
        XmlPath result = deleteObjects(signer(caller), bucket, "batch.txt");

        assertEquals(List.of(), result.getList("DeleteResult.Deleted.Key"));
        assertDenied(result, "batch.txt");
        assertPresent(bucket, "single.txt", ROOT_SIGNER);
        assertPresent(bucket, "batch.txt", ROOT_SIGNER);
    }

    @Test
    void aCrossAccountBucketPolicyGrantWithoutAnIdentityGrantIsDenied() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "delobj-cross-" + suffix;
        String owner = "delobj-owner-" + suffix;
        UserCredentials ownerCredentials = createUser(owner, ACCOUNT_A);
        userPolicy(owner, ACCOUNT_A, "{\"Effect\":\"Allow\",\"Action\":\"s3:*\",\"Resource\":\"*\"}");
        createBucket(bucket, signer(ownerCredentials));
        put(bucket, "shared.txt", signer(ownerCredentials));
        String user = "delobj-cross-user-" + suffix;
        UserCredentials caller = createUser(user, ACCOUNT_B);
        bucketPolicy(bucket, signer(ownerCredentials), """
                {"Effect":"Allow","Principal":{"AWS":"arn:aws:iam::%s:user/%s"},
                 "Action":"s3:DeleteObject","Resource":"arn:aws:s3:::%s/*"}""".formatted(ACCOUNT_B, user, bucket));

        XmlPath result = deleteObjects(signer(caller), bucket, "shared.txt");

        assertEquals(List.of(), result.getList("DeleteResult.Deleted.Key"));
        assertDenied(result, "shared.txt");
        assertPresent(bucket, "shared.txt", signer(ownerCredentials));
    }

    @Test
    void crossAccountDeletesRequireBothGrantsAndMutateOnlyTheBucketOwner() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "delobj-owner-" + suffix;
        String owner = "delobj-owner-" + suffix;
        UserCredentials ownerCredentials = createUser(owner, ACCOUNT_A);
        userPolicy(owner, ACCOUNT_A, "{\"Effect\":\"Allow\",\"Action\":\"s3:*\",\"Resource\":\"*\"}");
        S3RequestSigner ownerSigner = signer(ownerCredentials);
        cleanup.register(() -> EnforcementFixtures.removeBucket(baseUri.getPort(), ownerSigner, bucket));
        createBucket(bucket, ownerSigner);
        put(bucket, "single.txt", ownerSigner);
        put(bucket, "batch.txt", ownerSigner);
        put(bucket, "retained.txt", ownerSigner);
        String user = "delobj-caller-" + suffix;
        UserCredentials caller = createUser(user, ACCOUNT_B);
        userPolicy(user, ACCOUNT_B, "{\"Effect\":\"Allow\",\"Action\":\"s3:DeleteObject\",\"Resource\":\"*\"}");
        S3RequestSigner callerSigner = signer(caller);

        given().filter(callerSigner).delete("/" + bucket + "/single.txt").then().statusCode(403);
        assertDenied(deleteObjects(callerSigner, bucket, "batch.txt"), "batch.txt");
        assertPresent(bucket, "single.txt", ownerSigner);
        assertPresent(bucket, "batch.txt", ownerSigner);

        bucketPolicy(bucket, ownerSigner, """
                {"Effect":"Allow","Principal":{"AWS":"arn:aws:iam::%s:user/%s"},
                 "Action":"s3:DeleteObject","Resource":"arn:aws:s3:::%s/*"}""".formatted(ACCOUNT_B, user, bucket));
        given().filter(callerSigner).delete("/" + bucket + "/single.txt").then().statusCode(204);
        XmlPath deleted = deleteObjects(callerSigner, bucket, "batch.txt");
        assertEquals(List.of("batch.txt"), deleted.getList("DeleteResult.Deleted.Key"));
        assertEquals(List.of(), deleted.getList("DeleteResult.Error.Key"));
        given().filter(ownerSigner).head("/" + bucket + "/single.txt").then().statusCode(404);
        given().filter(ownerSigner).head("/" + bucket + "/batch.txt").then().statusCode(404);
        given().filter(callerSigner).delete("/" + bucket + "/single.txt").then().statusCode(204);

        given().filter(ownerSigner).delete("/" + bucket + "?policy").then().statusCode(204);
        given().filter(callerSigner).delete("/" + bucket + "/retained.txt").then().statusCode(403);
        assertPresent(bucket, "retained.txt", ownerSigner);
    }

    // The IAM layer skips an access key it does not know, as it skips an unsigned request, so with S3
    // auth enforcement on it is S3's own request authentication that refuses the batch outright.
    @Test
    void anAccessKeyNoOneIssuedIsRefusedBeforeAnyKeyIsDeleted() {
        String bucket = uniqueName("delobj-unknown-key");
        createBucket(bucket, ROOT_SIGNER);
        put(bucket, "kept.txt", ROOT_SIGNER);
        S3RequestSigner unknown = S3RequestSigner.signedAs("AKIAUNKNOWNDELOBJ001", "not-a-real-secret");

        given()
                .filter(unknown)
                .contentType("application/xml")
                .body("<Delete><Object><Key>kept.txt</Key></Object></Delete>")
        .when()
                .post("/" + bucket + "?delete")
        .then()
                .statusCode(403)
                .body("Error.Code", equalTo("InvalidAccessKeyId"));

        assertPresent(bucket, "kept.txt", ROOT_SIGNER);
    }

    private static void assertDenied(XmlPath result, String key) {
        assertEquals(List.of(key), result.getList("DeleteResult.Error.Key"));
        assertEquals(List.of("AccessDenied"), result.getList("DeleteResult.Error.Code"));
        assertEquals(List.of("Access Denied"), result.getList("DeleteResult.Error.Message"));
    }

    private static XmlPath deleteObjects(S3RequestSigner signer, String bucket, String key) {
        String response = given()
                .filter(signer)
                .contentType("application/xml")
                .body("<Delete><Object><Key>" + key + "</Key></Object></Delete>")
        .when()
                .post("/" + bucket + "?delete")
        .then()
                .statusCode(200)
                .extract().asString();
        return XmlPath.from(response);
    }

    private static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static void createBucket(String bucket, S3RequestSigner signer) {
        given().filter(signer).when().put("/" + bucket).then().statusCode(200);
    }

    private static void put(String bucket, String key, S3RequestSigner signer) {
        given().filter(signer).contentType("text/plain").body("seed")
                .when().put("/" + bucket + "/" + key).then().statusCode(200);
    }

    private static void assertPresent(String bucket, String key, S3RequestSigner signer) {
        given().filter(signer).when().head("/" + bucket + "/" + key).then().statusCode(200);
    }

    private static void bucketPolicy(String bucket, S3RequestSigner signer, String statement) {
        given().filter(signer).contentType("application/json")
                .body("{\"Version\":\"2012-10-17\",\"Statement\":[" + statement + "]}")
                .when().put("/" + bucket + "?policy").then().statusCode(200);
    }

    private UserCredentials createUser(String user, String accountKey) {
        return EnforcementFixtures.createUser(cleanup, baseUri.getPort(), auth(accountKey, "iam"), user);
    }

    private static S3RequestSigner signer(UserCredentials credentials) {
        return S3RequestSigner.signedAs(credentials.accessKeyId(), credentials.secretAccessKey());
    }

    private static void userPolicy(String user, String accountKey, String statement) {
        given().formParam("Action", "PutUserPolicy").formParam("UserName", user).formParam("PolicyName", "p")
                .formParam("PolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[" + statement + "]}")
                .header("Authorization", auth(accountKey, "iam")).when().post("/").then().statusCode(200);
    }
}
