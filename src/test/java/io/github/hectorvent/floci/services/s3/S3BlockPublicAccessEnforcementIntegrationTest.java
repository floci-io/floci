package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.testutil.S3RequestSigner;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * The read-time half of Block Public Access: {@code RestrictPublicBuckets} withholds the access a
 * public bucket policy grants, and {@code IgnorePublicAcls} withholds the access a public ACL
 * grants. Both act on an existing policy or ACL rather than on the write that created it, so
 * they only have meaning once anonymous authorization runs, which is what {@code enforce-auth}
 * turns on.
 */
@QuarkusTest
@TestProfile(S3BlockPublicAccessEnforcementIntegrationTest.EnforcementProfile.class)
class S3BlockPublicAccessEnforcementIntegrationTest {

    private static final S3RequestSigner OWNER = S3RequestSigner.signedAs("test", "test");

    private static final String PUBLIC_READ_POLICY = """
            {"Version":"2012-10-17","Statement":[{"Sid":"PublicRead","Effect":"Allow",
            "Principal":"*","Action":["s3:GetObject","s3:ListBucket"],
            "Resource":["arn:aws:s3:::%1$s","arn:aws:s3:::%1$s/*"]}]}
            """;

    public static final class EnforcementProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.s3.enforce-auth", "true");
        }
    }

    @Test
    void anonymousGetSucceedsOnAPublicPolicyWithoutRestrictPublicBuckets() {
        String bucket = bucketWithPublicPolicy();

        given().when().get("/" + bucket + "/object.txt")
                .then().statusCode(200).body(equalTo("hello"));
    }

    @Test
    void restrictPublicBucketsWithholdsAnonymousGetOnAPublicPolicy() {
        String bucket = bucketWithPublicPolicy();
        putBucketPublicAccessBlock(bucket, false, false, false, true);

        given().when().get("/" + bucket + "/object.txt")
                .then().statusCode(403)
                .body(containsString("AccessDenied"));
    }

    @Test
    void restrictPublicBucketsWithholdsAnonymousListBucketOnAPublicPolicy() {
        String bucket = bucketWithPublicPolicy();
        putBucketPublicAccessBlock(bucket, false, false, false, true);

        given().when().get("/" + bucket)
                .then().statusCode(403)
                .body(containsString("AccessDenied"));
    }

    @Test
    void restrictPublicBucketsLeavesTheOwnersSignedAccessAlone() {
        String bucket = bucketWithPublicPolicy();
        putBucketPublicAccessBlock(bucket, false, false, false, true);

        given().filter(OWNER).when().get("/" + bucket + "/object.txt")
                .then().statusCode(200).body(equalTo("hello"));
    }

    @Test
    void restrictPublicBucketsDoesNotAffectABucketWhosePolicyIsNotPublic() {
        String bucket = createBucket();
        putObject(bucket, "object.txt");
        putBucketPolicy(bucket, """
                {"Version":"2012-10-17","Statement":[{"Sid":"OneAccount","Effect":"Allow",
                "Principal":{"AWS":"arn:aws:iam::111122223333:root"},"Action":"s3:GetObject",
                "Resource":"arn:aws:s3:::%s/*"}]}
                """.formatted(bucket));
        putBucketPublicAccessBlock(bucket, false, false, false, true);

        given().filter(OWNER).when().get("/" + bucket + "/object.txt")
                .then().statusCode(200).body(equalTo("hello"));
    }

    @Test
    void theAccountLevelRestrictPublicBucketsWithholdsAnonymousGet() {
        String bucket = bucketWithPublicPolicy();
        putAccountPublicAccessBlock(false, false, false, true);
        try {
            given().when().get("/" + bucket + "/object.txt")
                    .then().statusCode(403)
                    .body(containsString("AccessDenied"));
        } finally {
            deleteAccountPublicAccessBlock();
        }
    }

    @Test
    void anonymousGetSucceedsOnAPublicObjectAclWithoutIgnorePublicAcls() {
        String bucket = bucketWithPublicObjectAcl();

        given().when().get("/" + bucket + "/object.txt")
                .then().statusCode(200).body(equalTo("hello"));
    }

    @Test
    void ignorePublicAclsWithholdsAnonymousGetOnAPublicObjectAcl() {
        String bucket = bucketWithPublicObjectAcl();
        putBucketPublicAccessBlock(bucket, false, true, false, false);

        given().when().get("/" + bucket + "/object.txt")
                .then().statusCode(403)
                .body(containsString("AccessDenied"));
    }

    @Test
    void ignorePublicAclsWithholdsAnonymousListBucketOnAPublicBucketAcl() {
        String bucket = createBucket();
        putObject(bucket, "object.txt");
        given().filter(OWNER).header("x-amz-acl", "public-read")
                .when().put("/" + bucket + "?acl")
                .then().statusCode(200);

        given().when().get("/" + bucket).then().statusCode(200);

        putBucketPublicAccessBlock(bucket, false, true, false, false);

        given().when().get("/" + bucket)
                .then().statusCode(403)
                .body(containsString("AccessDenied"));
    }

    @Test
    void ignorePublicAclsLeavesTheOwnersSignedAccessAlone() {
        String bucket = bucketWithPublicObjectAcl();
        putBucketPublicAccessBlock(bucket, false, true, false, false);

        given().filter(OWNER).when().get("/" + bucket + "/object.txt")
                .then().statusCode(200).body(equalTo("hello"));
    }

    private static String bucketWithPublicPolicy() {
        String bucket = createBucket();
        putObject(bucket, "object.txt");
        putBucketPolicy(bucket, PUBLIC_READ_POLICY.formatted(bucket));
        return bucket;
    }

    private static String bucketWithPublicObjectAcl() {
        String bucket = createBucket();
        putObject(bucket, "object.txt");
        given().filter(OWNER).header("x-amz-acl", "public-read")
                .when().put("/" + bucket + "/object.txt?acl")
                .then().statusCode(200);
        return bucket;
    }

    private static String createBucket() {
        String bucket = "bpa-enforce-" + UUID.randomUUID();
        given().filter(OWNER).when().put("/" + bucket).then().statusCode(200);
        return bucket;
    }

    private static void putObject(String bucket, String key) {
        given().filter(OWNER).body("hello")
                .when().put("/" + bucket + "/" + key)
                .then().statusCode(200);
    }

    private static void putBucketPolicy(String bucket, String policy) {
        given().filter(OWNER).body(policy)
                .when().put("/" + bucket + "?policy")
                .then().statusCode(200);
    }

    private static void putBucketPublicAccessBlock(String bucket, boolean blockPublicAcls,
                                                   boolean ignorePublicAcls, boolean blockPublicPolicy,
                                                   boolean restrictPublicBuckets) {
        given().filter(OWNER)
                .body(configuration(blockPublicAcls, ignorePublicAcls, blockPublicPolicy, restrictPublicBuckets))
                .when().put("/" + bucket + "?publicAccessBlock")
                .then().statusCode(200);
    }

    private static void putAccountPublicAccessBlock(boolean blockPublicAcls, boolean ignorePublicAcls,
                                                    boolean blockPublicPolicy, boolean restrictPublicBuckets) {
        given().filter(OWNER).header("x-amz-account-id", "000000000000")
                .body(configuration(blockPublicAcls, ignorePublicAcls, blockPublicPolicy, restrictPublicBuckets))
                .when().put("/v20180820/configuration/publicAccessBlock")
                .then().statusCode(200);
    }

    private static void deleteAccountPublicAccessBlock() {
        given().filter(OWNER).header("x-amz-account-id", "000000000000")
                .when().delete("/v20180820/configuration/publicAccessBlock")
                .then().statusCode(204);
    }

    private static String configuration(boolean blockPublicAcls, boolean ignorePublicAcls,
                                        boolean blockPublicPolicy, boolean restrictPublicBuckets) {
        return """
                <PublicAccessBlockConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                  <BlockPublicAcls>%s</BlockPublicAcls>
                  <IgnorePublicAcls>%s</IgnorePublicAcls>
                  <BlockPublicPolicy>%s</BlockPublicPolicy>
                  <RestrictPublicBuckets>%s</RestrictPublicBuckets>
                </PublicAccessBlockConfiguration>
                """.formatted(blockPublicAcls, ignorePublicAcls, blockPublicPolicy, restrictPublicBuckets);
    }
}
