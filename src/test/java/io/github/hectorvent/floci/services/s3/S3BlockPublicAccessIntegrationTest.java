package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * The write-time half of Block Public Access: {@code BlockPublicPolicy} and
 * {@code BlockPublicAcls} reject the call that would introduce public access.
 *
 * <p>AWS applies both whoever the caller is, so these run under the default configuration with
 * no {@code enforce-auth} and no IAM enforcement. The read-time half
 * ({@code RestrictPublicBuckets}, {@code IgnorePublicAcls}) only has meaning once anonymous
 * authorization runs at all, and is covered by
 * {@link S3BlockPublicAccessEnforcementIntegrationTest}.
 */
@QuarkusTest
class S3BlockPublicAccessIntegrationTest {

    private static final String PUBLIC_POLICY = """
            {"Version":"2012-10-17","Statement":[{"Sid":"PublicRead","Effect":"Allow",
            "Principal":"*","Action":"s3:GetObject","Resource":"arn:aws:s3:::%s/*"}]}
            """;

    private static final String ACCOUNT_SCOPED_POLICY = """
            {"Version":"2012-10-17","Statement":[{"Sid":"OneAccount","Effect":"Allow",
            "Principal":{"AWS":"arn:aws:iam::111122223333:root"},"Action":"s3:GetObject",
            "Resource":"arn:aws:s3:::%s/*"}]}
            """;

    @Test
    void blockPublicPolicyRejectsAPublicBucketPolicy() {
        String bucket = createBucket();
        putBucketPublicAccessBlock(bucket, false, false, true, false);

        given().body(PUBLIC_POLICY.formatted(bucket))
                .when().put("/" + bucket + "?policy")
                .then().statusCode(403)
                .body(containsString("AccessDenied"));
    }

    @Test
    void blockPublicPolicyLeavesANonPublicBucketPolicyAlone() {
        String bucket = createBucket();
        putBucketPublicAccessBlock(bucket, false, false, true, false);

        given().body(ACCOUNT_SCOPED_POLICY.formatted(bucket))
                .when().put("/" + bucket + "?policy")
                .then().statusCode(200);
    }

