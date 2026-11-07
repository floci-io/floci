package io.floci.aws.services.s3;

import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * A CreateBucket whose headers configure the new bucket needs more than {@code s3:CreateBucket}. The
 * CreateBucket API reference lists them: s3:PutBucketAcl for an ACL other than {@code private} or a
 * grant header, s3:PutBucketObjectLockConfiguration and s3:PutBucketVersioning for Object Lock, and
 * s3:PutBucketOwnershipControls for {@code x-amz-object-ownership}. A denied request creates nothing.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class S3BucketCreationIamEnforcementIntegrationTest {

    private static final String ADMIN = "test";

    @TestHTTPResource("/")
    static URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void createBucket_objectLockWithoutBothPermissions_isDeniedAndCreatesNothing() {
        String user = uniqueName("create-lock-deny");
        String accessKeyId = createUser(user);

        userPolicy(user, List.of("s3:CreateBucket"));
        assertDenied(accessKeyId, uniqueName("lock-none"), "x-amz-bucket-object-lock-enabled", "true");

        userPolicy(user, List.of("s3:CreateBucket", "s3:PutBucketObjectLockConfiguration"));
        assertDenied(accessKeyId, uniqueName("lock-no-versioning"), "x-amz-bucket-object-lock-enabled", "true");

        userPolicy(user, List.of("s3:CreateBucket", "s3:PutBucketVersioning"));
        assertDenied(accessKeyId, uniqueName("lock-no-config"), "x-amz-bucket-object-lock-enabled", "true");
    }

    @Test
    void createBucket_objectLockWithBothPermissions_createsALockedBucket() {
        String user = uniqueName("create-lock-allow");
        String accessKeyId = createUser(user);
        userPolicy(user, List.of("s3:CreateBucket", "s3:PutBucketObjectLockConfiguration", "s3:PutBucketVersioning"));
        String bucket = uniqueName("lock-allowed");

        assertCreated(accessKeyId, bucket, "x-amz-bucket-object-lock-enabled", "true");

        admin().when().get("/" + bucket + "?object-lock")
                .then().statusCode(200).body(containsString("<ObjectLockEnabled>Enabled</ObjectLockEnabled>"));
    }

    @Test
    void createBucket_objectOwnership_needsPutBucketOwnershipControls() {
        String user = uniqueName("create-ownership");
        String accessKeyId = createUser(user);

        userPolicy(user, List.of("s3:CreateBucket"));
        assertDenied(accessKeyId, uniqueName("ownership-denied"), "x-amz-object-ownership", "BucketOwnerPreferred");

        userPolicy(user, List.of("s3:CreateBucket", "s3:PutBucketOwnershipControls"));
        assertCreated(accessKeyId, uniqueName("ownership-allowed"), "x-amz-object-ownership", "BucketOwnerPreferred");
    }

    @Test
    void createBucket_privateCannedAcl_needsOnlyCreateBucket() {
        String user = uniqueName("create-private");
        String accessKeyId = createUser(user);
        userPolicy(user, List.of("s3:CreateBucket"));

        assertCreated(accessKeyId, uniqueName("acl-private"), "x-amz-acl", "private");
    }

    @Test
    void createBucket_otherCannedAcl_needsPutBucketAcl() {
        String user = uniqueName("create-canned");
        String accessKeyId = createUser(user);
        userPolicy(user, List.of("s3:CreateBucket"));

        assertDenied(accessKeyId, uniqueName("acl-canned"), "x-amz-acl", "authenticated-read");
    }

    @Test
    void createBucket_grantHeader_needsPutBucketAcl() {
        String user = uniqueName("create-grant");
        String accessKeyId = createUser(user);

        userPolicy(user, List.of("s3:CreateBucket", "s3:PutBucketOwnershipControls"));
        String denied = uniqueName("grant-denied");
        createRequest(accessKeyId, "x-amz-object-ownership", "ObjectWriter")
                .header("x-amz-grant-read", "id=\"123456789012\"")
                .when().put("/" + denied)
                .then().statusCode(403).body(containsString("AccessDenied"));
        assertMissing(denied);

        userPolicy(user, List.of("s3:CreateBucket", "s3:PutBucketOwnershipControls", "s3:PutBucketAcl"));
        String allowed = uniqueName("grant-allowed");
        cleanup.register(() -> deleteBucket(allowed));
        createRequest(accessKeyId, "x-amz-object-ownership", "ObjectWriter")
                .header("x-amz-grant-read", "id=\"123456789012\"")
                .when().put("/" + allowed)
                .then().statusCode(200);
    }

    private void assertDenied(String accessKeyId, String bucket, String header, String value) {
        createRequest(accessKeyId, header, value)
                .when().put("/" + bucket)
                .then().statusCode(403).body(containsString("AccessDenied"));
        assertMissing(bucket);
    }

    private void assertCreated(String accessKeyId, String bucket, String header, String value) {
        cleanup.register(() -> deleteBucket(bucket));
        createRequest(accessKeyId, header, value)
                .when().put("/" + bucket)
                .then().statusCode(200);
        admin().when().head("/" + bucket).then().statusCode(200);
    }

    private static RequestSpecification createRequest(String accessKeyId, String header, String value) {
        return given().header("Authorization", auth(accessKeyId, "s3")).header(header, value);
    }

    private static void assertMissing(String bucket) {
        admin().when().head("/" + bucket).then().statusCode(404);
    }

    private static void deleteBucket(String bucket) {
        admin().when().delete("/" + bucket).then().statusCode(204);
    }

    private static RequestSpecification admin() {
        return given().header("Authorization", auth(ADMIN, "s3"));
    }

    private static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String createUser(String user) {
        return EnforcementFixtures.createUser(cleanup, baseUri.getPort(), auth(ADMIN, "iam"), user).accessKeyId();
    }

    private void userPolicy(String user, List<String> actions) {
        String actionList = String.join("\",\"", actions);
        String statement = "{\"Effect\":\"Allow\",\"Action\":[\"" + actionList + "\"],\"Resource\":\"*\"}";
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), auth(ADMIN, "iam"), user, "p",
                "{\"Version\":\"2012-10-17\",\"Statement\":[" + statement + "]}");
    }
}
