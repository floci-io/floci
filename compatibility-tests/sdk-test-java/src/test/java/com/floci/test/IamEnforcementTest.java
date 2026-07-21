package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.*;

import java.util.logging.Level;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.*;

/**
 * End-to-end compatibility tests for IAM Enforcement Mode.
 *
 * <p>These tests require the Floci instance to be running with
 * {@code floci.services.iam.enforcement-enabled=true}.
 * When enforcement is disabled (the default), all tests are skipped via
 * {@link Assumptions} so the standard test-suite always passes.
 *
 * <p>Scenarios covered:
 * <ul>
 *   <li>User with no policy → DENY</li>
 *   <li>User with explicit allow policy → ALLOW</li>
 *   <li>Explicit Deny inline policy overrides attached Allow → DENY</li>
 *   <li>Wildcard action policy grants access → ALLOW</li>
 *   <li>Assumed role with no policies → DENY</li>
 *   <li>Assumed role with attached allow policy → ALLOW</li>
 *   <li>Assumed role session policy deny overrides role allow → DENY</li>
 *   <li>Assumed role session policy ListBucket prefix condition → matching prefix only</li>
 * </ul>
 */
@DisplayName("IAM Enforcement Mode")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IamEnforcementTest {

    private static final Logger LOG = Logger.getLogger(IamEnforcementTest.class.getName());

    // ── Resource names ─────────────────────────────────────────────────────────
    private static final String USER        = "iam-enf-test-user";
    private static final String ROLE        = "iam-enf-test-role";
    private static final String POLICY_NAME = "iam-enf-allow-s3list";

    private static final String TRUST_POLICY = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Principal":{"AWS":"arn:aws:iam::000000000000:root"},
               "Action":"sts:AssumeRole"}
            ]}""";

    // s3:ListAllMyBuckets is the IAM action for ListBuckets — simple, resource-agnostic.
    private static final String ALLOW_S3_LIST_POLICY = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"s3:ListAllMyBuckets","Resource":"*"}
            ]}""";

    private static final String DENY_S3_LIST_POLICY = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Deny","Action":"s3:ListAllMyBuckets","Resource":"*"}
            ]}""";

    private static final String ALLOW_S3_WILDCARD_POLICY = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"s3:*","Resource":"*"}
            ]}""";

    private static final String DENY_S3_LIST_SESSION_POLICY = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"s3:ListAllMyBuckets","Resource":"*"},
              {"Effect":"Deny","Action":"s3:ListAllMyBuckets","Resource":"*"}
            ]}""";

    private static final String ALLOW_S3_LIST_PREFIX_SESSION_POLICY = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"s3:ListBucket","Resource":"arn:aws:s3:::%s",
               "Condition":{"StringLike":{"s3:prefix":["%s","%s*"]}}},
              {"Effect":"Allow","Action":["s3:GetObject","s3:PutObject","s3:DeleteObject"],
               "Resource":"arn:aws:s3:::%s/%s*"}
            ]}""";

    private static final String ALLOW_S3_BUCKET_POLICY = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"s3:ListBucket","Resource":"arn:aws:s3:::%s"},
              {"Effect":"Allow","Action":["s3:GetObject","s3:PutObject","s3:DeleteObject"],
               "Resource":"arn:aws:s3:::%s/*"}
            ]}""";

    // ── Shared state ───────────────────────────────────────────────────────────
    private static IamClient iam;
    private static StsClient sts;
    private static String userAccessKeyId;
    private static String userSecretKey;
    private static String roleArn;
    private static String allowPolicyArn;
    /** Cached at @BeforeAll before any policies are attached. */
    private static boolean enforcementEnabled;

    @BeforeAll
    static void setup() {
        iam = TestFixtures.iamClient();
        sts = TestFixtures.stsClient();

        // Create the test user
        iam.createUser(CreateUserRequest.builder().userName(USER).build());

        // Create an access key for the test user
        CreateAccessKeyResponse keyResp = iam.createAccessKey(
                CreateAccessKeyRequest.builder().userName(USER).build());
        userAccessKeyId = keyResp.accessKey().accessKeyId();
        userSecretKey   = keyResp.accessKey().secretAccessKey();

        // Create the test role (no policies attached initially)
        CreateRoleResponse roleResp = iam.createRole(CreateRoleRequest.builder()
                .roleName(ROLE)
                .assumeRolePolicyDocument(TRUST_POLICY)
                .build());
        roleArn = roleResp.role().arn();

        // Create a managed allow-s3-list policy (attached/detached per test)
        CreatePolicyResponse policyResp = iam.createPolicy(CreatePolicyRequest.builder()
                .policyName(POLICY_NAME)
                .policyDocument(ALLOW_S3_LIST_POLICY)
                .build());
        allowPolicyArn = policyResp.policy().arn();

        // Probe enforcement ONCE before any policies are attached to the test user.
        // The user currently has zero policies — if enforcement is on, ListBuckets → 403.
        enforcementEnabled = probeEnforcementEnabled();
    }

    @AfterAll
    static void cleanup() {
        if (iam == null) return;
        try { iam.detachRolePolicy(DetachRolePolicyRequest.builder()
                .roleName(ROLE).policyArn(allowPolicyArn).build()); } catch (Exception ignored) {}
        try { iam.detachUserPolicy(DetachUserPolicyRequest.builder()
                .userName(USER).policyArn(allowPolicyArn).build()); } catch (Exception ignored) {}
        try { iam.deleteUserPolicy(DeleteUserPolicyRequest.builder()
                .userName(USER).policyName("inline-deny").build()); } catch (Exception ignored) {}
        cleanupResource("delete inline-prefix-allow policy from role " + ROLE,
                () -> iam.deleteRolePolicy(DeleteRolePolicyRequest.builder()
                        .roleName(ROLE).policyName("inline-prefix-allow").build()));
        try { iam.deleteAccessKey(DeleteAccessKeyRequest.builder()
                .userName(USER).accessKeyId(userAccessKeyId).build()); } catch (Exception ignored) {}
        try { iam.deleteRole(DeleteRoleRequest.builder().roleName(ROLE).build()); } catch (Exception ignored) {}
        try { iam.deletePolicy(DeletePolicyRequest.builder().policyArn(allowPolicyArn).build()); } catch (Exception ignored) {}
        try { iam.deleteUser(DeleteUserRequest.builder().userName(USER).build()); } catch (Exception ignored) {}
        iam.close();
        sts.close();
    }

    // ── Client factories ───────────────────────────────────────────────────────

    private static S3Client s3WithCredentials(String akid, String secret) {
        return S3Client.builder()
                .endpointOverride(TestFixtures.endpoint())
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(akid, secret)))
                .forcePathStyle(true)
                .build();
    }

    private static S3Client s3WithSessionCredentials(
            String akid, String secret, String sessionToken) {
        return S3Client.builder()
                .endpointOverride(TestFixtures.endpoint())
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsSessionCredentials.create(akid, secret, sessionToken)))
                .forcePathStyle(true)
                .build();
    }

    /**
     * Probes whether enforcement is active by calling ListBuckets with
     * the test user (who must have NO attached policies at this point).
     *
     * Returns {@code true} if the call is denied (HTTP 403), {@code false}
     * if the call succeeds (enforcement disabled).
     *
     * Must be called from {@code @BeforeAll} before any policies are attached.
     */
    private static boolean probeEnforcementEnabled() {
        try (S3Client s3 = s3WithCredentials(userAccessKeyId, userSecretKey)) {
            s3.listBuckets();
            return false;
        } catch (S3Exception e) {
            return e.statusCode() == 403;
        }
    }

    private static void assumeEnforcementEnabled() {
        Assumptions.assumeTrue(enforcementEnabled,
                "IAM enforcement is not enabled — set floci.services.iam.enforcement-enabled=true to run these tests");
    }

    // ── Tests ──────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("user with no policy is denied")
    void noPolicyGetsDenied() {
        assumeEnforcementEnabled();

        try (S3Client s3 = s3WithCredentials(userAccessKeyId, userSecretKey)) {
            assertThatThrownBy(s3::listBuckets)
                    .isInstanceOf(S3Exception.class)
                    .extracting(e -> ((S3Exception) e).statusCode())
                    .isEqualTo(403);
        }
    }

    @Test
    @Order(2)
    @DisplayName("attached allow policy grants access")
    void allowPolicyGrantsAccess() {
        assumeEnforcementEnabled();

        iam.attachUserPolicy(AttachUserPolicyRequest.builder()
                .userName(USER).policyArn(allowPolicyArn).build());

        try (S3Client s3 = s3WithCredentials(userAccessKeyId, userSecretKey)) {
            assertThatCode(s3::listBuckets).doesNotThrowAnyException();
        }
    }

    @Test
    @Order(3)
    @DisplayName("explicit inline Deny overrides attached Allow")
    void explicitDenyOverridesAllow() {
        assumeEnforcementEnabled();
        // allow policy was attached in @Order(2); add an inline deny on top

        iam.putUserPolicy(PutUserPolicyRequest.builder()
                .userName(USER)
                .policyName("inline-deny")
                .policyDocument(DENY_S3_LIST_POLICY)
                .build());

        try (S3Client s3 = s3WithCredentials(userAccessKeyId, userSecretKey)) {
            assertThatThrownBy(s3::listBuckets)
                    .isInstanceOf(S3Exception.class)
                    .extracting(e -> ((S3Exception) e).statusCode())
                    .isEqualTo(403);
        }

        // Remove the inline deny; restore to allow-only state
        iam.deleteUserPolicy(DeleteUserPolicyRequest.builder()
                .userName(USER).policyName("inline-deny").build());
    }

    @Test
    @Order(4)
    @DisplayName("wildcard action policy (s3:*) grants access")
    void wildcardActionPolicyGrantsAccess() {
        assumeEnforcementEnabled();
        // Detach the specific allow; replace with a wildcard s3:* inline policy

        iam.detachUserPolicy(DetachUserPolicyRequest.builder()
                .userName(USER).policyArn(allowPolicyArn).build());
        iam.putUserPolicy(PutUserPolicyRequest.builder()
                .userName(USER)
                .policyName("inline-s3-wildcard")
                .policyDocument(ALLOW_S3_WILDCARD_POLICY)
                .build());

        try (S3Client s3 = s3WithCredentials(userAccessKeyId, userSecretKey)) {
            assertThatCode(s3::listBuckets).doesNotThrowAnyException();
        }

        // Cleanup inline policy
        iam.deleteUserPolicy(DeleteUserPolicyRequest.builder()
                .userName(USER).policyName("inline-s3-wildcard").build());
    }

    @Test
    @Order(5)
    @DisplayName("assumed role with no policies is denied")
    void assumedRoleNoPolicyGetsDenied() {
        assumeEnforcementEnabled();
        // Role has no policies attached

        AssumeRoleResponse assumed = sts.assumeRole(AssumeRoleRequest.builder()
                .roleArn(roleArn)
                .roleSessionName("enf-test-no-policy")
                .build());

        try (S3Client s3 = s3WithSessionCredentials(
                assumed.credentials().accessKeyId(),
                assumed.credentials().secretAccessKey(),
                assumed.credentials().sessionToken())) {
            assertThatThrownBy(s3::listBuckets)
                    .isInstanceOf(S3Exception.class)
                    .extracting(e -> ((S3Exception) e).statusCode())
                    .isEqualTo(403);
        }
    }

    @Test
    @Order(6)
    @DisplayName("assumed role with allow policy grants access")
    void assumedRoleWithAllowPolicyGrantsAccess() {
        assumeEnforcementEnabled();

        iam.attachRolePolicy(AttachRolePolicyRequest.builder()
                .roleName(ROLE).policyArn(allowPolicyArn).build());

        AssumeRoleResponse assumed = sts.assumeRole(AssumeRoleRequest.builder()
                .roleArn(roleArn)
                .roleSessionName("enf-test-with-policy")
                .build());

        try (S3Client s3 = s3WithSessionCredentials(
                assumed.credentials().accessKeyId(),
                assumed.credentials().secretAccessKey(),
                assumed.credentials().sessionToken())) {
            assertThatCode(s3::listBuckets).doesNotThrowAnyException();
        }
    }

    @Test
    @Order(7)
    @DisplayName("assumed role session policy deny overrides role allow")
    void assumedRoleSessionPolicyDenyOverridesRoleAllow() {
        assumeEnforcementEnabled();

        iam.attachRolePolicy(AttachRolePolicyRequest.builder()
                .roleName(ROLE).policyArn(allowPolicyArn).build());

        AssumeRoleResponse assumed = sts.assumeRole(AssumeRoleRequest.builder()
                .roleArn(roleArn)
                .roleSessionName("enf-test-session-deny")
                .policy(DENY_S3_LIST_SESSION_POLICY)
                .build());

        try (S3Client s3 = s3WithSessionCredentials(
                assumed.credentials().accessKeyId(),
                assumed.credentials().secretAccessKey(),
                assumed.credentials().sessionToken())) {
            assertThatThrownBy(s3::listBuckets)
                    .isInstanceOf(S3Exception.class)
                    .extracting(e -> ((S3Exception) e).statusCode())
                    .isEqualTo(403);
        }
    }

    @Test
    @Order(8)
    @DisplayName("assumed role session policy ListBucket prefix condition restricts ListObjectsV2")
    void assumedRoleSessionPolicyListBucketPrefixConditionRestrictsListObjectsV2() {
        assumeEnforcementEnabled();

        String bucket = TestFixtures.uniqueName("iam-prefix");
        String allowedPrefix = "my_namespace/table/";
        String allowedKey = allowedPrefix + "metadata.json";
        String deniedPrefix = "other_namespace/table/";
        String deniedKey = deniedPrefix + "metadata.json";
        String inlinePolicyName = "inline-prefix-allow";

        try (S3Client adminS3 = TestFixtures.s3Client()) {
            try {
                adminS3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                iam.putRolePolicy(PutRolePolicyRequest.builder()
                        .roleName(ROLE)
                        .policyName(inlinePolicyName)
                        .policyDocument(ALLOW_S3_BUCKET_POLICY.formatted(bucket, bucket))
                        .build());
                adminS3.putObject(PutObjectRequest.builder().bucket(bucket).key(allowedKey).build(),
                        RequestBody.fromString("{}"));
                adminS3.putObject(PutObjectRequest.builder().bucket(bucket).key(deniedKey).build(),
                        RequestBody.fromString("{}"));

                AssumeRoleResponse assumed = sts.assumeRole(AssumeRoleRequest.builder()
                        .roleArn(roleArn)
                        .roleSessionName("enf-test-prefix-condition")
                        .policy(ALLOW_S3_LIST_PREFIX_SESSION_POLICY.formatted(
                                bucket, allowedPrefix, allowedPrefix, bucket, allowedPrefix))
                        .build());

                try (S3Client s3 = s3WithSessionCredentials(
                        assumed.credentials().accessKeyId(),
                        assumed.credentials().secretAccessKey(),
                        assumed.credentials().sessionToken())) {
                    ListObjectsV2Response allowed = s3.listObjectsV2(ListObjectsV2Request.builder()
                            .bucket(bucket)
                            .prefix(allowedPrefix)
                            .build());

                    assertThat(allowed.contents())
                            .anyMatch(object -> allowedKey.equals(object.key()));

                    assertThatThrownBy(() -> s3.listObjectsV2(ListObjectsV2Request.builder()
                            .bucket(bucket)
                            .prefix(deniedPrefix)
                            .build()))
                            .isInstanceOf(S3Exception.class)
                            .extracting(e -> ((S3Exception) e).statusCode())
                            .isEqualTo(403);
                }
            } finally {
                cleanupResource("delete inline role policy " + inlinePolicyName + " from role " + ROLE,
                        () -> iam.deleteRolePolicy(DeleteRolePolicyRequest.builder()
                                .roleName(ROLE).policyName(inlinePolicyName).build()));
                cleanupResource("delete S3 object " + bucket + "/" + allowedKey,
                        () -> adminS3.deleteObject(DeleteObjectRequest.builder()
                                .bucket(bucket).key(allowedKey).build()));
                cleanupResource("delete S3 object " + bucket + "/" + deniedKey,
                        () -> adminS3.deleteObject(DeleteObjectRequest.builder()
                                .bucket(bucket).key(deniedKey).build()));
                cleanupResource("delete S3 bucket " + bucket,
                        () -> adminS3.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build()));
            }
        }
    }

    private static void cleanupResource(String description, CleanupAction action) {
        try {
            action.run();
        } catch (Exception e) {
            LOG.log(Level.FINE, "Failed to " + description, e);
        }
    }

    @FunctionalInterface
    private interface CleanupAction {
        void run() throws Exception;
    }
}