    @Test
    void blockPublicPolicyAcceptsFixedAccountAccessPointWildcard() {
        String bucket = createBucket();
        putBucketPublicAccessBlock(bucket, false, false, true, false);
        String policy = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Principal":"*","Action":"s3:GetObject","Resource":"arn:aws:s3:::%s/*",
                "Condition":{"ArnLike":{"s3:DataAccessPointArn":
                "arn:aws:s3:us-west-2:123456789012:accesspoint/*"}}}]}
                """.formatted(bucket);

        given().body(policy)
                .when().put("/" + bucket + "?policy")
                .then().statusCode(200);
    }

    @Test
    void blockPublicPolicyAcceptsForAnyValueWithFixedPrincipalOrgPath() {
        String bucket = createBucket();
        putBucketPublicAccessBlock(bucket, false, false, true, false);
        String policy = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Principal":"*","Action":"s3:GetObject","Resource":"arn:aws:s3:::%s/*",
                "Condition":{"ForAnyValue:StringLike":{"aws:PrincipalOrgPaths":
                ["o-a1b2c3d4e5/r-ab12/ou-ab12-11111111/"]}}}]}
                """.formatted(bucket);

        given().body(policy)
                .when().put("/" + bucket + "?policy")
                .then().statusCode(200);
    }

    @Test
    void aPublicBucketPolicyIsAcceptedWithoutBlockPublicPolicy() {
        String bucket = createBucket();

        given().body(PUBLIC_POLICY.formatted(bucket))
                .when().put("/" + bucket + "?policy")
                .then().statusCode(200);
    }

    @Test
    void blockPublicPolicyDoesNotRemoveAPolicyStoredBeforeItWasSet() {
        String bucket = createBucket();
        given().body(PUBLIC_POLICY.formatted(bucket))
                .when().put("/" + bucket + "?policy")
                .then().statusCode(200);

        putBucketPublicAccessBlock(bucket, false, false, true, false);

        given().when().get("/" + bucket + "?policy")
                .then().statusCode(200)
                .body(containsString("PublicRead"));
    }

    @Test
    void theAccountLevelSettingBlocksAPublicPolicyOnItsOwn() {
        String bucket = createBucket();
        putAccountPublicAccessBlock(false, false, true, false);
        try {
            given().body(PUBLIC_POLICY.formatted(bucket))
                    .when().put("/" + bucket + "?policy")
                    .then().statusCode(403)
                    .body(containsString("AccessDenied"));
        } finally {
            deleteAccountPublicAccessBlock();
        }
    }

    @Test
    void blockPublicAclsRejectsAPublicBucketAcl() {
        String bucket = createBucket();
        putBucketPublicAccessBlock(bucket, true, false, false, false);

        given().header("x-amz-acl", "public-read")
                .when().put("/" + bucket + "?acl")
                .then().statusCode(403)
                .body(containsString("AccessDenied"));
    }

    @Test
    void blockPublicAclsLeavesAPrivateBucketAclAlone() {
        String bucket = createBucket();
        putBucketPublicAccessBlock(bucket, true, false, false, false);

        given().header("x-amz-acl", "private")
                .when().put("/" + bucket + "?acl")
                .then().statusCode(200);
    }

    @Test
    void blockPublicAclsRejectsAPublicObjectAcl() {
        String bucket = createBucket();
        given().body("hello").when().put("/" + bucket + "/object.txt").then().statusCode(200);
        putBucketPublicAccessBlock(bucket, true, false, false, false);

        given().header("x-amz-acl", "public-read")
                .when().put("/" + bucket + "/object.txt?acl")
                .then().statusCode(403)
                .body(containsString("AccessDenied"));
    }

    @Test
    void blockPublicAclsRejectsAPutObjectCarryingAPublicAcl() {
        String bucket = createBucket();
        putBucketPublicAccessBlock(bucket, true, false, false, false);

        given().header("x-amz-acl", "public-read").body("hello")
                .when().put("/" + bucket + "/public.txt")
                .then().statusCode(403)
                .body(containsString("AccessDenied"));
    }

    @Test
    void blockPublicAclsLeavesAPutObjectWithoutAPublicAclAlone() {
        String bucket = createBucket();
        putBucketPublicAccessBlock(bucket, true, false, false, false);

        given().body("hello")
                .when().put("/" + bucket + "/private.txt")
                .then().statusCode(200);
    }

    @Test
    void anAuthenticatedUsersAclCountsAsPublic() {
        String bucket = createBucket();
        putBucketPublicAccessBlock(bucket, true, false, false, false);

        given().header("x-amz-acl", "authenticated-read")
                .when().put("/" + bucket + "?acl")
                .then().statusCode(403)
                .body(containsString("AccessDenied"));
    }

    private static String createBucket() {
        String bucket = "bpa-" + UUID.randomUUID();
        given().when().put("/" + bucket).then().statusCode(200);
        return bucket;
    }

    private static void putBucketPublicAccessBlock(String bucket, boolean blockPublicAcls,
                                                   boolean ignorePublicAcls, boolean blockPublicPolicy,
                                                   boolean restrictPublicBuckets) {
        given().body(configuration(blockPublicAcls, ignorePublicAcls, blockPublicPolicy, restrictPublicBuckets))
                .when().put("/" + bucket + "?publicAccessBlock")
                .then().statusCode(200);
    }

    private static void putAccountPublicAccessBlock(boolean blockPublicAcls, boolean ignorePublicAcls,
                                                    boolean blockPublicPolicy, boolean restrictPublicBuckets) {
        given().header("x-amz-account-id", "000000000000")
                .body(configuration(blockPublicAcls, ignorePublicAcls, blockPublicPolicy, restrictPublicBuckets))
                .when().put("/v20180820/configuration/publicAccessBlock")
                .then().statusCode(200);
    }

    private static void deleteAccountPublicAccessBlock() {
        given().header("x-amz-account-id", "000000000000")
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
