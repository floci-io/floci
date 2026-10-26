package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.EnforcementFixtures.UserCredentials;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetBucketPolicyStatusResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** SDK coverage for the policy-status payload, missing-policy errors and IAM action routing. */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class S3PolicyStatusIntegrationTest {

    @TestHTTPResource("/")
    URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void reportsNoSuchBucketPolicyBeforeCreationAndAfterDeletion() {
        try (S3Client admin = client("test", "test")) {
            String bucket = createBucket(admin);
            assertMissingPolicy(admin, bucket);
            admin.putBucketPolicy(request -> request.bucket(bucket).policy(policy(bucket, "*")));
            admin.deleteBucketPolicy(request -> request.bucket(bucket));
            assertMissingPolicy(admin, bucket);
        }
    }

    @Test
    void decodesPublicAndPrivatePolicyStatus() {
        try (S3Client admin = client("test", "test")) {
            String bucket = createBucket(admin);
            admin.putBucketPolicy(request -> request.bucket(bucket).policy(policy(bucket, "*")));
            assertPolicyStatus(admin, bucket, true);
            admin.putBucketPolicy(request -> request.bucket(bucket)
                    .policy(policy(bucket, "arn:aws:iam::123456789012:root")));
            assertPolicyStatus(admin, bucket, false);
        }
    }

    @Test
    void statusOnlyPermissionDoesNotRequireListBucket() {
        String user = "policy-status-" + UUID.randomUUID();
        UserCredentials credentials = EnforcementFixtures.createUser(
                cleanup, baseUri.getPort(), auth("test", "iam"), user);
        try (S3Client admin = client("test", "test");
                S3Client reader = client(credentials.accessKeyId(), credentials.secretAccessKey())) {
            String bucket = createBucket(admin);
            admin.putBucketPolicy(request -> request.bucket(bucket).policy(policy(bucket, "*")));
            EnforcementFixtures.putUserPolicy(baseUri.getPort(), auth("test", "iam"), user, "status-only", """
                    {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                    "Action":"s3:GetBucketPolicyStatus","Resource":"arn:aws:s3:::%s"}]}
                    """.formatted(bucket));

            assertPolicyStatus(reader, bucket, true);
            S3Exception denied = assertThrows(S3Exception.class,
                    () -> reader.listObjectsV2(request -> request.bucket(bucket)));
            assertEquals(403, denied.statusCode());
            assertEquals("AccessDenied", denied.awsErrorDetails().errorCode());
        }
    }

    private String createBucket(S3Client admin) {
        String bucket = "policy-status-" + UUID.randomUUID();
        cleanup.register(() -> EnforcementFixtures.removeBucket(baseUri.getPort(), auth("test", "s3"), bucket));
        admin.createBucket(request -> request.bucket(bucket));
        return bucket;
    }

    private S3Client client(String accessKey, String secretKey) {
        return S3Client.builder()
                .endpointOverride(baseUri)
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .build();
    }

    private static String policy(String bucket, String principal) {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Principal":{"AWS":"%s"},"Action":"s3:GetObject","Resource":"arn:aws:s3:::%s/*"}]}
                """.formatted(principal, bucket);
    }

    private static void assertPolicyStatus(S3Client client, String bucket, boolean expected) {
        GetBucketPolicyStatusResponse response = client.getBucketPolicyStatus(request -> request.bucket(bucket));
        assertNotNull(response.policyStatus());
        assertEquals(expected, response.policyStatus().isPublic());
    }

    private static void assertMissingPolicy(S3Client client, String bucket) {
        S3Exception missing = assertThrows(S3Exception.class,
                () -> client.getBucketPolicyStatus(request -> request.bucket(bucket)));
        assertEquals(404, missing.statusCode());
        assertEquals("NoSuchBucketPolicy", missing.awsErrorDetails().errorCode());
    }
}
