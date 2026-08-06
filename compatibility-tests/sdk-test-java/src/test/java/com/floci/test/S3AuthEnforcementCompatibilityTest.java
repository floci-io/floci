package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutBucketPolicyRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("S3 Auth Enforcement")
class S3AuthEnforcementCompatibilityTest {

    private static final String PUBLIC_BUCKET = TestFixtures.uniqueName("sdk-s3-auth-public");
    private static final String PRIVATE_BUCKET = TestFixtures.uniqueName("sdk-s3-auth-private");
    private static final String KEY = "directory/known-file.txt";
    private static final String CONTENT = "anonymous read works";

    private static S3Client adminS3;
    private static S3Client anonymousS3;
    private static S3Client unknownCredentialS3;
    private static boolean enforcementEnabled;

    @BeforeAll
    static void setup() {
        adminS3 = TestFixtures.s3Client();
        anonymousS3 = s3WithAnonymousCredentials();
        unknownCredentialS3 = s3WithCredentials("bad-key", "bad-secret");

        adminS3.createBucket(CreateBucketRequest.builder().bucket(PUBLIC_BUCKET).build());
        adminS3.putBucketPolicy(PutBucketPolicyRequest.builder()
                .bucket(PUBLIC_BUCKET)
                .policy(publicReadPolicy(PUBLIC_BUCKET))
                .build());
        adminS3.putObject(PutObjectRequest.builder().bucket(PUBLIC_BUCKET).key(KEY).build(),
                RequestBody.fromString(CONTENT));

        adminS3.createBucket(CreateBucketRequest.builder().bucket(PRIVATE_BUCKET).build());
        adminS3.putObject(PutObjectRequest.builder().bucket(PRIVATE_BUCKET).key(KEY).build(),
                RequestBody.fromString(CONTENT));

        assertThat(readObject(anonymousS3, PUBLIC_BUCKET)).isEqualTo(CONTENT);
        enforcementEnabled = probeEnforcementEnabled();
    }

    @AfterAll
    static void cleanup() {
        close(adminS3);
        close(anonymousS3);
        close(unknownCredentialS3);
    }

    private static void close(S3Client client) {
        if (client != null) {
            client.close();
        }
    }

    @Test
    @DisplayName("anonymous public object read succeeds")
    void anonymousPublicObjectReadSucceeds() {
        assumeEnforcementEnabled();

        assertThat(readObject(anonymousS3, PUBLIC_BUCKET)).isEqualTo(CONTENT);
    }

    @Test
    @DisplayName("anonymous public directory list succeeds")
    void anonymousPublicDirectoryListSucceeds() {
        assumeEnforcementEnabled();

        ListObjectsV2Response response = anonymousS3.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(PUBLIC_BUCKET)
                .delimiter("/")
                .build());
        assertThat(response.commonPrefixes())
                .anyMatch(prefix -> "directory/".equals(prefix.prefix()));
    }

    @Test
    @DisplayName("anonymous private object read fails")
    void anonymousPrivateObjectReadFails() {
        assumeEnforcementEnabled();

        assertThatThrownBy(() -> readObject(anonymousS3, PRIVATE_BUCKET))
                .isInstanceOfSatisfying(S3Exception.class, S3AuthEnforcementCompatibilityTest::assertAccessDenied);
    }

    @Test
    @DisplayName("anonymous private directory list fails")
    void anonymousPrivateDirectoryListFails() {
        assumeEnforcementEnabled();

        assertThatThrownBy(() -> anonymousS3.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(PRIVATE_BUCKET)
                .delimiter("/")
                .build()))
                .isInstanceOfSatisfying(S3Exception.class, S3AuthEnforcementCompatibilityTest::assertAccessDenied);
    }

    @Test
    @DisplayName("public object read rejects unknown signed credentials")
    void publicObjectReadRejectsUnknownSignedCredentials() {
        assumeEnforcementEnabled();

        assertThatThrownBy(() -> readObject(unknownCredentialS3, PUBLIC_BUCKET))
                .isInstanceOfSatisfying(S3Exception.class, S3AuthEnforcementCompatibilityTest::assertInvalidAccessKey);
    }

    @Test
    @DisplayName("public bucket list rejects unknown signed credentials")
    void publicBucketListRejectsUnknownSignedCredentials() {
        assumeEnforcementEnabled();

        assertThatThrownBy(() -> unknownCredentialS3.listObjectsV2(
                ListObjectsV2Request.builder().bucket(PUBLIC_BUCKET).build()))
                .isInstanceOfSatisfying(S3Exception.class, S3AuthEnforcementCompatibilityTest::assertInvalidAccessKey);
    }

    private static boolean probeEnforcementEnabled() {
        try {
            readObject(unknownCredentialS3, PUBLIC_BUCKET);
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 403
                    && "InvalidAccessKeyId".equals(e.awsErrorDetails().errorCode())) {
                return true;
            }
            throw e;
        }
    }

    private static void assumeEnforcementEnabled() {
        Assumptions.assumeTrue(enforcementEnabled,
                "S3 auth enforcement is not enabled - set floci.services.s3.enforce-auth=true to run these tests");
    }

    private static String readObject(S3Client client, String bucket) {
        ResponseBytes<GetObjectResponse> response = client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key(KEY).build());
        return response.asString(StandardCharsets.UTF_8);
    }

    private static S3Client s3WithAnonymousCredentials() {
        return S3Client.builder()
                .endpointOverride(TestFixtures.endpoint())
                .region(Region.US_EAST_1)
                .credentialsProvider(AnonymousCredentialsProvider.create())
                .forcePathStyle(true)
                .build();
    }

    private static S3Client s3WithCredentials(String accessKeyId, String secretAccessKey) {
        return S3Client.builder()
                .endpointOverride(TestFixtures.endpoint())
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
                .forcePathStyle(true)
                .build();
    }

    private static void assertInvalidAccessKey(S3Exception e) {
        assertThat(e.statusCode()).isEqualTo(403);
        assertThat(e.awsErrorDetails().errorCode()).isEqualTo("InvalidAccessKeyId");
    }

    private static void assertAccessDenied(S3Exception e) {
        assertThat(e.statusCode()).isEqualTo(403);
        assertThat(e.awsErrorDetails().errorCode()).isEqualTo("AccessDenied");
    }

    private static String publicReadPolicy(String bucket) {
        return """
                {
                  "Version": "2012-10-17",
                  "Statement": {
                    "Effect": "Allow",
                    "Principal": {"AWS": ["*"]},
                    "Action": ["s3:GetObject", "s3:ListBucket"],
                    "Resource": ["arn:aws:s3:::%s/*", "arn:aws:s3:::%s"]
                  }
                }
                """.formatted(bucket, bucket);
    }
}
