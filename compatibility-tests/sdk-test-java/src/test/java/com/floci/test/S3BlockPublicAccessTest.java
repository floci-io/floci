package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.BucketCannedACL;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetBucketPolicyRequest;
import software.amazon.awssdk.services.s3.model.GetPublicAccessBlockRequest;
import software.amazon.awssdk.services.s3.model.GetPublicAccessBlockResponse;
import software.amazon.awssdk.services.s3.model.ObjectCannedACL;
import software.amazon.awssdk.services.s3.model.PublicAccessBlockConfiguration;
import software.amazon.awssdk.services.s3.model.PutBucketAclRequest;
import software.amazon.awssdk.services.s3.model.PutBucketPolicyRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutPublicAccessBlockRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * S3 Block Public Access, driven through the real AWS SDK.
 *
 * <p>Covers the half AWS enforces whoever the caller is: {@code BlockPublicPolicy} rejecting a
 * public bucket policy and {@code BlockPublicAcls} rejecting a public ACL. Both apply without
 * {@code enforce-auth}, which is the configuration this suite runs against. The read-time flags
 * ({@code RestrictPublicBuckets}, {@code IgnorePublicAcls}) need anonymous authorization to be
 * running and are covered by the in-tree integration tests instead.
 */
@DisplayName("S3 Block Public Access: BlockPublicPolicy / BlockPublicAcls")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3BlockPublicAccessTest {

    private static S3Client s3;

    private static final String BUCKET = "compat-bpa-bucket";

    private static final String PUBLIC_POLICY = """
            {"Version":"2012-10-17","Statement":[{"Sid":"PublicRead","Effect":"Allow",
            "Principal":"*","Action":"s3:GetObject","Resource":"arn:aws:s3:::%s/*"}]}
            """.formatted(BUCKET);

    private static final String ACCOUNT_SCOPED_POLICY = """
            {"Version":"2012-10-17","Statement":[{"Sid":"OneAccount","Effect":"Allow",
            "Principal":{"AWS":"arn:aws:iam::111122223333:root"},"Action":"s3:GetObject",
            "Resource":"arn:aws:s3:::%s/*"}]}
            """.formatted(BUCKET);

    @BeforeAll
    static void setup() {
        s3 = TestFixtures.s3Client();
        try {
            s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        } catch (BucketAlreadyOwnedByYouException expected) {
            // A previous run of this suite left the bucket behind; reuse it.
        }
        s3.putPublicAccessBlock(PutPublicAccessBlockRequest.builder()
                .bucket(BUCKET)
                .publicAccessBlockConfiguration(PublicAccessBlockConfiguration.builder()
                        .blockPublicAcls(true)
                        .ignorePublicAcls(false)
                        .blockPublicPolicy(true)
                        .restrictPublicBuckets(false)
                        .build())
                .build());
    }

    @AfterAll
    static void teardown() {
        if (s3 != null) {
            s3.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("GetPublicAccessBlock returns the flags PutPublicAccessBlock stored")
    void publicAccessBlockRoundTrips() {
        GetPublicAccessBlockResponse response = s3.getPublicAccessBlock(
                GetPublicAccessBlockRequest.builder().bucket(BUCKET).build());

        PublicAccessBlockConfiguration configuration = response.publicAccessBlockConfiguration();
        assertThat(configuration.blockPublicAcls()).isTrue();
        assertThat(configuration.blockPublicPolicy()).isTrue();
        assertThat(configuration.ignorePublicAcls()).isFalse();
        assertThat(configuration.restrictPublicBuckets()).isFalse();
    }

    @Test
    @Order(2)
    @DisplayName("BlockPublicPolicy rejects PutBucketPolicy with AccessDenied")
    void blockPublicPolicyRejectsAPublicPolicy() {
        S3Exception exception = catchThrowableOfType(
                () -> s3.putBucketPolicy(PutBucketPolicyRequest.builder()
                        .bucket(BUCKET)
                        .policy(PUBLIC_POLICY)
                        .build()),
                S3Exception.class);

        assertThat(exception).isNotNull();
        assertThat(exception.statusCode()).isEqualTo(403);
        assertThat(exception.awsErrorDetails().errorCode()).isEqualTo("AccessDenied");
    }

    @Test
    @Order(3)
    @DisplayName("BlockPublicPolicy leaves a policy scoped to one account alone")
    void aNonPublicPolicyIsStillAccepted() {
        s3.putBucketPolicy(PutBucketPolicyRequest.builder()
                .bucket(BUCKET)
                .policy(ACCOUNT_SCOPED_POLICY)
                .build());

        assertThat(s3.getBucketPolicy(GetBucketPolicyRequest.builder().bucket(BUCKET).build()).policy())
                .contains("OneAccount");
    }

    @Test
    @Order(4)
    @DisplayName("BlockPublicAcls rejects a public bucket ACL with AccessDenied")
    void blockPublicAclsRejectsAPublicBucketAcl() {
        S3Exception exception = catchThrowableOfType(
                () -> s3.putBucketAcl(PutBucketAclRequest.builder()
                        .bucket(BUCKET)
                        .acl(BucketCannedACL.PUBLIC_READ)
                        .build()),
                S3Exception.class);

        assertThat(exception).isNotNull();
        assertThat(exception.statusCode()).isEqualTo(403);
        assertThat(exception.awsErrorDetails().errorCode()).isEqualTo("AccessDenied");
    }

    @Test
    @Order(5)
    @DisplayName("BlockPublicAcls rejects a PutObject carrying a public ACL")
    void blockPublicAclsRejectsAPublicObjectAcl() {
        assertThatThrownBy(() -> s3.putObject(
                PutObjectRequest.builder()
                        .bucket(BUCKET)
                        .key("public.txt")
                        .acl(ObjectCannedACL.PUBLIC_READ)
                        .build(),
                RequestBody.fromString("hello")))
                .isInstanceOf(S3Exception.class)
                .satisfies(thrown -> assertThat(((S3Exception) thrown).statusCode()).isEqualTo(403));
    }

    @Test
    @Order(6)
    @DisplayName("A PutObject without a public ACL is unaffected")
    void aPrivatePutObjectIsUnaffected() {
        s3.putObject(
                PutObjectRequest.builder().bucket(BUCKET).key("private.txt").build(),
                RequestBody.fromString("hello"));
    }
}
