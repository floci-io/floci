package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.GetBucketPolicyStatusRequest;
import software.amazon.awssdk.services.s3.model.GetBucketPolicyStatusResponse;
import software.amazon.awssdk.services.s3.model.PutBucketPolicyRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SDK-level coverage for the S3 GetBucketPolicyStatus response shape and errors. */
class S3PolicyStatusTest {

    @Test
    void decodesPolicyStatusRootElement() {
        String bucket = TestFixtures.uniqueName("sdk-policy-status");
        String policy = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Principal":"*","Action":"s3:GetObject","Resource":"arn:aws:s3:::%s/*"}]}
                """.formatted(bucket);

        try (S3Client s3 = TestFixtures.s3Client()) {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            s3.putBucketPolicy(PutBucketPolicyRequest.builder().bucket(bucket).policy(policy).build());

            GetBucketPolicyStatusResponse response = s3.getBucketPolicyStatus(
                    GetBucketPolicyStatusRequest.builder().bucket(bucket).build());

            assertThat(response.policyStatus()).isNotNull();
            assertThat(response.policyStatus().isPublic()).isTrue();
        } finally {
            try (S3Client cleanup = TestFixtures.s3Client()) {
                cleanup.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build());
            } catch (Exception ignored) {
                // The compatibility runner may retain state after a failed assertion.
            }
        }
    }

    @Test
    void reportsMissingBucketPolicy() {
        String bucket = TestFixtures.uniqueName("sdk-policy-status");
        try (S3Client s3 = TestFixtures.s3Client()) {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());

            assertThatThrownBy(() -> s3.getBucketPolicyStatus(
                    GetBucketPolicyStatusRequest.builder().bucket(bucket).build()))
                    .isInstanceOfSatisfying(S3Exception.class, exception ->
                            assertThat(exception.awsErrorDetails().errorCode()).isEqualTo("NoSuchBucketPolicy"));
        } finally {
            try (S3Client cleanup = TestFixtures.s3Client()) {
                cleanup.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build());
            } catch (Exception ignored) {
                // The compatibility runner may retain state after a failed assertion.
            }
        }
    }
}
